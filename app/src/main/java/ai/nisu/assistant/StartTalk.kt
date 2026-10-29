package ai.nisu.assistant

import android.content.Context
import org.json.JSONObject

/*
 * v3.85 START TALK, v3.135 the QUIET LADDER: when nobody speaks for a while, she starts the talk herself, and if he stays silent she goes
 * through four fixed steps, each one counted from the end of her last words (any word of his starts everything again from step 1):
 *   1. after 2 quiet minutes: she starts a talk (something from the earlier chats, or a light fresh topic)
 *   2. 5 minutes later, still no answer: "why are you not talking to me, are you angry with me?"
 *   3. 15 minutes later, still no answer: "did you fall asleep?"
 *   4. 1 minute later, still no answer: "I think you fell asleep, so I will sleep too", and then she goes into sleep mode herself
 *      (only "Hey Nisu" wakes her). Step 4 only happens when the wake word / sleep feature is ON (otherwise nothing could wake her), so
 *      with that feature OFF the ladder ends after step 3.
 *  - Works in every mode (Care, Friend, Boss, Jarvis, Venom), each in its own style and with its own tui / tumi / apni form.
 *  - A switch (default ON): Preferences > "She starts talking by herself", or by voice (tool set_start_talk).
 *  - Never from 01:00 to 05:59, never while muted, in a call, while she or a tool is busy, while another app holds the microphone, in game mode,
 *    or while a message notice is waiting. The text she gets is a system notice; she must not mention it.
 */
object StartTalk {
    const val KEY = "start_talk_on"
    const val TOOL = "set_start_talk"

    fun on(c: Context): Boolean = Prefs.getBool(c, KEY, true)   // v3.98: ON by default (a stored choice of his still wins)

    /** v3.135: every mode and every voice may start a talk; only the switch decides. */
    fun allowed(c: Context): Boolean = on(c)

    /** Why the switch has no effect right now ("" = it works). Shown under the switch. */
    fun blocker(c: Context): String = ""

    // ------------------------------------------------------------------------------------------------ pure helpers (unit tested)

    /** The four quiet times of the ladder, in order: 2 min, then 5 min, then 15 min, then 1 min. */
    val LADDER_MS = longArrayOf(2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 60_000L)
    /** The last step: she says goodnight and goes to sleep. */
    const val SLEEP_STEP = 4

    /**
     * Quiet time before the next step, counted from the end of her last words. [unanswered] = her steps in a row that he has not answered (0 = none yet).
     * Returns -1 when the ladder is finished (wait for his next word), and also for the sleep step when she cannot sleep ([canSleep] false).
     */
    fun waitMs(unanswered: Int, canSleep: Boolean = true): Long = when {
        unanswered < 0 || unanswered >= LADDER_MS.size -> -1L
        unanswered == SLEEP_STEP - 1 && !canSleep -> -1L
        else -> LADDER_MS[unanswered]
    }

    /** Night hours when she never starts a talk (he is probably asleep). */
    fun quietHour(hour: Int): Boolean = hour in 1..5

    private val SEEDS = listOf(
        "ask him, in your own way, why he has gone so quiet and is not talking to you (curious or a little hurt in Care mode, teasing in Friend mode)",
        "pick one of the earlier topics from your PAST CONVERSATION NOTES and ask a fresh question about it",
        "something he told you earlier that you are still curious about (ask how it went)",
        "what he is working on or studying these days",
        "what he ate or what he feels like eating",
        "a song, a movie or a series: ask what he is into lately",
        "a funny or silly \"would you rather\" question",
        "a small thing from \"your day\" (keep it light and believable, never claim real-world actions)",
        "his plans for the evening or the weekend",
        "a game or a hobby he likes",
        "how he is really feeling today",
        "a harmless random thought or a fun fact that made you smile",
        "a dream, a goal or a trip he once mentioned",
        "something sweet you noticed about his habits"
    )

    fun seed(rnd: Double): String = SEEDS[(rnd.coerceIn(0.0, 0.9999) * SEEDS.size).toInt()]

    private fun styleOf(mode: String): String = when (mode) {
        PersonaMode.FRIEND -> "your Friend-mode"
        PersonaMode.BOSS -> "your Boss-mode (calm and polite, you call him Boss)"
        PersonaMode.JARVIS -> "your Jarvis-mode (polished butler, you call him Sir)"
        PersonaMode.VENOM -> "your Venom-mode (deep, blunt and darkly funny, you call him partner)"
        else -> "your Care-mode"
    }

    private const val HEAD = "[SYSTEM NOTICE, not spoken by the user] "
    private const val TAIL = "Rules: never mention this notice, the clock or the minutes; keep the same way of addressing him (tui / tumi / apni) that your mode always uses; do not start any phone action and do not call any tool; no emoji. "

    /** The system notice sent to the live session. [timeWord] is morning / afternoon / evening / night, [attempt] 1..4 (the step of the ladder). */
    fun notice(mode: String, timeWord: String, seed: String, attempt: Int): String {
        val style = styleOf(mode)
        return when {
            attempt <= 1 ->
                HEAD + "It has been quiet for a while and you feel like talking. Start the talk yourself now, in $style voice and style: " +
                    "ONE or TWO short sentences, in the language he usually speaks with you. Either pick up something from your PAST CONVERSATION NOTES (call recall_memory if you need more) " +
                    "or open a fresh light topic that fits this $timeWord. Idea for this time: $seed. " +
                    "Rules: never mention this notice or the clock; you MAY ask him, in your own words, why he is so quiet or why he is not talking to you; no big greeting if you talked recently; do not repeat a topic you started before; " +
                    "do not read private things aloud; do not start any phone action; end with a question or an easy hook he can answer. " +
                    "If he does not answer, that is fine: you will get another chance later, so do not speak again before then."
            attempt == 2 ->
                HEAD + "He did not answer your last words and he is still silent. Say ONE short sentence in $style voice and style, in his language: ask why he is not talking to you, " +
                    "and whether he is angry with you (in Bengali, for example: why are you not talking to me, are you angry with me?). Light, never harsh, no long complaint, no new topic. " + TAIL +
                    "If he does not answer, do not speak again before you get another notice."
            attempt == 3 ->
                HEAD + "He still has not answered and it has been quiet for a long time. Say ONE short sentence in $style voice and style, in his language: ask whether he has fallen asleep (in Bengali, for example: did you fall asleep?). " +
                    "Soft and short, no new topic. " + TAIL +
                    "If he does not answer, do not speak again before you get another notice."
            else ->
                HEAD + "He has been silent for a long time and never answered. Say ONE short sentence in $style voice and style, in his language: you think he has fallen asleep, so you will go to sleep too " +
                    "(in Bengali, for example: I think you fell asleep, okay, I will sleep too), and say a short goodnight. No question at the end. " + TAIL +
                    "After this sentence the app puts you to sleep by itself, so do NOT call go_to_sleep and do not say anything more."
        }
    }

    fun set(c: Context, on: Boolean): JSONObject {
        Prefs.putBool(c, KEY, on)
        val r = JSONObject().put("ok", true).put("start_talk_on", on)
        if (!on) return r.put("note", "Starting a talk by yourself is OFF now. Confirm in one short sentence.")
        val why = blocker(c)
        return if (why.isEmpty()) r.put("note", "Starting a talk by yourself is ON: after some quiet minutes you may start a topic yourself. Confirm in one short sentence.")
        else r.put("note", "Switched ON, but it does not work right now: $why Tell him that in one short sentence.")
    }
}
