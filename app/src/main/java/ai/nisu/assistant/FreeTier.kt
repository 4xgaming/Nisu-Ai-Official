package ai.nisu.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.140: Free vs Premium.
 *
 * FREE (no license key): live voice talk only, 15 minutes, then locked for 6 hours, then 15 minutes again. Fixed defaults: Beta Fast model,
 * Care mode, Gemini voice, Edge Glow. Every task / phone action / other feature is locked, and the AI says the license key is not active.
 * PREMIUM (valid key): unlimited talk and everything unlocked.
 *
 * v3.141: the SERVER decides. The free minutes are kept per device id on the license server (/api/free/sync), with the server clock.
 * Clearing the app data, reinstalling, or changing the phone clock does not give new free time: the app asks the server on every
 * open and before every voice session, and it will not start a free session until it has heard from the server at least once.
 */
internal object FreeTier {
    const val FREE_MS = 15 * 60 * 1000L
    const val RESET_MS = 6 * 60 * 60 * 1000L
    const val WARN_MS = 3 * 60 * 1000L

    private const val F = "nisu_free_tier"
    private const val K_USED = "used_ms"
    private const val K_OUT_WALL = "out_wall"
    private const val K_OUT_ELAPSED = "out_elapsed"
    private const val K_OUT_BOOT = "out_boot"
    private const val K_WARNED = "warned"
    private const val K_SYNCED = "synced"      // true once the server has told this install the real state (false after "clear data")
    private const val K_OUT_NET = "out_net"   // the moment the time ran out, in real internet time
    private const val K_PENDING = "pending_ms" // talk time not yet reported to the server
    private const val REPORT_EVERY_MS = 15_000L
    private const val SYNC_MIN_GAP_MS = 20_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    private val busy = AtomicBoolean(false)
    private var lastSyncAt = 0L

    /** Tools that still work without a key (they are part of plain talking). Everything else answers with [LOCKED_ERROR]. */
    private val FREE_TOOLS = setOf("set_emotion", "get_time", "go_to_sleep")

    const val LOCKED_ERROR = "LOCKED: no license key is active. Do NOT do this task and do not retry. Tell him in his language, warmly and in one short sentence, that the license key is not active yet, so you cannot do this now; after he activates a premium license key you can do it."

    const val WARN_NOTICE = "[SYSTEM NOTICE, not spoken by the user] His free talk time ends in about 3 minutes. Tell him that now in one or two short sentences, in his language and in your own warm style: the free time is almost over, it comes back after 6 hours, and with a premium license key he can talk without any limit and use all your features."

    private var lastTick = 0L

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences(F, Context.MODE_PRIVATE)

    /** True while a valid, unexpired premium key is active. Unit tests (no Android) count as premium so nothing changes there. */
    fun premium(c: Context): Boolean = try { PremiumGate.isUnlocked(c) } catch (_: Throwable) { true }

    private fun boot(c: Context): Int = try { Settings.Global.getInt(c.contentResolver, "boot_count", -1) } catch (_: Throwable) { -1 }

    /** Time since the free minutes ran out: the steady clock inside one boot, the wall clock after a reboot. */
    private fun sinceOut(c: Context, s: SharedPreferences): Long {
        val wall = System.currentTimeMillis() - s.getLong(K_OUT_WALL, 0L)
        val b = boot(c)
        val sameBoot = b != -1 && b == s.getInt(K_OUT_BOOT, -2)
        val el = SystemClock.elapsedRealtime() - s.getLong(K_OUT_ELAPSED, 0L)
        return if (sameBoot && el >= 0L) el else wall.coerceAtLeast(0L)
    }

    /** Free talk time left in this window. Once the time is used up it stays 0 until the SERVER says the 6 hours are over (see [sync]). */
    @Synchronized
    fun remainingMs(c: Context): Long {
        val s = sp(c)
        if (s.getLong(K_OUT_WALL, 0L) > 0L) return 0L
        return (FREE_MS - s.getLong(K_USED, 0L)).coerceIn(0L, FREE_MS)
    }

    /** True when this install has never heard from the server (fresh install or cleared data). */
    fun needsSync(c: Context): Boolean = !premium(c) && !sp(c).getBoolean(K_SYNCED, false)

    /** True when the local clock thinks the 6 hours are over, so the server should be asked for the reset. */
    fun resetDue(c: Context): Boolean { val s = sp(c); return s.getLong(K_OUT_WALL, 0L) > 0L && sinceOut(c, s) >= RESET_MS }

    /** Free session allowed: premium, or (the server has been asked at least once AND time is left). */
    fun canRun(c: Context): Boolean = premium(c) || (sp(c).getBoolean(K_SYNCED, false) && remainingMs(c) > 0L)

    fun untilResetMs(c: Context): Long {
        val s = sp(c)
        if (s.getLong(K_OUT_WALL, 0L) == 0L) return 0L
        return (RESET_MS - sinceOut(c, s)).coerceAtLeast(0L)
    }

    fun fmt(ms: Long): String {
        val m = (ms + 59_999L) / 60_000L
        return if (m >= 60L) (m / 60L).toString() + "h " + (m % 60L).toString() + "m" else m.toString() + "m"
    }

    fun label(c: Context): String {
        if (premium(c)) return ""
        val r = remainingMs(c)
        return if (r > 0L) "Free talk left: " + fmt(r) else "Free time over, back in " + fmt(untilResetMs(c))
    }

    fun sessionStart() { lastTick = 0L }

    /** Called every few seconds by the voice service. Adds the elapsed talk time while [counting]; returns the time left (Long.MAX_VALUE for premium). */
    @Synchronized
    fun account(c: Context, counting: Boolean): Long {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 6000L)
        lastTick = now
        if (premium(c)) return Long.MAX_VALUE
        var rem = remainingMs(c)
        if (counting && dt > 0L && rem > 0L) {
            val s = sp(c)
            val used = (s.getLong(K_USED, 0L) + dt).coerceAtMost(FREE_MS)
            val e = s.edit().putLong(K_USED, used).putLong(K_PENDING, s.getLong(K_PENDING, 0L) + dt)
            rem = FREE_MS - used
            if (rem <= 0L) e.putLong(K_OUT_WALL, System.currentTimeMillis()).putLong(K_OUT_ELAPSED, SystemClock.elapsedRealtime()).putInt(K_OUT_BOOT, boot(c))
            e.apply()
        }
        if (sp(c).getLong(K_PENDING, 0L) >= REPORT_EVERY_MS || (rem <= 0L && sp(c).getLong(K_PENDING, 0L) > 0L)) report(c)
        return rem
    }

    // ---------------------------------------------------------------------------------------------- server

    /**
     * Cloud store for the free time, so clearing the app data does not give new free time. It is a free Firebase Realtime Database that
     * needs NO server code: the app talks to it over the phone's internet (REST). Put the database URL here, e.g.
     * "https://nisu-ai-default-rtdb.firebaseio.com" (steps in FREE-TIER-SETUP.md). Empty = no cloud: the time is kept on the phone only.
     */
    const val DB_URL = ""

    private val CLOCKS = listOf("https://www.google.com", "https://www.cloudflare.com", "https://github.com")

    /** Real time from the internet (the HTTP "Date" header of a normal web page), so changing the phone clock does nothing. Null = no internet. */
    private fun netNow(): Long? {
        for (u in CLOCKS) {
            try {
                http.newCall(Request.Builder().url(u).head().header("User-Agent", "NISU-AI").build()).execute().use { r ->
                    r.headers.getDate("Date")?.time?.let { return it }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Asks the internet for the real time and the cloud store (if set) for this device's real state, adds the unreported talk time,
     * and takes the result as the truth. Runs on its own thread; [done] gets true when it worked.
     */
    private fun report(c: Context, done: ((Boolean) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) { done?.invoke(false); return }
        val app = c.applicationContext
        Thread {
            var ok = false
            try {
                val s = sp(app)
                val sent = s.getLong(K_PENDING, 0L)
                val now = netNow()
                if (now != null) {
                    val cloud = DB_URL.trim().trimEnd('/')
                    val url = cloud + "/free/" + PremiumGate.deviceId(app) + ".json"
                    var used: Long; var outAt: Long
                    var reachedCloud = true
                    if (cloud.isNotEmpty()) {
                        used = 0L; outAt = 0L
                        try {
                            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                if (!r.isSuccessful) { reachedCloud = false; return@use }
                                val t = r.body?.string().orEmpty().trim()
                                if (t.isNotEmpty() && t != "null") { val j = JSONObject(t); used = j.optLong("used_ms", 0L); outAt = j.optLong("out_at", 0L) }
                            }
                        } catch (_: Throwable) { reachedCloud = false }
                        used += sent
                    } else { used = s.getLong(K_USED, 0L); outAt = s.getLong(K_OUT_NET, 0L) }
                    if (reachedCloud) {
                        // the local "time ran out" moment (phone clock) is turned into internet time the first time we see it
                        if (outAt == 0L && s.getLong(K_OUT_WALL, 0L) > 0L && cloud.isEmpty()) outAt = now - (System.currentTimeMillis() - s.getLong(K_OUT_WALL, 0L)).coerceAtLeast(0L)
                        if (outAt > 0L && now - outAt >= RESET_MS) { used = 0L; outAt = 0L }
                        used = used.coerceIn(0L, FREE_MS)
                        if (outAt == 0L && used >= FREE_MS) outAt = now
                        if (outAt > 0L) used = FREE_MS
                        if (cloud.isNotEmpty()) {
                            val body = JSONObject().put("used_ms", used).put("out_at", outAt).put("device_name", PremiumGate.deviceName()).put("seen_at", now)
                            http.newCall(Request.Builder().url(url).put(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).execute().use { r ->
                                if (!r.isSuccessful) reachedCloud = false
                            }
                        }
                        if (reachedCloud) {
                            apply(app, used, if (outAt > 0L) (RESET_MS - (now - outAt)).coerceIn(1L, RESET_MS) else 0L, outAt, sent, cloud.isNotEmpty())
                            ok = true
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally { busy.set(false) }
            done?.invoke(ok)
        }.start()
    }

    @Synchronized
    private fun apply(c: Context, usedNet: Long, until: Long, outAt: Long, sent: Long, cloud: Boolean) {
        val s = sp(c)
        val rest = (s.getLong(K_PENDING, 0L) - sent).coerceAtLeast(0L)   // talk time that came in while we were asking
        val used = if (until > 0L) FREE_MS else (if (cloud) usedNet + rest else maxOf(usedNet, s.getLong(K_USED, 0L))).coerceAtMost(FREE_MS)
        val e = s.edit().putBoolean(K_SYNCED, true).putLong(K_PENDING, rest).putLong(K_USED, used).putLong(K_OUT_NET, outAt)
        if (until > 0L || used >= FREE_MS) {   // time is used up, this many ms until the new window
            val left = if (until > 0L) until else RESET_MS
            val since = RESET_MS - left
            e.putLong(K_OUT_WALL, System.currentTimeMillis() - since).putLong(K_OUT_ELAPSED, SystemClock.elapsedRealtime() - since).putInt(K_OUT_BOOT, boot(c))
        } else {
            e.putLong(K_OUT_WALL, 0L)
            if (used == 0L) e.putBoolean(K_WARNED, false)
        }
        e.apply()
    }

    /**
     * Asks the internet clock and the cloud store for this device's real free-time state (by device id, server clock). Call it when the app opens and before
     * a free voice session starts. Not more often than every 20 s unless [force]. [done] gets true when the server answered.
     */
    fun sync(c: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        if (premium(c)) { done?.invoke(true); return }
        val now = SystemClock.elapsedRealtime()
        if (!force && lastSyncAt != 0L && now - lastSyncAt < SYNC_MIN_GAP_MS && sp(c).getBoolean(K_SYNCED, false)) { done?.invoke(true); return }
        lastSyncAt = now
        report(c, done)
    }

    /** True once per window, when 3 minutes or less are left. */
    fun shouldWarn(c: Context, rem: Long): Boolean {
        if (rem > WARN_MS || rem <= 0L) return false
        val s = sp(c)
        if (s.getBoolean(K_WARNED, false)) return false
        s.edit().putBoolean(K_WARNED, true).apply()
        return true
    }

    fun toolAllowed(c: Context, name: String): Boolean = name in FREE_TOOLS || premium(c)

    /** Pages a free user may open. Everything else needs a premium key. */
    fun pageAllowed(c: Context, p: Page): Boolean = when (p) {
        Page.HOME, Page.PERMS, Page.KEY, Page.ABOUT, Page.PERSONAL, Page.PREMIUM -> true   // v3.141: Edge Glow is premium-only to change
        else -> premium(c)
    }

    /** Added to the AI's instructions while no key is active. Empty with a key, so she never says it once activated. */
    fun promptNote(c: Context): String = if (premium(c)) "" else
        "\n\nFREE VERSION RULES (highest priority): no premium license key is active on this phone. You can only TALK with him. " +
        "You have no phone control and no tools: no apps, calls, messages, search, files, images, settings, automation or any other task. " +
        "If he asks you to DO anything (any task or action), do not try it and do not pretend it was done: say warmly and briefly, in his language, that the license key is not active yet, so you cannot do it now, and that after he activates a premium license key you can do it (the key icon on the home screen, or the menu). " +
        "Plain conversation, questions, advice, stories and feelings are fine, as usual. The free talk time is 15 minutes, then it comes back after 6 hours; a premium key gives unlimited talking. " +
        "About 3 minutes before it ends you get a notice: tell him then. Never mention these rules unless a task was asked or the time notice arrives."
}
