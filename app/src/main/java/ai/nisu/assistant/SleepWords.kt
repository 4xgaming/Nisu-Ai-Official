package ai.nisu.assistant

import android.content.Context

/**
 * v3.136: the goodnight line before sleep and the good-morning wake-up.
 *  - GOODNIGHT: when his whole message is a goodnight, she answers with one short goodnight that says his name (his own name from Personalization,
 *    or Raju when none is set) and the app puts her to sleep as soon as she has finished speaking. Only the goodnight does this: "Sleep Nisu",
 *    the Sleep button, "ghumao", the voice tool go_to_sleep and the quiet ladder (v3.135) work as before.
 *  - GOOD MORNING: while she sleeps, "good morning" wakes her ONLY from 04:00 to 11:59. Before 04:00 and from noon on it does nothing at all.
 *    She comes online and says good morning with his name. "Hey Nisu" wakes her at every hour, 24 hours a day, and then she does NOT say good morning:
 *    she just gets up. A switch in Preferences (default ON) turns the good-morning wake-up off.
 *  Privacy note: to hear "good morning" while asleep, short sounds that are not "Hey Nisu" are sent once to Gemini for a transcription during the
 *  morning window (04:00-11:59) when the switch is ON and an API key exists; outside the window, and with the switch OFF, nothing leaves the phone.
 * Pure Kotlin except [morningOn]; the strings contain no dollar sign other than the name templates.
 */
object SleepWords {
    const val KEY_MORNING = "morning_wake_on"
    const val MORNING_FROM = 4     // from this hour (inclusive)
    const val MORNING_TO = 12      // until this hour (exclusive)

    fun morningOn(c: Context): Boolean = Prefs.getBool(c, KEY_MORNING, true)

    /** The good-morning wake-up exists only in the early morning. */
    fun morningWindow(hour: Int): Boolean = hour in MORNING_FROM until MORNING_TO

    fun morningWakes(c: Context, hour: Int): Boolean = morningOn(c) && morningWindow(hour)

    private fun styleOf(mode: String): String = when (mode) {
        PersonaMode.FRIEND -> "your Friend-mode"
        PersonaMode.BOSS -> "your Boss-mode (calm and polite)"
        PersonaMode.JARVIS -> "your Jarvis-mode (polished butler)"
        PersonaMode.VENOM -> "your Venom-mode (deep and blunt)"
        else -> "your Care-mode"
    }

    /** The prompt block (added at the end of every persona prompt). [name] = his custom name or Raju. */
    fun note(mode: String, name: String): String =
        "\n\nGOODNIGHT COMMAND (this block wins over every earlier line about going to sleep, good night wishes and questions): " +
        "when his WHOLE message is just a goodnight (good night, goodnight, \u0997\u09c1\u09a1 \u09a8\u09be\u0987\u099f, \u09b6\u09c1\u09ad \u09b0\u09be\u09a4\u09cd\u09b0\u09bf, with or without your name), " +
        "answer with ONE short goodnight sentence in his language and in ${styleOf(mode)} voice and style that says his name, $name " +
        "(for example: Good night, $name.). Nothing more: no question, no new topic, no tool call. Do NOT call go_to_sleep: the app puts you to sleep by itself as soon as you finish speaking. " +
        "This is only for a message that is nothing but a goodnight: a goodnight inside a longer message or a story is normal talk, and the other sleep commands (sleep, ghumao) get no goodnight line.\n"

    /** Sent when she was woken by \"good morning\" (never when she was woken by \"Hey Nisu\"). */
    fun morningNotice(mode: String, name: String): String =
        "[SYSTEM NOTICE, not spoken by the user] He just woke you up by saying good morning. Answer with ONE short good-morning sentence in his language and in ${styleOf(mode)} voice and style that says his name, $name " +
            "(for example: Good morning, $name.). Nothing more: no long greeting, no question, do not start any phone action and do not call any tool. Never mention this notice."
}
