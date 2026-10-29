package ai.nisu.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

enum class LockType(val label: String) { SWIPE("Swipe"), PATTERN("Pattern"), PIN("PIN"), PASSWORD("Password") }

sealed class VerifyResult {
    object Ok : VerifyResult()
    class Wrong(val attemptsBeforeLockout: Int) : VerifyResult()
    class LockedOut(val remainingMs: Long) : VerifyResult()
    object NotConfigured : VerifyResult()
}

/**
 * Stores and checks the NISU AI lock credential.
 * - The raw PIN / password / pattern is never stored. Only PBKDF2-HMAC-SHA256 (random salt, 120k rounds) of "<type>:<secret>".
 * - That hash is encrypted with a non-exportable AES-256-GCM key in the Android Keystore (hardware-backed where the device has it).
 *   If the Keystore is unusable at setup time, the salted hash is stored without that extra layer (mode "plain").
 * - Failed attempts are counted and persisted; every 5 wrong tries start a temporary lockout (30 s, doubling, max 30 min).
 * A pattern is the string of its dot numbers 0..8 (e.g. "0124"); a PIN is its digits.
 */
object LockCredentialManager {
    private const val FILE = "nishu_lock"
    private const val K_ENABLED = "enabled"
    private const val K_TYPE = "type"
    private const val K_CRED = "cred"
    private const val K_FP = "bio_fingerprint"
    private const val K_FACE = "bio_face"
    private const val K_FAILS = "fails"
    private const val K_LEVEL = "lockout_level"
    private const val K_UNTIL = "lockout_until"
    private const val ALIAS = "nishu_lock_wrap_v1"
    private const val ITER = 12_000          // fast unlock; brute force is stopped by the 5-try lockout, not by hash cost
    private const val SLOW_ITER = 40_000     // anything stored with more rounds is silently re-hashed faster after the next correct unlock
    const val MIN_PATTERN = 4
    const val MIN_PIN = 4
    const val MAX_PIN = 16
    const val MIN_PASSWORD = 6
    private const val ATTEMPTS = 5
    private const val BASE_LOCK_MS = 30_000L
    private const val MAX_LOCK_MS = 30 * 60_000L

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ configuration
    fun isEnabled(c: Context): Boolean = sp(c).getBoolean(K_ENABLED, false)
    fun type(c: Context): LockType = try { LockType.valueOf(sp(c).getString(K_TYPE, LockType.SWIPE.name) ?: LockType.SWIPE.name) } catch (_: Throwable) { LockType.SWIPE }
    fun hasCredential(c: Context): Boolean = !sp(c).getString(K_CRED, null).isNullOrEmpty()
    fun fingerprintOn(c: Context): Boolean = sp(c).getBoolean(K_FP, false)
    fun faceOn(c: Context): Boolean = sp(c).getBoolean(K_FACE, false)
    fun setFingerprint(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_FP, on).apply()
    fun setFace(c: Context, on: Boolean) = sp(c).edit().putBoolean(K_FACE, on).apply()

    /** Validates a new secret for the type; returns an error text or null. */
    fun validate(type: LockType, secret: String): String? = when (type) {
        LockType.SWIPE -> null
        LockType.PATTERN -> if (secret.length < MIN_PATTERN) "Connect at least $MIN_PATTERN dots." else null
        LockType.PIN -> when {
            secret.length < MIN_PIN -> "A PIN needs at least $MIN_PIN digits."
            secret.length > MAX_PIN -> "A PIN can have at most $MAX_PIN digits."
            !secret.all { it in '0'..'9' } -> "A PIN has digits only."
            else -> null
        }
        LockType.PASSWORD -> if (secret.length < MIN_PASSWORD) "A password needs at least $MIN_PASSWORD characters." else null
    }

    /** Saves (or replaces) the lock. SWIPE needs no secret. The caller has already authenticated the user when a lock existed. */
    @Synchronized
    fun save(c: Context, type: LockType, secret: String?): Boolean {
        val e = sp(c).edit()
        if (type == LockType.SWIPE) {
            e.putString(K_TYPE, type.name).remove(K_CRED).putBoolean(K_FP, false).putBoolean(K_FACE, false)
        } else {
            val s = secret ?: return false
            if (validate(type, s) != null) return false
            val stored = encode(type, s) ?: return false
            e.putString(K_TYPE, type.name).putString(K_CRED, stored)
        }
        e.putBoolean(K_ENABLED, true).putInt(K_FAILS, 0).putInt(K_LEVEL, 0).putLong(K_UNTIL, 0L).apply()
        return true
    }

    /** Switches the custom lock off and wipes the stored credential. */
    @Synchronized
    fun remove(c: Context) {
        sp(c).edit().putBoolean(K_ENABLED, false).remove(K_CRED).putString(K_TYPE, LockType.SWIPE.name)
            .putBoolean(K_FP, false).putBoolean(K_FACE, false).putInt(K_FAILS, 0).putInt(K_LEVEL, 0).putLong(K_UNTIL, 0L).apply()
        cachedKey = null
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Throwable) {}
        LockStateManager.cleared(c)
    }

    // ------------------------------------------------------------------ attempts and lockout
    fun failedAttempts(c: Context): Int = sp(c).getInt(K_FAILS, 0)

    fun lockoutRemainingMs(c: Context): Long {
        val until = sp(c).getLong(K_UNTIL, 0L)
        if (until <= 0L) return 0L
        val left = until - System.currentTimeMillis()
        // a clock moved far backwards must not create an endless lockout
        return if (left <= 0L) 0L else minOf(left, MAX_LOCK_MS)
    }

    fun resetFailures(c: Context) {
        sp(c).edit().putInt(K_FAILS, 0).putInt(K_LEVEL, 0).putLong(K_UNTIL, 0L).apply()
    }

    private fun registerFailure(c: Context): VerifyResult {
        val sp = sp(c)
        val fails = sp.getInt(K_FAILS, 0) + 1
        if (fails >= ATTEMPTS) {
            val level = sp.getInt(K_LEVEL, 0)
            val ms = minOf(BASE_LOCK_MS shl minOf(level, 6), MAX_LOCK_MS)
            sp.edit().putInt(K_FAILS, 0).putInt(K_LEVEL, level + 1).putLong(K_UNTIL, System.currentTimeMillis() + ms).apply()
            return VerifyResult.LockedOut(ms)
        }
        sp.edit().putInt(K_FAILS, fails).apply()
        return VerifyResult.Wrong(ATTEMPTS - fails)
    }

    // ------------------------------------------------------------------ verification
    /** Checks a secret. Swipe has nothing to check and is always Ok. */
    @Synchronized
    fun verify(c: Context, secret: String): VerifyResult {
        if (!isEnabled(c)) return VerifyResult.Ok
        val type = type(c)
        if (type == LockType.SWIPE) return VerifyResult.Ok
        val left = lockoutRemainingMs(c)
        if (left > 0L) return VerifyResult.LockedOut(left)
        val stored = sp(c).getString(K_CRED, null) ?: return VerifyResult.NotConfigured
        val ok = matches(type, secret, stored) ?: return VerifyResult.NotConfigured
        if (!ok) return registerFailure(c)
        resetFailures(c)
        // old credentials were stored with 120k rounds (slow on weak phones): re-hash once, now that we know the secret
        try {
            val rounds = stored.split(":").getOrNull(2)?.toIntOrNull() ?: 0
            if (rounds > SLOW_ITER) encode(type, secret)?.let { sp(c).edit().putString(K_CRED, it).apply() }
        } catch (_: Throwable) {}
        return VerifyResult.Ok
    }

    // ------------------------------------------------------------------ crypto
    private fun derive(type: LockType, secret: String, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec("${type.name}:$secret".toCharArray(), salt, iter, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    @Volatile private var cachedKey: SecretKey? = null

    /** Loads the Keystore key and runs one tiny PBKDF2 so the first real unlock is not slow. Call from a background thread. */
    fun warmUp() { try { keystoreKey(); derive(LockType.PIN, "0", ByteArray(16), 1_000) } catch (_: Throwable) {} }

    private fun keystoreKey(): SecretKey? = cachedKey ?: keystoreKeyUncached()?.also { cachedKey = it }

    private fun keystoreKeyUncached(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build()
            )
            gen.generateKey()
        }
    } catch (_: Throwable) { null }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** "v1:<ks|plain>:<rounds>:<salt>:<hash or iv+encrypted hash>" */
    private fun encode(type: LockType, secret: String): String? = try {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(type, secret, salt, ITER)
        var mode = "plain"
        var blob = hash
        val key = keystoreKey()
        if (key != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                blob = cipher.iv + cipher.doFinal(hash)
                mode = "ks"
            } catch (_: Throwable) { mode = "plain"; blob = hash }
        }
        "v1:$mode:$ITER:${b64(salt)}:${b64(blob)}"
    } catch (_: Throwable) { null }

    /** true / false = match / no match; null = the stored credential cannot be read (for example its Keystore key is gone). */
    private fun matches(type: LockType, secret: String, stored: String): Boolean? = try {
        val p = stored.split(":")
        if (p.size != 5 || p[0] != "v1") null else {
            val salt = unb64(p[3])
            val blob = unb64(p[4])
            val expected: ByteArray? = if (p[1] == "ks") {
                val key = keystoreKey()
                if (key == null || blob.size <= 12) null else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                    cipher.doFinal(blob.copyOfRange(12, blob.size))
                }
            } else blob
            if (expected == null) null else MessageDigest.isEqual(expected, derive(type, secret, salt, p[2].toInt()))
        }
    } catch (_: Throwable) { null }
}
