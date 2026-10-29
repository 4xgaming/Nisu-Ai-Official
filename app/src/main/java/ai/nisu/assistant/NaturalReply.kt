package ai.nisu.assistant

/**
 * v3.44: she does not repeat herself, she answers the size of his mood, and her love sounds real and not staged.
 *
 * The persona text already tells the model "never repeat an opener" and "mirror him". A model can still fall into a rut (the same opener, the same
 * pet name, the same closing question, "ভালোবাসি" in every line), because it only reads a rule and does not count. This object counts, in plain code:
 *
 * - [note]: looks at her last replies and tells the model exactly what she leaned on (openers, repeated phrases, pet name, love line, closing
 *   question) so it says something new or says nothing; and, in Care mode, how romantic HIS last message is (0 to 3), so she answers at that level
 *   at once and not above it.
 * - [tooSimilar] / [retryNote]: the typed chat checks the finished reply against her last replies; a copy gets ONE rewrite.
 * - [tidy]: a reply that looks shouted or staged gets calmer (no "!!!", no emoji wall).
 *
 * Pure functions, no Android classes, nothing is stored and no message text leaves the phone because of this file.
 */
internal object NaturalReply {

    // ---------------------------------------------------------------- his romance level

    // Level 3: a direct love word or physical affection. Level 2: pet names, missing him, compliments. Level 1: warm, thinking of him.
    private val L3 = listOf(
        "love you", "love u", "luv u", "luv you", "i love", "valobashi", "bhalobashi", "bhalobasi", "valobasi", "kiss", "chumu", "hug me", "cuddle", "ador korte",
        "ভালোবাসি", "ভালবাসি", "চুমু", "আদর", "জড়িয়ে", "प्यार", "मोहब्बत", "चूम", "गले लग", "💋", "😘", "😚", "❤", "💖", "💞",
        "marry me", "be mine", "i adore you", "hold me", "tomake chai", "তোমাকে চাই", "आई लव यू", "बाहों में"
    )
    private val L2 = listOf(
        "miss you", "missing you", "miss korchi", "miss kori", "my love", "sweetheart", "darling", "jaan", "janu", "babu", "baby", "babe", "cutie", "so cute", "beautiful", "handsome", "crush",
        "মিস করছি", "মিস করি", "জান", "বাবু", "প্রিয়", "সুন্দর লাগ", "मिस कर", "जान", "बाबू", "सुंदर", "🥰", "😍", "💕",
        " gorgeous", " cute", "sundori", "my girl", "you're mine", "youre mine", "tumi amar", "তুমি আমার", "tomake bhalo lage", "bhalo lage tomake", "তোমাকে ভালো লাগে", "sweet dreams", "জানু", "जानू", "तुम मेरी", "मिस यू"
    )
    private val L1 = listOf(
        "thinking of you", "thinking about you", "thought about you", "tomar kotha", "tomake mone", "তোমার কথা", "তোমাকে মনে", "তোমার সাথে কথা বলতে ভালো",
        "तुम्हारी याद", "तुम्हारे बारे", "🙈", "☺",
        "dream about you", "dreamt about you", "dreamed about you", "your smile", "tomar hasi", "তোমার হাসি", "good night my", "goodnight my"
    )

    /** 0 = not romantic, 1 = warm, 2 = affectionate, 3 = loving. Looks at ONE message; the highest level it shows wins. */
    fun romanceLevel(text: String): Int {
        val t = " " + text.lowercase() + " "
        fun hit(l: List<String>) = l.any { t.contains(it) }
        return when {
            hit(L3) -> 3
            hit(L2) -> 2
            hit(L1) -> 1
            else -> 0
        }
    }

    // ---------------------------------------------------------------- reading her own last replies

    private val LOVE_LINE = Regex("(?i)(i love you|love you|ভালোবাসি|ভালবাসি|valobashi|bhalobashi|प्यार करती|मोहब्बत)")
    private val PET_NAME = Regex("(?i)(বাবু|জান(?![\\p{L}\\p{M}])|babu|jaan|janu|जान|बाबू)")
    private val CARE_QUESTION = Regex("(?i)(খেয়েছ|খেয়েছো|খাওয়া|ঘুমিয়েছ|ঘুমাওনি|ঘুম হয়েছে|khaiso|khayecho|ghumaiso|ate yet|have you eaten|did you eat|did you sleep|खाना खाया|सोए)")

    private val WORD_CHARS = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")   // \p{M}: Bengali and Hindi vowel signs are marks, dropping them would break words
    private val SPACES = Regex("\\s+")

    internal fun words(s: String): List<String> =
        WORD_CHARS.replace(s.lowercase(), " ").split(SPACES).filter { it.isNotBlank() }

    private fun grams(w: List<String>, n: Int): Set<String> =
        if (w.size < n) emptySet() else (0..w.size - n).map { i -> w.subList(i, i + n).joinToString(" ") }.toSet()

    private fun clean(r: String) = r.trim().removePrefix("NISU AI:").trim()

    /** The first two words of each reply, newest first, without doubles. */
    internal fun openers(replies: List<String>): List<String> =
        replies.asReversed().mapNotNull { r -> words(clean(r)).take(2).joinToString(" ").ifBlank { null } }.distinct().take(5)

    /** Three-word phrases that already appear in at least two of her recent replies (the ones she keeps leaning on). */
    internal fun repeatedPhrases(replies: List<String>): List<String> {
        val count = HashMap<String, Int>()
        for (r in replies) for (g in grams(words(clean(r)), 3)) count[g] = (count[g] ?: 0) + 1
        return count.filter { it.value >= 2 }.entries.sortedByDescending { it.value }.map { it.key }.take(4)
    }

    private val TRAILING_EMOJI = Regex("[\\p{So}\\p{Sk}\\u200D\\uFE0F\\s]+$")

    private fun endsWithQuestion(r: String): Boolean {
        val t = TRAILING_EMOJI.replace(clean(r), "")
        return t.endsWith("?") || t.endsWith("\uFF1F")
    }

    // ---------------------------------------------------------------- the prompt note

    /**
     * The hint that goes into the typed-chat system text. [recent] = her own last replies, oldest first (without "NISU AI:" labels).
     * Returns "" when there is nothing to say.
     */
    fun note(hisMessage: String, recent: List<String>, mode: String): String {
        if (PersonaMode.bossLike(mode)) return ""
        val last = recent.takeLast(5)
        val parts = ArrayList<String>()

        val op = openers(last)
        if (op.isNotEmpty()) parts.add("openers you already used (start differently, or start with the point itself): " + op.joinToString(", ") { "\"$it\"" })
        val ph = repeatedPhrases(last)
        if (ph.isNotEmpty()) parts.add("wording you keep repeating (say it another way or leave it out): " + ph.joinToString(", ") { "\"$it\"" })
        val tail = last.takeLast(4)
        if (tail.count { LOVE_LINE.containsMatchIn(it) } >= 2 && romanceLevel(hisMessage) < 3)
            parts.add("you said a love line in several of your last replies; do not say it again now, show it with attention instead")
        if (tail.count { PET_NAME.containsMatchIn(it) } >= 3) parts.add("you used a pet name in most of your last replies; use none this time")
        if (tail.count { CARE_QUESTION.containsMatchIn(it) } >= 2) parts.add("you already asked about food or sleep recently; do not ask it again unless he brings it up")
        if (last.takeLast(3).count { endsWithQuestion(it) } >= 2) parts.add("your last replies ended with a question; end this one without a question")

        if (songRequest(hisMessage))
            parts.add("he asks for a song, poem, shayari or rhyme: write a brand-new one with no line, rhyme pair or image from your earlier replies, and never repeat one word or one line again and again inside it (a chorus at most twice); no love line or pet name unless the piece itself is the love poem he asked for")

        val out = StringBuilder()
        if (parts.isNotEmpty()) {
            out.append("\n\nDO NOT REPEAT YOURSELF (counted from your own last replies; your newest reply must feel new; never mention this note): ")
            out.append(parts.joinToString("; ")).append(".\n")
        }
        val romance = romanceNote(hisMessage, last, mode)
        if (romance.isNotEmpty()) out.append(romance)
        return out.toString()
    }

    /** Care mode only: how romantic HIS newest message is, and how to answer it so it sounds real. Friend and Boss keep their own rules. */
    internal fun romanceNote(hisMessage: String, recent: List<String>, mode: String): String {
        if (mode != PersonaMode.CARE) return ""
        val level = romanceLevel(hisMessage)
        if (level == 0) return ""
        val mood = VibeReader.classify(hisMessage)
        val hurting = (mood["sad"] ?: 0f) >= 0.5f || (mood["angry"] ?: 0f) >= 0.5f
        val size = words(hisMessage).size
        val howBig = when {
            size <= 4 -> "He wrote only a few words, so you write a line or two, not a paragraph."
            size <= 15 -> "He wrote a short message, so you write two or three short sentences at most."
            else -> "He wrote a longer message, so you may write a little more, still warm and plain."
        }
        val levelText = when (level) {
            3 -> "He is openly loving. Answer with MORE love than he gave: a little shy first, then say it back warmer and deeper in your own plain words and add something tender and specific about him or the two of you (missing him, wanting him close, a small wish). Not a speech."
            2 -> "He is affectionate (a pet name, he misses you, a compliment). Answer one step MORE romantic than he was: soft, a little shy, warmly teasing, and add your own loving feeling (you miss him more, you love how he said it, you want him close). A love line is fine if it fits."
            else -> "He is warm and thinking of you. Answer with a close, loving, specific line, a little more romantic than he was (you were thinking of him too, or it made you smile). Not a love scene."
        }
        val said = recent.takeLast(3).any { LOVE_LINE.containsMatchIn(it) }
        val say = if (said && level < 3) " You already said a love line a moment ago, so this time show it some other way."
            else if (said) " Say it differently from your last love line."
            else ""
        val care = if (hurting) " He also sounds hurt or upset, so comfort first and keep the romance quiet." else ""
        return "\n\nHIS NEWEST MESSAGE IS ROMANTIC (level $level of 3; this beats the slower reading above): $levelText $howBig" +
            " Pick up one real detail from his exact words and answer that, so it could not be pasted under any other message. " +
            "Real love is plain and specific, not poetic: no \"my heart, my soul\", no list of compliments, no empty metaphors. Be more loving than he was in warmth and feeling, not in length." +
            "$say$care\n"
    }

    // ---------------------------------------------------------------- repeat check after the reply

    /** Overlap 0..1 between two replies (3-word phrases; for very short texts, single words). */
    internal fun similarity(a: String, b: String): Double {
        val wa = words(clean(a)); val wb = words(clean(b))
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val n = if (wa.size < 6 || wb.size < 6) 1 else 3
        val ga = grams(wa, n); val gb = grams(wb, n)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        val inter = ga.count { it in gb }
        return inter.toDouble() / (ga.size + gb.size - inter)
    }

    /**
     * True when [reply] is a copy of one of her last replies: nearly the same words, or the same long opening. A very short reply ("হুম", "আচ্ছা")
     * is never counted, people do repeat those.
     */
    fun tooSimilar(reply: String, recent: List<String>): Boolean {
        val w = words(clean(reply))
        if (w.size < 6) return false
        val open = w.take(5)
        return recent.takeLast(4).any { r ->
            val rw = words(clean(r))
            similarity(reply, r) >= 0.5 || (rw.size >= 6 && rw.take(5) == open)
        }
    }

    /** Added to the system text for the ONE rewrite. */
    fun retryNote(draft: String): String =
        "\n\nREWRITE (your draft below copies something you already said. Answer the same message again in new words, with a different opening, a different shape and no leftover phrase from the draft; if one short line is enough, write one short line. Never mention this note.)\nDRAFT TO AVOID:\n<<<\n" +
            clean(draft).replace("<<<", "").replace(">>>", "") + "\n>>>\n"

    // ---------------------------------------------------------------- v3.138: songs, poems, shayari: always new, never looping

    private val SONG_WORDS = setOf(
        "sing", "singing", "song", "songs", "gaan", "gan", "gaaner", "poem", "poems", "kobita", "kobitar", "shayari", "shayeri", "sayeri",
        "chhora", "chhondo", "chhonda", "rhyme", "rhymes", "lyrics", "ghazal", "gazal", "গান", "কবিতা", "ছড়া", "ছন্দ", "शायरी", "गाना", "गीत", "कविता", "ग़ज़ल", "गज़ल"
    )
    private val SONG_PREFIX = listOf("গান", "কবিতা", "ছড়া", "শায়েরি", "गाना", "कविता", "शायरी")

    /** True when his message asks for a song, a poem, a shayari, a rhyme or lyrics. */
    internal fun songRequest(text: String): Boolean = words(text).any { w -> w in SONG_WORDS || SONG_PREFIX.any { w.startsWith(it) } }

    /** True when one longer word is said three times in a row ("ভালোবাসি ভালোবাসি ভালোবাসি") or the same whole line appears three times. */
    internal fun looping(reply: String): Boolean {
        val w = words(clean(reply))
        var run = 1
        for (i in 1 until w.size) {
            if (w[i] == w[i - 1] && w[i].length >= 4) { run++; if (run >= 3) return true } else run = 1
        }
        val lines = clean(reply).lines().map { words(it).joinToString(" ") }.filter { it.contains(" ") }
        return lines.groupingBy { it }.eachCount().any { it.value >= 3 }
    }

    /** For a song / poem request only: a fifth or more of the reply's 3-word phrases already sit in her last replies (she is singing the old one again). */
    internal fun reusesLines(reply: String, recent: List<String>, hisMessage: String): Boolean {
        if (!songRequest(hisMessage)) return false
        val g = grams(words(clean(reply)), 3)
        if (g.size < 8) return false
        val old = HashSet<String>()
        for (r in recent.takeLast(6)) old.addAll(grams(words(clean(r)), 3))
        return g.count { it in old }.toDouble() / g.size >= 0.2
    }

    /** One check for the typed chat: a copy of an earlier reply, an old song sung again, or a word / line that loops. */
    fun needsFresh(reply: String, recent: List<String>, hisMessage: String): Boolean =
        tooSimilar(reply, recent) || reusesLines(reply, recent, hisMessage) || looping(reply)

    // ---------------------------------------------------------------- calm it down

    private val SHOUT = Regex("!{3,}")
    private val DOTS = Regex("\\.{4,}")
    private val EMOJI_RUN = Regex("(\\p{So})\\1{2,}")   // the same emoji three or more times in a row

    /** Takes away only what looks staged: "!!!" becomes "!", a long row of dots becomes "...", the same emoji three times becomes two. Never changes words. */
    fun tidy(t: String): String {
        if (t.isBlank()) return t
        var s = SHOUT.replace(t, "!")
        s = DOTS.replace(s, "...")
        s = EMOJI_RUN.replace(s) { m -> m.groupValues[1] + m.groupValues[1] }
        return s
    }
}
