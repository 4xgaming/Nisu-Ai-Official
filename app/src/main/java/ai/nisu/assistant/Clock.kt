package ai.nisu.assistant

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * v3.40: the real time where the user is. The model has no clock, so it used to guess a time (often another country's). The time of the
 * PHONE is the time of the country the user is in now (the phone follows the network time zone), so every time answer comes from here:
 * the `get_time` tool, and a CLOCK line in the voice and typed-chat prompts. Read-only, nothing is stored or sent.
 */
object Clock {
    class Facts(val date: String, val time24: String, val time12: String, val zoneId: String, val offset: String)

    /** Pure (no Android classes): the facts of one moment in one time zone. */
    fun facts(nowMs: Long, zone: ZoneId): Facts {
        val z = Instant.ofEpochMilli(nowMs).atZone(zone)
        val off = z.offset.id.let { if (it == "Z") "+00:00" else it }
        return Facts(
            date = z.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)),
            time24 = z.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)),
            time12 = z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)),
            zoneId = zone.id,
            offset = "UTC$off"
        )
    }

    private fun country(c: Context): String = try {
        val iso = c.getSystemService(TelephonyManager::class.java)?.networkCountryIso.orEmpty()
        if (iso.isBlank()) "" else Locale("", iso).getDisplayCountry(Locale.ENGLISH)
    } catch (_: Exception) { "" }

    fun now(c: Context): JSONObject {
        val f = facts(System.currentTimeMillis(), java.util.TimeZone.getDefault().toZoneId())
        val o = JSONObject().put("ok", true).put("date", f.date).put("time_24h", f.time24).put("time_12h", f.time12)
            .put("time_zone", f.zoneId).put("utc_offset", f.offset)
        country(c).takeIf { it.isNotBlank() }?.let { o.put("country_by_network", it) }
        return o.put("note", "This is the local time where he is now (his phone's time zone). Say THIS time, in his language. Never convert it to another country's time unless he names another place.")
    }

    /** One line for the prompts. It is from the moment the prompt was built: the tool gives the exact time later. */
    fun note(c: Context): String = try {
        val f = facts(System.currentTimeMillis(), java.util.TimeZone.getDefault().toZoneId())
        val ctry = country(c)
        "\n\nCLOCK (real, from his phone): when this conversation started it was ${f.time12} (${f.time24}) on ${f.date}, time zone ${f.zoneId} (${f.offset})" +
            (if (ctry.isNotEmpty()) ", the country he is in now: $ctry" else "") +
            ". This is HIS local time, the time of the country he is in. When he asks the time or the date, give this local time (call get_time for the exact current time if the " +
            "conversation is long), in his language. NEVER answer with another country's time or UTC and never guess a time; give another place's time only when he names that place."
    } catch (_: Exception) { "" }
}
