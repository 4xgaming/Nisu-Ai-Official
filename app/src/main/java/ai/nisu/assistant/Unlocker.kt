package ai.nisu.assistant

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phone Unlock: NISU AI opens the phone's own lock screen with the PIN, password or pattern the owner saved.
 * Face and fingerprint are NOT handled (Android keeps those for the person).
 *
 * Safety rules:
 *  - the secret is encrypted with a key kept in the Android Keystore; it is never logged, never sent to the AI model and never in a tool result;
 *  - the unlock tool takes no arguments and is blocked for a Guest / unverified voice (see AssistantService and VoiceId);
 *  - at most ONE attempt per command, and after two failed attempts in 10 minutes it stops for 5 minutes, so Android's own
 *    "too many wrong attempts" lock-out never hits the owner.
 * Call [unlock] from a background thread.
 */
object Unlocker {
    const val K_ON = "unlock_on"
    const val K_TYPE = "unlock_type"          // pin | password | pattern
    private const val K_SECRET = "unlock_secret"
    private const val ALIAS = "nisu_unlock_key"

    // ------------------------------------------------------------------------------------------ secret storage

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    } catch (_: Exception) { null }

    /** Pure: the checked secret for [type], or null when it is not valid. Pattern = digits 1..9 (row by row), no repeats, at least 4 dots. */
    internal fun validSecret(type: String, raw: String): String? = when (type) {
        "pin" -> raw.trim().takeIf { it.length in 4..16 && it.all { c -> c in '0'..'9' } }
        "password" -> raw.takeIf { it.length in 4..64 && it.none { c -> c == '\n' || c == '\r' } }
        "pattern" -> raw.trim().takeIf { it.length in 4..9 && it.all { c -> c in '1'..'9' } && it.toSet().size == it.length }
        else -> null
    }

    fun save(ctx: Context, type: String, raw: String): Boolean {
        val v = validSecret(type, raw) ?: return false
        return try {
            Prefs.put(ctx, K_SECRET, encrypt(v)); Prefs.put(ctx, K_TYPE, type); Prefs.putBool(ctx, K_ON, true); resetAttempts(); true
        } catch (_: Exception) { false }
    }

    fun clear(ctx: Context) { Prefs.put(ctx, K_SECRET, ""); Prefs.putBool(ctx, K_ON, false); resetAttempts() }
    /**
     * v3.78: the voice switch "Phone unlocking system off / on". Off = [unlock] refuses at once (the saved lock stays saved). Only the verified Owner voice may use it
     * (VoiceId); switching it ON needs a saved lock. Same switch as in NISU AI > Phone Unlock.
     */
    fun setSystem(ctx: Context, on: Boolean): JSONObject {
        if (VoiceId.blocksTools(ctx)) return err("Not allowed: the speaker is not the verified Owner. Do not retry; politely decline.")
        if (on && !isSet(ctx)) return err("No lock is saved in NISU AI yet, so the unlocking system cannot be switched on.", "Open NISU AI > Phone Unlock, choose PIN, password or pattern and save it.")
        Prefs.putBool(ctx, K_ON, on)
        return JSONObject().put("ok", true).put("unlock_system_on", on).put("note",
            if (on) "Phone unlocking system is ON: you can unlock the phone when he clearly asks. Confirm in one short sentence."
            else "Phone unlocking system is OFF: from now on, whoever asks you to unlock the phone, say you cannot. Confirm in one short sentence. Only he can switch it on again.")
    }
    fun isSet(ctx: Context): Boolean = Prefs.get(ctx, K_SECRET, "").isNotBlank()
    fun isOn(ctx: Context): Boolean = isSet(ctx) && Prefs.getBool(ctx, K_ON, false)
    fun type(ctx: Context): String = Prefs.get(ctx, K_TYPE, "pin")

    // ------------------------------------------------------------------------------------------ attempt guard

    @Volatile private var fails = 0
    @Volatile private var firstFailAt = 0L
    @Volatile private var blockedUntil = 0L
    private fun resetAttempts() { fails = 0; firstFailAt = 0L; blockedUntil = 0L }

    /** Pure rule: after [fails] failures starting at [firstAt], is a new attempt allowed at [now]? Returns 0 when allowed, else the ms still to wait. */
    internal fun waitMs(fails: Int, firstAt: Long, blockedUntil: Long, now: Long): Long = when {
        now < blockedUntil -> blockedUntil - now
        else -> 0L
    }

    private fun noteFail() {
        val now = SystemClock.elapsedRealtime()
        if (fails == 0 || now - firstFailAt > 10 * 60_000L) { fails = 1; firstFailAt = now } else fails++
        if (fails >= 2) blockedUntil = now + 5 * 60_000L
    }

    // ------------------------------------------------------------------------------------------ unlocking

    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("unlocked", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun locked(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun waitUnlocked(ctx: Context, ms: Long): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (!locked(ctx)) return true; sleep(120) }
        return !locked(ctx)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        try {
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "nisu:unlock")
            wl.acquire(4000L)
        } catch (_: Exception) {}
        val end = SystemClock.uptimeMillis() + 1200
        while (!pm.isInteractive && SystemClock.uptimeMillis() < end) sleep(60)
        if (!pm.isInteractive && Root.available()) { Root.exec("input keyevent 224", 4000); sleep(300) }
    }

    /** True when the lock screen already shows the place where the secret goes. */
    private fun credentialVisible(acc: NisuAccessibilityService?, type: String): Boolean {
        acc ?: return false
        return when (type) {
            "pattern" -> acc.lockHas("lockpattern")
            "pin" -> acc.lockHas("pinentry") || acc.lockHas("key_enter") || acc.lockHas("keyguard_pin") || acc.lockHas("key1")
            else -> acc.lockHas("passwordentry") || acc.lockHas("password_entry") || acc.lockHas("keyguard_password")
        }
    }

    private fun swipeUp(ctx: Context) {
        val acc = NisuAccessibilityService.inst
        val (w, h) = acc?.screenPx() ?: (ctx.resources.displayMetrics.widthPixels to ctx.resources.displayMetrics.heightPixels)
        val x = w / 2f; val y1 = h * 0.88f; val y2 = h * 0.22f
        val ok = acc?.swipe(x, y1, x, y2, 260) ?: false
        if (!ok && Root.available()) Root.exec("input swipe ${x.toInt()} ${y1.toInt()} ${x.toInt()} ${y2.toInt()} 260", 4000)
    }

    /** The 9 dot centres (row by row, numbered 1..9) of the pattern grid. Uses the real view when the screen is readable; else an estimate. */
    private fun patternDots(ctx: Context, acc: NisuAccessibilityService?): Pair<List<Pair<Float, Float>>, Boolean> {
        val b = acc?.lockNodeBounds("lockpattern")
        val (w, h) = acc?.screenPx() ?: (ctx.resources.displayMetrics.widthPixels to ctx.resources.displayMetrics.heightPixels)
        val left: Float; val top: Float; val side: Float; val real: Boolean
        if (b != null) {
            side = minOf(b.width(), b.height()).toFloat()
            left = b.left + (b.width() - side) / 2f; top = b.top + (b.height() - side) / 2f; real = true
        } else {
            side = w * 0.82f; left = (w - side) / 2f; top = h * 0.40f; real = false
        }
        val dots = ArrayList<Pair<Float, Float>>(9)
        for (r in 0 until 3) for (c in 0 until 3) dots.add((left + side * (c + 0.5f) / 3f) to (top + side * (r + 0.5f) / 3f))
        return dots to real
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun enterPin(ctx: Context, acc: NisuAccessibilityService?, pin: String): String? {
        if (acc != null) {
            var all = true
            for (ch in pin) {
                if (!acc.clickLockKey(ch.toString(), "key$ch")) { all = false; break }
                sleep(70)
            }
            if (all) { sleep(150); submit(ctx, acc); return "accessibility" }
        }
        if (Root.available()) {
            Root.exec("input text " + shQuote(pin), 6000); sleep(150)
            submit(ctx, acc); return "root"
        }
        return null
    }

    /** PINs often need Enter; ignored when the phone already unlocked by itself (auto-confirm). */
    private fun submit(ctx: Context, acc: NisuAccessibilityService?) {
        if (waitUnlocked(ctx, 450)) return
        var done = acc?.clickLockKey("Enter", "key_enter") ?: false
        if (!done) done = acc?.pressEnter() ?: false
        if (!done && Root.available()) Root.exec("input keyevent 66", 3000)
    }

    private fun enterPassword(ctx: Context, acc: NisuAccessibilityService?, pw: String): String? {
        if (acc != null && acc.typeText(pw, replace = true, submit = true)) return "accessibility"
        if (Root.available()) {
            Root.exec("input text " + shQuote(pw.replace(" ", "%s")), 8000); sleep(150)
            Root.exec("input keyevent 66", 3000); return "root"
        }
        return null
    }

    private fun enterPattern(ctx: Context, acc: NisuAccessibilityService?, cells: String): Triple<String?, Boolean, String?> {
        val (dots, real) = patternDots(ctx, acc)
        val pts = cells.map { dots[it - '1'] }
        if (acc != null && acc.drawPath(pts, 140L * pts.size)) return Triple("accessibility", real, null)
        if (Root.available() && Build.VERSION.SDK_INT >= 29) {
            val sb = StringBuilder("input motionevent DOWN ${pts[0].first.toInt()} ${pts[0].second.toInt()}; ")
            for (i in 1 until pts.size) sb.append("sleep 0.08; input motionevent MOVE ${pts[i].first.toInt()} ${pts[i].second.toInt()}; ")
            sb.append("sleep 0.08; input motionevent UP ${pts.last().first.toInt()} ${pts.last().second.toInt()}")
            if (Root.exec(sb.toString(), 8000).first == 0) return Triple("root", real, null)
        }
        return Triple(null, real, NisuAccessibilityService.lastGestureError.ifBlank { null })
    }

    fun unlock(ctx: Context): JSONObject {
        if (!isSet(ctx)) return err("No lock is saved in NISU AI yet.", "Open NISU AI > Phone Unlock, choose PIN, password or pattern and save it.")
        if (!Prefs.getBool(ctx, K_ON, false)) return err("The PHONE UNLOCKING SYSTEM is OFF (the owner switched it off). You cannot unlock the phone: tell him plainly in one short sentence that you cannot, and do not look for another way.", "Only the owner can switch it on again: by saying Phone unlocking system on, or in NISU AI > Phone Unlock.")
        if (VoiceId.blocksTools(ctx)) return err("Not allowed: the speaker is not the verified Owner. Do not retry; politely decline.")
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!locked(ctx) && pm.isInteractive) return JSONObject().put("ok", true).put("unlocked", true).put("note", "The phone was already unlocked.")
        val wait = waitMs(fails, firstFailAt, blockedUntil, SystemClock.elapsedRealtime())
        if (wait > 0) return err("Stopped on purpose: two unlock attempts failed, and more wrong tries could lock the phone for a long time. Wait about ${(wait + 59_999) / 60_000} minutes or unlock by hand.",
            "Check the saved lock in NISU AI > Phone Unlock.")
        val type = type(ctx)
        val secret = decrypt(Prefs.get(ctx, K_SECRET, "")) ?: return err("The saved lock could not be read (the phone's key store changed).", "Save the lock again in NISU AI > Phone Unlock.")
        val acc = NisuAccessibilityService.inst ?: (if (PhoneControl.reviveAccessibility(ctx)) NisuAccessibilityService.inst else null)
        if (acc == null && !Root.available())
            return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.", "Enable the NISU AI accessibility service, or grant root.")

        wakeScreen(ctx)
        sleep(350)
        if (!locked(ctx)) return JSONObject().put("ok", true).put("unlocked", true).put("note", "The screen came on and the phone had no lock in the way.")
        // a swipe up shows the PIN / pattern / password screen on most phones (some show it at once)
        if (!credentialVisible(acc, type)) { swipeUp(ctx); sleep(550) }
        if (acc != null && !credentialVisible(NisuAccessibilityService.inst, type)) {
            val end = SystemClock.uptimeMillis() + 1200
            while (SystemClock.uptimeMillis() < end && !credentialVisible(NisuAccessibilityService.inst, type)) sleep(100)
        }

        var method: String? = null
        var estimated = false
        var gestureProblem: String? = null
        when (type) {
            "pin" -> method = enterPin(ctx, NisuAccessibilityService.inst, secret)
            "password" -> method = enterPassword(ctx, NisuAccessibilityService.inst, secret)
            "pattern" -> { val r = enterPattern(ctx, NisuAccessibilityService.inst, secret); method = r.first; estimated = !r.second; gestureProblem = r.third }
        }
        if (method == null)
            return err("NISU AI could not enter the ${if (type == "pattern") "pattern" else type}: ${gestureProblem ?: "the lock screen keys were not readable and there is no root"}.",
                "Keep the lock screen on, make sure the accessibility service is connected, or grant root. Nothing was entered, so no wrong attempt was counted.")

        val ok = waitUnlocked(ctx, 3000)
        return if (ok) { resetAttempts(); JSONObject().put("ok", true).put("unlocked", true).put("method", method).put("verified", true) }
        else {
            noteFail()
            err("NISU AI entered the $type but the phone is still locked." + (if (estimated) " The pattern grid could not be located exactly, so the dots were estimated." else ""),
                "Check that the saved $type is the one the phone uses, and that it is not a face / fingerprint-only lock. Do not retry at once.")
        }
    }
}
