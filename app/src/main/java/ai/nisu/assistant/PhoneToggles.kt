package ai.nisu.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Silent control-panel switches. Nothing here opens or pulls down the control panel: each switch is changed straight through
 * the Android API, the secure-settings permission or root, and then READ BACK, so ok=true only when the phone really changed.
 * Call from a background thread (they wait briefly for Android to react).
 *
 * Switches (the "toggle" argument): flashlight, location, dnd, ringer, ambient_display, wifi, bluetooth, mobile_data, airplane,
 * hotspot, auto_rotate, battery_saver, dark_mode, nfc.
 * "state" is on / off for every switch; for ringer it is normal (general) / silent / vibrate.
 */
object PhoneToggles {
    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun hasSecure(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Waits up to [ms] for [read] to equal [want]; returns the last value read. */
    private fun <T> settle(ms: Long, want: T, read: () -> T): T {
        val end = System.currentTimeMillis() + ms
        var v = read()
        while (v != want && System.currentTimeMillis() < end) { sleep(120); v = read() }
        return v
    }

    // ---------------------------------------------------------------------------------------------- name matching

    /** Pure: maps what people say (English and Bengali, typed in English letters too) to a switch name; null when unknown. */
    internal fun canonical(raw: String): String? {
        val t = raw.lowercase().trim().replace(Regex("[\\s_\\-]+"), "")
        if (t.isEmpty()) return null
        fun has(vararg k: String) = k.any { t.contains(it) }
        return when {
            has("flash", "torch", "টর্চ", "ফ্ল্যাশ", "ফ্লাশ") -> "flashlight"
            has("airplane", "flight", "aeroplane", "এয়ারপ্লেন", "ফ্লাইট") -> "airplane"
            has("hotspot", "tether", "হটস্পট") -> "hotspot"
            has("wifi", "ওয়াইফাই", "ওয়াইফাই") -> "wifi"
            has("bluetooth", "ব্লুটুথ") -> "bluetooth"
            has("mobiledata", "cellulardata", "internet", "data", "মোবাইলডাটা", "মোবাইলডেটা", "ডাটা", "ডেটা") -> "mobile_data"
            has("ambient", "alwayson", "aod", "lockscreendisplay", "অ্যাম্বিয়েন্ট", "এম্বিয়েন্ট", "এম্বিয়েন", "অলওয়েজ") -> "ambient_display"
            has("location", "gps", "লোকেশন") -> "location"
            has("donotdisturb", "dnd", "disturb", "ডিস্টার্ব", "ডিস্টাব", "ডুনট") -> "dnd"
            has("silent", "vibrate", "ringer", "general", "normalmode", "soundmode", "ringmode", "সাইলেন্ট", "ভাইব্রেট", "জেনারেল", "রিং") -> "ringer"
            has("rotat", "রোটেট", "ঘোর") -> "auto_rotate"
            has("batterysaver", "powersaving", "powersave", "lowpower", "ব্যাটারিসেভার") -> "battery_saver"
            has("dark", "night", "ডার্ক") -> "dark_mode"
            has("nfc", "এনএফসি") -> "nfc"
            else -> null
        }
    }

    /** Pure: the wanted state, normalised. Returns "on", "off", "normal", "silent" or "vibrate"; null when it cannot be understood. */
    internal fun wantedState(raw: String): String? {
        val t = raw.lowercase().trim()
        return when {
            t in setOf("on", "true", "enable", "enabled", "yes", "1", "chalu", "চালু", "অন", "খোল") -> "on"
            t in setOf("off", "false", "disable", "disabled", "no", "0", "bondho", "bandho", "বন্ধ", "অফ") -> "off"
            t in setOf("normal", "general", "ring", "sound", "loud", "জেনারেল", "নরমাল") -> "normal"
            t in setOf("silent", "mute", "quiet", "সাইলেন্ট") -> "silent"
            t in setOf("vibrate", "vibration", "ভাইব্রেট") -> "vibrate"
            else -> null
        }
    }

    // ---------------------------------------------------------------------------------------------- entry points

    /** Entry used by the set_switch tool: "status" / "all" only read, everything else changes a switch. */
    fun dispatch(ctx: Context, a: JSONObject): JSONObject {
        val t = a.optString("toggle", a.optString("name", "")).lowercase().trim()
        val st = a.optString("state", "").lowercase().trim()
        if (t == "all" || t == "everything" || t.isEmpty()) return status(ctx)
        if (st == "status" || st == "check" || st == "read" || st.isEmpty() && canonical(t) != null) {
            val name = canonical(t) ?: return err("Unknown switch \"$t\".")
            val all = status(ctx)
            return JSONObject().put("ok", true).put("toggle", name).put("state", all.getJSONObject("switches").opt(name) ?: JSONObject.NULL)
        }
        return run(ctx, a)
    }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val name = canonical(a.optString("toggle", a.optString("name", "")))
            ?: return err("Unknown switch \"${a.optString("toggle")}\".", "Use: flashlight, location, dnd, ringer, ambient_display, wifi, bluetooth, mobile_data, airplane, hotspot, auto_rotate, battery_saver, dark_mode, nfc.")
        val raw = a.optString("state", "")
        val state = wantedState(raw)
        if (name == "ringer") {
            if (state != "normal" && state != "silent" && state != "vibrate") {
                // "ringer on" = general, "ringer off" = silent
                val mapped = when (state) { "on" -> "normal"; "off" -> "silent"; else -> null }
                    ?: return err("For the ringer say state normal (general), silent or vibrate.")
                return ringer(ctx, mapped)
            }
            return ringer(ctx, state)
        }
        if (state != "on" && state != "off") return err("Say state on or off for $name.")
        val on = state == "on"
        return when (name) {
            "flashlight" -> flashlight(ctx, on)
            "dnd" -> dnd(ctx, on)
            "location" -> location(ctx, on)
            "ambient_display" -> ambient(ctx, on)
            "wifi" -> radio(ctx, "wifi", on)
            "bluetooth" -> radio(ctx, "bluetooth", on)
            "mobile_data" -> radio(ctx, "mobile_data", on)
            "airplane" -> radio(ctx, "airplane", on)
            "hotspot" -> radio(ctx, "hotspot", on)
            "nfc" -> radio(ctx, "nfc", on)
            "auto_rotate" -> autoRotate(ctx, on)
            "battery_saver" -> batterySaver(ctx, on)
            "dark_mode" -> darkMode(ctx, on)
            else -> err("Unknown switch $name.")
        }
    }

    /** Reads the real state of every switch we can read; nothing is changed. */
    fun status(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("flashlight", torchState ?: JSONObject.NULL)
        o.put("location", locationOn(ctx))
        o.put("dnd", dndOn(ctx))
        o.put("ringer", ringerName(ctx))
        o.put("ambient_display", ambientOn(ctx) ?: JSONObject.NULL)
        o.put("wifi", readGlobal(ctx, "wifi_on")?.let { it != 0 } ?: JSONObject.NULL)
        o.put("bluetooth", readGlobal(ctx, "bluetooth_on")?.let { it != 0 } ?: JSONObject.NULL)
        o.put("mobile_data", readGlobal(ctx, "mobile_data")?.let { it != 0 } ?: JSONObject.NULL)
        o.put("airplane", readGlobal(ctx, "airplane_mode_on")?.let { it != 0 } ?: JSONObject.NULL)
        o.put("auto_rotate", readSystem(ctx, Settings.System.ACCELEROMETER_ROTATION)?.let { it != 0 } ?: JSONObject.NULL)
        return JSONObject().put("ok", true).put("switches", o)
    }

    // ---------------------------------------------------------------------------------------------- settings helpers

    private fun readGlobal(ctx: Context, key: String): Int? = try { Settings.Global.getInt(ctx.contentResolver, key) } catch (_: Exception) { null }
    private fun readSecure(ctx: Context, key: String): Int? = try { Settings.Secure.getInt(ctx.contentResolver, key) } catch (_: Exception) { null }
    private fun readSystem(ctx: Context, key: String): Int? = try { Settings.System.getInt(ctx.contentResolver, key) } catch (_: Exception) { null }

    /** Writes a global / secure / system int: first with the permission NISU AI has, then with root. Returns the method used or null. */
    private fun writeInt(ctx: Context, table: String, key: String, value: Int): String? {
        val cr = ctx.contentResolver
        try {
            when (table) {
                "global" -> if (hasSecure(ctx) && Settings.Global.putInt(cr, key, value)) return "secure-settings"
                "secure" -> if (hasSecure(ctx) && Settings.Secure.putInt(cr, key, value)) return "secure-settings"
                "system" -> if (Settings.System.canWrite(ctx) && Settings.System.putInt(cr, key, value)) return "system-settings"
            }
        } catch (_: Exception) {}
        if (Root.available() && Root.exec("settings put $table $key $value", 6000).first == 0) return "root"
        return null
    }

    private fun rootRun(cmd: String): Boolean = Root.available() && Root.exec(cmd, 8000).first == 0

    private fun missingPower(what: String) = err("Android does not let NISU AI change $what without extra power.",
        "Give NISU AI the secure-settings permission once (adb shell pm grant ai.nisu.assistant android.permission.WRITE_SECURE_SETTINGS), or use a rooted phone and run setup_self.")

    private fun result(ok: Boolean, name: String, wanted: Boolean, got: Boolean?, method: String): JSONObject =
        if (ok) JSONObject().put("ok", true).put("toggle", name).put("state", if (wanted) "on" else "off").put("method", method).put("verified", true)
        else err("$name did not change: it is ${when (got) { true -> "on"; false -> "off"; null -> "unreadable" }}, not ${if (wanted) "on" else "off"}.",
            "Try once more; if it keeps failing, the phone maker blocks this switch for apps. Root or the secure-settings permission is needed.")

    // ---------------------------------------------------------------------------------------------- flashlight

    @Volatile private var torchState: Boolean? = null
    @Volatile private var torchCallbackSet = false

    private fun torchId(cm: CameraManager): String? = try {
        cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) { null }

    private fun flashlight(ctx: Context, on: Boolean): JSONObject {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return err("This phone has no flashlight.")
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = torchId(cm) ?: return err("Could not find the flashlight of this phone.")
        val seen = CountDownLatch(1)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) { torchState = enabled; if (enabled == on) seen.countDown() } }
            override fun onTorchModeUnavailable(cameraId: String) {}
        }
        torchState = null   // never trust an old value: the callback below reports the real one at once
        val h = Handler(Looper.getMainLooper())
        try { cm.registerTorchCallback(cb, h) } catch (_: Exception) {}
        try {
            // the first callback gives the current state; wait a moment for it so "already on" is recognised
            sleep(150)
            if (torchState == on) return JSONObject().put("ok", true).put("toggle", "flashlight").put("state", if (on) "on" else "off").put("verified", true).put("note", "It was already ${if (on) "on" else "off"}.")
            try { cm.setTorchMode(id, on) }
            catch (e: CameraAccessException) {
                if (rootRun(if (on) "cmd flashlight enable" else "cmd flashlight disable")) {} // some ROMs
                else return err("Android refused the flashlight: ${e.message}", "Close any app that is using the camera, then try again.")
            } catch (e: Exception) { return err("Android refused the flashlight: ${e.message}") }
            seen.await(1500, TimeUnit.MILLISECONDS)
            val got = torchState
            return if (got == on) JSONObject().put("ok", true).put("toggle", "flashlight").put("state", if (on) "on" else "off").put("method", "camera-torch").put("verified", true)
            else result(false, "flashlight", on, got, "camera-torch")
        } finally {
            try { cm.unregisterTorchCallback(cb) } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------------------------------------- Do Not Disturb

    private fun dndOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
    }

    private fun dnd(ctx: Context, on: Boolean): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        var method = ""
        if (nm.isNotificationPolicyAccessGranted) {
            try {
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                method = "notification-policy"
            } catch (_: Exception) {}
        }
        if (method.isEmpty()) {
            if (Root.available() && (Root.exec("cmd notification set_dnd ${if (on) "priority" else "off"}", 6000).first == 0 ||
                        Root.exec("settings put global zen_mode ${if (on) 1 else 0}", 6000).first == 0)) method = "root"
            else return err("Do Not Disturb access is not allowed for NISU AI.", "Allow 'Do Not Disturb access' for NISU AI (Permissions page), or grant root.")
        }
        val got = settle(1500, on) { dndOn(ctx) }
        return result(got == on, "dnd", on, got, method)
    }

    // ---------------------------------------------------------------------------------------------- ringer: silent / vibrate / normal (general)

    private fun ringerName(ctx: Context): String = when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    private fun ringer(ctx: Context, mode: String): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val target = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        var method = "audio-manager"
        // Android turns Do Not Disturb on by itself when a phone is set to silent; leaving it on blocks the way back to normal
        if (mode == "normal" && dndOn(ctx) && nm.isNotificationPolicyAccessGranted) {
            try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) {}
        }
        var refused: String? = null
        try { am.ringerMode = target } catch (e: SecurityException) { refused = e.message ?: "refused" }
        if (refused != null || am.ringerMode != target) {
            if (Root.available()) {
                val m = when (target) { AudioManager.RINGER_MODE_SILENT -> 0; AudioManager.RINGER_MODE_VIBRATE -> 1; else -> 2 }
                if (mode == "normal") Root.exec("cmd notification set_dnd off", 5000)
                Root.exec("cmd audio set-ringer-mode $m 2>/dev/null || service call audio 22 i32 $m", 6000)
                method = "root"
            } else if (refused != null) {
                return err("Android refused the $mode mode: $refused", "Allow 'Do Not Disturb access' for NISU AI (Permissions page), or grant root.")
            }
        }
        val got = settle(1500, target) { am.ringerMode }
        return if (got == target) JSONObject().put("ok", true).put("toggle", "ringer").put("state", mode).put("method", method).put("verified", true)
        else err("The phone is still in ${ringerName(ctx)} mode, not $mode.", "Allow 'Do Not Disturb access' for NISU AI (Permissions page), or grant root.")
    }

    // ---------------------------------------------------------------------------------------------- location

    private fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return try { if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else (readSecure(ctx, "location_mode") ?: 0) != 0 } catch (_: Exception) { (readSecure(ctx, "location_mode") ?: 0) != 0 }
    }

    private fun location(ctx: Context, on: Boolean): JSONObject {
        var method: String? = writeInt(ctx, "secure", "location_mode", if (on) 3 else 0)
        if (method == null && Root.available()) {
            if (Root.exec("cmd location set-location-enabled $on", 6000).first == 0) method = "root"
        }
        if (method == null) return missingPower("Location")
        val got = settle(2000, on) { locationOn(ctx) }
        return result(got == on, "location", on, got, method)
    }

    // ---------------------------------------------------------------------------------------------- ambient display / always-on display

    /** The real "ambient" switch differs by phone maker: stock Android, Pixel and Samsung each use another key. */
    private val AMBIENT_KEYS = listOf(
        Triple("secure", "doze_always_on", 1),      // Pixel / AOSP always-on display
        Triple("secure", "doze_enabled", 1),        // AOSP ambient display (lift / tap to show)
        Triple("secure", "doze_pulse_on_pick_up", 1),
        Triple("secure", "doze_pulse_on_double_tap", 1),
        Triple("system", "aod_mode", 1),            // Samsung always-on display
        Triple("secure", "aod_mode", 1),
        Triple("system", "aod_show_state", 1)       // Xiaomi / others
    )

    private fun readKey(ctx: Context, table: String, key: String): Int? = when (table) {
        "secure" -> readSecure(ctx, key); "system" -> readSystem(ctx, key); else -> readGlobal(ctx, key)
    }

    /** null when this phone has none of the known ambient keys. */
    private fun ambientOn(ctx: Context): Boolean? {
        val present = AMBIENT_KEYS.filter { readKey(ctx, it.first, it.second) != null }
        if (present.isEmpty()) return null
        return present.any { readKey(ctx, it.first, it.second) == it.third }
    }

    private fun ambient(ctx: Context, on: Boolean): JSONObject {
        val keys = AMBIENT_KEYS.filter { readKey(ctx, it.first, it.second) != null }
        if (keys.isEmpty()) {
            // some phones do not create the row until it is first used: write the common ones anyway
            var m: String? = null
            for (k in listOf(AMBIENT_KEYS[0], AMBIENT_KEYS[1], AMBIENT_KEYS[4])) m = writeInt(ctx, k.first, k.second, if (on) k.third else 0) ?: m
            if (m == null) return missingPower("Ambient / Always-on display")
            val got = ambientOn(ctx)
            return result(got == on, "ambient_display", on, got, m)
        }
        var method: String? = null
        // "on" turns on the always-on keys; the pick-up / double-tap pulses are left as they are unless they exist and the user wants everything off
        val main = keys.filter { it.second in setOf("doze_always_on", "aod_mode", "aod_show_state", "doze_enabled") }.ifEmpty { keys }
        for (k in main) writeInt(ctx, k.first, k.second, if (on) k.third else 0)?.let { method = it }
        if (method == null) return missingPower("Ambient / Always-on display")
        val got = settle(1500, on) { main.any { readKey(ctx, it.first, it.second) == it.third } }
        return result(got == on, "ambient_display", on, got, method!!)
    }

    // ---------------------------------------------------------------------------------------------- radios and other control-panel tiles

    private fun radioState(ctx: Context, which: String): Boolean? = when (which) {
        "wifi" -> readGlobal(ctx, "wifi_on")?.let { it != 0 }
        "bluetooth" -> readGlobal(ctx, "bluetooth_on")?.let { it != 0 }
        "mobile_data" -> readGlobal(ctx, "mobile_data")?.let { it != 0 }
        "airplane" -> readGlobal(ctx, "airplane_mode_on")?.let { it != 0 }
        "hotspot" -> null   // no public way to read it back: reported honestly as unverified
        "nfc" -> try { android.nfc.NfcAdapter.getDefaultAdapter(ctx)?.isEnabled } catch (_: Exception) { null }
        else -> null
    }

    private fun radio(ctx: Context, which: String, on: Boolean): JSONObject {
        if (!Root.available()) {
            // Without root only airplane mode and mobile data can be written with the secure-settings permission, and Android 10+ ignores plain writes to the others.
            if (which == "airplane" && hasSecure(ctx)) {
                writeInt(ctx, "global", "airplane_mode_on", if (on) 1 else 0)
                sleep(300)
                val got = settle(2000, on) { radioState(ctx, "airplane") == true }
                return result(got == on, "airplane", on, got, "secure-settings")
            }
            return err("Android does not let normal apps switch $which. NISU AI needs root for it, so it cannot do it silently.",
                "Run setup_self on a rooted phone, or switch $which in the control panel by hand.")
        }
        val e = if (on) "enable" else "disable"
        val cmd = when (which) {
            "wifi" -> "svc wifi $e"
            "bluetooth" -> "svc bluetooth $e || cmd bluetooth_manager $e"
            "mobile_data" -> "svc data $e"
            "airplane" -> if (Build.VERSION.SDK_INT >= 30) "cmd connectivity airplane-mode $e" else "settings put global airplane_mode_on ${if (on) 1 else 0}; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on"
            "hotspot" -> if (on) "cmd connectivity start-tethering wifi 2>/dev/null || svc wifi disable" else "cmd connectivity stop-tethering wifi"
            "nfc" -> "svc nfc $e"
            else -> return err("Unknown switch $which.")
        }
        Root.exec(cmd, 10000)
        val known = radioState(ctx, which)
        if (known == null) {
            // this switch cannot be read back on this phone: say so honestly instead of claiming it worked
            return JSONObject().put("ok", true).put("toggle", which).put("state", if (on) "on" else "off").put("method", "root").put("verified", false)
                .put("note", "The command ran as root but this switch cannot be read back, so say it was requested, not that it is confirmed.")
        }
        val got = settle(4000, on) { radioState(ctx, which) ?: !on }
        return result(got == on, which, on, got, "root")
    }

    // ---------------------------------------------------------------------------------------------- auto-rotate, battery saver, dark mode

    private fun autoRotate(ctx: Context, on: Boolean): JSONObject {
        val method = writeInt(ctx, "system", Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            ?: return err("NISU AI is not allowed to modify system settings.", "Allow 'Modify system settings' for NISU AI (Permissions page), or grant root.")
        val got = settle(1200, on) { readSystem(ctx, Settings.System.ACCELEROMETER_ROTATION) == 1 }
        return result(got == on, "auto_rotate", on, got, method)
    }

    private fun batterySaver(ctx: Context, on: Boolean): JSONObject {
        var method = writeInt(ctx, "global", "low_power", if (on) 1 else 0)
        if (method == null) return missingPower("Battery saver")
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val got = settle(2000, on) { pm.isPowerSaveMode }
        return result(got == on, "battery_saver", on, got, method)
    }

    private fun darkMode(ctx: Context, on: Boolean): JSONObject {
        if (!Root.available()) return err("Android does not let normal apps switch dark mode. NISU AI needs root for it.", "Run setup_self on a rooted phone, or switch it by hand.")
        Root.exec("cmd uimode night ${if (on) "yes" else "no"}", 6000)
        val ui = ctx.getSystemService(android.app.UiModeManager::class.java)
        val got = settle(2000, on) { ui.nightMode == android.app.UiModeManager.MODE_NIGHT_YES }
        return result(got == on, "dark_mode", on, got, "root")
    }
}
