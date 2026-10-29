package ai.nisu.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Restarts NISU AI after a reboot when possible. Android 14+ may block microphone services started from boot; then just open the app once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PremiumGate.isUnlocked(c)) return
        if (!Prefs.getBool(c, "autostart", true)) return
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try { c.startForegroundService(Intent(c, AssistantService::class.java).putExtra(AssistantService.EXTRA_ASLEEP, true)) } catch (_: Exception) {}
    }
}
