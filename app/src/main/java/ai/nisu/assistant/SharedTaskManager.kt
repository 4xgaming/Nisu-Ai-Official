package ai.nisu.assistant

/*
 * Shared Task Manager contract (step 8). Replaces the empty socket of step 6.
 *
 * There is ONE task manager for the whole app ([TaskManager], TaskManager.kt). It owns the task database, the one global
 * queue for WhatsApp and Messenger, the rule "one messaging task at a time", the pause between confirmed sends, the recovery
 * after a restart and the cancellation. Both Master Agents hold this interface (default [NoSharedTaskManager]) and the
 * Agent Catalog hands them the real manager. Neither agent calls it yet: nothing in this build sends or replies by itself.
 *
 * What a later step will use it for:
 * - [submitSend]: the automatic-reply workflow asks for ONE prepared draft to be sent. Ids only, never message text.
 * - [cancelConversation]: the owner answered by hand, so everything still waiting for that conversation is dropped.
 *
 * Step 9: [cancelConversation] with [TaskManager.OWNER_REPLIED] / [TaskManager.OWNER_UNCERTAIN] is now used by the owner check
 * (OwnerWatch.kt) when Raj answers by hand or may have taken over; it runs through TaskManager.cancelForOwner.
 *
 * Rules for every implementation: ids only, no message text, no sender or chat names in logs.
 */

/** A request to send the prepared draft that answers one incoming message. Describes ONE conversation of ONE app. */
class SendRequest(
    val source: MessageSource,
    val packageName: String,
    val conversationId: String,
    /** The incoming message the draft answers ([ProcessedMessage.messageId]). */
    val messageId: Long,
    /** Step 10: true = the one polite closing message of a conversation that reached the reply limit (no draft needed). */
    val closing: Boolean = false,
    /** Step 10: hash naming ONE person on ONE app ([AutoReply.counterKey]); the reply counter lives under it. Required for a send. */
    val counterKey: String? = null
)

sealed class SubmitResult {
    /** Saved in the queue. This is NOT "sent": the task still has to run, and only the sender can confirm. */
    class Accepted(val taskId: String) : SubmitResult()
    /** A send for this very message already exists (waiting, running, or finished). Nothing was added. */
    object Duplicate : SubmitResult()
    /** Not queued. [reason] is a short code such as "no_sender_for_app". */
    class Rejected(val reason: String) : SubmitResult()
}

interface SharedTaskManager {
    /** false = there is no running task manager (the app's background service is off, or this is the empty default). */
    val available: Boolean

    /** Queues a send. At most one send per incoming message, ever (also across restarts). */
    fun submitSend(request: SendRequest): SubmitResult

    /**
     * Cancels everything waiting or running for ONE conversation (for example [TaskManager.OWNER_REPLIED]).
     * A send that was already handed to the sender cannot be taken back: it ends as an uncertain send.
     * Returns how many tasks were affected.
     */
    suspend fun cancelConversation(conversationId: String, reason: String): Int
}

/** The default of the agents' constructors: no manager. Queues nothing, cancels nothing. */
object NoSharedTaskManager : SharedTaskManager {
    override val available: Boolean = false
    override fun submitSend(request: SendRequest): SubmitResult = SubmitResult.Rejected("task_manager_not_available")
    override suspend fun cancelConversation(conversationId: String, reason: String): Int = 0
}
