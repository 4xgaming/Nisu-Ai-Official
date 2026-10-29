package ai.nisu.assistant

import kotlinx.coroutines.CancellationException
import java.util.Locale

/*
 * Messenger action engine (step 6).
 *
 * An action is a small closed unit of work on ONE incoming Messenger message. Three exist and nothing else can be run:
 *
 *   IDENTIFY_MESSAGE_TYPE   what kind of message is it (text, photo, voice message, ...)           real, no AI
 *   REPLY_TO_INCOMING       prepare a reply DRAFT for this message                                  real, uses the AI
 *   CONTINUE_CONVERSATION   the same, for a chat that already has earlier saved messages: the       real, uses the AI
 *                           draft takes that chat's own history into account
 *
 * A draft is text kept in memory. Nothing in this step sends it, taps a button, opens Messenger or touches the screen, and
 * no action ever reports success for something that did not happen: a result is either what was really done
 * ([MessengerActionResult.TypeIdentified], [MessengerActionResult.DraftPrepared]), a deliberate "nothing to do"
 * ([MessengerActionResult.NoAction]) or a failure. AI output is untrusted text; it is cleaned and stored, never executed,
 * and there is no code path from it to a shell, a command or an Android action.
 *
 * The WhatsApp agent has an engine of the same shape (WhatsAppActions.kt). The two are kept apart on purpose and share
 * only [ActionSupport]: a change to one agent cannot change the other.
 */

enum class MessengerActionType { IDENTIFY_MESSAGE_TYPE, REPLY_TO_INCOMING, CONTINUE_CONVERSATION }

class MessengerCapability(val name: String, val support: ActionSupport, val note: String)

/**
 * The honest list of what the Messenger agent can and cannot do in THIS build. Separate from MessengerKnowledge on purpose:
 * knowing that Messenger has a feature says nothing about whether Android can automate it.
 */
object MessengerCapabilities {
    val ALL: List<MessengerCapability> = listOf(
        MessengerCapability("Identify the message type", ActionSupport.IMPLEMENTED,
            "From the notification text only (English wording and symbols); other languages look like text."),
        MessengerCapability("Prepare a reply draft", ActionSupport.DRAFT_ONLY,
            "Text messages only. The draft stays in memory; nothing is sent."),
        MessengerCapability("Continue an existing conversation", ActionSupport.DRAFT_ONLY,
            "A draft that uses that chat's own saved messages, prepared only because a new message arrived."),
        MessengerCapability("Send a prepared reply", ActionSupport.IMPLEMENTED,
            "Step 10, only through the Automatic replies switch: the notification's own Reply button, after the owner check, and only counted as sent when the app shows the message. " +
                "Chats whose notification has no Reply button are not answered."),
        MessengerCapability("Automatic replies / waiting time", ActionSupport.IMPLEMENTED, "Off by default (Settings). Private chats only, newest message only, at most 20 per person, then one closing message."),
        MessengerCapability("Start a conversation or message anyone unprompted", ActionSupport.NOT_IMPLEMENTED, "Deliberately not offered."),
        MessengerCapability("Understand photos, videos, voice messages, stickers or files", ActionSupport.NOT_IMPLEMENTED, "Only a placeholder text is visible."),
        MessengerCapability("Send media, react, mark as read, call, accept message requests, manage groups", ActionSupport.NOT_IMPLEMENTED, "No such action exists.")
    )

    fun support(type: MessengerActionType): ActionSupport = when (type) {
        MessengerActionType.IDENTIFY_MESSAGE_TYPE -> ActionSupport.IMPLEMENTED
        MessengerActionType.REPLY_TO_INCOMING, MessengerActionType.CONTINUE_CONVERSATION -> ActionSupport.DRAFT_ONLY
    }
}

/** Everything one action may look at. It describes ONE conversation: there is no way to reach another one from here. */
class MessengerActionEnv(
    val message: ProcessedMessage,
    val info: MsgrMessageInfo,
    /** Earlier saved messages of THIS conversation, oldest first. Never contains [message] or anything newer. */
    val history: List<StoredMessage>,
    /** The Main Brain (the only writer of reply text; the agent has no AI access of its own). */
    val responses: BrainReplyPort,
    /** The user's switch "write reply drafts with Gemini". Off = nothing is sent to the AI. */
    val draftsEnabled: Boolean,
    /** false = a newer text message of this chat arrived while the draft was being written: the draft is dropped. */
    val stillRelevant: (suspend () -> Boolean)?,
    val now: Long,
    /** v2.0: first reply to this person: the draft starts with the introduction of NISU AI as Raj's AI assistant. */
    val introduce: Boolean = false
)

sealed class MessengerActionResult {
    class TypeIdentified(val info: MsgrMessageInfo) : MessengerActionResult()
    class DraftPrepared(val draft: MessengerDraft) : MessengerActionResult()
    /** Nothing to do, by design. [code] is a short code such as "unsupported_image" or "no_reply_needed". */
    class NoAction(val code: String) : MessengerActionResult()
    class Failed(val code: String, val retryable: Boolean) : MessengerActionResult()
}

interface MessengerAction {
    val type: MessengerActionType
    suspend fun execute(env: MessengerActionEnv): MessengerActionResult
}

class MessengerIdentifyMessageTypeAction : MessengerAction {
    override val type = MessengerActionType.IDENTIFY_MESSAGE_TYPE
    override suspend fun execute(env: MessengerActionEnv): MessengerActionResult = MessengerActionResult.TypeIdentified(env.info)
}

class MessengerReplyToIncomingAction : MessengerAction {
    override val type = MessengerActionType.REPLY_TO_INCOMING
    override suspend fun execute(env: MessengerActionEnv): MessengerActionResult = MessengerDrafter.draft(env, type)
}

class MessengerContinueConversationAction : MessengerAction {
    override val type = MessengerActionType.CONTINUE_CONVERSATION
    override suspend fun execute(env: MessengerActionEnv): MessengerActionResult = MessengerDrafter.draft(env, type)
}

object MessengerActionEngine {
    /** Runs one action. Never throws except for cancellation; any other problem becomes a failure that carries no message text. */
    suspend fun run(action: MessengerAction, env: MessengerActionEnv): MessengerActionResult =
        try {
            action.execute(env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            MessengerActionResult.Failed("action_error_" + e.javaClass.simpleName.take(40), false)   // class name only: messages may contain chat text
        }
}

/**
 * The Messenger side of reply drafting. The text itself is written by the MAIN BRAIN ([BrainReplyPort] -> MainBrainReplyWriter: its own model,
 * same general rules, same prompt layout and limits as for WhatsApp). What stays here is only what belongs to Messenger: the
 * notes for the AI ([APP_INSTRUCTIONS]), the knowledge of which saved lines are placeholders ([MessengerKnowledge]) and the
 * translation of the engine's result into a Messenger action result. Nothing is sent.
 */
internal object MessengerDrafter {
    const val APP_INSTRUCTIONS =
        "This is a Facebook Messenger chat (private chat or group; Messenger or Messenger Lite). " + MessengerKnowledge.AI_VIEW_LIMITS +
            " Messenger is often used for scams (fake prizes, fake help, urgent money requests, hacked-friend messages, " +
            "\"verify your account\" links): answer any such message with a brief, neutral reply that does not comply."

    suspend fun draft(env: MessengerActionEnv, purpose: MessengerActionType): MessengerActionResult {
        // v2.2: a voice message cannot be heard, so it gets the fixed apology in the conversation's language (no AI call, nothing invented about the audio).
        if (env.info.type == MsgrMessageType.VOICE_NOTE) {
            if (env.stillRelevant?.invoke() == false) return MessengerActionResult.NoAction(ResponseCodes.NO_LONGER_RELEVANT)
            val text = BrainLanguage.voiceReply(env.history, { MessengerKnowledge.classify(it) != MsgrMessageType.TEXT }, env.introduce)
            return MessengerActionResult.DraftPrepared(MessengerDraft(env.message.conversationId, env.message.messageId, text, purpose, System.currentTimeMillis()))
        }
        env.info.draftBlocker?.let { return MessengerActionResult.NoAction(it) }
        if (!env.draftsEnabled) return MessengerActionResult.NoAction("ai_disabled")
        val response = env.responses.generate(
            ResponseRequest(
                platform = MessageSource.MESSENGER,
                conversationId = env.message.conversationId,
                message = env.message,
                history = env.history,
                appInstructions = APP_INSTRUCTIONS,
                describeNonText = { raw -> MessengerKnowledge.classify(raw).let { if (it == MsgrMessageType.TEXT) null else it.label } },
                continuing = purpose == MessengerActionType.CONTINUE_CONVERSATION,
                stillRelevant = env.stillRelevant,
                introduce = env.introduce
            )
        )
        return when (response.status) {
            ResponseStatus.GENERATED -> {
                val text = response.text
                if (text.isNullOrEmpty()) MessengerActionResult.Failed(ResponseCodes.EMPTY_RESPONSE, false)
                else MessengerActionResult.DraftPrepared(MessengerDraft(response.conversationId, response.forMessageId, text, purpose, response.generatedAt))
            }
            ResponseStatus.NO_REPLY_NEEDED, ResponseStatus.SKIPPED, ResponseStatus.DISCARDED ->
                MessengerActionResult.NoAction(response.note ?: response.status.name.lowercase(Locale.ROOT))
            ResponseStatus.FAILED ->
                MessengerActionResult.Failed(response.error?.code ?: "ai_unknown", response.error?.retryable ?: false)
        }
    }
}
