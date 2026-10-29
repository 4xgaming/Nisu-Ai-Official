package ai.nisu.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import org.json.JSONObject

/**
 * v3.121: restart_phone. Restarts the WHOLE phone, only after his own spoken yes. Two steps, enforced HERE (not only in the prompt):
 *   1. restart_phone()                -> nothing happens, answers needs_confirmation (he must be asked in plain words).
 *   2. restart_phone(confirmed=true)  -> only works if step 1 happened in the last 60 seconds.
 * How: root `reboot` when root is granted; otherwise the system power menu is opened through the accessibility service and its
 * "Restart" button is pressed (only while System UI is really the window on screen). The reboot starts a few seconds AFTER the
 * answer is returned, so NISU AI can say one short sentence first. After a restart the phone is locked and NISU AI starts again
 * only as far as Android allows (see BootReceiver).
 */
object PhoneRestart {
    @Volatile private var askedAt = 0L
    @Volatile private var busy = false
    private const val CONFIRM_WINDOW_MS = 60_000L
    private const val DELAY_MS = 4_000L

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    fun run(ctx: Context, confirmed: Boolean): JSONObject {
        if (!confirmed) {
            askedAt = System.currentTimeMillis()
            return JSONObject().put("ok", true).put("needs_confirmation", true)
                .put("message", "The phone was NOT restarted. Ask him ONE short question in his language: should I restart the phone now? Wait for his clear yes. " +
                    "Only then call restart_phone again with confirmed true. If he says no or is unsure, do nothing.")
        }
        if (askedAt == 0L || System.currentTimeMillis() - askedAt > CONFIRM_WINDOW_MS)
            return err("Ask first: call restart_phone with no confirmed, ask him, and call again with confirmed true only after his yes.")
        if (busy) return err("A restart is already starting.")
        askedAt = 0L

        val root = Root.available()
        val acc = NisuAccessibilityService.inst
        if (!root && acc == null)
            return err("NISU AI cannot restart the phone: there is no root and Phone control (accessibility) is not connected. Tell him plainly, and that he can hold the power button and tap Restart.")

        busy = true
        Thread {
            try {
                Thread.sleep(DELAY_MS)          // time for the one short spoken sentence
                AppState.add("Restarting the phone (he said yes)")
                if (root && rootReboot()) return@Thread
                if (acc != null) powerMenuRestart(acc)
            } catch (_: InterruptedException) {
            } finally { busy = false }
        }.apply { name = "PhoneRestart"; isDaemon = true }.start()

        return JSONObject().put("ok", true).put("restarting", true).put("method", if (root) "root" else "power menu")
            .put("note", "The phone restarts in about 4 seconds. Say ONE very short sentence in his language (for example Okay, restarting the phone) and nothing more. " +
                "You will be off for a minute; after the restart he must unlock the phone himself.")
    }

    private fun rootReboot(): Boolean {
        for (cmd in listOf("svc power reboot", "reboot")) {
            val (code, _) = Root.exec(cmd, 8000)
            if (code == 0) return true          // normally the phone is already going down here
        }
        return false
    }

    /** No root: open the power menu and press Restart (twice at most, some phones ask again). */
    private fun powerMenuRestart(acc: NisuAccessibilityService) {
        val labels = listOf("Restart", "Reboot", "Restart now", "রিস্টার্ট", "রিবুট", "रीस्टार्ट", "रिबूट")
        try { acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) } catch (_: Throwable) { return }
        val end = System.currentTimeMillis() + 10_000L
        var pressed = 0
        var lastPress = 0L
        while (System.currentTimeMillis() < end && pressed < 2) {
            val top = try { acc.rootInActiveWindow?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
            if (top.contains("systemui", true) && System.currentTimeMillis() - lastPress > 1200L) {
                for (l in labels) { if (acc.clickText(l, 0L)) { pressed++; lastPress = System.currentTimeMillis(); break } }
            }
            try { Thread.sleep(350) } catch (_: InterruptedException) { return }
        }
        if (pressed == 0) AppState.add("Phone restart: no Restart button found in the power menu")
    }
}
