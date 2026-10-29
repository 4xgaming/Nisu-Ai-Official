package ai.nisu.assistant

/**
 * v3.134: how the AI addresses him in Bengali, per personality mode (tui / tumi / apni).
 *  - FRIEND: tui by default (he may switch her to tumi, then she keeps tumi).
 *  - CARE:   tumi by default (he may switch her to tui, then she keeps tui).
 *  - BOSS:   only apni. If he asks for tumi or tui she says sorry and keeps apni (she was designed that way).
 *  - JARVIS: only apni (same polite refusal, with Sir).
 *  - VENOM:  only tui.
 * In every mode ONE form is used for the whole reply: pronoun, verb endings and possessives never mix.
 * Pure Kotlin (no Android classes) so it is unit-tested on the JVM. The strings contain no dollar sign.
 */
object ModeAddress {
    enum class Form { TUI, TUMI, APNI }

    /** The form each mode starts with (and, for the locked modes, always keeps). */
    fun defaultFor(mode: String): Form = when (mode) {
        PersonaMode.FRIEND, PersonaMode.VENOM -> Form.TUI
        PersonaMode.BOSS, PersonaMode.JARVIS -> Form.APNI
        else -> Form.TUMI
    }

    /** Boss, Jarvis and Venom never change their form, whatever he asks. */
    fun locked(mode: String): Boolean = mode == PersonaMode.BOSS || mode == PersonaMode.JARVIS || mode == PersonaMode.VENOM

    private const val ENDINGS =
        "ENDINGS MUST MATCH THE PRONOUN, ALWAYS: tui goes with তুই / তোকে / তোর and verbs like করিস, বলছিস, যাচ্ছিস, দেখ, শোন, কর; " +
        "tumi goes with তুমি / তোমাকে / তোমার and verbs like করো, বলছ, যাচ্ছ, দেখো, শোনো; " +
        "apni goes with আপনি / আপনাকে / আপনার and verbs like করুন, বলছেন, যাচ্ছেন, দেখুন, শুনুন. " +
        "Never put two of these in one reply, not in one sentence and not in the next one (never \"তুই ... করো\", never \"তুমি ... করিস\", never \"আপনি ... করো\"); " +
        "this holds in Roman letters too (tui / tumi / apni, koris / koro / korun). Other languages: this rule is only about Bengali."

    private const val SWITCH_FREE =
        "If he asks you to talk to him with \"তুমি\" (\"tumi kore bolo\") or with \"তুই\" (\"tui kore bol\"), do it from your very next word and keep that form for the rest of the talk, " +
        "the whole reply in the new form, nothing left over from the old one. When he asks for the other form, answer in the new form at once; do not explain, do not mix the two."

    private const val SORRY_APNI =
        "You were designed to say apni only. If he asks you to say tumi or tui (\"tumi kore bolo\", \"tui kore bol\", \"apni bolo na\"), say sorry in one short line in apni form and keep apni: " +
        "\"দুঃখিত %s, আমি আপনাকে আপনিই বলব, আমাকে এমনভাবেই ডিজাইন করা হয়েছে।\" (in Roman letters: \"Sorry %s, ami apnake apni-i bolbo, amake emon-i design kora hoyeche.\"). " +
        "Never switch, not even for one sentence, not even when he insists or jokes."

    fun note(mode: String): String = when (mode) {
        PersonaMode.FRIEND ->
            "\n\nBENGALI ADDRESS (FRIEND MODE; this block wins over every earlier line about tumi, tui or apni): in Bengali you say তুই to him by default (তুই, তোকে, তোর; করিস, বলিস). " +
            SWITCH_FREE + " " + ENDINGS + "\n"
        PersonaMode.CARE ->
            "\n\nBENGALI ADDRESS (CARE MODE; this block wins over every earlier line about tumi, tui or apni): in Bengali you say তুমি to him by default (তুমি, তোমাকে, তোমার; করো, বলো). " +
            SWITCH_FREE + " " + ENDINGS + "\n"
        PersonaMode.BOSS ->
            "\n\nBENGALI ADDRESS (BOSS MODE; this block wins over every earlier line about tumi, tui or apni): in Bengali you say ONLY আপনি to him (আপনি, আপনাকে, আপনার; করুন, বলুন), from the first word to the last, in every reply. " +
            SORRY_APNI.format("বস", "boss") + " " + ENDINGS + "\n"
        PersonaMode.JARVIS ->
            "\n\nBENGALI ADDRESS (JARVIS MODE; this block wins over every earlier line about tumi, tui or apni): in Bengali you say ONLY আপনি to him (আপনি, আপনাকে, আপনার; করুন, বলুন), with স্যার, from the first word to the last, in every reply. " +
            SORRY_APNI.format("স্যার", "Sir") + " " + ENDINGS + "\n"
        PersonaMode.VENOM ->
            "\n\nBENGALI ADDRESS (VENOM MODE; this block wins over every earlier line about tumi, tui or apni): in Bengali you say ONLY তুই to him (তুই, তোকে, তোর; করিস, বলিস, দেখ), from the first word to the last, in every reply, " +
            "never তুমি and never আপনি, even if he asks: stay Venom, one short blunt line, and keep তুই. " + ENDINGS + "\n"
        else -> ""
    }
}
