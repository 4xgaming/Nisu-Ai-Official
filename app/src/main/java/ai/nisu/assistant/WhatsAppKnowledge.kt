package ai.nisu.assistant

/*
 * WhatsApp knowledge (step 5).
 *
 * What NISU AI KNOWS about WhatsApp messages as they reach it through the Notification Listener. This file is knowledge
 * only: it never touches the screen, never sends anything and says nothing about what NISU AI is able to DO. What the
 * agent can actually do is listed, honestly, in WhatsAppActions.kt (WhatsAppCapabilities). Knowing that WhatsApp has
 * polls or voice messages does not mean Android can automate them.
 *
 * Everything here is judged from the notification text alone. WhatsApp does not document the placeholder texts it
 * shows for media, they can change between versions, and they are translated. Recognition is therefore best effort:
 * English words and the usual leading symbols only. A placeholder in another language looks like ordinary text; the
 * drafting prompt (WhatsAppActions.kt) tells the AI to answer NO_REPLY for such lines.
 */

/** What a message turned out to be. Only [TEXT] is something a person wrote and the agent can answer. */
enum class WaMessageType(val label: String) {
    EMPTY("empty message"),
    TEXT("text message"),
    IMAGE("photo"),
    VIDEO("video"),
    VOICE_NOTE("voice message"),
    AUDIO("audio file"),
    STICKER("sticker"),
    GIF("GIF"),
    DOCUMENT("document"),
    LOCATION("location"),
    CONTACT_CARD("contact card"),
    POLL("poll"),
    REACTION("reaction"),
    DELETED("deleted message"),
    CALL_NOTICE("call notice"),
    SYSTEM_NOTICE("system notice")
}

/** Facts about one message, derived from its own metadata and text. No text is copied into this object. */
class WaMessageInfo(
    val type: WaMessageType,
    /** PRIVATE / GROUP when Android said so, UNKNOWN otherwise. Never guessed. */
    val conversation: ConversationKind,
    /** true = WhatsApp Business, false = WhatsApp. */
    val business: Boolean,
    val senderKnown: Boolean,
    val timeExact: Boolean,
    /** The notification text ends with the "..." character Android uses when it cuts a long preview. */
    val possiblyTruncated: Boolean
) {
    /**
     * null = the message can be answered from its text. Otherwise a short code (letters, digits and _ only) that says
     * why no reply draft is made. Media, notices and cut-off previews are never answered: the agent cannot see media
     * and must not guess what an incomplete message says.
     */
    val draftBlocker: String?
        get() = when {
            type == WaMessageType.EMPTY -> "empty_message"
            type != WaMessageType.TEXT -> "unsupported_" + type.name.lowercase(java.util.Locale.ROOT)
            possiblyTruncated -> "incomplete_message"
            else -> null
        }
}

object WhatsAppKnowledge {
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    private const val TRUNCATION_MARK = '\u2026'

    /** What the notification pipeline of this app hands to the agent (see MessageParser / ProcessedMessage). */
    val AVAILABLE: List<String> = listOf(
        "The text of a message as the notification shows it (the first 2000 characters are kept).",
        "The chat name: the contact for a private chat, the group name for a group.",
        "The sender's display name, when Android provides it (null otherwise, never guessed).",
        "Whether a chat is a group or private, only when Android says so; otherwise it stays unknown.",
        "The message time when Android provides it, otherwise the time of the notification.",
        "Which app it came from: WhatsApp or WhatsApp Business."
    )

    val NOT_AVAILABLE: List<String> = listOf(
        "The content of photos, videos, voice messages, documents and other attachments: only a placeholder text appears.",
        "Your own sent messages: they are skipped, so the saved history of a chat holds incoming messages only.",
        "Messages that were read in the app, or dismissed, before NISU AI saw them.",
        "Which earlier message is being quoted or replied to, forwarded / edited marks, read receipts.",
        "A stable WhatsApp message id or the other person's phone number."
    )

    /** One sentence for the AI, so it does not pretend to see more than it does. */
    const val AI_VIEW_LIMITS =
        "You only see the text of recent incoming messages of this one chat. You cannot see photos, videos, voice messages, " +
            "files, quoted messages, or the owner's own earlier replies."

    // ------------------------------------------------------------------ message type recognition

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)   // declared first: used by the rules below
    private fun rx(pattern: String) = Regex(pattern, OPTIONS)

    private fun cp(codePoint: Int): String = StringBuilder().appendCodePoint(codePoint).toString()

    /** A leading symbol (emoji) that WhatsApp puts in front of a placeholder, and what must follow it. */
    private class EmojiRule(val type: WaMessageType, val symbols: List<String>, val rest: Regex?)

    private val LEADING_SYMBOLS = Regex("""^[\p{So}\p{Sk}\p{Mn}\p{Cf}\s]+""")

    private val EMOJI_RULES: List<EmojiRule> = listOf(
        // A photo or video can carry a caption after the symbol: still a photo / video.
        EmojiRule(WaMessageType.IMAGE, listOf(cp(0x1F4F7), cp(0x1F4F8), cp(0x1F5BC)), null),
        EmojiRule(WaMessageType.VIDEO, listOf(cp(0x1F3A5), cp(0x1F4F9)), null),
        EmojiRule(WaMessageType.VOICE_NOTE, listOf(cp(0x1F3A4), cp(0x1F399)), null),
        EmojiRule(WaMessageType.AUDIO, listOf(cp(0x1F3B5), cp(0x1F3A7)), rx("""audio.*|.+\.(mp3|m4a|aac|ogg|opus|wav)""")),
        EmojiRule(WaMessageType.GIF, listOf(cp(0x1F39E)), rx("gif.*")),
        EmojiRule(WaMessageType.DOCUMENT, listOf(cp(0x1F4C4), cp(0x1F4CE), cp(0x1F4C1)), rx("""(\d+ )?documents?.*|.+\.[a-z0-9]{2,5}""")),
        EmojiRule(WaMessageType.LOCATION, listOf(cp(0x1F4CD)), rx("(live )?location.*")),
        EmojiRule(WaMessageType.CONTACT_CARD, listOf(cp(0x1F464)), rx("""(\d+ )?contacts?.*""")),
        EmojiRule(WaMessageType.POLL, listOf(cp(0x1F4CA)), rx("poll.*"))
    )

    /** Whole-text placeholders, matched against the complete message only. */
    private val WORD_RULES: List<Pair<Regex, WaMessageType>> = listOf(
        rx("""(photo|image|picture)s?(\s*\(\d+\))?""") to WaMessageType.IMAGE,
        rx("""video(\s*\(\d{1,2}:\d{2}(:\d{2})?\))?""") to WaMessageType.VIDEO,
        rx("""voice message(\s*\(\d{1,2}:\d{2}(:\d{2})?\))?""") to WaMessageType.VOICE_NOTE,
        rx("""audio(\s*\(\d{1,2}:\d{2}(:\d{2})?\))?""") to WaMessageType.AUDIO,
        rx("""(ভয়েস মেসেজ|ভয়েস মেসেজ|ভয়েস নোট)(\s*\(\d{1,2}:\d{2}(:\d{2})?\))?""") to WaMessageType.VOICE_NOTE,
        rx("sticker") to WaMessageType.STICKER,
        rx("gif") to WaMessageType.GIF,
        rx("documents?") to WaMessageType.DOCUMENT,
        rx("(live )?location") to WaMessageType.LOCATION,
        rx("contact(: .*)?") to WaMessageType.CONTACT_CARD,
        rx("""reacted \S{1,12} to ["\u201C].*""") to WaMessageType.REACTION,
        rx("this message was deleted") to WaMessageType.DELETED,
        rx("""missed (voice |video |group )?call.*""") to WaMessageType.CALL_NOTICE,
        rx("""(waiting for this message|messages and calls are end-to-end encrypted|you were added).*""") to WaMessageType.SYSTEM_NOTICE
    )

    /**
     * Best-effort type of a notification text. Anything that is not recognised as a placeholder is [WaMessageType.TEXT].
     * When in doubt the result errs towards "not text" for symbol-prefixed lines, which only means no draft is made.
     */
    fun classify(raw: String): WaMessageType {
        val text = raw.trim()
        if (text.isEmpty()) return WaMessageType.EMPTY
        val lead = LEADING_SYMBOLS.find(text)?.value.orEmpty()
        if (lead.isNotEmpty()) {
            val rest = text.substring(lead.length).trim()
            for (rule in EMOJI_RULES) {
                if (rule.symbols.none { lead.contains(it) }) continue
                val required = rule.rest
                if (required == null || required.matches(rest)) return rule.type
            }
        }
        for ((pattern, type) in WORD_RULES) if (pattern.matches(text)) return type
        return WaMessageType.TEXT
    }

    /** Facts about one stored message. Only ever looks at that message. */
    fun analyze(m: ProcessedMessage): WaMessageInfo = WaMessageInfo(
        type = classify(m.text),
        conversation = m.kind,
        business = m.packageName == WHATSAPP_BUSINESS_PACKAGE,
        senderKnown = m.senderName != null,
        timeExact = m.timestampExact,
        possiblyTruncated = m.text.trimEnd().endsWith(TRUNCATION_MARK)
    )
}
