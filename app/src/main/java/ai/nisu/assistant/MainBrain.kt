package ai.nisu.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/*
 * Main Brain (step 4, task handling moved to the Shared Task Manager in step 8).
 *
 *   NisuNotificationService -> MessageParser -> IncomingMessages -> MessageProcessor (store, one worker thread)
 *        -> MessageGateway.consumer == MainBrain.onMessage()      validate + decide + hand a task to the Task Manager, returns at once
 *        -> TaskManager (TaskManager.kt)                          ONE queue for both apps: SQLite, one task at a time, order per conversation
 *        -> MainBrain.runIncoming()                               builds what the agent gets; re-reads the message through its own conversation
 *        -> AgentManager.agentFor(task.source)                    WhatsApp tasks -> WhatsApp agent, Messenger -> Messenger agent
 *        -> AppAgent.handle(AgentContext)                         the agent sees ONE conversation only
 *
 * The brain lives in the background service as a [BackgroundModule] ([MainBrainModule]). While the service is off the
 * brain is not registered, so messages simply stay stored as pending and are handed over when the service starts.
 *
 * What stays here: deciding whether a message becomes a task, registering the agents and the shared AI, and running one
 * INCOMING_MESSAGE task for an agent. What moved to the Task Manager: the task database, the queue, the dispatcher, the
 * recovery after a restart and the cancellation.
 *
 * Step 9: before a message becomes a task, OwnerWatch opens the conversation's 30-second wait (see OwnerWatch.kt) and the task is
 * created due at the end of it; a message of a conversation whose owner already replied (or may have) gets no task.
 *
 * Step 10 (automatic replies, AutoReply.kt / NotificationReply.kt): this file registers the two senders (WhatsApp, Messenger) with the
 * Task Manager and, before an agent is called, lets [AutoReply.preflight] handle a person who already reached the reply limit (the one
 * closing message, no AI call). Everything else is unchanged: the brain itself still never sends, taps or automates anything; the
 * agents' hooks queue SEND_REPLY tasks and the Task Manager runs them one at a time.
 *
 * Step 11 (privacy and conversation management, Privacy.kt): a message of a conversation that is paused (Raj answered by hand), completed
 * (reply limit reached) or blocked gets no task at all, and every task carries the conversation's key so the Task Manager can check the
 * state again before it starts. The shared AI engine gets the privacy output check, so an AI reply that contains private data of another
 * chat, an API key or the AI's own instructions never becomes a draft.
 *
 * Root (RootAccess.kt): the brain never runs `su` and never builds a shell command. It reads the ONE shared root status and may ask the Root
 * Gateway for an action from the closed [RootAction] list. The request only waits for Raj's approval on the Automation screen; the agents
 * get no root access (AgentContext has no handle for it). The brain asks only when notification access is off while automation is on, and only
 * when root is already known to be available, so it never causes a root manager prompt.
 *
 * A task for an app whose agent is not registered waits (up to [TaskLimits.MAX_AGE_MS]) and is then dropped as expired.
 * Message text, sender and chat names are never logged.
 */

object BrainRouting {
    /**
     * Which apps the brain knows how to route. An exhaustive `when`: adding a value to [MessageSource] does not compile
     * until it is decided here, so a new app can never be routed by accident.
     */
    fun supports(source: MessageSource): Boolean = when (source) {
        MessageSource.WHATSAPP, MessageSource.MESSENGER -> true
    }
}

/** What the UI shows about the brain. Real values only. */
data class BrainSnapshot(
    val running: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val tasks: TaskCounts = TaskCounts(),
    /** Messages the brain took over but did not make a task of (too old, unsupported, inconsistent, owner replied or uncertain), since the app started. */
    val ignored: Int = 0,
    /** Step 9: conversations by owner-check phase right now (waiting for the 30 s, clear to continue, owner replied, held as uncertain). */
    val owner: OwnerCounts = OwnerCounts()
)

object BrainState {
    val snapshot = kotlinx.coroutines.flow.MutableStateFlow(BrainSnapshot())
}

object MainBrain : MessageConsumer {
    private const val TAG = "NisuBrain"

    private sealed class Decision {
        class Accept(val task: AgentTask) : Decision()
        class Ignore(val reason: String) : Decision()
    }

    @Volatile private var appCtx: Context? = null
    @Volatile private var aiRef: BrainAi? = null
    @Volatile private var responsesRef: MainBrainReplyWriter? = null
    @Volatile private var isRunning = false
    private val ignored = AtomicInteger(0)

    // ------------------------------------------------------------------ 1. receiving (processor worker thread)

    /**
     * Called by [MessageProcessor] on its worker thread, one message at a time. Fast and non-blocking apart from one small
     * database write. Returning normally = the message is taken over. Throwing (brain or database not ready) = the processor
     * offers it again later, so a message is never lost between the two stores. A repeat of an already known message is harmless.
     */
    override fun onMessage(message: ProcessedMessage) {
        if (!isRunning) { AutoReplyLog.add("brain", "not_ready_retry_later", true); throw IllegalStateException("brain_not_ready") }
        val now = System.currentTimeMillis()
        when (val d = decide(message, now)) {
            is Decision.Ignore -> {
                ignored.incrementAndGet()
                Log.i(TAG, "message not turned into a task: " + d.reason)
                AutoReplyLog.add("brain", message.source.name.take(4) + "_ignored_" + d.reason, true)
            }
            is Decision.Accept -> {
                // Step 9: the owner check. The conversation's 30-second wait is opened (or joined) first; the task is due when it ends.
                // Throws when the task manager is not ready: the processor offers the message again, and admitting twice changes nothing.
                when (val a = TaskManager.ownerAdmit(message, now)) {
                    is OwnerWatch.Admission.Wait -> {
                        val added = TaskManager.submit(d.task.copy(notBefore = maxOf(a.deadline, now + BurstGuard.SETTLE_MS)))   // v3.42: short settle wait so a burst becomes ONE reply   // false = this message already has its task: harmless
                        AutoReplyLog.add("owner", message.source.name.take(4) + "_wait_30s_task_" + (if (added) "queued" else "exists"))
                    }
                    is OwnerWatch.Admission.Skip -> {
                        ignored.incrementAndGet()
                        Log.i(TAG, "message not turned into a task: " + a.reason)
                        AutoReplyLog.add("owner", message.source.name.take(4) + "_skipped_" + a.reason, true)
                    }
                }
            }
        }
        refreshSnapshot()
    }

    /** The routing decision. Looks only at the message's own metadata, never at other conversations. */
    private fun decide(m: ProcessedMessage, now: Long): Decision {
        if (!TaskStore.validConversationId(m.conversationId)) return Decision.Ignore("bad_conversation_id")
        if (MessageSource.fromPackage(m.packageName) != m.source) return Decision.Ignore("source_mismatch")
        if (!BrainRouting.supports(m.source)) return Decision.Ignore("unsupported_source")
        if (m.text.isBlank()) return Decision.Ignore("empty")
        val at = if (m.timestampExact) m.timestamp else m.receivedAt
        if (now - at > TaskLimits.MAX_AGE_MS) return Decision.Ignore("stale")
        // Step 13: automation off, paused, or this app's switch off: no new task is scheduled at all (so nothing is drafted and no text goes to the AI).
        // The same AutoReplySettings answer is checked again by the agents' hooks, by AutoReply and by the sender, so a switch changed later still wins.
        val app = appCtx ?: throw IllegalStateException("brain_not_ready")
        AutoReplySettings.blockReason(app, m.source)?.let { return Decision.Ignore(it) }
        // Step 11: a paused, completed or blocked conversation gets no task. Throws when the state cannot be read (the processor offers the message again).
        val key = AutoReply.counterKey(m)
        TaskManager.lifecycleRefusal(key)?.let { return Decision.Ignore(it) }
        return Decision.Accept(
            AgentTask(
                id = TaskStore.newId(), type = TaskType.INCOMING_MESSAGE, source = m.source, packageName = m.packageName,
                conversationId = m.conversationId, messageId = m.messageId, status = TaskStatus.PENDING, agentId = null,
                attempts = 0, createdAt = now, updatedAt = now, startedAt = 0L, finishedAt = 0L, notBefore = 0L, outcome = null,
                counterKey = key
            )
        )
    }

    // ------------------------------------------------------------------ 2. running (inside the background service)

    /** Lives as long as the background service. Cancelled when the service stops. */
    internal suspend fun run(ctx: Context) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) {
            appCtx = app
            AutoReplyLog.init(app)
            AutoReplyLog.add("brain", "started_master_" + (if (AutoReplySettings.masterOn(app)) "on" else "off"))
            val brainAi = MainBrainModel(app)   // v2.1: the Main Brain's OWN model (the one selected in the model menu), used silently
            aiRef = brainAi
            responsesRef = MainBrainReplyWriter(brainAi, PrivacyOutputGuard(app))   // the Main Brain's reply writer (with the step 11 privacy check); agents ask the Brain through BrainReplyPort
            BrainReplyMemory.clear()
            PrivacyState.reset()
            ConversationLifecycle.refreshCounts(app)
            AgentManager.onChanged = { TaskManager.wakeUp(); refreshSnapshot() }
            TaskManager.onChanged = { refreshSnapshot() }
            var rootWatcher: Job? = null
            try {
                // Root: follows the notification-access state (re-read first, the stored default is OFF) and asks the Root Gateway when needed.
                MessageAccess.refresh(app)
                // A failure here must never take the brain down (a failed child would cancel the whole brain), so it is contained.
                rootWatcher = launch {
                    MessageAccess.state.collect { st ->
                        try { askRootHelpIfNeeded(app, st) } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
                    }
                }
                for (a in AgentCatalog.create(app)) {
                    val r = AgentManager.register(a, app)
                    if (r is RegisterResult.Rejected) Log.w(TAG, "an agent was not registered: " + r.reason)
                }
                // Step 10: one sender per app. Each reads the draft of its own app's agent only and sends through the notification's Reply action.
                AutoReplyState.reset()
                if (!TaskManager.registerSender(WhatsAppReplySender(app))) Log.w(TAG, "the WhatsApp sender was not registered")
                if (!TaskManager.registerSender(MessengerReplySender(app))) Log.w(TAG, "the Messenger sender was not registered")
                TaskManager.run(app, { task, agent -> runIncoming(task, agent) }) {
                    // The queue is open and interrupted tasks are recovered: from now on stored messages arrive in onMessage(), pending ones first.
                    isRunning = true
                    refreshSnapshot()
                    MessageGateway.register(app, this@MainBrain)
                }
            } finally {
                rootWatcher?.cancel()
                MessageGateway.unregister(this@MainBrain)
                AgentManager.onChanged = null
                TaskManager.onChanged = null
                AgentManager.unregisterAll()
                isRunning = false
                refreshSnapshot()
            }
        }
    }

    /**
     * Runs ONE INCOMING_MESSAGE task for the agent the Task Manager chose. The agent never gets a message from the task's data
     * alone: it is read again, through the task's own conversation, and checked against the task.
     */
    private suspend fun runIncoming(task: AgentTask, agent: AppAgent): TaskVerdict {
        val app = appCtx ?: throw IllegalStateException("brain_not_ready")
        val message = MessageGateway.message(app, task.conversationId, task.messageId)
            ?: return TaskVerdict.Dropped("message_gone")   // pruned, or the history was deleted
        val problem = validate(task, agent, message)
        if (problem != null) return TaskVerdict.Failed(problem, false)

        // v2.39: ONE reply per burst. (1) A newer message of the same chat is already stored: that newer message answers everything
        // (the agent reads the whole recent chat), so this older one gets no reply of its own. (2) A reply to this chat is already
        // sent or on its way and this message was in the chat before it: it is covered, so nothing more is sent until a NEW message comes.
        try {
            val newest = MessageGateway.history(app, task.conversationId, 6).lastOrNull { it.text.isNotBlank() }
            if (newest != null && newest.messageId > task.messageId) { AutoReplyLog.add("burst", task.source.name.take(4) + "_superseded_by_newer"); return TaskVerdict.Dropped("superseded_by_newer_message") }
            val st = TaskManager.openStore(app)
            if (st != null && BurstGuard.answered(message, st.replyCoveredId(task.conversationId, null, true), st.replyBarrier(task.conversationId, null, false, System.currentTimeMillis()))) {
                AutoReplyLog.add("burst", task.source.name.take(4) + "_already_answered"); return TaskVerdict.Dropped("already_answered")
            }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { /* never block a reply because of this check */ }

        // Step 10: a person who already got the maximum number of automatic replies is not answered by the AI any more.
        AutoReply.preflight(app, task, message)?.let { return it }

        val taskAi = aiRef ?: MainBrainModel(app)
        val ctx = AgentContext(
            appContext = app, task = task, message = message,
            conversation = TaskManager.conversationStateOf(task.conversationId),
            responses = responsesRef ?: MainBrainReplyWriter(taskAi, PrivacyOutputGuard(app)),
            loadHistory = { n -> MessageGateway.history(app, task.conversationId, n) }
        )
        return when (val r = AgentManager.invoke(agent, ctx)) {
            is AgentResult.Completed -> { AutoReplyLog.add("agent", task.source.name.take(4) + "_" + (r.note ?: "done")); TaskVerdict.Done(r.note) }
            is AgentResult.Failed -> { AutoReplyLog.add("agent", task.source.name.take(4) + "_failed_" + r.code, true); TaskVerdict.Failed(r.code, r.retryable) }
        }
    }

    /**
     * The brain's ONLY Root request. Notification access is off while automation is on: nothing can be received, and the existing root
     * routine ([RootAction.SELF_SETUP]) can grant it. The request goes through the Root Gateway, which refuses it unless root is already
     * known to be available (no `su` call here, so no new root manager prompt) and which only records it for Raj's approval on the
     * Automation screen. "Allowed but not connected" is not asked for: the service's own rebind loop handles it without root.
     * Only the outcome code is logged.
     */
    private suspend fun askRootHelpIfNeeded(app: Context, access: MessageAccess.State) {
        if (access != MessageAccess.State.OFF) return
        if (!AutoReplySettings.masterOn(app)) return
        when (val r = RootAccess.request(app, RootCaller.MAIN_BRAIN, RootAction.SELF_SETUP)) {
            RootOutcome.AwaitingOwner -> Log.i(TAG, "root help requested, waiting for the owner")
            is RootOutcome.Refused -> Log.i(TAG, "root help not requested: " + r.code)
            is RootOutcome.Ran -> {}
        }
    }

    /** Checks that make sure an agent only ever gets a consistent task: right type, right app, message of the right conversation. */
    private fun validate(task: AgentTask, agent: AppAgent, m: ProcessedMessage): String? = when {
        task.type != TaskType.INCOMING_MESSAGE -> "unknown_task_type"
        !TaskStore.validId(task.id) -> "bad_task_id"
        !TaskStore.validConversationId(task.conversationId) -> "bad_conversation_id"
        task.source !in agent.sources -> "agent_wrong_source"
        m.conversationId != task.conversationId -> "conversation_mismatch"
        m.messageId != task.messageId -> "message_mismatch"
        m.source != task.source -> "source_mismatch"
        m.packageName != task.packageName -> "package_mismatch"
        MessageSource.fromPackage(m.packageName) != task.source -> "package_mismatch"
        m.text.isBlank() -> "empty_message"
        else -> null
    }

    // ------------------------------------------------------------------ 3. state and control (usable from any code, off the main thread)
    // Thin wrappers: the work is done by the Task Manager. Names and behaviour are unchanged since step 4.

    /** State of one conversation: idle, queued or busy. No message text. */
    suspend fun conversationState(ctx: Context, conversationId: String): ConversationState = TaskManager.conversationState(ctx, conversationId)

    /**
     * Cancels a task. A waiting task is cancelled at once; a running one has its coroutine cancelled and ends as
     * CANCELLED. Returns false when the task is unknown or already finished.
     */
    suspend fun cancelTask(ctx: Context, taskId: String): Boolean = TaskManager.cancelTask(ctx, taskId)

    /** Cancels everything waiting or running for ONE conversation. Returns how many tasks were affected. */
    suspend fun cancelConversation(ctx: Context, conversationId: String): Int = TaskManager.cancelConversation(ctx, conversationId)

    /** Cancels everything waiting or running. Returns how many tasks were affected. */
    suspend fun cancelAll(ctx: Context): Int = TaskManager.cancelAll(ctx)

    // ------------------------------------------------------------------ status

    @Synchronized private fun refreshSnapshot() {
        try {
            BrainState.snapshot.value = BrainSnapshot(isRunning, AgentManager.infos(), TaskManager.counts(AgentManager.sourcesServed()), ignored.get(), TaskManager.ownerCounts())
        } catch (_: Exception) {}
    }
}

/** Hosts the Main Brain inside [NisuBackgroundService]: it starts and stops with the service and is isolated from the other modules. */
class MainBrainModule : BackgroundModule {
    override val id = "main-brain"
    override suspend fun run(env: BackgroundEnv) { MainBrain.run(env.context) }
}
