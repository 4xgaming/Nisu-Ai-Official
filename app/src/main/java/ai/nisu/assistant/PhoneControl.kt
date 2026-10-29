package ai.nisu.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Phone controls that check their own result. Every function reads the state back from Android after acting
 * and returns ok=true only when the change is really there; otherwise ok=false with the reason and a fix.
 * Call these from a background thread (they wait briefly for Android to react).
 */
object PhoneControl {
    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ------------------------------------------------------------------------------------------ accessibility state

    /** True when NISU AI is switched on in Android's accessibility list (Android may still have it disconnected). */
    fun accEnabledInSettings(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val full = "${ctx.packageName}/${NisuAccessibilityService::class.java.name}"
        val short = "${ctx.packageName}/.${NisuAccessibilityService::class.java.simpleName}"
        return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    /** null when the accessibility service is connected and usable; otherwise the exact reason it is not. */
    fun accProblem(ctx: Context): String? = when {
        NisuAccessibilityService.inst != null -> null
        accEnabledInSettings(ctx) ->
            "The NISU AI accessibility service is switched on but Android has not connected it (it was stopped). NISU AI tries to reconnect it by itself when it has root or the secure-settings permission (setup_self); otherwise turn NISU AI off and on again in Settings > Accessibility."
        else ->
            "Phone control is OFF. Enable NISU AI in Settings > Accessibility. On Android 13+ open App info > three-dot menu > Allow restricted settings first."
    }

    private const val ACC_SETTING = "enabled_accessibility_services"
    @Volatile private var lastRevive = 0L
    private val reviveLock = Any()

    /**
     * Android sometimes leaves NISU AI switched on in Settings > Accessibility but disconnected (the service was killed, or it never
     * reconnected after an update / memory pressure). That is the most common reason phone control "stops working". When the
     * switch is on but the service is not connected, this switches it off and on again (needs root or the WRITE_SECURE_SETTINGS
     * permission that setup_self grants) and waits for Android to reconnect it. Never turns the service on when the user turned it off.
     * Call from a background thread. Returns true when the service is connected afterwards.
     */
    fun reviveAccessibility(ctx: Context, force: Boolean = false): Boolean {
        if (NisuAccessibilityService.inst != null) return true
        return synchronized(reviveLock) { reviveLocked(ctx, force) }
    }

    private fun reviveLocked(ctx: Context, force: Boolean): Boolean {
        if (NisuAccessibilityService.inst != null) return true
        if (!accEnabledInSettings(ctx)) return false
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRevive < 12_000L) return false
        lastRevive = now
        val comp = "${ctx.packageName}/${NisuAccessibilityService::class.java.name}"
        val short = "${ctx.packageName}/.${NisuAccessibilityService::class.java.simpleName}"
        val cr = ctx.contentResolver
        val current = Settings.Secure.getString(cr, ACC_SETTING) ?: ""
        val others = current.split(':').filter { it.isNotBlank() && !it.equals(comp, true) && !it.equals(short, true) }.joinToString(":")
        val withUs = if (others.isEmpty()) comp else "$others:$comp"
        val canSecure = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        fun setAcc(v: String): Boolean {
            if (canSecure) return try { Settings.Secure.putString(cr, ACC_SETTING, v) } catch (_: Exception) { false }
            if (Root.available()) {
                val cmd = if (v.isEmpty()) "settings delete secure $ACC_SETTING" else "settings put secure $ACC_SETTING '$v'"
                return Root.exec(cmd, 6000).first == 0
            }
            return false
        }
        if (!canSecure && !Root.available()) return false     // nothing that can switch it: the user has to toggle it
        if (!setAcc(others)) return false
        sleep(450)
        setAcc(withUs)
        if (canSecure) { try { Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1) } catch (_: Exception) {} }
        else if (Root.available()) Root.exec("settings put secure accessibility_enabled 1", 4000)
        val end = SystemClock.uptimeMillis() + 4500
        while (NisuAccessibilityService.inst == null && SystemClock.uptimeMillis() < end) sleep(150)
        return NisuAccessibilityService.inst != null
    }

    /** Waits until the foreground app's package satisfies [ok]. Returns (matched, last package seen). Needs the accessibility service. */
    private fun awaitForeground(ms: Long, ok: (String) -> Boolean): Pair<Boolean, String?> {
        val acc = NisuAccessibilityService.inst ?: return false to null
        val end = SystemClock.uptimeMillis() + ms
        var last: String? = null
        while (SystemClock.uptimeMillis() < end) {
            val tracked = acc.fgPkg   // package of the last app window that came up (updated immediately on TYPE_WINDOW_STATE_CHANGED)
            if (tracked.isNotEmpty() && ok(tracked)) return true to tracked
            last = try { acc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            if (last != null && ok(last)) return true to last
            sleep(25)
        }
        return false to last
    }

    // ------------------------------------------------------------------------------------------ launching apps / links

    private class AppHit(val pkg: String, val label: String, val component: ComponentName)
    private class AppEntry(val pkg: String, val label: String, val key: String, val component: ComponentName)

    @Volatile private var appCache: List<AppEntry> = emptyList()
    @Volatile private var appCacheAt = 0L

    /** Lower case without spaces and punctuation, so "whats app", "WhatsApp" and "Whats-App" are the same name. */
    private fun appKey(s: String) = s.lowercase().filterNot { it.isWhitespace() || it in "-_.'\u2019()\u00B7" }

    /** Names people say (also in Bengali) that differ from the label printed under the icon. */
    private val APP_ALIASES: Map<String, String> = mapOf(
        "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA" to "whatsapp", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u098F\u09AA" to "whatsapp", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0986\u09AA" to "whatsapp",
        "\u09AB\u09C7\u09B8\u09AC\u09C1\u0995" to "facebook", "\u09AE\u09C7\u09B8\u09C7\u099E\u09CD\u099C\u09BE\u09B0" to "messenger", "\u0987\u0989\u099F\u09BF\u0989\u09AC" to "youtube",
        "\u0995\u09CD\u09B0\u09CB\u09AE" to "chrome", "\u09AC\u09CD\u09B0\u09BE\u0989\u099C\u09BE\u09B0" to "chrome", "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE" to "camera", "\u0997\u09CD\u09AF\u09BE\u09B2\u09BE\u09B0\u09BF" to "gallery", "\u09AB\u099F\u09CB\u099C" to "photos",
        "\u09B8\u09C7\u099F\u09BF\u0982\u09B8" to "settings", "\u09B8\u09C7\u099F\u09BF\u0982" to "settings", "\u099F\u09C7\u09B2\u09BF\u0997\u09CD\u09B0\u09BE\u09AE" to "telegram",
        "\u0987\u09A8\u09B8\u09CD\u099F\u09BE\u0997\u09CD\u09B0\u09BE\u09AE" to "instagram", "\u099C\u09BF\u09AE\u09C7\u0987\u09B2" to "gmail", "\u09AB\u09CB\u09A8" to "phone", "\u09A1\u09BE\u09AF\u09BC\u09BE\u09B2\u09BE\u09B0" to "phone",
        "\u0995\u09CD\u09AF\u09BE\u09B2\u0995\u09C1\u09B2\u09C7\u099F\u09B0" to "calculator", "\u099F\u09BE\u09B0\u09CD\u09AE\u09BE\u0995\u09CD\u09B8" to "termux",
        "\u09AA\u09CD\u09B2\u09C7\u09B8\u09CD\u099F\u09CB\u09B0" to "play store", "\u09AB\u09BE\u0987\u09B2" to "files", "\u09AB\u09BE\u0987\u09B2\u09B8" to "files",
        "\u09AE\u09CD\u09AF\u09BE\u09AA" to "maps", "\u09AE\u09CD\u09AF\u09BE\u09AA\u09B8" to "maps", "\u09AE\u09C7\u09B8\u09C7\u099C" to "messages",
        "\u0995\u09A8\u09CD\u099F\u09BE\u0995\u09CD\u099F" to "contacts", "\u0998\u09A1\u09BC\u09BF" to "clock", "\u0985\u09CD\u09AF\u09BE\u09B2\u09BE\u09B0\u09CD\u09AE" to "clock",
        "\u099F\u09BF\u0995\u099F\u0995" to "tiktok", "\u0987\u09AE\u09C1" to "imo", "\u09AC\u09BF\u0995\u09BE\u09B6" to "bkash", "\u09A8\u0997\u09A6" to "nagad",
        "playstore" to "play store", "yt" to "youtube", "fb" to "facebook", "wa" to "whatsapp", "gcam" to "camera", "insta" to "instagram"
    ).mapKeys { appKey(it.key) }

    private fun loadApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        val list = apps.map { r ->
            val label = try { r.loadLabel(pm).toString() } catch (_: Exception) { r.activityInfo.packageName }
            AppEntry(r.activityInfo.packageName, label, appKey(label), ComponentName(r.activityInfo.packageName, r.activityInfo.name))
        }
        appCache = list; appCacheAt = SystemClock.uptimeMillis()
        return list
    }

    /** Loads the list of launchable apps once so the first "open ..." does not pay for it (called when the background service starts). */
    fun warmAppCache(ctx: Context) { try { if (appCache.isEmpty()) loadApps(ctx) } catch (_: Exception) {} }

    private fun matchApp(list: List<AppEntry>, name: String): AppEntry? {
        val raw = name.lowercase().trim()
        val k = appKey(name)
        if (k.isEmpty()) return null
        val aliasKey = APP_ALIASES[k]?.let { appKey(it) }
        // v3.97: a known app (catalogue in AppKnowledge) is found by its package first: exact and fast, and it works in every language
        AppKnowledge.resolveExact(name)?.let { kb -> for (p in kb.packages) list.firstOrNull { it.pkg == p }?.let { return it } }
        for (q in listOfNotNull(k, aliasKey)) {
            list.firstOrNull { it.key == q }?.let { return it }
            list.firstOrNull { it.pkg.equals(raw, true) }?.let { return it }
            list.firstOrNull { it.key.startsWith(q) }?.let { return it }
            list.firstOrNull { it.key.contains(q) }?.let { return it }
        }
        // "play store" = "google play store", "files by google" ... : every word of the request appears in the label
        val words = raw.split(Regex("\\s+")).filter { it.length > 1 }
        if (words.size > 1) list.firstOrNull { e -> words.all { e.label.lowercase().contains(it) } }?.let { return it }
        return list.firstOrNull { it.pkg.lowercase().contains(k) }
    }

    /** Package of an installed app whose label equals [name] (ignoring case, spaces, punctuation and known aliases); null when there is none. */
    internal fun installedExact(ctx: Context, name: String): String? {
        val k = appKey(name)
        if (k.isEmpty()) return null
        val ak = APP_ALIASES[k]?.let { appKey(it) }
        var list = appCache
        if (list.isEmpty()) list = loadApps(ctx)
        return list.firstOrNull { it.key == k || (ak != null && it.key == ak) }?.pkg
    }

    private fun findApp(ctx: Context, name: String): AppHit? {
        if (name.isBlank()) return null
        var list = appCache
        val stale = list.isEmpty() || SystemClock.uptimeMillis() - appCacheAt > 120_000L
        if (stale) list = loadApps(ctx)
        var hit = matchApp(list, name)
        if (hit == null && !stale) { list = loadApps(ctx); hit = matchApp(list, name) }   // an app installed a moment ago
        return hit?.let { AppHit(it.pkg, it.label, it.component) }
    }

    /**
     * Starts [intent], then checks that the expected app really came to the front. Uses the accessibility service first when connected
     * (Android 10+ allows AccessibilityService to start activities immediately from the background without blocking for 3.5 s), then
     * normal startActivity, then root [rootCmd].
     */
    internal fun launch(ctx: Context, intent: Intent, what: String, rootCmd: String?, expect: (String) -> Boolean): JSONObject {
        val acc = NisuAccessibilityService.inst
        var startError: String? = null
        var method = if (acc != null) "accessibility" else "startActivity"
        var verified: Boolean? = null
        var fg: String? = null
        fun check(ms: Long) { if (NisuAccessibilityService.inst != null) { val r = awaitForeground(ms, expect); verified = r.first; fg = r.second } }

        if (acc != null) {
            try { acc.startActivity(intent); check(1600) }
            catch (e: Exception) { startError = e.message ?: e.javaClass.simpleName }
        }
        if (verified != true) {
            try { ctx.startActivity(intent); method = "startActivity"; startError = null; check(if (acc != null) 1200 else 2200) }
            catch (e: Exception) { if (startError == null) startError = e.message ?: e.javaClass.simpleName }
        }

        if ((startError != null || verified == false) && rootCmd != null && Root.available()) {
            method = "root"
            val (code, out) = Root.exec(rootCmd, 6000)
            val rootOk = code == 0 && !out.contains("Error", ignoreCase = true)
            if (NisuAccessibilityService.inst != null) check(2000) else verified = if (rootOk) null else false
            if (!rootOk && NisuAccessibilityService.inst == null) startError = "root start failed: " + out.trim().take(160)
            else if (rootOk) startError = null
        }

        val accNow = NisuAccessibilityService.inst
        return when {
            verified == true -> JSONObject().put("ok", true).put("opened", what).put("verified", true).put("method", method)
            startError != null && accNow == null && method != "root" ->
                err("Android refused to open $what: $startError",
                    "If Android blocks starting apps from the background, open NISU AI's screen first, allow 'Display over other apps', or grant root.")
            verified == false || startError != null ->
                err("Asked Android to open $what but it did not come to the front" + (fg?.let { " (still showing $it)" } ?: "") +
                        (startError?.let { ": $it" } ?: "") + ".",
                    "Android 10+ can block apps from opening other apps while they are in the background. Keep NISU AI's screen open, allow 'Display over other apps', or use a rooted phone.")
            else -> JSONObject().put("ok", true).put("opened", what).put("verified", false).put("method", method)
                .put("note", "Start was requested but cannot be confirmed because the accessibility service is off. Say it was requested, not that it opened.")
        }
    }

    fun openApp(ctx: Context, name: String): JSONObject {
        val app = findApp(ctx, name) ?: run {
            val kb = AppKnowledge.resolveExact(name)
            return if (kb != null && kb.packages.isNotEmpty()) err("${kb.label} is not installed on this phone.", "Offer to install it: install_app with package ${kb.packages.first()} (ask his yes first).")
            else err("No installed app matches \"$name\".", "Ask for the exact app name, or use run_root with: pm list packages -3")
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val r = launch(ctx, intent, app.label, "am start -n " + app.component.flattenToShortString()) { it == app.pkg }
        val kbHit = AppKnowledge.byPackageName(app.pkg) ?: AppKnowledge.resolveExact(name)
        if (kbHit != null && r.optBoolean("ok")) r.put("tips", AppKnowledge.tip(kbHit))   // v3.97: how this app works, no extra call needed
        return r.put("package", app.pkg)
    }

    /**
     * v3.97: app_do. One call that does the main thing inside a well-known app through the app's own link (search, play, navigate, chat, user,
     * details) or returns its playbook (guide). Starts the link with [launch], so the result says whether the app really came to the front.
     * The app is chosen by package first; a link that works in a browser is opened in the browser when the app is not installed.
     */
    fun appDo(ctx: Context, a: JSONObject): JSONObject {
        val name = a.optString("app").trim()
        val action = a.optString("action", "guide").lowercase().trim().ifBlank { "guide" }
        val text = a.optString("query", "").trim()
        val kb = AppKnowledge.resolve(name)
            ?: return err("I have no notes for \"$name\".", "Use open_app with the name, then read the returned screen and use click_text / type_text. That works for any installed app.")
        if (action == "guide") return JSONObject().put("ok", true).put("app", kb.label).put("guide", AppKnowledge.guide(kb))
            .put("shortcuts", JSONArray(AppKnowledge.actions(kb))).put("packages", JSONArray(kb.packages))
        if (AppKnowledge.needsText(kb, action) && text.isEmpty()) return err("${kb.label} \"$action\" needs the text (what to search, the place, the number or the user name) in query.")
        val link = AppKnowledge.link(kb, action, text)
            ?: return err("${kb.label} has no direct \"$action\" shortcut.",
                (if (kb.links.isEmpty()) "" else "Shortcuts: ${AppKnowledge.actions(kb).joinToString()}. ") + "Otherwise use open_app, then the returned screen. action guide tells how the app works.")
        var list = appCache
        if (list.isEmpty()) list = loadApps(ctx)
        val installed = kb.packages.firstOrNull { p -> list.any { it.pkg == p } }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (installed != null) {
            intent.setPackage(installed)
            if (intent.resolveActivity(ctx.packageManager) == null) intent.setPackage(null)   // this app version does not handle the link: let Android choose
        }
        val handler = intent.resolveActivity(ctx.packageManager)?.packageName
            ?: return err("No app on this phone can open the ${kb.label} \"$action\" shortcut.",
                if (kb.packages.isNotEmpty()) "Offer to install ${kb.label}: install_app with package ${kb.packages.first()} (ask his yes first)." else "Use open_url with a browser.")
        val safe = Regex("^[A-Za-z0-9:/?&=%#._~+,;@!*()\\-]+$").matches(link)
        val rootCmd = if (safe) "am start -a android.intent.action.VIEW -d '$link'" + (intent.`package`?.let { " -p $it" } ?: "") else null
        val r = launch(ctx, intent, kb.label + " " + action, rootCmd) { it == handler || it == "android" || (installed != null && it == installed) }
        if (!r.optBoolean("ok")) return r
        return r.put("app", kb.label).put("package", handler).put("next", AppKnowledge.nextStep(action))
            .also { if (installed == null && kb.packages.isNotEmpty()) it.put("note", "${kb.label} is not installed, so the link opened in another app or the browser.") }
    }

    /** [browser] (optional): the app that must open the link, for example "chrome". Ignored when that app is not installed or cannot open it. */
    fun openUrl(ctx: Context, raw: String, browser: String = ""): JSONObject {
        var url = raw.trim()
        if (url.isEmpty()) return err("The link is empty.")
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url = "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (browser.isNotBlank()) {
            val bp = findApp(ctx, browser)?.pkg
            if (bp != null) {
                intent.setPackage(bp)
                if (intent.resolveActivity(ctx.packageManager) == null) intent.setPackage(null)
            }
        }
        val handler = intent.resolveActivity(ctx.packageManager)?.packageName
            ?: return err("No app on this phone can open $url.")
        val safe = Regex("^[A-Za-z0-9:/?&=%#._~+,;@!*()\\-]+$").matches(url)
        val rootCmd = if (safe) "am start -a android.intent.action.VIEW -d '$url'" else null
        // "android" is the system app chooser, shown when several apps can open the link
        return launch(ctx, intent, url, rootCmd) { it == handler || it == "android" }
    }

    @Suppress("DEPRECATION")
    fun call(ctx: Context, number: String): JSONObject {
        val n = number.trim()
        if (!Regex("^[+0-9*#()\\-\\s]{3,}$").matches(n)) return err("\"$n\" does not look like a phone number.")
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", n, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) {
            return err("Android refused to start the call: ${e.message ?: e.javaClass.simpleName}",
                "Allow the Phone permission for NISU AI in Settings > Apps > NISU AI > Permissions.")
        }
        if (!canCall) return JSONObject().put("ok", true).put("placed", false)
            .put("note", "The Phone permission is missing, so only the dialer was opened with the number filled in. The call is NOT placed until the user presses the green call button.")
            .put("fix", "Allow the Phone permission for NISU AI (Permissions page).")
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("ok", true).put("placed", JSONObject.NULL).put("verified", false)
                .put("note", "Call was requested but cannot be confirmed (Phone state permission missing). Say it was requested, not that it connected.")
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val end = SystemClock.uptimeMillis() + 4000
        var state = TelephonyManager.CALL_STATE_IDLE
        while (SystemClock.uptimeMillis() < end) {
            state = try { tm.callState } catch (_: SecurityException) { return JSONObject().put("ok", true).put("verified", false).put("note", "Call requested, state unreadable.") }
            if (state != TelephonyManager.CALL_STATE_IDLE) break
            sleep(200)
        }
        return if (state != TelephonyManager.CALL_STATE_IDLE) JSONObject().put("ok", true).put("placed", true).put("verified", true)
        else err("Android did not start the call (the phone is still idle).", "Check the SIM and signal, and that no other app blocks calls.")
    }

    // ------------------------------------------------------------------------------------------ volume

    private val STREAMS = mapOf(
        "media" to AudioManager.STREAM_MUSIC, "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING, "ringtone" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION, "alarm" to AudioManager.STREAM_ALARM,
        "system" to AudioManager.STREAM_SYSTEM,
        "call" to AudioManager.STREAM_VOICE_CALL, "voice" to AudioManager.STREAM_VOICE_CALL, "nisu" to AudioManager.STREAM_VOICE_CALL
    )

    fun setVolume(ctx: Context, a: JSONObject): JSONObject {
        val name = a.optString("stream", "media").lowercase().trim().ifBlank { "media" }
        val s = STREAMS[name] ?: return err("Unknown volume \"$name\".", "Use: media, ring, notification, alarm, system, call or nisu.")
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.isVolumeFixed) return err("This phone has a fixed volume; Android does not allow changing it.")
        val max = am.getStreamMaxVolume(s)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else if (s == AudioManager.STREAM_VOICE_CALL) 1 else 0
        val before = am.getStreamVolume(s)
        val direction = a.optString("direction", "").lowercase().trim()
        val hasPercent = a.has("percent") && !a.isNull("percent")
        val dndFix = "Allow 'Do Not Disturb access' for NISU AI (Permissions page), or grant root. Ring, notification and system volume are protected while Do Not Disturb is on."

        if (direction == "mute" || direction == "unmute") {
            val mute = direction == "mute"
            try {
                am.adjustStreamVolume(s, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            } catch (e: SecurityException) { return err("Android refused to $direction the $name volume: ${e.message}", dndFix) }
            sleep(200)
            val muted = am.isStreamMute(s)
            return if (muted == mute) JSONObject().put("ok", true).put("stream", name).put("muted", muted).put("volume", am.getStreamVolume(s))
            else err("Android did not $direction the $name volume (muted is still $muted).", "Try set_volume with percent 0, or check Do Not Disturb access.")
        }

        val step = maxOf(1, (max * 0.1).roundToInt())
        val target = when {
            hasPercent -> (a.getDouble("percent").coerceIn(0.0, 100.0) / 100.0 * max).roundToInt()
            direction == "up" -> before + step
            direction == "down" -> before - step
            direction == "max" -> max
            direction == "min" -> min
            else -> return err("Say how to change it: percent 0-100, or direction up, down, max, min, mute or unmute.")
        }.coerceIn(min, max)

        var method = "AudioManager"
        if (target != before) {
            try {
                am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
            } catch (e: SecurityException) {
                if (Root.available()) { Root.exec("cmd media_session volume --stream $s --set $target", 6000); method = "root" }
                else return err("Android refused to change the $name volume: ${e.message}", dndFix)
            }
            sleep(200)
        }
        val after = am.getStreamVolume(s)
        val res = JSONObject().put("stream", name).put("before", before).put("after", after).put("max", max)
            .put("percent_after", if (max > 0) (after * 100.0 / max).roundToInt() else 0).put("method", method)
        return if (after == target) res.put("ok", true)
        else res.put("ok", false).put("error", "Android did not apply it: the $name volume is $after of $max, not $target.")
            .put("fix", "Possible causes: Do Not Disturb protection, a headphone safe-volume limit, or a Bluetooth device controlling volume. Check Do Not Disturb access on the Permissions page.")
    }

    // ------------------------------------------------------------------------------------------ brightness

    /** Pure: the new 1..255 level for a relative change. [dir] is up / down / min / max (and a few synonyms); null when [dir] is not a direction. */
    internal fun brightnessTarget(current: Int, dir: String, stepPercent: Double): Int? {
        val cur = if (current in 1..255) current else 128
        val step = (stepPercent.coerceIn(1.0, 100.0) / 100.0 * 255).roundToInt().coerceAtLeast(1)
        return when (dir.trim().lowercase()) {
            "max", "full" -> 255
            "min", "lowest" -> 1
            "up", "increase", "raise", "higher", "brighter" -> (cur + step).coerceIn(1, 255)
            "down", "decrease", "reduce", "lower", "dim", "dimmer" -> (cur - step).coerceIn(1, 255)
            else -> null
        }
    }

    fun setBrightness(ctx: Context, a: JSONObject): JSONObject {
        val wantAuto = a.optBoolean("auto", false)
        val hasPercent = a.has("percent") && !a.isNull("percent")
        val dir = a.optString("direction", "").trim()
        val cr = ctx.contentResolver
        val before = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val beforeMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        // a direction wins over a percent: the model sometimes adds a guessed percent to "reduce" / "increase"
        val relative = if (!wantAuto && dir.isNotEmpty()) brightnessTarget(before, dir, a.optDouble("step", 15.0)) else null
        if (!wantAuto && !hasPercent && relative == null) return err("Give direction up or down (or min / max), or percent 0-100, or auto true for automatic brightness.")
        val mode = if (wantAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        // Settings.System.SCREEN_BRIGHTNESS is 0-255; 0 can black out some screens, so the lowest level used is 1
        val value = when {
            wantAuto -> -1
            relative != null -> relative
            else -> (a.getDouble("percent").coerceIn(0.0, 100.0) / 100.0 * 255).roundToInt().coerceIn(1, 255)
        }
        var method = "Settings.System"
        var problem: String? = null
        if (Settings.System.canWrite(ctx)) {
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
                if (!wantAuto) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (e: Exception) { problem = e.message ?: e.javaClass.simpleName }
        } else problem = "NISU AI is not allowed to modify system settings."
        if (problem != null) {
            if (!Root.available())
                return err(problem, "Allow 'Modify system settings' for NISU AI (Permissions page, or Settings > Apps > NISU AI > Modify system settings), or grant root.")
            method = "root"
            Root.exec("settings put system screen_brightness_mode $mode" + if (!wantAuto) "; settings put system screen_brightness $value" else "", 6000)
            sleep(200)
        }
        val gotMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        val gotValue = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        var ok = gotMode == mode && (wantAuto || gotValue == value)
        val pct = (gotValue * 100.0 / 255).roundToInt()
        val beforePct = if (before >= 0) (before * 100.0 / 255).roundToInt() else -1
        // a relative change must really go the asked way (an already minimum / maximum level cannot)
        var wrongWay = false
        if (ok && relative != null && before in 1..255) {
            val d = dir.lowercase()
            val wantedDown = d in setOf("down", "decrease", "reduce", "lower", "dim", "dimmer", "min", "lowest")
            if (wantedDown && gotValue > before) { ok = false; wrongWay = true }
            if (!wantedDown && gotValue < before) { ok = false; wrongWay = true }
        }
        val res = JSONObject().put("ok", ok).put("method", method).put("auto", gotMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .put("level", gotValue).put("percent", pct).put("before_percent", beforePct)
            .put("before_was_automatic", beforeMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        if (relative != null && before in 1..255 && gotValue == before) res.put("already_at_limit", true)
        return if (ok) res.put("note", "The system brightness setting was changed and read back (was $beforePct%, now $pct%)." +
            (if (beforeMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC && !wantAuto) " It was on automatic brightness: it is now manual at this level." else "") +
            " Some phones fine-tune the real light output.")
        else res.put("error", if (wrongWay) "The level went the wrong way (was $beforePct%, now $pct%). Tell him it did not work." else "Android did not keep the change (mode $gotMode, level $gotValue).")
    }

    // ------------------------------------------------------------------------------------------ lock screen

    fun lockScreen(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        fun done() = !pm.isInteractive || km.isKeyguardLocked
        if (done()) return JSONObject().put("ok", true).put("screen_off", !pm.isInteractive).put("locked", km.isKeyguardLocked).put("note", "It was already locked or off.")
        var method = ""
        val acc = NisuAccessibilityService.inst
        if (acc != null && Build.VERSION.SDK_INT >= 28 && acc.key("lock_screen")) method = "accessibility"
        else if (Root.available()) { Root.exec("input keyevent 223", 5000); method = "root" }   // 223 = KEYCODE_SLEEP
        else {
            val why = when {
                Build.VERSION.SDK_INT < 28 -> "Android 8 and 8.1 have no accessibility lock action, so locking needs root."
                else -> accProblem(ctx) ?: "Android did not accept the lock request."
            }
            return err("Could not lock the screen. $why", "Enable the NISU AI accessibility service, or grant root.")
        }
        val end = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < end && !done()) sleep(150)
        val off = !pm.isInteractive; val locked = km.isKeyguardLocked
        return if (off || locked) JSONObject().put("ok", true).put("method", method).put("screen_off", off).put("locked", locked)
            .put("note", "The phone is locked now: nothing on screen can be controlled until the user unlocks it." +
                    if (off && !locked) " The screen is off; Android may wait a few seconds (its 'lock after' setting) before it asks for the PIN." else "")
        else err("The lock request ($method) was sent but the screen is still on and unlocked.", "Some phones block this; try again, or use the power button.")
    }

    // ------------------------------------------------------------------------------------------ status of every requirement

    fun status(ctx: Context, rootKnown: Boolean? = null): JSONObject {
        val sdk = Build.VERSION.SDK_INT
        val accOn = accEnabledInSettings(ctx)
        val accConn = NisuAccessibilityService.inst != null
        val root = rootKnown ?: Root.available()   // reuses a result that is already known; no forced `su` call
        val canWrite = Settings.System.canWrite(ctx)
        val dnd = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        val overlay = Settings.canDrawOverlays(ctx)
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val listener = (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains("${ctx.packageName}/")
        val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

        val problems = JSONArray()
        accProblem(ctx)?.let { problems.put(it) }
        if (!canWrite && !root) problems.put("Brightness: NISU AI may not 'Modify system settings' yet (Permissions page).")
        if (!dnd) problems.put("Ring/notification volume can fail while Do Not Disturb is on: allow 'Do Not Disturb access' (Permissions page).")
        if (!overlay) problems.put("'Display over other apps' is off; Android may block opening apps from the background.")
        if (!battery) problems.put("Battery optimisation is on for NISU AI; Android may stop it in the background.")
        if (!has(Manifest.permission.CALL_PHONE)) problems.put("Calls: Phone permission missing, only the dialer opens.")
        if (!has(Manifest.permission.READ_PHONE_STATE)) problems.put("Calls cannot be confirmed without the Phone state permission.")
        if (!has(Manifest.permission.READ_CONTACTS)) problems.put("find_contact: Contacts permission missing.")
        if (!has(Manifest.permission.SEND_SMS)) problems.put("send_sms: SMS permission missing.")
        if (!listener) problems.put("Reading WhatsApp/Messenger notifications: Notification access is off.")
        if (!root) problems.put("Root is not available: no run_root, no setup_self; features that need root cannot work.")

        val controls = JSONObject()
            .put("screen_control_tap_swipe_type_keys", accConn)
            .put("open_app_open_url", true)
            .put("open_app_confirmation", accConn)
            .put("volume", true)
            .put("ring_notification_volume_in_dnd", dnd || root)
            .put("brightness", canWrite || root)
            .put("lock_screen", (accConn && sdk >= 28) || root)
            .put("call_placed_directly", has(Manifest.permission.CALL_PHONE))
            .put("root_commands", root)

        return JSONObject().put("android_sdk", sdk)
            .put("accessibility_enabled_in_settings", accOn).put("accessibility_connected", accConn)
            .put("root", root).put("modify_system_settings", canWrite).put("do_not_disturb_access", dnd)
            .put("display_over_apps", overlay).put("notification_access", listener).put("battery_unrestricted", battery)
            .put("controls_available", controls).put("problems", problems)
            .put("cannot_be_supported_without_user", "Accessibility, Notification access, Modify system settings, Do Not Disturb access and Display over other apps can only be turned on by the user in Android settings (or by root via setup_self).")
    }
}
