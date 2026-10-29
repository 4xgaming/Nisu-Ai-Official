package ai.nisu.assistant

/**
 * v2.89: the last touch on a chat reply, so it reads like a person typed it and not like a customer-service assistant.
 * Used for the open-talk reply modes (Default NISU AI, Friend conversation) and typed chat. It never adds words and never changes the meaning:
 * it only takes away markdown, a spelled-out "hehe", robotic openers and a closing "let me know if you need anything" line.
 * A text it would empty, or a text of only one sentence, is returned as it was.
 */
internal object HumanText {
    private val BOLD = Regex("\\*\\*|__|`+")
    private val HEADER = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val STAR_BULLET = Regex("(?m)^\\s*[*•]\\s+")
    private val SINGLE_STAR = Regex("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])")

    /** A robotic opener at the very start ("Certainly!", "I'd be happy to help!"), followed by the real answer. */
    private val ROBOT_OPENER = Regex("(?i)^(certainly|absolutely|great question|i(?:'d| would) be (?:happy|glad) to help|as an ai(?: language model)?)[!,.:]?\\s+")

    /** A closing line that only offers more help. */
    private val CLOSER = Regex(
        "(?i)(let me know if|feel free to|i hope this helps|hope that helps|is there anything else|how (?:can|may) i (?:help|assist)|don'?t hesitate|happy to help (?:further|more)|" +
            "(আর|আরও)\\s.{0,30}(জানাবেন|জানাইবেন|বলবেন|জানাতে পারেন)|" +
            "और कुछ .{0,30}(बताइए|बताना|पूछिए)|कोई और मदद)"
    )

    private val SENTENCE_BREAK = Regex("(?<=[.!?।])\\s+|\\n+")

    fun polish(t: String): String {
        if (t.isBlank()) return t
        var s = ElevenStyle.noHehe(t)
        s = HEADER.replace(s, "")
        s = STAR_BULLET.replace(s, "- ")
        s = SINGLE_STAR.replace(s, "$1")
        s = BOLD.replace(s, "")
        val opener = ROBOT_OPENER.find(s)
        if (opener != null && s.length > opener.value.length + 8) s = s.substring(opener.value.length).replaceFirstChar { it.uppercaseChar() }
        val parts = s.split(SENTENCE_BREAK).filter { it.isNotBlank() }
        if (parts.size > 1 && CLOSER.containsMatchIn(parts.last()) && parts.last().length < 120) {
            val cut = s.lastIndexOf(parts.last())
            if (cut > 0) s = s.substring(0, cut).trimEnd()
        }
        s = s.trim()
        return if (s.isEmpty()) t.trim() else s
    }
}
