package ai.nisu.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException

/*
 * WhatsApp Master Agent (step 5).
 *
 *   MainBrain -> AgentManager.agentFor(WHATSAPP) -> WhatsAppMasterAgent.handle(AgentContext)
 *     1. gate           only WhatsApp / WhatsApp Business tasks; anything else is refused (Messenger never gets here)
 *     2. analyse        WhatsAppKnowledge: what kind of message is it, private or group, complete or cut off
 *     3. context        this conversation's own saved messages (AgentContext cannot reach another conversation)
 *     4. action         WhatsAppActionEngine: IDENTIFY_MESSAGE_TYPE, then REPLY_TO_INCOMING or CONTINUE_CONVERSATION
 *     5. state          WhatsAppConversations: draft, last result, counters of THIS conversation
 *     6. automation     WhatsAppAutomation hook (disabled in this step)
 *     7. result         a short code for the Main Brain (AgentResult); no message text, no names
 *
 * In this step the agent understands a message and PREPARES a reply draft in memory. It does not send, tap, open WhatsApp,
 * reply automatically, wait, or start conversations. See WhatsAppCapabilities for the honest list.
 *
 * Handling a task has no side effect outside this process apart from the AI request, so a task that was running when the
 * process died may safely run again ([restartSafe]).
 */
class WhatsAppMasterAgent(
    private val automation: WhatsAppAutomation = DisabledWhatsAppAutomation,
    /** The Shared Task Manager (TaskManager.kt, step 8). Handed over by the Agent Catalog; not called yet because nothing here sends or replies by itself. */
    @Suppress("unused") val sharedTasks: SharedTaskManager = NoSharedTaskManager
) : AppAgent {
    override val id = AgentIds.WHATSAPP_MASTER
    override val displayName = "WhatsApp Master Agent"
    override val sources = setOf(MessageSource.WHATSAPP)
    override val restartSafe = true

    private companion object {
        const val HISTORY_LIMIT = 40
        const val SUPERSEDED = "superseded_by_newer_message"
    }

    private val conversations = WhatsAppConversations()

    override fun onRegistered(appContext: Context) {
        conversations.clear()
        WhatsAppAgentState.reset()
    }

    override fun onUnregistered() {
        conversations.clear()
        WhatsAppAgentState.reset()
    }

    /** The newest fresh draft of ONE conversation (for the future approval / automation steps), or null. */
    fun draftFor(conversationId: String): WhatsAppDraft? = conversations.draftFor(conversationId, System.currentTimeMillis())

    override suspend fun handle(ctx: AgentContext): AgentResult {
        val m = ctx.message

        // 1. gate: this agent never processes anything but WhatsApp, even if it were handed something else by mistake
        if (ctx.task.source != MessageSource.WHATSAPP || m.source != MessageSource.WHATSAPP ||
            m.packageName !in Apps.WHATSAPP || m.conversationId != ctx.task.conversationId
        ) {
            WhatsAppAgentState.failedRun()
            return AgentResult.Failed("not_a_whatsapp_message")
        }

        val now = System.currentTimeMillis()

        // 2. + 3. this message and this conversation's own history; the newest message may have older and newer neighbours
        val info = WhatsAppKnowledge.analyze(m)
        val saved = ctx.history(HISTORY_LIMIT).filter { it.messageId != m.messageId }
        val newer = saved.filter { isNewer(it, m) }
        val earlier = saved.filter { !isNewer(it, m) }

        val env = WhatsAppActionEnv(
            message = m, info = info, history = earlier, responses = ctx.responses,
            draftsEnabled = Prefs.getBool(ctx.appContext, Prefs.WA_DRAFTS, true),
            // asked by the shared generator before and after its AI call: has a newer text message of this chat arrived meanwhile?
            stillRelevant = { ctx.history(HISTORY_LIMIT).none { it.messageId != m.messageId && isNewer(it, m) && WhatsAppKnowledge.classify(it.text) == WaMessageType.TEXT } },
            now = now,
            introduce = AutoReply.isFirstReply(ctx.appContext, m) && !ReplySettings.chatMode()
        )

        // 4. actions
        val identified = WhatsAppActionEngine.run(IdentifyMessageTypeAction(), env)
        val type = (identified as? WhatsAppActionResult.TypeIdentified)?.info?.type ?: info.type

        // A newer text message of the same chat is already saved: its own task writes the one draft that sees the whole
        // exchange, so answering this older message separately would only produce a stale draft.
        val supersededBy = info.draftBlocker == null && newer.any { WhatsAppKnowledge.classify(it.text) == WaMessageType.TEXT }
        val result: WhatsAppActionResult = when {
            supersededBy -> WhatsAppActionResult.NoAction(SUPERSEDED)
            earlier.isEmpty() -> WhatsAppActionEngine.run(ReplyToIncomingAction(), env)
            else -> WhatsAppActionEngine.run(ContinueConversationAction(), env)
        }

        // 5. + 6. + 7. state, automation hook, result for the Main Brain
        return when (result) {
            is WhatsAppActionResult.DraftPrepared -> {
                val automationCode = runAutomationHook(m, result.draft)
                recordState(m, type, "draft_ready", result.draft, keepOldDraft = false, automationCode = automationCode, now = now)
                WhatsAppAgentState.draftPrepared()
                AgentResult.Completed("draft_ready")
            }
            is WhatsAppActionResult.NoAction -> {
                val code = "no_draft:" + result.code
                recordState(m, type, code, null, keepOldDraft = result.code == SUPERSEDED || result.code == ResponseCodes.NO_LONGER_RELEVANT, automationCode = null, now = now)
                WhatsAppAgentState.noDraft()
                AgentResult.Completed(code)
            }
            is WhatsAppActionResult.Failed -> {
                WhatsAppAgentState.failedRun()
                AgentResult.Failed(result.code, result.retryable)
            }
            is WhatsAppActionResult.TypeIdentified -> {
                WhatsAppAgentState.failedRun()
                AgentResult.Failed("unexpected_action_result")   // an action of the wrong kind answered: cannot happen, but never reported as success
            }
        }
    }

    /** Tells the automation layer about the draft and records its answer. The agent never sends: at most the hook queues a send task (step 10). */
    private suspend fun runAutomationHook(m: ProcessedMessage, draft: WhatsAppDraft): String {
        val decision: AutomationDecision = try {
            automation.onDraftPrepared(AutoReplyRequest(m.conversationId, m.messageId, draft, m.receivedAt))
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
        m: ProcessedMessage, type: WaMessageType, outcome: String, draft: WhatsAppDraft?,
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
