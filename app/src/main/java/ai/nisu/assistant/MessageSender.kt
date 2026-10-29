package ai.nisu.assistant

/*
 * Message sender contract (step 8). Step 10 added the implementations (AutoReply.kt: one sender per app, both on the notification
 * Reply action) and [MessageSender.precheck]; the rules below are what they follow.
 *
 * A [MessageSender] is what will one day put a prepared draft into a chat (a later step). The Shared Task Manager runs it as
 * a SEND_REPLY task, alone, one at a time for WhatsApp and Messenger together, and never reports a send as done unless the
 * sender says [SendResult.Confirmed]. Because nothing implements this interface yet, nothing can be sent and the Task Manager
 * refuses to create send tasks (see [SharedTaskManager.submitSend]).
 *
 * Rules for an implementation:
 * - It gets ids only ([AgentTask]: app, conversation, the incoming message the draft answers). It reads the draft from the agent
 *   itself and must refuse (NotSent) if the draft is gone or answers another message.
 * - Confirmed means the chat app itself shows the message as sent. A tap that "should have worked" is not a confirmation.
 * - NotSent only when it is CERTAIN that nothing went out. Anything doubtful is Uncertain. Uncertain sends are never repeated.
 * - Message text, sender names and chat names are never logged; results carry short codes only.
 * - It must cooperate with cancellation. A cancelled send ends as an uncertain send, because the cancel may have come too late.
 */

sealed class SendResult {
    /** The message was delivered and the app shows it. The only result that counts as sent. */
    object Confirmed : SendResult()

    /** Certain that nothing was sent. [retryable]: a later attempt is safe (for example the chat app was not ready). */
    class NotSent(val code: String, val retryable: Boolean = false) : SendResult()

    /** The message may or may not have gone out. Never retried automatically. */
    class Uncertain(val code: String) : SendResult()
}

/** The answer of [MessageSender.verify] for a send that was interrupted by a restart. */
enum class SendCheck { DELIVERED, NOT_DELIVERED, UNKNOWN }

interface MessageSender {
    /** Unique, lowercase, like an agent id. */
    val id: String
    /** The app(s) this sender can write to. Two senders can never serve the same app. */
    val sources: Set<MessageSource>

    /**
     * Step 10: called by the Task Manager BEFORE the task becomes ATTEMPTING. Returns a [SendResult.NotSent] to refuse the send
     * (owner replied, limit reached, no reply action, ...): that is certain, nothing was sent. Null = go on. Must not send.
     * The default does not check anything. [send] must repeat the checks that can change, right before it sends.
     */
    suspend fun precheck(task: AgentTask): SendResult.NotSent? = null

    suspend fun send(task: AgentTask): SendResult

    /**
     * After a restart: can the sender find out whether the interrupted send was delivered? The default says no, and then the
     * send stays an uncertain send. Only a real check of the chat may answer DELIVERED or NOT_DELIVERED.
     */
    suspend fun verify(task: AgentTask): SendCheck = SendCheck.UNKNOWN
}
