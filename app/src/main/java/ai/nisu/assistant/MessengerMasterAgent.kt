package ai.nisu.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException

/*
 * Messenger Master Agent (step 6).
 *
 *   MainBrain -> AgentManager.agentFor(MESSENGER) -> MessengerMasterAgent.handle(AgentContext)
 *     1. gate           only Messenger / Messenger Lite tasks; anything else is refused (WhatsApp never gets here)
 *     2. analyse        MessengerKnowledge: what kind of message is it, private or group, complete or cut off
 *     3. context        this conversation's own saved messages (AgentContext cannot reach another conversation)
 *     4. action         MessengerActionEngine: IDENTIFY_MESSAGE_TYPE, then REPLY_TO_INCOMING or CONTINUE_CONVERSATION
 *     5. state          MessengerConversations: draft, last result, counters of THIS conversation
 *     6. automation     MessengerAutomation hook (disabled in this step)
 *     7. result         a short code for the Main Brain (AgentResult); no message text, no names
 *
 * In this step the agent understands a message and PREPARES a reply draft in memory. It does not send, tap, open Messenger,
 * reply automatically, wait, or start conversations. See MessengerCapabilities for the honest list.
 *
 * Handling a task has no side effect outside this process apart from the AI request, so a task that was running when the
 * process died may safely run again ([restartSafe]).
 *
 * Isolation from the WhatsApp Master Agent: separate class, separate conversation state, separate counters, separate
 * switch, separate action engine. The Agent Manager serves one agent per app, and the gate below refuses anything that is
 * not a Messenger message even if it were handed over by mistake.
 */
class MessengerMasterAgent(
    private val automation: MessengerAutomation = DisabledMessengerAutomation,
    /** The Shared Task Manager (TaskManager.kt, step 8). Handed over by the Agent Catalog; not called yet because nothing here sends or replies by itself. */
    @Suppress("unused") val sharedTasks: SharedTaskManager = NoSharedTaskManager
) : AppAgent {
    override val id = AgentIds.MESSENGER_MASTER
    override val displayName = "Messenger Master Agent"
    override val sources = setOf(MessageSource.MESSENGER)
    override val restartSafe = true

    private companion object {
        const val HISTORY_LIMIT = 40
        const val SUPERSEDED = "superseded_by_newer_message"
    }

    private val conversations = MessengerConversations()

    override fun onRegistered(appContext: Context) {
        conversations.clear()
        MessengerAgentState.reset()
    }

    override fun onUnregistered() {
        conversations.clear()
        MessengerAgentState.reset()
    }

    /** The newest fresh draft of ONE conversation (for the future approval / automation steps), or null. */
    fun draftFor(conversationId: String): MessengerDraft? = conversations.draftFor(conversationId, System.currentTimeMillis())

    override suspend fun handle(ctx: AgentContext): AgentResult {
        val m = ctx.message

        // 1. gate: this agent never processes anything but Messenger, even if it were handed something else by mistake
        if (ctx.task.source != MessageSource.MESSENGER || m.source != MessageSource.MESSENGER ||
            m.packageName !in Apps.MESSENGER || m.conversationId != ctx.task.conversationId
        ) {
            MessengerAgentState.failedRun()
            return AgentResult.Failed("not_a_messenger_message")
        }

        val now = System.currentTimeMillis()

        // 2. + 3. this message and this conversation's own history; the newest message may have older and newer neighbours
        val info = MessengerKnowledge.analyze(m)
        val saved = ctx.history(HISTORY_LIMIT).filter { it.messageId != m.messageId }
        val newer = saved.filter { isNewer(it, m) }
        val earlier = saved.filter { !isNewer(it, m) }

        val env = MessengerActionEnv(
            message = m, info = info, history = earlier, responses = ctx.responses,
            draftsEnabled = Prefs.getBool(ctx.appContext, Prefs.MSGR_DRAFTS, true),
            // asked by the shared generator before and after its AI call: has a newer text message of this chat arrived meanwhile?
            stillRelevant = { ctx.history(HISTORY_LIMIT).none { it.messageId != m.messageId && isNewer(it, m) && MessengerKnowledge.classify(it.text) == MsgrMessageType.TEXT } },
            now = now,
            introduce = AutoReply.isFirstReply(ctx.appContext, m) && !ReplySettings.chatMode()
        )

        // 4. actions
        val identified = MessengerActionEngine.run(MessengerIdentifyMessageTypeAction(), env)
        val type = (identified as? MessengerActionResult.TypeIdentified)?.info?.type ?: info.type

        // A newer text message of the same chat is already saved: its own task writes the one draft that sees the whole
        // exchange, so answering this older message separately would only produce a stale draft.
        val supersededBy = info.draftBlocker == null && newer.any { MessengerKnowledge.classify(it.text) == MsgrMessageType.TEXT }
        val result: MessengerActionResult = when {
            supersededBy -> MessengerActionResult.NoAction(SUPERSEDED)
            earlier.isEmpty() -> MessengerActionEngine.run(MessengerReplyToIncomingAction(), env)
            else -> MessengerActionEngine.run(MessengerContinueConversationAction(), env)
        }

        // 5. + 6. + 7. state, automation hook, result for the Main Brain
        return when (result) {
            is MessengerActionResult.DraftPrepared -> {
                val automationCode = runAutomationHook(m, result.draft)
                recordState(m, type, "draft_ready", result.draft, keepOldDraft = false, automationCode = automationCode, now = now)
                MessengerAgentState.draftPrepared()
                AgentResult.Completed("draft_ready")
            }
            is MessengerActionResult.NoAction -> {
                val code = "no_draft:" + result.code
                recordState(m, type, code, null, keepOldDraft = result.code == SUPERSEDED || result.code == ResponseCodes.NO_LONGER_RELEVANT, automationCode = null, now = now)
                MessengerAgentState.noDraft()
                AgentResult.Completed(code)
            }
            is MessengerActionResult.Failed -> {
                MessengerAgentState.failedRun()
                AgentResult.Failed(result.code, result.retryable)
            }
            is MessengerActionResult.TypeIdentified -> {
                MessengerAgentState.failedRun()
                AgentResult.Failed("unexpected_action_result")   // an action of the wrong kind answered: cannot happen, but never reported as success
            }
        }
    }

    /** Tells the automation layer about the draft and records its answer. The agent never sends: at most the hook queues a send task (step 10). */
    private suspend fun runAutomationHook(m: ProcessedMessage, draft: MessengerDraft): String {
        val decision: AutomationDecision = try {
            automation.onDraftPrepared(MessengerAutoReplyRequest(m.conversationId, m.messageId, draft, m.receivedAt))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            AutomationDecision.NotEnabled
        }
        return when (decision) {
            is AutomationDecision.NotEnabled -> "not_enabled"
            is AutomationDecision.Queued -> "reply_queued"
            is AutomationDecision.Skipped -> "skipped_" + decision.code
        }
    }

    private fun recordState(
        m: ProcessedMessage, type: MsgrMessageType, outcome: String, draft: MessengerDraft?,
        keepOldDraft: Boolean, automationCode: String?, now: Long
    ) {
        conversations.access(m.conversationId) { s ->
            s.kind = m.kind
            s.lastHandledMessageId = m.messageId
            s.handledMessages++
            s.lastType = type
            s.lastOutcome = outcome
            s.lastActivityAt = now
            s.automation = automationCode
            if (draft != null) s.draft = draft else if (!keepOldDraft) s.draft = null
        }
    }

    /** Message order inside a chat is (time, store id). The store id alone is not enough: backlog is stored in arrival order. */
    private fun isNewer(s: StoredMessage, m: ProcessedMessage): Boolean =
        s.timestamp > m.timestamp || (s.timestamp == m.timestamp && s.messageId > m.messageId)
}
