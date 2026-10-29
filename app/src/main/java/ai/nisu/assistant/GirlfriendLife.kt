package ai.nisu.assistant

import android.content.Context
import java.util.Calendar

/**
 * v2.97: REAL-LIFE MOMENT. A real girlfriend knows what time it is and how long it has been since they last talked, and that changes how she
 * opens: "এত রাতে জেগে আছো?", a soft good morning, or a little অভিমান after a long silence. This builds a short note from the phone's clock and
 * the time of the last saved talk. Only numbers (a timestamp) are stored, never message text.
 */
object GirlfriendLife {
    private const val KEY_LAST = "gf_last_talk_ms"

    /** Call whenever an exchange is saved. */
    fun touch(c: Context) {
        try { Prefs.put(c, KEY_LAST, System.currentTimeMillis().toString()) } catch (_: Exception) {}
    }

    fun note(c: Context): String = try {
        val mode = PersonaMode.saved(c)
        val now = System.currentTimeMillis()
        val last = Prefs.get(c, KEY_LAST, "").toLongOrNull() ?: 0L
        val gapMin = if (last <= 0L) -1L else ((now - last).coerceAtLeast(0L)) / 60_000L
        val cal = Calendar.getInstance()
        momentText(mode, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_WEEK), gapMin)
    } catch (_: Exception) { "" }

    /** Pure, so it can be unit tested. [gapMin] = minutes since the last talk, -1 when they have never talked. */
    fun momentText(mode: String, hour: Int, dayOfWeek: Int, gapMin: Long): String {
        if (PersonaMode.bossLike(mode)) return ""
        val care = mode == PersonaMode.CARE
        val time = when (hour) {
            in 5..9 -> "It is early morning for him. Sound fresh and soft, a little sleepy-sweet." +
                (if (care) " A warm, simple good morning feels intimate, never a long speech." else "")
            in 10..15 -> "It is daytime; he may be busy with work or studies. Keep it light and quick unless he wants to talk."
            in 16..19 -> "It is evening. Be relaxed and curious about how his day went, the specific things he mentioned before."
            in 20..23 -> "It is night. Be slower, softer and closer" + (if (care) ", the cosy late talk of a girlfriend before sleep." else ", the easy late talk of a close friend.") +
                ""
            else -> "It is very late at night (after midnight). Sound a little sleepy and soft and keep the talk close and quiet. Whether you may mention sleep is decided only by the SLEEP AND FOOD REMINDERS block (at most one reminder per hour); never end the talk if he needs you."
        }
        val day = when (dayOfWeek) {
            Calendar.FRIDAY -> " It is Friday, the weekend: more relaxed and playful."
            Calendar.SATURDAY -> " It is Saturday: still a relaxed feeling."
            Calendar.SUNDAY -> " It is Sunday, the start of the work week: he may be tired or busy."
            else -> ""
        }
        val gap = when {
            gapMin < 0 -> "This is your first talk on this phone: be warm and natural, curious about him, not overly familiar yet in your words about the past (you have no shared memories yet)."
            gapMin < 15 -> "You were just talking a moment ago: continue naturally, no greeting, no restart."
            gapMin < 180 -> "You talked a little while ago: no big greeting, just pick up casually."
            gapMin < 12 * 60 -> "You have not talked for some hours. A light, natural line about it is fine (" +
                (if (care) "\"কোথায় ছিলে এতক্ষণ?\"" else "\"কী খবর, কই ছিলি?\"") + "), then ask about his day."
            gapMin < 48 * 60 -> if (care) "You have not talked for about a day. You really missed him: show it softly and honestly, with a tiny sweet অভিমান (one short line), then melt warmly and ask what kept him busy."
                else "You have not talked for about a day: say you missed the chats in a friendly way and ask what he was up to."
            else -> if (care) "You have not talked for ${gapMin / (24 * 60)} days. You missed him a lot: a real, short অভিমান first (\"এতদিন পরে মনে পড়লো?\"), let him coax you a little, then melt and be truly happy he is back. Never guilt-trip and never make him promise anything."
                else "You have not talked for ${gapMin / (24 * 60)} days: tease him lightly for disappearing, then be genuinely glad he is back."
        }
        return "\n\nRIGHT NOW (real-life moment, from the phone's clock; use it naturally, never read it out or mention the clock): $time$day $gap\n"
    }
}
