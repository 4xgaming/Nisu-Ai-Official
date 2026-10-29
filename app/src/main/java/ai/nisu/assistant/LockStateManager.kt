package ai.nisu.assistant

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The three states of the NISU AI custom lock. */
enum class LockState { UNLOCKED, LOCKED, SCREEN_OFF }

/**
 * Holds the lock state and persists the one bit that must survive a process restart ("must be locked").
 *
 * SINGLE SOURCE OF TRUTH: the persisted bit "must_lock". The in-memory [state] is only its live view, and the two are
 * ALWAYS written together in one place ([commit]), under one lock, with a synchronous disk write. Nothing else sets either.
 *
 * Rules:
 *  - custom lock off                      -> always UNLOCKED
 *  - after a reboot                       -> LOCKED, never silently unlocked (boot_count, or the uptime clock when boot_count is unreadable)
 *  - process (re)started with screen off  -> the screen went off while we could not hear it: "must be locked" (never a ghost unlock)
 *  - screen off                           -> SCREEN_OFF (and "must be locked" is stored)
 *  - screen on while it must be locked    -> LOCKED
 *  - successful authentication            -> UNLOCKED, but only while the screen is on: a late biometric / duplicate callback that
 *                                            arrives after the screen went off can never open the lock
 * Nothing here starts, stops or restarts any service.
 */
object LockStateManager {
    private const val FILE = "nishu_lock"
    private const val K_MUST_LOCK = "must_lock"
    private const val K_BOOT = "boot_count"
    private const val K_ELAPSED = "last_elapsed"

    private val _state = MutableStateFlow(LockState.UNLOCKED)
    val state: StateFlow<LockState> = _state

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun bootCount(c: Context): Int = try { Settings.Global.getInt(c.contentResolver, "boot_count", -1) } catch (_: Throwable) { -1 }

    /** true = the screen is on and interactive. Unknown (no PowerManager) counts as on, so a broken query can never lock a person out. */
    private fun interactive(c: Context): Boolean = try { c.getSystemService(PowerManager::class.java)?.isInteractive ?: true } catch (_: Throwable) { true }

    /** The ONE writer: persisted bit and live state change together, and the bit is on disk before the call returns. */
    private fun commit(c: Context, mustLock: Boolean, st: LockState) {
        sp(c).edit().putBoolean(K_MUST_LOCK, mustLock).putLong(K_ELAPSED, SystemClock.elapsedRealtime()).commit()
        _state.value = st
    }

    /** Called once per process start (NishuApp). Restores the state without ever unlocking by itself. */
    @Synchronized
    fun restore(c: Context) {
        if (!LockCredentialManager.isEnabled(c)) { _state.value = LockState.UNLOCKED; return }
        val sp = sp(c)
        val boot = bootCount(c)
        val rebooted = if (boot >= 0) boot != sp.getInt(K_BOOT, -2)
                       else !sp.contains(K_BOOT) || SystemClock.elapsedRealtime() < sp.getLong(K_ELAPSED, 0L)   // uptime went backwards = a reboot
        sp.edit().putInt(K_BOOT, boot).commit()
        var must = sp.getBoolean(K_MUST_LOCK, true)
        if (rebooted) must = true
        // The process may have been dead while the screen went off (the screen-off broadcast is only heard while we run).
        // A stored "open" would then survive a screen-off: treat a non-interactive screen at start as a screen-off.
        val off = !interactive(c)
        if (off) must = true
        commit(c, must, if (!must) LockState.UNLOCKED else if (off) LockState.SCREEN_OFF else LockState.LOCKED)
    }

    @Synchronized
    fun screenOff(c: Context) {
        if (!LockCredentialManager.isEnabled(c)) { _state.value = LockState.UNLOCKED; return }
        commit(c, true, LockState.SCREEN_OFF)
    }

    /** Screen switched on: if the lock is enabled and was not opened since the screen went off, the state is LOCKED. */
    @Synchronized
    fun screenOn(c: Context) {
        if (!LockCredentialManager.isEnabled(c)) { _state.value = LockState.UNLOCKED; return }
        if (sp(c).getBoolean(K_MUST_LOCK, true)) _state.value = LockState.LOCKED
    }

    @Synchronized
    fun lockNow(c: Context) {
        if (!LockCredentialManager.isEnabled(c)) return
        commit(c, true, LockState.LOCKED)
    }

    /**
     * Opens the lock. Returns false (and changes nothing) when the screen is off: nobody can be authenticating then, so such a
     * call is a late or duplicate callback and must not open the lock. Idempotent: a second call while open is harmless.
     */
    @Synchronized
    fun unlocked(c: Context): Boolean {
        if (_state.value == LockState.UNLOCKED && !sp(c).getBoolean(K_MUST_LOCK, true)) return true
        if (!interactive(c)) return false
        commit(c, false, LockState.UNLOCKED)
        return true
    }

    /** Called when the lock is switched off or removed. */
    @Synchronized
    fun cleared(c: Context) {
        commit(c, false, LockState.UNLOCKED)
    }

    fun isLocked(c: Context): Boolean =
        LockCredentialManager.isEnabled(c) && _state.value != LockState.UNLOCKED
}
