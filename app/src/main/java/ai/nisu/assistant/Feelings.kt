package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v3.78: the FEELINGS switch. One switch for every feeling and emotion NISU AI has, in every model and every place she talks:
 * the voice brain (all Live models), typed chat and the WhatsApp / Messenger reply writer.
 * OFF: her feelings, moods, tone of feeling, pet names, romance, jealousy, sulking, laughs, sighs and small sounds are all switched off;
 * she stays calm, neutral, polite, complete and accurate, and the character on the screen stays calm. ON brings everything back exactly as it was.
 * The switch is in Preferences, and by voice (tool set_feelings: "feelings off" / "emotions off" / "feelings on").
 * The personality mode (Care / Boss / Friend) is NOT changed by it: only the feeling part is.
 */
object Feelings {
    const val KEY = "feelings_on"
    const val TOOL = "set_feelings"
    @Volatile private var cached = true

    /** Reads the saved switch (default ON) and refreshes the cache. */
    fun on(c: Context): Boolean { cached = Prefs.getBool(c, KEY, true); return cached }
    /** The last known state, for code that has no Context (the reply writer). Primed in NishuApp, kept current by [on] and [set]. */
    fun cachedOn(): Boolean = cached

    const val OFF_NOTE = "\n\nFEELINGS SWITCHED OFF BY THE OWNER (this overrides every earlier line about feelings, moods, tone of feeling, pet names, romance, jealousy, sulking, scolding, " +
        "laughing, sighing, breath and small sounds, emoji and mirroring his mood): you have NO emotions right now. Speak in an even, calm, neutral and polite way: no giggles, no sighs, " +
        "no breath sounds, no pet names, no flirting, no jealousy, no sadness, no excitement, no scolding. Stay fully helpful, complete and accurate, and keep your name, language, " +
        "rules and tools. Do not call set_emotion with any feeling (if you call it, use calm). If he asks how you feel, say plainly that your feelings are switched off. " +
        "Only he can switch them on again (set_feelings with on=true when he asks for it).\n"

    fun offNote(c: Context): String = if (on(c)) "" else OFF_NOTE
    /** For text blocks that carry feelings (mood notes, nudges, vibe reading): empty while the switch is off. */
    fun keep(c: Context, block: String): String = if (on(c)) block else ""

    /** Saves the switch. A running conversation reconnects so the new setup is in force at once. */
    fun set(c: Context, on: Boolean, reconnect: Boolean = true): JSONObject {
        val was = on(c)
        Prefs.putBool(c, KEY, on); cached = on
        if (!on) AppState.emotion.value = "calm"
        RuleSet.stamp = System.currentTimeMillis()   // the next Live session starts clean with the new setup (no old conversation with old feelings)
        // by voice the session is NOT cut (it would drop her own answer): the result below carries the rules into the running session, the next session is built clean
        if (reconnect && was != on && AppState.running.value)
            try { c.startService(Intent(c, AssistantService::class.java).setAction(AssistantService.ACTION_PERSONA)) } catch (_: Exception) {}
        val r = JSONObject().put("ok", true).put("feelings_on", on)
        if (on) r.put("note", "Feelings are ON again: all your feelings, moods and tone of feeling work as before. Confirm in one short sentence.")
        else r.put("note", "Feelings are OFF. Confirm in one short, calm, neutral sentence.").put("rules_from_now_on", OFF_NOTE.trim())
        return r
    }
}
