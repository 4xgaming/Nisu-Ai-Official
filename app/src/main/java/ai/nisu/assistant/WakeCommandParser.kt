package ai.nisu.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * v2.21: strict wake / sleep phrase parser. No Android classes: it is unit-tested on the JVM.
 *
 * Rules (deliberately narrow):
 *  - The WHOLE utterance, after normalisation, must be exactly one of the phrases built from the explicit lists below.
 *  - No substring, prefix, fuzzy, phonetic or edit-distance matching. "hey nishu please call mom" is NOT a wake phrase,
 *    "নিশু" alone is NOT, "হে নিশা" is NOT.
 *  - Wake: "Hey Nishu" / "Hey Nisu" (or "Okay ..."). Sleep: "Sleep Nishu" / "Sleep Nisu" / "Nishu Sleep" / "Nisu Sleep".
 *  - A new spelling is accepted only by adding it to the lists below AND to WakeCommandParserTest.
 */
object WakeCommandParser {

    enum class Command { WAKE, SLEEP, NONE }

    // Accepted transcriptions of the name. "নিশা" (a different word) and bare "নিশু" are not wake phrases by themselves.
    private val NAMES = setOf("নিশু", "নিসু", "nishu", "nisu")

    // "হে নিশু" / "Hey Nishu"
    private val HEY = setOf("হে", "হেই", "hey")

    // "ওকে নিশু" / "Okay Nishu" ("o.k." and "ও কে" normalise to "o k" / "ও কে")
    private val OKAY = setOf("ওকে", "ও কে", "okay", "ok", "o k")

    // "নিশু স্লিপ" / "Nishu Sleep"
    private val SLEEP_WORD = setOf("স্লিপ", "স্লীপ", "শ্লিপ", "sleep")

    // "নিশু চুপ করো" / "Nishu Chup Koro"
    private val QUIET_WORD = setOf("চুপ করো", "chup koro")

    // v2.51: ONLY "Hey Nishu" / "Hey Nisu" wakes NISU AI. "Okay Nishu", "Hi Nishu", a bare name or anything else never does.
    private val WAKE_PHRASES: Set<String> =
        HEY.flatMap { p -> NAMES.map { n -> "$p $n" } }.toSet()

    // v2.23: the name can also come second: "Sleep Nishu" / "স্লিপ নিশু" (only the sleep word itself, not "chup koro")
    private val SLEEP_PHRASES: Set<String> =
        (NAMES.flatMap { n -> (SLEEP_WORD + QUIET_WORD).map { w -> "$n $w" } } +
            SLEEP_WORD.flatMap { w -> NAMES.map { n -> "$w $n" } }).toSet()

    /** The single-word sleep spellings (used to tell "sleep" from a real "lock the screen" request). */
    val sleepWords: Set<String> get() = SLEEP_WORD

    // ---- v2.31: tolerant matching, used ONLY by the service (the strict functions above and their tests are unchanged).
    // Speech recognisers spell the name in many ways; a wake / sleep phrase that is clearly meant must not be lost to a spelling.
    // v2.40: many more spellings and a tolerant sleep command (see isSleepLoose), and the wake phrase may be followed by a filler word.
    private val LOOSE_NAMES = NAMES + setOf(
        "nishoo", "nisoo", "neeshu", "neesu", "nishuu", "নীশু", "নীসু", "নিশো", "নিসো",
        "neshu", "nesu", "nishhu", "nisshu", "nishou", "nisou", "নিশূ", "নিসূ", "নিশুউ", "নিসুউ", "নিশু", "নেশু", "নেসু", "নিছু", "nichu"
    )
    // v2.51: only spellings of "Hey" (no hi / hello / okay / yo / ও / এই: those are everyday words and woke NISU AI by mistake)
    private val LOOSE_HEY = HEY + setOf("hay", "hei", "hae", "হেয়", "হেই", "হেঈ")

    // words that may follow "Hey Nishu" without changing its meaning ("hey nishu wake up", "hey nishu are you there")
    // v2.51: nothing may follow "Hey Nishu": the phrase must be exactly that, so a sentence that merely contains it never wakes NISU AI
    private val WAKE_FILLER = emptySet<String>()

    // v2.40: sleep command. SLEEP_ANY needs no name when it is (nearly) the only thing said; SLEEP_NAMED only together with the name.
    private val SLEEP_NAMES = LOOSE_NAMES + setOf("nisha", "নিশা", "nisa", "নিসা", "nishi", "nisi", "নিশি", "নিসি")
    private val SLEEP_ANY = SLEEP_WORD + setOf("slip", "sleeping", "slap", "স্লিপ", "স্লীপ", "স্লিপিং", "সলিপ", "শ্লিপ", "স্লিপ্")
    private val SLEEP_NAMED = SLEEP_ANY + setOf(
        "ঘুমাও", "ঘুমা", "ঘুমাই", "ঘুমান", "ঘুমিয়ে", "ঘুমাতে", "ঘুম", "ghumao", "ghumau", "ghuma", "ghum", "ghumaw", "gumao",
        "চুপ", "chup", "chhup", "চুপচাপ", "বন্ধ", "bondho", "bandho"
    )
    private val SLEEP_FILLER = setOf(
        "go", "to", "please", "pls", "plz", "now", "ok", "okay", "hey", "hay", "hi", "hei", "hello", "right", "just", "mode", "ab", "tum", "tumi",
        "koro", "kor", "করো", "কর", "করুন", "যাও", "জাও", "jao", "যা", "চলো", "এখন", "এবার", "এখনই", "প্লিজ", "তুমি", "তুই", "একটু", "ekhon", "ebar", "ekta", "একটা", "দাও", "dao", "হও", "হয়ে", "পড়ো", "পরো", "মোড", "ওকে", "হে", "হেই", "হাই", "ও", "এই"
    )

    /** "Hey Nishu" with common spelling variants (and an optional filler word after it). The greeting and the name are still required. */
    fun isWakeLoose(text: String): Boolean {
        val n = normalize(text)
        if (n in WAKE_PHRASES) return true
        val t = n.split(' ')
        if (t.size < 2 || t.size > 5) return false
        val idx = when {
            t[0] in LOOSE_HEY -> 1
            t.size >= 3 && (t[0] + " " + t[1]) in LOOSE_HEY -> 2
            else -> return false
        }
        if (t[idx] !in LOOSE_NAMES) return false
        return t.drop(idx + 1).all { it in WAKE_FILLER }
    }

    /**
     * A sleep command at the END of what was said (\"... nishu sleep\", \"go to sleep nishu\", \"nishu ghumao\", \"নিশু স্লিপ\", \"nishu chup koro\", or just \"sleep\").
     * Only the last sentence counts, and only if it consists of the name, a sleep word and harmless filler words (\"go to\", \"please\", \"now\" ...),
     * so a long sentence that merely mentions sleep never puts NISU AI to sleep. v2.40: works on the end of a long transcript too.
     */
    fun isSleepLoose(text: String): Boolean {
        val segs = text.split(Regex("[.?!।\\n]+")).map { normalize(it) }.filter { it.isNotEmpty() }
        val last = segs.lastOrNull() ?: return false
        if (last in SLEEP_PHRASES) return true
        val all = last.split(' ')
        return sleepTokens(all) || (all.size > 4 && sleepTokens(all.takeLast(4)))   // an unpunctuated transcript: the very end counts
    }

    private fun sleepTokens(t: List<String>): Boolean {
        if (t.isEmpty() || t.size > 7) return false
        val hasName = t.any { it in SLEEP_NAMES }
        val named = t.any { it in SLEEP_NAMED }
        val any = t.any { it in SLEEP_ANY }
        val rest = t.filter { it !in SLEEP_NAMES && it !in SLEEP_NAMED && it !in SLEEP_FILLER }
        if (rest.isNotEmpty()) return false
        return if (hasName) named else (any && t.size <= 3)
    }

    /** True if [text] is only a "Hey" (used to join "Hey" + "Nishu" when they arrive as two short sounds). */
    fun isHeyOnly(text: String): Boolean = normalize(text) in LOOSE_HEY

    /** True if [text] is only the name (any accepted spelling). */
    fun isNameOnly(text: String): Boolean = normalize(text) in LOOSE_NAMES

    /** Exposed for tests / README generation. */
    val wakePhrases: Set<String> get() = WAKE_PHRASES
    val sleepPhrases: Set<String> get() = SLEEP_PHRASES

    /** Hints for the recogniser (biasing only; matching is still done by [classify]). */
    val wakeBiasingStrings: List<String> = listOf("হে নিশু", "হে নিসু", "Hey Nishu", "Hey Nisu")

    /**
     * Unicode NFC, lower case, zero-width characters removed, every character that is not a letter / digit /
     * combining mark (Bengali vowel signs and the virama are combining marks) turned into a space, whitespace collapsed.
     */
    fun normalize(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) {
            when {
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\u200E' || ch == '\u200F' || ch == '\uFEFF' -> {}
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                isMark(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun isMark(ch: Char): Boolean {
        val t = Character.getType(ch)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt()
    }

    // ---- v3.136: "good night" (puts her to sleep after a goodnight line) and "good morning" (wakes her, early morning only; the service checks the clock).
    // Like every command here the WHOLE utterance must be the phrase: only a greeting word before it (hey / hi / ok) and her name before or after it are allowed.
    private val GOODNIGHT = setOf("good night", "goodnight", "gud night", "good nite", "goodnite", "\u0997\u09c1\u09a1 \u09a8\u09be\u0987\u099f", "\u0997\u09c1\u09a1\u09a8\u09be\u0987\u099f", "\u09b6\u09c1\u09ad \u09b0\u09be\u09a4\u09cd\u09b0\u09bf", "\u09b6\u09c1\u09ad\u09b0\u09be\u09a4\u09cd\u09b0\u09bf", "\u09b6\u09c1\u09ad \u09b0\u09be\u09a4\u09cd\u09b0\u09c0", "shubho ratri", "shuvo ratri", "subho ratri", "shubhoratri")
    private val GOODMORNING = setOf("good morning", "goodmorning", "gud morning", "good mourning", "\u0997\u09c1\u09a1 \u09ae\u09b0\u09cd\u09a8\u09bf\u0982", "\u0997\u09c1\u09a1 \u09ae\u09b0\u09a8\u09bf\u0982", "\u0997\u09c1\u09a1\u09ae\u09b0\u09cd\u09a8\u09bf\u0982", "\u0997\u09c1\u09a1 \u09ae\u09cb\u09b0\u09cd\u09a8\u09bf\u0982", "\u09b6\u09c1\u09ad \u09b8\u0995\u09be\u09b2", "\u09b6\u09c1\u09ad\u09b8\u0995\u09be\u09b2", "\u09b8\u09c1\u09aa\u09cd\u09b0\u09ad\u09be\u09a4", "shubho sokal", "shuvo shokal", "subho sokal", "suprobhat", "suprabhat")
    private val GREET_FILLER = setOf("hey", "hi", "hello", "ok", "okay", "\u09b9\u09c7", "\u09b9\u09c7\u0987", "\u09b9\u09be\u0987", "\u09b9\u09cd\u09af\u09be\u09b2\u09cb", "\u0993\u0995\u09c7")

    private fun isGreeting(text: String, core: Set<String>): Boolean {
        var t = normalize(text).split(' ').filter { it.isNotEmpty() }
        while (t.size > 1 && t.first() in GREET_FILLER) t = t.drop(1)
        if (t.size > 1 && t.first() in SLEEP_NAMES) t = t.drop(1)
        else if (t.size > 1 && t.last() in SLEEP_NAMES) t = t.dropLast(1)
        return t.isNotEmpty() && t.joinToString(" ") in core
    }

    /** True when the whole utterance is a goodnight ("good night", "goodnight Nisu", "\u0997\u09c1\u09a1 \u09a8\u09be\u0987\u099f", "\u09b6\u09c1\u09ad \u09b0\u09be\u09a4\u09cd\u09b0\u09bf" ...). */
    fun isGoodNight(text: String): Boolean = isGreeting(text, GOODNIGHT)

    /** True when the whole utterance is a good morning ("good morning", "good morning Nisu", "\u09b6\u09c1\u09ad \u09b8\u0995\u09be\u09b2", "\u09b8\u09c1\u09aa\u09cd\u09b0\u09ad\u09be\u09a4" ...). */
    fun isGoodMorning(text: String): Boolean = isGreeting(text, GOODMORNING)

    fun isWake(text: String): Boolean = normalize(text) in WAKE_PHRASES
    fun isSleep(text: String): Boolean = normalize(text) in SLEEP_PHRASES

    fun classify(text: String): Command {
        val n = normalize(text)
        return when {
            n in WAKE_PHRASES -> Command.WAKE
            n in SLEEP_PHRASES -> Command.SLEEP
            else -> Command.NONE
        }
    }

    /** Alternatives the recogniser may return that are looked at (best first). Everything after is ignored. */
    const val MAX_ALTERNATIVES = 3

    /** If the recogniser reports a confidence (0..1) lower than this for an alternative, that alternative is ignored. Unknown (-1 / missing) is accepted. */
    const val MIN_CONFIDENCE = 0.3f

    /** True if one of the first [MAX_ALTERNATIVES] alternatives is exactly an allowed wake phrase. */
    fun findWake(alternatives: List<String>, confidences: FloatArray? = null): Boolean {
        for (i in 0 until minOf(alternatives.size, MAX_ALTERNATIVES)) {
            val c = confidences?.getOrNull(i) ?: -1f
            if (c in 0f..MIN_CONFIDENCE && c < MIN_CONFIDENCE) continue
            if (isWake(alternatives[i])) return true
        }
        return false
    }
}
