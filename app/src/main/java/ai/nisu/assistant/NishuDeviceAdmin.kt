package ai.nisu.assistant

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device Admin receiver used only to block uninstall. While it is active, Android's own "Uninstall" /
 * "App info -> Uninstall" path is refused by the system (standard Android behaviour for any device admin
 * app, not something this app enforces itself), so removing NISU AI first requires turning this admin off
 * in Settings -> Security -> Device admin apps, which NISU AI's own Settings page gates behind the lock
 * secret (see LockSettingsPage / AdminGuard). This is a personal self-control feature: it only ever
 * protects the single admin component below, never reads or changes anything else on the device.
 */
class NishuDeviceAdmin : DeviceAdminReceiver() {
    companion object {
        fun componentName(c: Context) = ComponentName(c, NishuDeviceAdmin::class.java)

        fun isActive(c: Context): Boolean = try {
            (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                ?.isAdminActive(componentName(c)) == true
        } catch (_: Throwable) { false }

        /** Intent that opens Android's own "Activate device admin" screen for NISU AI, with an explanation. */
        fun requestIntent(c: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(c))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Stops NISU AI from being uninstalled by anyone who doesn't know the lock. Turn this off (here, or in Settings > Security > Device admin apps) before uninstalling."
            )

        /** Turns the protection off. Call only after the lock secret has been verified. */
        fun deactivate(c: Context) {
            try { (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)?.removeActiveAdmin(componentName(c)) } catch (_: Throwable) {}
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "This turns off NISU AI's uninstall protection. If you didn't mean to, tap Cancel."
}
