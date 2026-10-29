package ai.nisu.assistant

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point of the NISU AI custom lock. It only listens to screen events and shows [LockScreenActivity];
 * it never starts, stops or restarts a service, so the background engine, automation, notification processing,
 * root features and the AI session keep running while the screen is off or the lock is up.
 *
 * Honest limit: this is a software lock inside the app's own process. It is not the Android Keyguard and cannot
 * replace it. It is drawn above the Keyguard (showWhenLocked) when the screen turns on and it guards NISU AI's own screens.
 */
object NishuLockManager {
    private val registered = AtomicBoolean(false)
    @Volatile private var lastLaunch = 0L

    /** Called from [NishuApp.onCreate] in every process start. Safe to call again. */
    fun init(app: Application) {
        try {
            LockStateManager.restore(app)
            if (registered.compareAndSet(false, true)) {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            // process started while the screen is on and the lock is still closed (for example after a reboot)
            val pm = app.getSystemService(PowerManager::class.java)
            if (LockStateManager.isLocked(app) && pm != null && pm.isInteractive) show(app)
        } catch (_: Throwable) { /* the lock must never be able to crash the app */ }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> LockStateManager.screenOff(c)
                    Intent.ACTION_SCREEN_ON -> { LockStateManager.screenOn(c); if (LockStateManager.isLocked(c) && !automationRunning()) show(c) }
                    Intent.ACTION_USER_PRESENT -> if (LockStateManager.isLocked(c) && !automationRunning()) show(c)
                }
            } catch (_: Throwable) {}
        }
    }

    /** NISU is working on the phone right now (the lock is for people, never for NISU). */
    private fun automationRunning(): Boolean =
        android.os.SystemClock.uptimeMillis() < (NisuAccessibilityService.inst?.automationUntil ?: 0L)

    /** Opens the lock screen (no-op when the lock is off or already open). Needs "Display over other apps" on Android 10+ to open from the background. */
    @Synchronized
    fun show(c: Context) {
        // Re-check at launch time: a launch requested a moment ago (screen on, user present, lock gate, onResume) must never open
        // the lock screen again after a valid unlock, and must not stack a duplicate on one that is already coming up.
        if (!LockStateManager.isLocked(c)) return
        if (LockScreenActivity.visible) return   // already in front
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 800L) return
        lastLaunch = now
        try {
            c.startActivity(
                Intent(c, LockScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        } catch (_: Throwable) {}
    }

    /** The "Lock now" button. */
    fun lockNow(c: Context) {
        LockStateManager.lockNow(c)
        if (LockStateManager.isLocked(c)) show(c)
    }

    /** Called from onResume of NISU AI's own screens: returns true when the lock is closed and the lock screen was opened. */
    fun guard(a: Activity): Boolean {
        if (a is LockScreenActivity) return false
        if (!LockStateManager.isLocked(a)) return false
        resetDebounce()
        show(a)
        return true
    }

    /** Called by the accessibility lock gate: re-opens the lock screen immediately (no 800 ms debounce). */
    fun guardFromService(c: Context) {
        resetDebounce()
        show(c)
    }

    @Synchronized private fun resetDebounce() { lastLaunch = 0L }

    fun canShowFromBackground(c: Context): Boolean = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(c)
}
