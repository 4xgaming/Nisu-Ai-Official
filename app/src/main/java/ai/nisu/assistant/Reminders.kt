package ai.nisu.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/*
 * v3.58: NISU AI sets her own reminders and alarms, and can call someone at a set time.
 * set_reminder / list_reminders / cancel_reminder are phone tools. A reminder is kept on the phone (survives a restart), fires at the exact time
 * through the Android alarm clock, shows a notification (loud for a wake-up alarm), tells NISU AI to say it to him if she is awake, and, for
 * a "call" reminder, places the phone call itself. Calls are placed only to a number he asked for.
 */
object Reminders {
    class R(val id: Int, val at: Long, val text: String, val repeat: String, val action: String, val number: String, val name: String, val loud: Boolean)

    private const val KEY = "reminders_v1"
    private const val KEY_ID = "reminders_next_id"
    private const val ACT = "ai.nisu.REMINDER"
    private const val CH = "nisu_reminder"
    private const val CH_LOUD = "nisu_alarm"
    private const val MAX = 60

    /** Set by the voice service while she is awake: the note she should say out loud. */
    @Volatile var onFire: ((String) -> Unit)? = null
    private val pending = ArrayList<String>()
    fun addPending(note: String) { synchronized(pending) { pending.add(note); while (pending.size > 6) pending.removeAt(0) } }
    fun takePending(): List<String> = synchronized(pending) { val l = ArrayList(pending); pending.clear(); l }

    // ------------------------------------------------------------------------------------------------ storage
    @Synchronized private fun load(ctx: Context): MutableList<R> {
        val out = ArrayList<R>()
        try {
            val a = JSONArray(Prefs.get(ctx, KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(R(o.optInt("id"), o.optLong("at"), o.optString("text"), o.optString("repeat", "none"), o.optString("action", "remind"), o.optString("number"), o.optString("name"), o.optBoolean("loud")))
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized private fun save(ctx: Context, l: List<R>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("repeat", it.repeat).put("action", it.action).put("number", it.number).put("name", it.name).put("loud", it.loud)) }
        Prefs.put(ctx, KEY, a.toString())
    }

    private fun nextId(ctx: Context): Int { val n = (Prefs.get(ctx, KEY_ID, "0").toIntOrNull() ?: 0) + 1; Prefs.put(ctx, KEY_ID, n.toString()); return n }

    fun whenText(ms: Long): String = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(ms)

    private fun parseAt(s: String): Long? {
        val t = s.trim().replace('T', ' ').replace(Regex("\\s+"), " ")
        for (f in listOf("yyyy-M-d H:mm", "yyyy-M-d H:mm:ss")) {
            try { return SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(t)?.time } catch (_: Exception) {}
        }
        return null
    }

    private fun advance(ms: Long, repeat: String): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        c.add(Calendar.DAY_OF_YEAR, if (repeat == "weekly") 7 else 1)
        return c.timeInMillis
    }

    // ------------------------------------------------------------------------------------------------ tools
    fun set(ctx: Context, a: JSONObject): JSONObject {
        val text = a.optString("text").trim().take(300)
        if (text.isEmpty()) return err("Say what the reminder is about (text).")
        val now = System.currentTimeMillis()
        val at: Long = when {
            a.has("in_minutes") && a.optDouble("in_minutes", 0.0) > 0.0 -> now + (a.optDouble("in_minutes") * 60_000.0).toLong()
            a.optString("at").isNotBlank() -> parseAt(a.optString("at")) ?: return err("Time not understood. Use the 24-hour local form YYYY-MM-DD HH:mm (for example 2026-10-24 07:30), or in_minutes.")
            else -> return err("Give the time: at (YYYY-MM-DD HH:mm, local) or in_minutes.")
        }
        if (at < now + 5_000L) return err("That time has already passed. Check the date with get_time and try again.")
        val repeat = a.optString("repeat", "none").lowercase().let { if (it == "daily" || it == "weekly") it else "none" }
        val action = a.optString("action", "remind").lowercase().let { if (it == "call") "call" else "remind" }
        val number = a.optString("number").trim()
        if (action == "call" && !Regex("^[+0-9*#()\\-\\s]{3,}$").matches(number)) return err("A call reminder needs the phone number (find it with find_contact first). Ask him whom to call.")
        val l = load(ctx)
        if (l.size >= MAX) return err("Too many reminders. Cancel some first.")
        val r = R(nextId(ctx), at, text, repeat, action, number, a.optString("name").trim().take(60), a.optBoolean("loud", false))
        l.add(r); save(ctx, l)
        val exact = schedule(ctx, r)
        val o = JSONObject().put("ok", true).put("id", r.id).put("when", whenText(at)).put("repeat", repeat).put("action", action).put("exact", exact)
        if (!exact) o.put("note", "Android has not allowed exact alarms for NISU AI, so it may come a few minutes late. Tell him to allow 'Alarms & reminders' for NISU AI in Settings > Apps > NISU AI.")
        if (action == "call") o.put("call_note", "At that time the phone will place the call by itself (needs the Phone permission).")
        return o
    }

    fun list(ctx: Context): JSONObject {
        val a = JSONArray()
        load(ctx).sortedBy { it.at }.forEach { a.put(JSONObject().put("id", it.id).put("when", whenText(it.at)).put("text", it.text).put("repeat", it.repeat).put("action", it.action).put("name", it.name)) }
        return JSONObject().put("ok", true).put("reminders", a).put("count", a.length())
    }

    fun cancel(ctx: Context, a: JSONObject): JSONObject {
        val l = load(ctx)
        val all = a.optString("id").equals("all", true) || a.optBoolean("all", false)
        val id = a.optInt("id", -1)
        val q = a.optString("text").trim().lowercase()
        val gone = l.filter { all || it.id == id || (q.isNotEmpty() && it.text.lowercase().contains(q)) }
        if (gone.isEmpty()) return err("No such reminder. Use list_reminders to see them.")
        gone.forEach { unschedule(ctx, it.id) }
        save(ctx, l.filter { r -> gone.none { it.id == r.id } })
        return JSONObject().put("ok", true).put("cancelled", gone.size)
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    // ------------------------------------------------------------------------------------------------ alarm clock
    private fun pi(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, 7000 + id, Intent(ctx, ReminderReceiver::class.java).setAction(ACT).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Returns true when the alarm is exact. */
    private fun schedule(ctx: Context, r: R): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(ctx, r.id)); false }
            else { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(ctx, r.id)); true }
        } catch (_: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi(ctx, r.id)) } catch (_: Exception) {}
            false
        }
    }

    private fun unschedule(ctx: Context, id: Int) {
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, id)) } catch (_: Exception) {}
    }

    /** After a restart or an update the alarms are put back; one that was missed in the last 6 hours still fires now. */
    fun rescheduleAll(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            var l = load(ctx)
            val missed = ArrayList<R>()
            l = l.mapNotNull { r ->
                when {
                    r.at >= now - 30_000L -> { schedule(ctx, r); r }
                    r.repeat != "none" -> { var t = r.at; while (t < now) t = advance(t, r.repeat); val n = R(r.id, t, r.text, r.repeat, r.action, r.number, r.name, r.loud); schedule(ctx, n); n }
                    now - r.at < 6 * 3600_000L -> { missed.add(r); null }
                    else -> null
                }
            }.toMutableList()
            save(ctx, l)
            missed.forEach { notify(ctx, it, missed = true) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------------ firing
    fun fire(ctx: Context, id: Int, done: () -> Unit) {
        val l = load(ctx)
        val r = l.firstOrNull { it.id == id }
        if (r == null) { done(); return }
        if (r.repeat == "none") save(ctx, l.filter { it.id != id })
        else {
            var t = r.at; val now = System.currentTimeMillis()
            while (t <= now) t = advance(t, r.repeat)
            val n = R(r.id, t, r.text, r.repeat, r.action, r.number, r.name, r.loud)
            save(ctx, l.map { if (it.id == id) n else it }); schedule(ctx, n)
        }
        notify(ctx, r, missed = false)
        val who = if (r.name.isNotBlank()) r.name else r.number
        val note = if (r.action == "call")
            "[SYSTEM NOTICE, not spoken by the user] The reminder you set for him is due now: call " + who + " (" + r.text + "). The phone is placing the call by itself. Tell him in one short sentence."
        else
            "[SYSTEM NOTICE, not spoken by the user] The reminder you set for him is due NOW: \"" + r.text + "\". Say it to him now in your own warm words, in one or two short sentences, and then carry on."
        val cb = onFire
        if (cb != null) cb(note) else addPending(note)
        if (r.action == "call") {
            thread(name = "nisu-reminder-call") {
                try { PhoneControl.call(ctx, r.number) } catch (_: Exception) {}
                done()
            }
        } else done()
    }

    private fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "NISU AI reminders", NotificationManager.IMPORTANCE_HIGH))
        if (nm.getNotificationChannel(CH_LOUD) == null) {
            val c = NotificationChannel(CH_LOUD, "NISU AI alarms", NotificationManager.IMPORTANCE_HIGH)
            c.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            c.enableVibration(true)
            nm.createNotificationChannel(c)
        }
    }

    private fun notify(ctx: Context, r: R, missed: Boolean) {
        try {
            channels(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val title = (if (missed) "Missed reminder" else if (r.action == "call") "Calling now" else "Reminder") + (if (r.name.isNotBlank()) " - " + r.name else "")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, if (r.loud) CH_LOUD else CH) else Notification.Builder(ctx)
            b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(r.text).setStyle(Notification.BigTextStyle().bigText(r.text))
                .setContentIntent(open).setAutoCancel(true).setCategory(if (r.loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            if (r.loud && Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60_000L)
            val n = b.build()
            if (r.loud) n.flags = n.flags or Notification.FLAG_INSISTENT   // the alarm sound repeats until he dismisses it
            ctx.getSystemService(NotificationManager::class.java)?.notify(9000 + r.id, n)
        } catch (_: Exception) {}
    }

    /** Added to the voice persona. */
    const val NOTE = """

REMINDERS, ALARMS AND CALLS (you do these yourself): when he asks to be reminded, woken up, or wants somebody called at a time, call set_reminder at once: text = what to say, at = the local time as YYYY-MM-DD HH:mm (24 h; work it out from the CLOCK line or call get_time), or in_minutes for "in 20 minutes". repeat = daily or weekly when he says every day / every week. loud = true for a wake-up alarm. For a call at a set time use action "call" with the phone number (find_contact first; ask him if there are several matches) and name. Say back in one short sentence what you set and when. Also set a reminder yourself, without being asked, when he mentions something with a time he must not forget (a meeting, a medicine, an exam, a payment, a birthday): set it a little before, and tell him in a few words that you did. Use list_reminders to tell him what is set and cancel_reminder to remove one (id, or text, or all). When a reminder is due you will get a notice: say it to him sweetly in your own words. You can also call anybody right now with find_contact and call_number when he asks. Place a call only to a number he asked for."""
}

/** Fires when an alarm is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        if (id < 0) return
        val pr = goAsync()
        val app = c.applicationContext
        thread(name = "nisu-reminder") {
            try { Reminders.fire(app, id) { try { pr.finish() } catch (_: Exception) {} } } catch (_: Exception) { try { pr.finish() } catch (_: Exception) {} }
        }
    }
}
