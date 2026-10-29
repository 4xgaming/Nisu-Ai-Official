package ai.nisu.assistant

/*
 * Messenger automation interface (step 6): a prepared socket, nothing more.
 *
 * Later steps plug the automatic-reply workflow in here. The owner-availability check and the 30-second wait now exist (step 9,
 * OwnerWatch.kt) and run BEFORE any agent task starts, in the Task Manager; this hook is not part of them. Still no automatic
 * reply and no sending of any kind. Step 10 plugs the automatic-reply workflow in here ([MessengerAutoReply], AutoReply.kt); the
 * default [DisabledMessengerAutomation] still answers "not enabled" and does nothing. The agent calls the hook after it prepared a
 * draft and only records the answer; it never sends because of it.
 *
 * [OwnerAvailability], [OwnerAvailabilityCheck] and [AutomationDecision] are the app-independent types declared in
 * WhatsAppAutomation.kt and are reused as they are. Only the request type is Messenger-specific, because it carries a
 * [MessengerDraft].
 *
 * Rules for the future implementation: it only ever reacts to an incoming message that already exists (never starts a
 * conversation or sends unsolicited messages), it works on one conversation at a time, and it must not log message text.
 */

/** What the agent tells the automation layer after it prepared a draft. Describes ONE conversation and ONE incoming message. */
class MessengerAutoReplyRequest(
    val conversationId: String,
    val messageId: Long,
    val draft: MessengerDraft,
    /** When NISU AI stored the incoming message. */
    val messageReceivedAt: Long
)

interface MessengerAutomation {
    /** false = the workflow does not run. */
    val enabled: Boolean
    suspend fun onDraftPrepared(request: MessengerAutoReplyRequest): AutomationDecision
}

object DisabledMessengerAutomation : MessengerAutomation {
    override val enabled: Boolean = false
    override suspend fun onDraftPrepared(request: MessengerAutoReplyRequest): AutomationDecision = AutomationDecision.NotEnabled
}
