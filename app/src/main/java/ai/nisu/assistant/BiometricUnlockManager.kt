package ai.nisu.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal

/**
 * Fingerprint / face unlock for the NISU AI lock, only through Android's own biometric API
 * (android.hardware.biometrics.BiometricPrompt, API 28+; no extra library).
 * Nothing is recognised by this app: the system shows its prompt and only reports success or failure.
 * An option is offered only when the hardware feature exists AND the system says a biometric is usable now.
 *
 * Limits (Android): the public API does not tell which kind (finger / face) is enrolled, and the system prompt
 * picks the kind itself. So "Face" is offered when the device declares face hardware and a biometric is enrolled;
 * the prompt may then use whichever enrolled biometric the system prefers.
 */
object BiometricUnlockManager {
    private const val FEATURE_FACE = "android.hardware.biometrics.face"

    fun fingerprintSupported(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        if (!c.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        return usable(c, strong = true)
    }

    fun faceSupported(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (!c.packageManager.hasSystemFeature(FEATURE_FACE)) return false
        return usable(c, strong = false)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun usable(c: Context, strong: Boolean): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                val bm = c.getSystemService(BiometricManager::class.java)
                val flag = if (strong) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK
                bm != null && bm.canAuthenticate(flag) == BiometricManager.BIOMETRIC_SUCCESS
            }
            Build.VERSION.SDK_INT == 29 -> {
                val bm = c.getSystemService(BiometricManager::class.java)
                bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
            else -> {   // API 28: only the fingerprint check exists
                val fm = c.getSystemService(FingerprintManager::class.java)
                fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
            }
        }
    } catch (_: Throwable) { false }

    /** True when the stored settings ask for a biometric AND the device can still do it now (it may have been un-enrolled). */
    fun wanted(c: Context): Boolean {
        if (!LockCredentialManager.isEnabled(c) || LockCredentialManager.type(c) == LockType.SWIPE) return false
        return (LockCredentialManager.fingerprintOn(c) && fingerprintSupported(c)) ||
            (LockCredentialManager.faceOn(c) && faceSupported(c))
    }

    /**
     * Shows the system biometric prompt. [onSuccess] runs only after the system reports a successful authentication.
     * [onDone] runs for cancel / error / too many tries with a short text (null when the user just chose the credential).
     * Returns the signal to cancel the prompt when the screen goes away.
     */
    fun authenticate(activity: Activity, onSuccess: () -> Unit, onDone: (String?) -> Unit): CancellationSignal? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val face = LockCredentialManager.faceOn(activity) && faceSupported(activity)
            val exec = activity.mainExecutor
            val b = BiometricPrompt.Builder(activity)
                .setTitle("Unlock NISU AI")
                .setSubtitle("Use your fingerprint or face")
                .setNegativeButton("Use PIN / pattern / password", exec) { _, _ -> onDone(null) }
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    if (face) BiometricManager.Authenticators.BIOMETRIC_WEAK else BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
            }
            val signal = CancellationSignal()
            b.build().authenticate(signal, exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onSuccess() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    // 5 = cancelled by the system / by us, 10 = cancelled by the user: nothing to tell
                    onDone(if (errorCode == 5 || errorCode == 10) null else errString?.toString())
                }
            })
            signal
        } catch (_: Throwable) { onDone("Biometric unlock is not available right now."); null }
    }
}
