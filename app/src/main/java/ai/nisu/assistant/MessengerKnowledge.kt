package ai.nisu.assistant

import java.util.Locale

/*
 * Messenger knowledge (step 6).
 *
 * What NISU AI KNOWS about Facebook Messenger messages as they reach it through the Notification Listener. This file is
 * knowledge only: it never touches the screen, never sends anything and says nothing about what NISU AI is able to DO.
 * What the agent can actually do is listed, honestly, in MessengerActions.kt (MessengerCapabilities). Knowing that
 * Messenger has calls, reactions or message requests does not mean Android can automate them.
 *
 * Everything here is judged from the notification text alone. Messenger does not document the placeholder texts it
 * shows for photos, voice messages, calls and so on; the wording differs between app versions and is translated. The
 * patterns below are therefore BEST EFFORT, English only, written from the wording Messenger usually shows, and have not
 * been checked against every version. A placeholder in another language looks like ordinary text; the drafting prompt
 * (MessengerActions.kt) tells the AI to answer NO_REPLY for such lines. A wrong guess only ever means "no draft".
 */

/** What a message turned out to be. Only [TEXT] is something a person wrote and the agent can answer. */
enum class MsgrMessageType(val label: String) {
    EMPTY("empty message"),
    TEXT("text message"),
    SYMBOLS_ONLY("emoji or symbols only"),
    IMAGE("photo"),
    VIDEO("video"),
    VOICE_NOTE("voice message"),
    STICKER("sticker"),
    GIF("GIF"),
    FILE("file or attachment"),
    LOCATION("location"),
    REACTION("reaction"),
    UNSENT("unsent message"),
    CALL_NOTICE("call notice"),
    HIDDEN_CONTENT("message whose content is not shown"),
    SYSTEM_NOTICE("system notice")
}

/** Facts about one message, derived from its own metadata and text. No text is copied into this object. */
class MsgrMessageInfo(
    val type: MsgrMessageType,
    /** PRIVATE / GROUP when Android said so, UNKNOWN otherwise. Never guessed. */
    val conversation: ConversationKind,
    /** true = Messenger Lite, false = Messenger. */
    val lite: Boolean,
    val senderKnown: Boolean,
    val timeExact: Boolean,
    /** The notification text ends with the "..." character Android uses when it cuts a long preview. */
    val possiblyTruncated: Boolean
) {
    /**
     * null = the message can be answered from its text. Otherwise a short code (letters, digits and _ only) that says
     * why no reply draft is made. Media, notices, emoji-only lines and cut-off previews are never answered: the agent
     * cannot see media and must not guess what an incomplete message says.
     */
    val draftBlocker: String?
        get() = when {
            type == MsgrMessageType.EMPTY -> "empty_message"
            type != MsgrMessageType.TEXT -> "unsupported_" + type.name.lowercase(Locale.ROOT)
            possiblyTruncated -> "incomplete_message"
            else -> null
        }
}

object MessengerKnowledge {
    const val MESSENGER_LITE_PACKAGE = "com.facebook.mlite"
    private const val TRUNCATION_MARK = '\u2026'

    /** What the notification pipeline of this app hands to the agent (see MessageParser / ProcessedMessage). */
    val AVAILABLE: List<String> = listOf(
        "The text of a message as the notification shows it (the first 2000 characters are kept).",
        "The chat name: the contact for a private chat, the group name for a group. A group without a name may be shown by Messenger as a list of its members.",
        "The sender's display name, only when the notification is in Android's messaging style (null otherwise, never guessed). Plain notifications carry no separate sender.",
        "Whether a chat is a group or private, only when Android says so; otherwise it stays unknown.",
        "The message time when Android provides it, otherwise the time of the notification.",
        "Which app it came from: Messenger or Messenger Lite."
    )

    val NOT_AVAILABLE: List<String> = listOf(
        "The content of photos, videos, voice messages, files and other attachments: at most a placeholder text appears.",
        "Your own sent messages: they are skipped, so the saved history of a chat holds incoming messages only.",
        "Messages that were read in the app, or dismissed, before NISU AI saw them.",
        "Which earlier message is being quoted or replied to, forwarded marks, seen status, reactions to your own messages beyond a short notice.",
        "A stable Messenger message or conversation id, or the other person's Facebook profile.",
        "Whether a chat is a message request, a Page or Marketplace chat, or an end-to-end encrypted chat. Messenger may show only a generic text for some of them; such a line gets no draft."
    )

    /** One sentence for the AI, so it does not pretend to see more than it does. */
    const val AI_VIEW_LIMITS =
        "You only see the text of recent incoming messages of this one chat. You cannot see photos, videos, voice messages, " +
            "files, stickers, quoted messages, or the owner's own earlier replies."

    // ------------------------------------------------------------------ message type recognition

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)   // declared first: used by the rules below
    private fun rx(pattern: String) = Regex(pattern, OPTIONS)

    /**
     * Whole-text placeholders, matched against the complete message only (a sentence that merely contains such words is
     * ordinary text). Order matters only where two rules could match the same text; none overlap on purpose.
     */
    private val WORD_RULES: List<Pair<Regex, MsgrMessageType>> = listOf(
        rx("""((sent|shared) (you )?(an? |\d+ )?)?(photos?|pictures?|images?)\.?""") to MsgrMessageType.IMAGE,
        rx("""((sent|shared) (you )?(an? |\d+ )?)?videos?\.?""") to MsgrMessageType.VIDEO,
        rx("""(sent (you )?(an? )?)?(voice message|voice clip|audio message|audio clip)\.?""") to MsgrMessageType.VOICE_NOTE,
        rx("""sent (you )?(an? )?audio\.?""") to MsgrMessageType.VOICE_NOTE,
        rx("""(.{1,60} )?(একটি )?(ভয়েস মেসেজ|ভয়েস ক্লিপ|অডিও মেসেজ|অডিও ক্লিপ)( পাঠিয়েছেন| পাঠিয়েছে)?[।.]?""") to MsgrMessageType.VOICE_NOTE,
        rx("""(sent (you )?(an? )?)?sticker\.?""") to MsgrMessageType.STICKER,
        rx("""(sent (you )?(an? )?)?gif\.?""") to MsgrMessageType.GIF,
        rx("""sent (you )?(an? |\d+ )?(files?|attachments?|documents?)\.?""") to MsgrMessageType.FILE,
        rx("""((shared|sent) (you )?(an? |their |his |her |my )?)?(live )?location\.?""") to MsgrMessageType.LOCATION,
        rx("""reacted \S{1,12} to (your|a|this|their|his|her) message.*""") to MsgrMessageType.REACTION,
        rx("""(liked|loved|laughed at|disliked|emphasized|cried at|questioned) (your|a|this) message.*""") to MsgrMessageType.REACTION,
        rx("""(.{1,60} )?unsent a message\.?""") to MsgrMessageType.UNSENT,
        rx("""(this )?message (was )?unsent\.?""") to MsgrMessageType.UNSENT,
        rx("""((you )?missed|incoming|outgoing) (an? )?(voice |video |group |audio )?call.*""") to MsgrMessageType.CALL_NOTICE,
        rx("""(.{1,60} )?(missed|declined) your (voice |video |audio |group )?call.*""") to MsgrMessageType.CALL_NOTICE,
        rx("""(started|ended) (a |the )?(video chat|video call|voice call|audio call|group call|call).*""") to MsgrMessageType.CALL_NOTICE,
        rx("""(sent (you )?(a )?(new )?messages?|(you have )?(a )?new messages?|you have \d+ new messages?)\.?""") to MsgrMessageType.HIDDEN_CONTENT,
        rx("""messages and calls are (secured|protected) with end-to-end encryption.*""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?(named|renamed) the (group|chat).*""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?changed the (group|chat) (name|photo|picture|color|colour|theme|emoji).*""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?(left|joined) the (group|chat)\.?""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?added .{1,60} to the (group|chat)\.?""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?removed .{1,60} from the (group|chat)\.?""") to MsgrMessageType.SYSTEM_NOTICE,
        rx("""(.{1,60} )?(set|changed) (the )?nickname.*""") to MsgrMessageType.SYSTEM_NOTICE
    )

    /**
     * Best-effort type of a notification text. Anything that is not recognised as a placeholder, and that contains at
     * least one letter or digit, is [MsgrMessageType.TEXT]. When in doubt the result errs towards "not text", which only
     * means no draft is made.
     */
    fun classify(raw: String): MsgrMessageType {
        val text = raw.trim()
        if (text.isEmpty()) return MsgrMessageType.EMPTY
        for ((pattern, type) in WORD_RULES) if (pattern.matches(text)) return type
        if (text.none { it.isLetterOrDigit() }) return MsgrMessageType.SYMBOLS_ONLY   // e.g. the thumbs-up "Like" message
        return MsgrMessageType.TEXT
    }

    /** Facts about one stored message. Only ever looks at that message. */
    fun analyze(m: ProcessedMessage): MsgrMessageInfo = MsgrMessageInfo(
        type = classify(m.text),
        conversation = m.kind,
        lite = m.packageName == MESSENGER_LITE_PACKAGE,
        senderKnown = m.senderName != null,
        timeExact = m.timestampExact,
        possiblyTruncated = m.text.trimEnd().endsWith(TRUNCATION_MARK)
    )
}
