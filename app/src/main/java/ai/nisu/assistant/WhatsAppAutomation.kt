package ai.nisu.assistant

import android.content.Context

/*
 * WhatsApp automation interface (step 5): a prepared socket, nothing more.
 *
 * Later steps plug the automatic-reply workflow in here. The owner-availability check and the 30-second wait now exist (step 9,
 * OwnerWatch.kt) and run BEFORE any agent task starts, in the Task Manager; this hook is not part of them. Still no automatic
 * reply and no sending of any kind. Step 10 plugs the automatic-reply workflow in here ([WhatsAppAutoReply], AutoReply.kt); the
 * default [DisabledWhatsAppAutomation] still answers "not enabled" and does nothing. The agent calls the hook after it prepared a
 * draft and only records the answer; it never sends because of it.
 *
 * Rules for the future implementation: it only ever reacts to an incoming message that already exists (never starts a
 * conversation or sends unsolicited messages), it works on one conversation at a time, and it must not log message text.
 */

enum class OwnerAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

/** Future: answers "is the owner around right now?". No implementation exists yet. */
interface OwnerAvailabilityCheck {
    suspend fun check(appContext: Context): OwnerAvailability
}

/** What the agent tells the automation layer after it prepared a draft. Describes ONE conversation and ONE incoming message. */
class AutoReplyRequest(
    val conversationId: String,
    val messageId: Long,
    val draft: WhatsAppDraft,
    /** When NISU AI stored the incoming message. */
    val messageReceivedAt: Long
)

sealed class AutomationDecision {
    /** Automatic replies are switched off (or this app's hook is the disabled default). Nothing was queued. */
    object NotEnabled : AutomationDecision()
    /** Step 10: the send of the draft was queued in the Shared Task Manager. This is NOT "sent": only the sender can confirm a send. */
    class Queued(val taskId: String) : AutomationDecision()
    /** Step 10: automation is on, but this draft is not sent. [code] is a short code such as "limit_reached" or "not_private_chat". */
    class Skipped(val code: String) : AutomationDecision()
}

interface WhatsAppAutomation {
    /** false = the workflow does not run. */
    val enabled: Boolean
    suspend fun onDraftPrepared(request: AutoReplyRequest): AutomationDecision
}

object DisabledWhatsAppAutomation : WhatsAppAutomation {
    override val enabled: Boolean = false
    override suspend fun onDraftPrepared(request: AutoReplyRequest): AutomationDecision = AutomationDecision.NotEnabled
}
