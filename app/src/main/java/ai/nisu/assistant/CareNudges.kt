package ai.nisu.assistant

import android.content.Context
import java.util.Calendar

/**
 * v3.45: she stops nagging about sleep and food.
 *
 * Before this, nearly every persona block said "ask whether he has eaten / slept" and the time note asked for it in every message, so late at night
 * she told him to sleep in reply after reply and asked about food again and again. A prompt rule alone does not stop that, the model cannot count.
 * Now the app counts, in plain code:
 *
 * - SLEEP: only at night (22:00 to 04:59), and at most ONE sleep reminder per hour. A reminder she already gave counts from that moment.
 * - FOOD: one question per part of the day, never more: morning 05-10 (breakfast), noon 11-15 (lunch), evening 16-19, night 20-23 (dinner).
 *   After midnight and in the small hours she does not ask about food at all. If he tells her he ate (or talks about food himself), that part of
 *   the day is done too.
 * - When HE brings up sleep or food himself, she answers him normally: the rules are only about her starting it.
 *
 * What is stored: one timestamp (her last sleep reminder) and one short line (the date and which meal parts are done). Never any message text.
 * Typed chat also checks the finished reply: a reminder that is not allowed is cut out (or, when it is part of a long sentence, the reply is
 * written once more). A live voice call gets the same rule as text at connect time and after every finished turn the saved state is updated.
 */
internal object CareNudges {
    const val SLEEP_GAP_MS = 60 * 60_000L
    private const val KEY_SLEEP = "nudge_sleep_ms"
    private const val KEY_MEALS = "nudge_meals"

    enum class Kind { SLEEP, FOOD }

    /** [sleep] = a sleep reminder is allowed now; [slot] = the part of the day (or null in the small hours); [food] = a food question is allowed now. */
    class Allow(val sleep: Boolean, val slot: String?, val food: Boolean)

    // ---------------------------------------------------------------- the rules (pure)

    fun slotOf(hour: Int): String? = when (hour) {
        in 5..10 -> "morning"
        in 11..15 -> "noon"
        in 16..19 -> "evening"
        in 20..23 -> "night"
        else -> null
    }

    fun inSleepWindow(hour: Int): Boolean = hour >= 22 || hour <= 4

    fun allow(hour: Int, nowMs: Long, lastSleepMs: Long, mealsDone: Set<String>): Allow {
        val slot = slotOf(hour)
        val sleepOk = inSleepWindow(hour) && (lastSleepMs <= 0L || nowMs - lastSleepMs >= SLEEP_GAP_MS)
        return Allow(sleepOk, slot, slot != null && slot !in mealsDone)
    }

    private val SLEEP_TALK = listOf(
        "ঘুম", "ghum", "sleep", "tired", "ক্লান্ত", "klanto", "good night", "goodnight", "শুভ রাত", "শুভরাত", "শুয়ে", "shuye", "নিদ্রা", "नींद", "सोना", "सोने", "सो ", "थक"
    )
    private val FOOD_TALK = listOf(
        "খেয়", "খাব", "খাই", "খাচ্ছ", "খাওয়া", "খিদে", "ক্ষুধা", "ভাত", "নাস্তা", "নাশতা", "লাঞ্চ", "ডিনার", "রান্না", "বিরিয়ানি",
        "खाना", "खाया", "भूख", "नाश्ता"
    )
    // English words need word edges ("eat" is inside "great"); the Banglish words are prefixes (khai, khaiso, khabo ...)
    private val FOOD_LATIN = Regex("(?i)\\b(eat|eating|ate|eaten|lunch|dinner|breakfast|hungry|food|meal|snack)\\b|\\b(khai|khab|khaw|khey|khide|bhat|nasta)")

    /** True when HIS message is about sleep / food himself (then she may answer it normally). */
    fun mentions(hisMessage: String, kind: Kind): Boolean {
        val t = " " + hisMessage.lowercase() + " "
        return if (kind == Kind.SLEEP) SLEEP_TALK.any { t.contains(it) } else FOOD_TALK.any { t.contains(it) } || FOOD_LATIN.containsMatchIn(t)
    }

    private val SLEEP_NUDGE = Regex(
        "(?i)(ঘুমাও|ঘুমিয়ে পড়|ঘুমাতে যাও|ঘুমাবে না|ঘুমাচ্ছ না|ঘুমিয়েছ|ঘুম হয়েছে|ঘুমানো উচিত|ঘুমাতে হবে|ঘুমানোর সময়|এখনো জেগে|জেগে আছ|জেগে থাক|শুয়ে পড়|এত রাতে|" +
            "ghumao|ghumaw|ghumiye por|ghumaiso|ghumate jao|ghum hoyeche|ghumabe na|jege achho|jege acho|" +
            "go to sleep|get some sleep|go to bed|should sleep|still awake|still up|get some rest|sleep now|need to sleep|time to sleep|" +
            "सो जाओ|सो जा|सोया नहीं|सो गए|जाग रहे|सोने जाओ)"
    )
    private val FOOD_NUDGE = Regex(
        "(?i)(খেয়েছ|খেয়েছিলে|খাওনি|খাও নি|খেয়ে নাও|খেয়ে নিও|খেয়ে নে|খাবার খে|খাওয়া-দাওয়া|খাওয়াদাওয়া|খাওয়া হয়েছে|ভাত খে|নাস্তা করেছ|নাস্তা করো|লাঞ্চ করেছ|ডিনার করেছ|খিদে পায়নি|খিদে পেয়েছে|কিছু খেয়ে|" +
            "khaiso|khaicho|khayecho|kheyecho|khawa dawa|kheye nao|kheye nish|" +
            "have you eaten|did you eat|eaten yet|eat something|had (your |any )?(lunch|dinner|breakfast)|have (your )?(lunch|dinner|breakfast)|" +
            "खाना खाया|खा लिया|कुछ खाया|खाना खा)"
    )

    /** True when the reply carries a reminder of that kind. */
    fun contains(reply: String, kind: Kind): Boolean = (if (kind == Kind.SLEEP) SLEEP_NUDGE else FOOD_NUDGE).containsMatchIn(reply)

    /** The reminders in [reply] that are NOT allowed now (and that he did not ask for himself). */
    fun violations(reply: String, hisMessage: String, a: Allow): Set<Kind> {
        val v = HashSet<Kind>()
        if (!a.sleep && contains(reply, Kind.SLEEP) && !mentions(hisMessage, Kind.SLEEP)) v.add(Kind.SLEEP)
        if (!a.food && contains(reply, Kind.FOOD) && !mentions(hisMessage, Kind.FOOD)) v.add(Kind.FOOD)
        return v
    }

    private val CHUNK = Regex("[^.!?\u0964\uFF1F\\n]+[.!?\u0964\uFF1F]*[\\p{So}\\p{Sk}\\u200D\\uFE0F \\t]*\\n*")
    private const val SHORT_CHUNK = 90

    /**
     * Cuts the short sentences that carry a forbidden reminder. A long sentence that carries one is kept (cutting it would cut the real answer);
     * the caller then asks for one rewrite. Never returns an empty text: when everything would go, the reply is returned as it was.
     */
    fun strip(reply: String, kinds: Set<Kind>): String {
        if (kinds.isEmpty() || reply.isBlank()) return reply
        val chunks = CHUNK.findAll(reply).map { it.value }.toList()
        if (chunks.joinToString("").length != reply.length) return reply   // could not split it cleanly: leave it
        val kept = chunks.filterNot { c -> c.trim().length <= SHORT_CHUNK && kinds.any { contains(c, it) } }
        val out = kept.joinToString("").trim()
        return if (out.isEmpty()) reply else out
    }

    /** For the ONE rewrite when a long sentence still carries a forbidden reminder. */
    fun retryNote(kinds: Set<Kind>): String =
        "\n\nREWRITE (your draft contained " + kinds.joinToString(" and ") { if (it == Kind.SLEEP) "a sleep reminder" else "a food question" } +
            " that is not allowed right now. Answer the same message again without it, no other change, and without hinting at it in other words. Never mention this note.)\n"

    // ---------------------------------------------------------------- the prompt note

    private fun slotLabel(slot: String) = when (slot) {
        "morning" -> "morning (breakfast)"
        "noon" -> "noon (lunch)"
        "evening" -> "evening (a snack or early dinner)"
        else -> "night (dinner)"
    }

    /** The text for the prompt. Empty in Boss mode. */
    fun note(a: Allow, mode: String = PersonaMode.CARE): String {
        if (PersonaMode.bossLike(mode)) return ""
        val sleep = if (a.sleep)
            "SLEEP: it is night, and a sleep reminder is due now. Say it ONCE, softly and in new words, no lecture, and not in the same reply as a food question. After you have said it, no sleep talk at all for the next hour, even in other words. "
        else
            "SLEEP: do NOT tell him to sleep, do not ask whether he slept, and do not worry that he is awake or that it is late. Sleep reminders belong only to the night (10 pm to 5 am), at most one per hour, and you gave one recently or it is not night. "
        val slot = a.slot
        val food = when {
            slot == null -> "FOOD: it is the small hours, so do NOT ask about food or tell him to eat. "
            a.food -> "FOOD: one food question is allowed for this part of the day, the " + slotLabel(slot) + ". Ask whether he has eaten ONCE, only where it fits naturally, then never again in this part of the day. "
            else -> "FOOD: you already asked about food in this part of the day (or he told you), so do NOT ask about eating again until the next part of the day. "
        }
        return "\n\nSLEEP AND FOOD REMINDERS (fixed rules counted by the app; they win over every earlier line that asks you to check on his food, sleep, rest or the late hour; never mention this note): " +
            sleep + food +
            "When HE brings up food, hunger, sleep or tiredness himself, answer him normally and kindly. Never repeat a reminder in the next replies, not even in other words. Show your care in other ways (his day, his mood, what he told you).\n"
    }

    // ---------------------------------------------------------------- stored state

    private fun dayKey(cal: Calendar): String = "%04d%02d%02d".format(java.util.Locale.US, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    private fun meals(c: Context, today: String): Set<String> = try {
        val raw = Prefs.get(c, KEY_MEALS, "")
        if (raw.substringBefore('|') == today) raw.substringAfter('|', "").split(',').filter { it.isNotBlank() }.toSet() else emptySet()
    } catch (_: Exception) { emptySet() }

    /** What is allowed right now, from the phone's clock and the saved state. */
    fun allowNow(c: Context): Allow = try {
        val cal = Calendar.getInstance()
        val last = Prefs.get(c, KEY_SLEEP, "").toLongOrNull() ?: 0L
        allow(cal.get(Calendar.HOUR_OF_DAY), System.currentTimeMillis(), last, meals(c, dayKey(cal)))
    } catch (_: Exception) { Allow(false, null, false) }   // when in doubt: no reminder

    fun note(c: Context): String = try { note(allowNow(c), PersonaMode.saved(c)) } catch (_: Exception) { "" }

    /**
     * Call for every saved exchange (voice turn or typed message): remembers a sleep reminder she gave and the meal part of the day that is done
     * (she asked, or he talked about food himself).
     */
    @Synchronized fun observe(c: Context, you: String, nisu: String) {
        try {
            val cal = Calendar.getInstance()
            val now = System.currentTimeMillis()
            if (contains(nisu, Kind.SLEEP)) Prefs.put(c, KEY_SLEEP, now.toString())
            val slot = slotOf(cal.get(Calendar.HOUR_OF_DAY))
            if (slot != null && (contains(nisu, Kind.FOOD) || mentions(you, Kind.FOOD))) {
                val today = dayKey(cal)
                val done = meals(c, today) + slot
                Prefs.put(c, KEY_MEALS, today + "|" + done.joinToString(","))
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun forget(c: Context) { Prefs.put(c, KEY_SLEEP, ""); Prefs.put(c, KEY_MEALS, "") }
}
