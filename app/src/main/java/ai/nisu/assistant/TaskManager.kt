package ai.nisu.assistant

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/*
 * Shared Task Manager and global message queue (step 8).
 *
 *   MainBrain.onMessage()  --submit(task)-->  TaskManager  (ONE queue for WhatsApp and Messenger, SQLite, survives restarts)
 *   automatic replies      --submitSend()-->               |   (step 10: AutoReply.consider / AutoReply.preflight)
 *                                                          v   one lane: ONE task runs at a time, whatever the app
 *                              INCOMING_MESSAGE  -> MainBrain.runIncoming -> the app's agent (WhatsApp / Messenger Master Agent)
 *                              SEND_REPLY        -> the app's MessageSender (step 10: WhatsAppReplySender / MessengerReplySender)
 *
 * What lives here and nowhere else:
 * - the task database ([TaskStore]) and every status change; a task is claimed once, atomically, so it cannot run twice;
 * - the queue rules: oldest first; ONE running task for both apps together; within a conversation strict order (a waiting task
 *   blocks the newer ones of its conversation); new messages are queued while another task runs; a failed task ends in a final
 *   state and the next task is picked, so one failure never blocks the queue;
 * - the pause between two CONFIRMED sends (3..5 s, [sendDelayMs]); it applies to send tasks only, never to AI drafting;
 * - recovery after a restart, including the rule that an uncertain send is never repeated on its own;
 * - cancellation (a task, a conversation, everything), also with a reason such as [OWNER_REPLIED].
 *
 * The Main Brain stays what it was: it validates and routes incoming messages and builds what an agent gets. It hands
 * tasks to this manager and runs INCOMING_MESSAGE tasks when the manager asks. Nothing here sends anything by itself.
 *
 * Step 9 (owner check, OwnerWatch.kt): a task is created with notBefore = the end of the conversation's 30-second wait, and
 * pickAndClaim asks OwnerWatch.gate() before it starts any task: Wait (not yet), Proceed (no sign of a manual reply), or Cancel
 * (owner replied / uncertain). The wait is therefore a saved deadline, not a sleeping thread, and survives restarts.
 *
 * Logs contain task ids and short error types only, never message text, sender or chat names.
 */

/** What running one INCOMING_MESSAGE task came to. Produced by the Main Brain, applied by the manager. */
internal sealed class TaskVerdict {
    class Done(val note: String?) : TaskVerdict()
    /** [retryable] failures are tried again later, up to [TaskLimits.MAX_ATTEMPTS] runs. */
    class Failed(val code: String, val retryable: Boolean) : TaskVerdict()
    /** The task is no longer valid (for example the message was pruned): ends CANCELLED. */
    class Dropped(val code: String) : TaskVerdict()
}

object TaskManager : SharedTaskManager {
    private const val TAG = "NisuTasks"
    private const val HOUSEKEEPING_MS = 10L * 60 * 1000

    /**
     * Tasks running at the same time, for WhatsApp and Messenger TOGETHER. 1 on purpose: both apps share one phone screen and
     * one owner, and two sends must never overlap.
     */
    const val LANES = 1

    /** Outcome code of tasks cancelled because the owner answered by hand. For [cancelConversation]. */
    const val OWNER_REPLIED = "owner_replied"
    const val CANCELLED = "cancelled"

    private class Pick(val task: AgentTask, val agent: AppAgent?, val sender: MessageSender?)

    @Volatile private var appCtx: Context? = null
    @Volatile private var storeRef: TaskStore? = null
    @Volatile private var isRunning = false
    /** Told when queue contents change, so the Main Brain can refresh what the UI shows. Called on any thread. */
    @Volatile internal var onChanged: (() -> Unit)? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Tasks with a cancel request, and why. A running task that finds its id here ends CANCELLED with that reason. */
    private val cancelReasons = ConcurrentHashMap<String, String>()
    private val busyConversations: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val senderLock = Any()
    private val senders = LinkedHashMap<String, MessageSender>()   // guarded by senderLock

    override val available: Boolean get() = isRunning

    // ------------------------------------------------------------------ senders (none exist in this build)

    /** Registers the one sender of an app (step 10: the Main Brain registers the WhatsApp and the Messenger sender). Rejected when the id is malformed or the app already has a sender. */
    fun registerSender(sender: MessageSender): Boolean {
        val ok = synchronized(senderLock) {
            val id = try { sender.id } catch (_: Throwable) { return false }
            val sources = try { sender.sources } catch (_: Throwable) { return false }
            if (!Regex("^[a-z0-9][a-z0-9-]{1,40}$").matches(id) || sources.isEmpty()) return false
            if (sources.any { !BrainRouting.supports(it) }) return false
            if (senders.containsKey(id) || senders.values.any { o -> o.sources.any { it in sources } }) return false
            senders[id] = sender
            true
        }
        if (ok) wakeUp()
        return ok
    }

    fun unregisterAllSenders() = synchronized(senderLock) { senders.clear() }

    private fun senderFor(source: MessageSource): MessageSender? = synchronized(senderLock) { senders.values.firstOrNull { source in it.sources } }
    private fun senderById(id: String?): MessageSender? = if (id == null) null else synchronized(senderLock) { senders[id] }
    private fun sourcesWithSender(): Set<MessageSource> = synchronized(senderLock) { senders.values.flatMap { it.sources }.toSet() }

    // ------------------------------------------------------------------ receiving tasks

    /**
     * From the Main Brain (processor worker thread, one message at a time). Saves the task in the queue and returns at once.
     * Returns false for a task that already exists: one INCOMING_MESSAGE task per (conversation, message), also after a crash
     * and a repeated hand-over. Throws when the manager is not ready, so the processor offers the message again later.
     */
    internal fun submit(task: AgentTask): Boolean {
        val s = storeRef ?: throw IllegalStateException("task_manager_not_ready")
        val added = s.insertIfNew(task)
        if (added) wake.trySend(Unit)
        notifyChanged()
        return added
    }

    /** From future automation: queue the send of one prepared draft. At most one send per message, ever (the database key survives restarts). */
    override fun submitSend(request: SendRequest): SubmitResult {
        val s = storeRef
        if (s == null || !isRunning) return SubmitResult.Rejected("task_manager_not_running")
        if (!TaskStore.validConversationId(request.conversationId)) return SubmitResult.Rejected("bad_conversation_id")
        if (!BrainRouting.supports(request.source)) return SubmitResult.Rejected("unsupported_source")
        if (MessageSource.fromPackage(request.packageName) != request.source) return SubmitResult.Rejected("source_mismatch")
        if (senderFor(request.source) == null) return SubmitResult.Rejected("no_sender_for_app")   // nothing can send: do not queue a task that cannot run
        val key = request.counterKey
        if (key == null || !TaskStore.validCounterKey(key)) return SubmitResult.Rejected("no_counter_key")   // without it the reply limit could not be kept
        val now = System.currentTimeMillis()
        val task = AgentTask(
            id = TaskStore.newId(), type = TaskType.SEND_REPLY, source = request.source, packageName = request.packageName,
            conversationId = request.conversationId, messageId = request.messageId, status = TaskStatus.PENDING, agentId = null,
            attempts = 0, createdAt = now, updatedAt = now, startedAt = 0L, finishedAt = 0L, notBefore = 0L, outcome = null,
            sendKind = if (request.closing) SendKind.CLOSING else SendKind.REPLY, counterKey = key
        )
        return try {
            if (s.insertIfNew(task)) { wake.trySend(Unit); notifyChanged(); SubmitResult.Accepted(task.id) } else SubmitResult.Duplicate
        } catch (_: Exception) {
            SubmitResult.Rejected("queue_error")
        }
    }

    /** Something changed that could make a waiting task runnable (an agent or a sender came or went). */
    internal fun wakeUp() { wake.trySend(Unit) }

    // ------------------------------------------------------------------ running (inside the background service)

    /**
     * Lives as long as the Main Brain. Opens the database, recovers tasks that were running when the process died, then
     * dispatches until cancelled. [onReady] runs after the recovery and right before the first dispatch: the Main Brain starts
     * accepting messages there. [incoming] runs one INCOMING_MESSAGE task for the agent that was chosen for it.
     */
    internal suspend fun run(ctx: Context, incoming: suspend (AgentTask, AppAgent) -> TaskVerdict, onReady: () -> Unit) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) {
            val s = store(app) ?: throw IllegalStateException("The task database could not be opened.")
            appCtx = app
            try {
                recoverInterrupted(s)
                housekeeping(s)
                isRunning = true
                notifyChanged()
                onReady()
                coroutineScope { dispatchLoop(this, s, incoming) }
            } finally {
                isRunning = false
                unregisterAllSenders()
                notifyChanged()
            }
        }
    }

    /**
     * Tasks that were RUNNING when the process died.
     * - INCOMING_MESSAGE: runs again if its agent says that is safe, otherwise FAILED ("interrupted").
     * - SEND_REPLY: see [recoverSend]. A send is never repeated unless it is certain that nothing went out.
     */
    private suspend fun recoverInterrupted(s: TaskStore) {
        val now = System.currentTimeMillis()
        for (t in s.running()) {
            if (t.type == TaskType.SEND_REPLY) { recoverSend(s, t, now); continue }
            val agent = AgentManager.agentById(t.agentId)
            if (agent != null && agent.restartSafe && t.attempts < TaskLimits.MAX_ATTEMPTS) s.requeue(t.id, now, "interrupted_retry", now)
            else s.finish(t.id, TaskStatus.FAILED, "interrupted", now)
        }
    }

    private suspend fun recoverSend(s: TaskStore, t: AgentTask, now: Long) {
        when (t.sendPhase) {
            // The sender was never called: nothing can have been sent, so waiting again is safe.
            SendPhase.NONE ->
                if (t.attempts < TaskLimits.MAX_ATTEMPTS) s.requeue(t.id, now, "interrupted_before_send", now, SendPhase.NONE)
                else s.finish(t.id, TaskStatus.FAILED, "interrupted", now)
            // The sender was called and the process died: unknown, unless the sender can look at the chat and tell.
            SendPhase.ATTEMPTING -> {
                val sender = senderById(t.agentId)
                val check = if (sender == null) SendCheck.UNKNOWN else try {
                    sender.verify(t)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    SendCheck.UNKNOWN
                }
                when (check) {
                    SendCheck.DELIVERED -> s.finish(t.id, TaskStatus.COMPLETED, "sent_verified", now, SendPhase.CONFIRMED)
                    SendCheck.NOT_DELIVERED ->
                        if (t.attempts < TaskLimits.MAX_ATTEMPTS) s.requeue(t.id, now, "verified_not_sent", now, SendPhase.NONE)
                        else s.finish(t.id, TaskStatus.FAILED, "verified_not_sent", now, SendPhase.NOT_SENT)
                    SendCheck.UNKNOWN -> s.finish(t.id, TaskStatus.FAILED, "send_uncertain_after_restart", now, SendPhase.UNCERTAIN)
                }
            }
            // Not possible for a RUNNING task (final phases are written together with the final status). Never repeated.
            SendPhase.CONFIRMED, SendPhase.NOT_SENT, SendPhase.UNCERTAIN -> s.finish(t.id, TaskStatus.FAILED, "interrupted", now)
        }
    }

    private fun housekeeping(s: TaskStore) {
        val now = System.currentTimeMillis()
        s.expire(now); s.trimPending(now); s.pruneFinished(now); s.pruneCounters(now); s.lifecyclePrune(now)
        try { if (s.refreshLimitDue(now, AutoReplyLimits.max(), AutoReplyLimits.REFRESH_MS) > 0) ConversationLifecycle.publish(s) } catch (e: Exception) { Log.w(TAG, "limit refresh failed: " + e.javaClass.simpleName) }
        OwnerWatch.housekeeping(s, now)
        notifyChanged()
    }

    private suspend fun dispatchLoop(scope: CoroutineScope, s: TaskStore, incoming: suspend (AgentTask, AppAgent) -> TaskVerdict) {
        val lane = Semaphore(LANES)
        var lastHousekeeping = System.currentTimeMillis()
        while (true) {
            try {
                lane.acquire()
                var started = false
                try {
                    val next = pickAndClaim(s)
                    if (next != null) { launchTask(scope, s, next, lane, incoming); started = true }
                } finally {
                    if (!started) lane.release()
                }
                if (started) continue
                // Nothing runnable: housekeeping if due, then sleep until a signal, the next retry, the end of the send pause, or the next housekeeping.
                val now = System.currentTimeMillis()
                if (now - lastHousekeeping >= HOUSEKEEPING_MS) { housekeeping(s); lastHousekeeping = now }
                var until = s.nextNotBefore(now)
                val pauseEnd = sendPauseEnd(s)
                if (pauseEnd > now) until = if (until == null) pauseEnd else minOf(until, pauseEnd)
                val wait = if (until != null) (until - now).coerceIn(50L, HOUSEKEEPING_MS) else HOUSEKEEPING_MS
                withTimeoutOrNull(wait) { wake.receive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "dispatcher problem: " + e.javaClass.simpleName)
                delay(5_000L)
            }
        }
    }

    /**
     * Takes the oldest task that may run now and claims it (PENDING -> RUNNING, atomic in the database). Rules:
     * the app must have an agent (or, for a send, a sender); the conversation must have no running task; an older waiting task of
     * the same conversation (delayed, without agent, or waiting for the send pause) blocks the newer ones, so the order inside
     * a conversation is kept; and a send waits until the pause after the last confirmed send is over. Only a send waits for
     * the pause: drafting for incoming messages never does.
     */
    private fun pickAndClaim(s: TaskStore): Pick? {
        val sources = AgentManager.sourcesServed() + sourcesWithSender()
        if (sources.isEmpty()) return null
        val now = System.currentTimeMillis()
        val pauseEnd = sendPauseEnd(s)
        val blocked = HashSet<String>()
        for (t in s.pending(sources, 200)) {
            if (t.conversationId in blocked) continue
            if (t.conversationId in busyConversations || t.notBefore > now) { blocked.add(t.conversationId); continue }
            // Step 11: the conversation's lifecycle. Only an ACTIVE conversation may start any task (drafting or sending). A task made before
            // this version has no key and is not checked here (the lifecycle table was empty then); every newer task carries its key.
            val lifeKey = t.counterKey
            if (lifeKey != null) {
                val why = ConversationLifecycle.refusal(s, lifeKey)
                if (why == ConversationLifecycle.UNAVAILABLE) { blocked.add(t.conversationId); continue }   // cannot tell: wait, cancel nothing
                if (why != null) {
                    s.cancelPendingOfConversation(t.conversationId, why, now)
                    AutoReplyLog.add("queue", "cancelled_" + why, true)
                    blocked.add(t.conversationId); notifyChanged(); continue
                }
            }
            // Step 9: the owner check. Only a conversation whose 30-second wait ended without a sign of a manual reply may start a task.
            when (val g = OwnerWatch.gate(s, t, now)) {
                is OwnerWatch.Gate.Wait -> {
                    s.delayPendingOfConversation(t.conversationId, g.until)   // keeps the saved deadline in step so the dispatcher wakes on time
                    blocked.add(t.conversationId); continue
                }
                is OwnerWatch.Gate.Cancel -> {
                    s.cancelPendingOfConversation(t.conversationId, g.reason, now)
                    AutoReplyLog.add("owner", t.source.name.take(4) + "_30s_check_stopped_" + g.reason, true)
                    blocked.add(t.conversationId); notifyChanged(); continue
                }
                OwnerWatch.Gate.Proceed -> { if (t.type == TaskType.INCOMING_MESSAGE) AutoReplyLog.add("owner", t.source.name.take(4) + "_30s_check_passed_no_reply_seen") }
            }
            if (t.type == TaskType.INCOMING_MESSAGE) {
                val agent = AgentManager.agentFor(t.source)
                if (agent == null) { blocked.add(t.conversationId); continue }
                val claimed = s.claim(t.id, agent.id, now) ?: continue   // somebody else (or a cancel) got it first
                busyConversations.add(claimed.conversationId)
                return Pick(claimed, agent, null)
            } else {
                val sender = senderFor(t.source)
                if (sender == null || now < pauseEnd) { blocked.add(t.conversationId); continue }
                val claimed = s.claim(t.id, sender.id, now) ?: continue
                busyConversations.add(claimed.conversationId)
                return Pick(claimed, null, sender)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ the pause between confirmed sends

    /** The pause the user chose, always inside 3..5 s. */
    fun sendDelayMs(ctx: Context? = appCtx): Long {
        val c = ctx ?: return TaskLimits.SEND_DELAY_DEFAULT_MS
        return Prefs.getInt(c, Prefs.SEND_DELAY_MS, TaskLimits.SEND_DELAY_DEFAULT_MS.toInt()).toLong()
            .coerceIn(TaskLimits.SEND_DELAY_MIN_MS, TaskLimits.SEND_DELAY_MAX_MS)
    }

    fun setSendDelayMs(ctx: Context, ms: Long) {
        Prefs.putInt(ctx, Prefs.SEND_DELAY_MS, ms.coerceIn(TaskLimits.SEND_DELAY_MIN_MS, TaskLimits.SEND_DELAY_MAX_MS).toInt())
    }

    /**
     * When the next send may start: the finish time of the last CONFIRMED send plus the pause; 0 = no pause. It counts from
     * confirmed sends only (failed, uncertain and cancelled ones start no pause) and is read from the database, so it is
     * the same after a restart. A finish time in the future (clock set back) is ignored.
     */
    private fun sendPauseEnd(s: TaskStore): Long {
        val last = s.lastConfirmedSendAt()
        if (last <= 0L || last > System.currentTimeMillis()) return 0L
        return last + sendDelayMs()
    }

    // ------------------------------------------------------------------ running one task

    private fun launchTask(scope: CoroutineScope, s: TaskStore, pick: Pick, lane: Semaphore, incoming: suspend (AgentTask, AppAgent) -> TaskVerdict) {
        val task = pick.task
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val sender = pick.sender
                val agent = pick.agent
                if (sender != null) executeSend(s, task, sender)
                else if (agent != null) executeIncoming(s, task, agent, incoming)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An unexpected problem ends this one task only; it must never cancel the dispatcher or other tasks.
                Log.w(TAG, "task " + task.id + " failed unexpectedly: " + e.javaClass.simpleName)
                AutoReplyLog.add("queue", "task_crashed_" + e.javaClass.simpleName.take(40), true)
                failUnexpectedly(s, task)
            } finally {
                busyConversations.remove(task.conversationId)
                jobs.remove(task.id)
                cancelReasons.remove(task.id)
                lane.release()
                notifyChanged()
                wake.trySend(Unit)
            }
        }
        jobs[task.id] = job
        job.start()
    }

    /** A send that had been handed to the sender when the manager itself failed is uncertain, not simply failed. */
    private fun failUnexpectedly(s: TaskStore, task: AgentTask) {
        try {
            val now = System.currentTimeMillis()
            val uncertain = task.type == TaskType.SEND_REPLY && s.get(task.id)?.sendPhase == SendPhase.ATTEMPTING
            if (uncertain) s.finish(task.id, TaskStatus.FAILED, "send_uncertain", now, SendPhase.UNCERTAIN)
            else s.finish(task.id, TaskStatus.FAILED, "brain_error", now)
        } catch (_: Throwable) {}
    }

    private fun backoffMs(attempts: Int): Long = TaskLimits.RETRY_BASE_MS * (1L shl ((attempts - 1) * 2).coerceIn(0, 10))

    /** One run of one INCOMING_MESSAGE task. It always ends in exactly one final state or goes back to waiting. */
    private suspend fun executeIncoming(s: TaskStore, task: AgentTask, agent: AppAgent, incoming: suspend (AgentTask, AppAgent) -> TaskVerdict) {
        fun end(status: TaskStatus, outcome: String?) { s.finish(task.id, status, outcome, System.currentTimeMillis()) }
        try {
            val reason = cancelReasons[task.id]
            if (reason != null) { end(TaskStatus.CANCELLED, reason); return }
            if (System.currentTimeMillis() - task.createdAt > TaskLimits.MAX_AGE_MS) { end(TaskStatus.CANCELLED, "expired"); return }

            when (val v = incoming(task, agent)) {
                is TaskVerdict.Done -> end(TaskStatus.COMPLETED, v.note ?: "done")
                is TaskVerdict.Dropped -> { AutoReplyLog.add("queue", "dropped_" + v.code, true); end(TaskStatus.CANCELLED, v.code) }
                is TaskVerdict.Failed ->
                    if (v.retryable && task.attempts < TaskLimits.MAX_ATTEMPTS) {
                        val now = System.currentTimeMillis()
                        s.requeue(task.id, now + backoffMs(task.attempts), v.code, now)
                    } else end(TaskStatus.FAILED, v.code)
            }
        } catch (e: CancellationException) {
            // Cancelled on request: final state CANCELLED. Cancelled because the service stops: stay RUNNING, recovery decides at the next start.
            val reason = cancelReasons[task.id]
            if (reason != null) end(TaskStatus.CANCELLED, reason) else throw e
        }
    }

    /**
     * One run of one SEND_REPLY task. The rules that prevent a duplicate or a false "sent":
     * 1. the phase ATTEMPTING is saved BEFORE the sender is called (a crash from here on is an uncertain send);
     * 2. COMPLETED is written only for [SendResult.Confirmed];
     * 3. a timeout, an exception or a cancel after the attempt began is an uncertain send, never a failed one and never repeated;
     * 4. only a send that is certainly NOT sent and marked retryable goes back to waiting.
     */
    private suspend fun executeSend(s: TaskStore, task: AgentTask, sender: MessageSender) {
        fun end(status: TaskStatus, outcome: String?, phase: SendPhase? = null) { s.finish(task.id, status, outcome, System.currentTimeMillis(), phase) }
        var attempted = false
        try {
            val reason = cancelReasons[task.id]
            if (reason != null) { end(TaskStatus.CANCELLED, reason); return }
            if (System.currentTimeMillis() - task.createdAt > TaskLimits.MAX_AGE_MS) { end(TaskStatus.CANCELLED, "expired"); return }

            // v2.39: one reply per burst. If this chat was answered after the message arrived, this send is not needed (nothing goes out).
            if (task.sendKind == SendKind.REPLY) {
                val app = appCtx
                val covered = try {
                    val m = if (app != null) MessageGateway.message(app, task.conversationId, task.messageId) else null
                    m != null && BurstGuard.answered(m, s.replyCoveredId(task.conversationId, task.id, false), s.replyBarrier(task.conversationId, task.id, false, System.currentTimeMillis()))
                } catch (e: CancellationException) { throw e } catch (_: Throwable) { false }
                if (covered) { AutoReplyLog.add("burst", task.source.name.take(4) + "_send_skipped_already_answered"); end(TaskStatus.CANCELLED, "already_answered"); return }
            }

            // Step 10: everything that can be checked BEFORE a send is checked here, before the phase becomes ATTEMPTING. A refusal
            // is certain (nothing went out) and ends NOT_SENT; a cancel during these checks ends CANCELLED, not "uncertain".
            val refusal: SendResult.NotSent? = try {
                sender.precheck(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SendResult.NotSent("precheck_error_" + e.javaClass.simpleName.take(40), false)
            }
            if (refusal != null) { AutoReplyLog.add("send", task.source.name.take(4) + "_not_sent_" + refusal.code, true); end(TaskStatus.FAILED, refusal.code, SendPhase.NOT_SENT); return }

            if (!s.markAttempting(task.id, System.currentTimeMillis())) { end(TaskStatus.FAILED, "send_state_error"); return }
            attempted = true

            val result: SendResult = try {
                withTimeout(TaskLimits.SEND_TIMEOUT_MS) { sender.send(task) }
            } catch (_: TimeoutCancellationException) {
                SendResult.Uncertain("send_timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SendResult.Uncertain("sender_error_" + e.javaClass.simpleName.take(40))   // class name only: messages may contain chat text
            }

            when (result) {
                is SendResult.Confirmed -> { AutoReplyLog.add("send", task.source.name.take(4) + (if (task.sendKind == SendKind.CLOSING) "_closing" else "_reply") + "_sent_confirmed"); end(TaskStatus.COMPLETED, "sent_confirmed", SendPhase.CONFIRMED) }
                is SendResult.NotSent ->
                    if (result.retryable && task.attempts < TaskLimits.MAX_ATTEMPTS) {
                        val now = System.currentTimeMillis()
                        s.requeue(task.id, now + backoffMs(task.attempts), result.code, now, SendPhase.NONE)
                    } else { AutoReplyLog.add("send", task.source.name.take(4) + "_not_sent_" + result.code, true); end(TaskStatus.FAILED, result.code, SendPhase.NOT_SENT) }
                is SendResult.Uncertain -> { AutoReplyLog.add("send", task.source.name.take(4) + "_handed_over_not_verified_" + result.code, true); end(TaskStatus.FAILED, result.code, SendPhase.UNCERTAIN) }
            }
        } catch (e: CancellationException) {
            val reason = cancelReasons[task.id]
            if (reason == null) throw e   // the service is stopping: stays RUNNING, recovery treats an ATTEMPTING send as uncertain
            if (attempted) end(TaskStatus.FAILED, "send_uncertain_cancelled", SendPhase.UNCERTAIN)   // the cancel may have come too late
            else end(TaskStatus.CANCELLED, reason)
        }
    }

    // ------------------------------------------------------------------ state and control (usable from any code, off the main thread)

    /** State of one conversation: idle, queued or busy. No message text. */
    internal fun conversationStateOf(conversationId: String): ConversationState {
        val idle = ConversationState(conversationId, null, ConversationPhase.IDLE, null, 0, 0L, null)
        return try { storeRef?.conversationState(conversationId) ?: idle } catch (_: Exception) { idle }
    }

    suspend fun conversationState(ctx: Context, conversationId: String): ConversationState = withContext(Dispatchers.IO) {
        val idle = ConversationState(conversationId, null, ConversationPhase.IDLE, null, 0, 0L, null)
        if (!TaskStore.validConversationId(conversationId)) return@withContext idle
        try { store(ctx)?.conversationState(conversationId) ?: idle } catch (_: Exception) { idle }
    }

    /**
     * Cancels a task. A waiting task is cancelled at once; a running one has its coroutine cancelled and ends as CANCELLED
     * (a send that was already handed to the sender ends as an uncertain send). False when the task is unknown or finished.
     */
    suspend fun cancelTask(ctx: Context, taskId: String, reason: String = CANCELLED): Boolean = withContext(Dispatchers.IO) {
        if (!TaskStore.validId(taskId)) return@withContext false
        val s = store(ctx) ?: return@withContext false
        try {
            if (s.cancelPending(taskId, reason, System.currentTimeMillis())) { notifyChanged(); return@withContext true }
            val t = s.get(taskId) ?: return@withContext false
            if (t.status != TaskStatus.RUNNING) return@withContext false
            requestCancel(t.id, reason)
            true
        } catch (_: Exception) { false }
    }

    /** Cancels everything waiting or running for ONE conversation (nothing of any other conversation). Returns how many tasks were affected. */
    suspend fun cancelConversation(ctx: Context, conversationId: String, reason: String = CANCELLED): Int = withContext(Dispatchers.IO) {
        if (!TaskStore.validConversationId(conversationId)) return@withContext 0
        val s = store(ctx) ?: return@withContext 0
        try {
            val waiting = s.cancelPendingOfConversation(conversationId, reason, System.currentTimeMillis())
            val running = s.running().filter { it.conversationId == conversationId }
            running.forEach { requestCancel(it.id, reason) }
            notifyChanged()
            waiting + running.size
        } catch (_: Exception) { 0 }
    }

    override suspend fun cancelConversation(conversationId: String, reason: String): Int {
        val app = appCtx ?: return 0
        return cancelConversation(app, conversationId, reason)
    }

    /** Cancels everything waiting or running. Returns how many tasks were affected. */
    suspend fun cancelAll(ctx: Context, reason: String = CANCELLED): Int = withContext(Dispatchers.IO) {
        val s = store(ctx) ?: return@withContext 0
        try {
            val waiting = s.cancelAllPending(reason, System.currentTimeMillis())
            val running = s.running()
            running.forEach { requestCancel(it.id, reason) }
            notifyChanged()
            waiting + running.size
        } catch (_: Exception) { 0 }
    }

    /**
     * Step 13 (Automation Control Panel): automation was switched off, paused or stopped for [sources]. Ends every WAITING task of those
     * apps as CANCELLED with [reason], and cancels the RUNNING reply-drafting tasks of those apps (an AI draft is harmless to cancel).
     * A RUNNING send is deliberately NOT cancelled: if the reply action was already fired, cancelling would turn a message that
     * may have been delivered into an unverified one. Such a send is stopped by the sender's own switch checks (before the attempt and
     * right before the reply action is fired); one already fired finishes and is verified as usual.
     * Returns how many waiting and how many running drafting tasks were affected. Blocking database work: call it off the main thread.
     */
    internal fun cancelForAutomation(ctx: Context, sources: Set<MessageSource>, reason: String): Pair<Int, Int> {
        val s = store(ctx) ?: return 0 to 0
        return try {
            val waiting = s.cancelPendingOfSources(sources, reason, System.currentTimeMillis())
            val drafting = s.running().filter { it.type == TaskType.INCOMING_MESSAGE && it.source in sources }
            drafting.forEach { requestCancel(it.id, reason) }
            notifyChanged()
            wake.trySend(Unit)
            waiting to drafting.size
        } catch (_: Exception) { 0 to 0 }
    }

    /** Step 13: cancels the WAITING tasks of one conversation (the owner paused or stopped it). A running task is left to the checks of the sender. */
    internal fun cancelWaitingOfConversation(ctx: Context, conversationId: String, reason: String): Int {
        if (!TaskStore.validConversationId(conversationId)) return 0
        val s = store(ctx) ?: return 0
        return try {
            val n = s.cancelPendingOfConversation(conversationId, reason, System.currentTimeMillis())
            notifyChanged(); wake.trySend(Unit)
            n
        } catch (_: Exception) { 0 }
    }

    private fun requestCancel(taskId: String, reason: String) {
        cancelReasons[taskId] = reason
        jobs[taskId]?.cancel(CancellationException("cancel_requested"))
        // If the job has not been registered yet, the task sees the reason at its first step.
    }

    // ------------------------------------------------------------------ owner check (step 9)

    /** Const for tasks cancelled because the owner may have taken over but it is not certain. See [OwnerWatch]. */
    const val OWNER_UNCERTAIN = OwnerWatch.UNCERTAIN_CODE

    /** The task database, for [OwnerWatch]. Null when it cannot be opened. */
    internal fun openStore(ctx: Context): TaskStore? = store(ctx)

    /**
     * From the Main Brain, for a new message: opens or joins the conversation's wait and says when its task is due. Throws when the
     * manager is not ready, so the processor offers the message again later (the same rule as [submit]). A "skip" wakes the
     * dispatcher so older waiting tasks of that conversation are cancelled right away.
     */
    internal fun ownerAdmit(message: ProcessedMessage, now: Long): OwnerWatch.Admission {
        val s = storeRef ?: throw IllegalStateException("task_manager_not_ready")
        val a = OwnerWatch.admit(s, message, now)
        wake.trySend(Unit)
        return a
    }

    /**
     * The owner replied or may have taken over: cancels everything waiting AND running for ONE conversation (never another one).
     * Same effect as [cancelConversation] but callable from the message worker thread without a coroutine. Returns the number affected.
     */
    internal fun cancelForOwner(ctx: Context, conversationId: String, reason: String): Int {
        if (!TaskStore.validConversationId(conversationId)) return 0
        val s = store(ctx) ?: return 0
        return try {
            val waiting = s.cancelPendingOfConversation(conversationId, reason, System.currentTimeMillis())
            val running = s.running().filter { it.conversationId == conversationId }
            running.forEach { requestCancel(it.id, reason) }
            notifyChanged()
            wake.trySend(Unit)
            waiting + running.size
        } catch (_: Exception) { 0 }
    }

    internal fun ownerCounts(): OwnerCounts = try { storeRef?.ownerCounts() ?: OwnerCounts() } catch (_: Exception) { OwnerCounts() }

    // ------------------------------------------------------------------ conversation lifecycle (step 11)

    /**
     * From the Main Brain, for a new message: null = the conversation is ACTIVE and the message may become a task; otherwise the short code
     * why not ("conversation_paused", ...). Throws when the manager is not ready or the state cannot be read, so the processor offers the
     * message again later instead of losing it or treating the conversation as active.
     */
    internal fun lifecycleRefusal(key: String): String? {
        val s = storeRef ?: throw IllegalStateException("task_manager_not_ready")
        val why = ConversationLifecycle.refusal(s, key)
        if (why == ConversationLifecycle.UNAVAILABLE) throw IllegalStateException("lifecycle_unavailable")
        return why
    }

    /** A conversation's state was changed by the owner (Settings): wakes the dispatcher so waiting tasks of it are cancelled at once, and refreshes the status. */
    internal fun lifecycleChanged() { wake.trySend(Unit); notifyChanged() }

    // ------------------------------------------------------------------ storage and status

    internal fun counts(served: Set<MessageSource>): TaskCounts =
        try { storeRef?.counts(served + sourcesWithSender()) ?: TaskCounts() } catch (_: Exception) { TaskCounts() }

    @Synchronized private fun store(ctx: Context): TaskStore? {
        storeRef?.let { return it }
        val app = ctx.applicationContext
        return try {
            TaskStore(app).also { it.writableDatabase; storeRef = it }
        } catch (e: SQLiteException) {
            // damaged file: start again with an empty one rather than never routing anything
            Log.w(TAG, "task database could not be opened (" + e.javaClass.simpleName + "), recreating it")
            try { app.deleteDatabase(TaskStore.NAME); TaskStore(app).also { it.writableDatabase; storeRef = it } } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    private fun notifyChanged() { try { onChanged?.invoke() } catch (_: Throwable) {} }
}
