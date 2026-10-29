package ai.nisu.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Root Gateway: the connection between the EXISTING Root system (Root.kt: `su`, blocklist, read-only check; Device.setupSelf)
 * and the new architecture (Main Brain, Automation Control Panel).
 *
 * This is NOT a second root system. It asks no permission of its own, starts no process and has no shell command of its own.
 * It only does three things:
 *
 *  1. ONE shared root status. Root.available() reports every result here ([report]), whoever asked (voice tools, phone control,
 *     the panel's "Check root" button). The panel and the Main Brain read [status]; neither probes `su` by itself, so a root
 *     manager prompt is never shown again because of them. NOT_CHECKED means "nobody has asked `su` yet".
 *  2. A closed list of Root-dependent actions the Main Brain may ask for ([RootAction]). Each one maps to code that already
 *     exists. Nothing in an action comes from a message or from AI output: there is no parameter, no command string.
 *  3. Owner approval. The Main Brain can only REQUEST; the request waits in [pending] until the owner taps "Allow" in the
 *     Automation panel. A request that carries its own confirmation is refused unless it comes from the panel.
 *
 * The WhatsApp and Messenger agents have no access: [AgentContext] carries no root handle, [RootCaller] has no agent entry, and
 * no action lists an agent. Message text and AI output are data and never reach this file. The voice assistant's own tools
 * (run_root, setup_self, root_status in Tools.kt) are unchanged; they keep their own spoken-confirmation rules.
 */

/** Who is asking. Agents are deliberately not on this list. */
enum class RootCaller { MAIN_BRAIN, CONTROL_PANEL }

/**
 * The complete list of Root-dependent actions the new architecture may use. Adding one is a code change on purpose.
 * Every action needs the owner's approval, whatever the caller.
 */
enum class RootAction(val code: String, val callers: Set<RootCaller>, val label: String) {
    /** Existing [Device.setupSelf]: grants NISU AI its own declared permissions, notification access, battery freedom and accessibility. */
    SELF_SETUP(
        "self_setup", setOf(RootCaller.MAIN_BRAIN, RootCaller.CONTROL_PANEL),
        "repair NISU AI's own permissions (notification access, background use, accessibility) using root."
    )
}

sealed class RootOutcome {
    /** The action ran. [ok] = the existing routine reported success. */
    class Ran(val ok: Boolean) : RootOutcome()
    /** Recorded in [RootAccess.pending]; nothing ran. The owner decides in the Automation panel. */
    object AwaitingOwner : RootOutcome()
    /** Nothing ran. [code] is one of the short codes in [RootAccess]. */
    class Refused(val code: String) : RootOutcome()
}

object RootAccess {
    private const val TAG = "NisuRootAccess"

    const val NOT_ALLOWED = "caller_not_allowed"
    const val CONFIRM_FROM_OWNER_ONLY = "confirmation_only_from_owner"
    const val NOT_CHECKED = "root_not_checked"
    const val UNAVAILABLE = "root_unavailable"
    const val BUSY = "root_busy"

    private val _status = MutableStateFlow(RootStatus.NOT_CHECKED)
    /** The one root status of the app. Updated by every real `su` check (see [report]); reading it never runs `su`. */
    val status: StateFlow<RootStatus> = _status

    private val _pending = MutableStateFlow<RootAction?>(null)
    /** An action the Main Brain asked for that waits for the owner. Null = nothing waiting. */
    val pending: StateFlow<RootAction?> = _pending

    private val busy = AtomicBoolean(false)

    /** Called by [Root.available] after every real check. Keeps the shared status true to what `su` actually answered. */
    internal fun report(available: Boolean) {
        _status.value = if (available) RootStatus.AVAILABLE else RootStatus.UNAVAILABLE
        if (!available) _pending.value = null   // nothing can run without root, so a waiting request is void
    }

    /**
     * Owner-driven check (the panel's "Check root" button): asks `su` for real, which may show the root manager's own prompt.
     * Blocking. Never call it from the background or from the Main Brain.
     */
    fun verify(): RootStatus {
        try { Root.available(true) } catch (_: Throwable) { report(false) }
        return _status.value
    }

    /** The owner said "Not now". */
    fun dismiss() { _pending.value = null }

    /**
     * The pipeline. Order of checks, each one a hard stop:
     *  caller allowed for this action -> confirmation only from the panel -> root already known to be available
     *  (the status is only READ; no `su`, no new prompt) -> owner approval -> run the existing routine, one at a time.
     */
    suspend fun request(ctx: Context, caller: RootCaller, action: RootAction, ownerConfirmed: Boolean = false): RootOutcome {
        if (caller !in action.callers) return RootOutcome.Refused(NOT_ALLOWED)
        if (ownerConfirmed && caller != RootCaller.CONTROL_PANEL) return RootOutcome.Refused(CONFIRM_FROM_OWNER_ONLY)
        when (_status.value) {
            RootStatus.UNAVAILABLE -> return RootOutcome.Refused(UNAVAILABLE)   // denied or no root: safe stop, no retry, no prompt
            RootStatus.NOT_CHECKED -> return RootOutcome.Refused(NOT_CHECKED)   // never probe from the background
            RootStatus.AVAILABLE -> {}
        }
        if (!ownerConfirmed) {
            _pending.value = action
            return RootOutcome.AwaitingOwner
        }
        if (!busy.compareAndSet(false, true)) return RootOutcome.Refused(BUSY)
        return try {
            val ok = withContext(Dispatchers.IO) { execute(ctx.applicationContext, action) }
            _pending.value = null
            RootOutcome.Ran(ok)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "root action failed: " + e.javaClass.simpleName)   // class name only
            RootOutcome.Ran(false)
        } finally {
            busy.set(false)
        }
    }

    /** Exhaustive on purpose: a new [RootAction] does not compile until it is wired to existing code here. */
    private fun execute(app: Context, action: RootAction): Boolean = when (action) {
        RootAction.SELF_SETUP -> {
            val ok = Device.setupSelf(app).optBoolean("ok", false)
            MessageAccess.refresh(app)   // the screen and the brain see the new notification-access state at once
            ok
        }
    }
}
