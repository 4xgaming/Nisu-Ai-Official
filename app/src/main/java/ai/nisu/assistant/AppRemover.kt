package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.61: uninstall_app. Two steps, enforced HERE (not only in the prompt):
 *   1. uninstall_app(name)                  -> finds the app, answers needs_confirmation, nothing is removed.
 *   2. uninstall_app(name, confirmed=true)  -> only works if step 1 found exactly this app in the last 2 minutes.
 * Removing: root `pm uninstall` when root is granted (silent), otherwise the system's own uninstall dialog, which NISU AI then
 * confirms through the accessibility service (only while that dialog is really the window on screen). The result is checked
 * against PackageManager, so "uninstalled" is only reported when the app is really gone.
 * Never touched: system apps, NISU AI itself.
 */
object AppRemover {
    private class Pending(val pkg: String, val at: Long)
    @Volatile private var pending: Pending? = null
    private const val CONFIRM_WINDOW_MS = 120_000L

    private class Hit(val pkg: String, val label: String, val system: Boolean)

    private fun installed(ctx: Context): List<Hit> {
        val pm = ctx.packageManager
        val list = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
        return list.map {
            val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            Hit(it.packageName, try { pm.getApplicationLabel(it).toString() } catch (_: Exception) { it.packageName }, sys)
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Best matching tier of apps (exact name, starts with, contains, package contains). */
    private fun match(all: List<Hit>, name: String): List<Hit> {
        val q = norm(name)
        if (q.isEmpty()) return emptyList()
        val tiers = listOf<(Hit) -> Boolean>(
            { norm(it.label) == q },
            { it.pkg.lowercase() == name.trim().lowercase() },
            { norm(it.label).startsWith(q) },
            { norm(it.label).contains(q) },
            { it.pkg.lowercase().contains(q) }
        )
        for (t in tiers) { val h = all.filter(t); if (h.isNotEmpty()) return h }
        return emptyList()
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { true }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    fun run(ctx: Context, name: String, confirmed: Boolean): JSONObject {
        val all = installed(ctx)
        // user-installed apps first, so "chrome" never hides behind a system app of a similar name
        val user = all.filter { !it.system && it.pkg != ctx.packageName }
        var hits = match(user, name)
        if (hits.isEmpty()) {
            val any = match(all, name)
            if (any.isNotEmpty()) {
                val h = any.first()
                return if (h.pkg == ctx.packageName) err("That is NISU AI itself. NISU AI never uninstalls itself: he can do that from Android settings.")
                else err("\"${h.label}\" is a system app that came with the phone, so it cannot be uninstalled. Tell him that plainly. (It can only be switched off in Settings > Apps.)")
            }
            val names = JSONArray(); user.map { it.label }.distinct().sorted().take(80).forEach { names.put(it) }
            return err("No installed app matches \"$name\". Ask him for the exact name, or pick from installed_apps (the name he says may be in another language).").put("installed_apps", names)
        }
        // an exact name is enough even if other apps contain the same words
        if (hits.size > 1) {
            val q = norm(name)
            val exact = hits.filter { norm(it.label) == q }
            if (exact.size == 1) hits = exact
        }
        if (hits.size > 1) {
            val c = JSONArray(); hits.take(8).forEach { c.put(JSONObject().put("name", it.label).put("package", it.pkg)) }
            return err("Several apps match. Ask him which one he means, then call again with that exact name.").put("candidates", c)
        }
        val app = hits.first()

        if (!confirmed) {
            pending = Pending(app.pkg, System.currentTimeMillis())
            return JSONObject().put("ok", true).put("needs_confirmation", true).put("app_name", app.label).put("package", app.pkg)
                .put("message", "Nothing was removed yet. Ask him in plain words, in his language: should I really uninstall \"${app.label}\"? Wait for his clear yes. Only then call uninstall_app again with confirmed true and the same name. If he says no, do nothing.")
        }
        val p = pending
        if (p == null || p.pkg != app.pkg || System.currentTimeMillis() - p.at > CONFIRM_WINDOW_MS)
            return err("Ask first: call uninstall_app with just the name (no confirmed), ask him, and call again with confirmed true only after his yes.")
        pending = null
        return doRemove(ctx, app)
    }

    private fun doRemove(ctx: Context, app: Hit): JSONObject {
        val r = JSONObject().put("app_name", app.label).put("package", app.pkg)
        if (!isInstalled(ctx, app.pkg)) return r.put("ok", true).put("uninstalled", true).put("note", "It was already gone.")

        // 1. root: silent
        if (Root.available()) {
            val (code, out) = Root.exec("pm uninstall " + app.pkg, 30000)
            if (code == 0 && out.contains("Success", true)) {
                if (waitGone(ctx, app.pkg, 6000)) return r.put("ok", true).put("uninstalled", true).put("method", "root")
            }
            r.put("root_output", out.take(200))
        }

        // 2. the system's own uninstall dialog, confirmed through accessibility
        val acc = NisuAccessibilityService.inst
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            return r.put("ok", false).put("uninstalled", false).put("error", "Android did not open the uninstall screen: " + (e.message ?: e.javaClass.simpleName).take(100))
        }
        if (acc == null)
            return r.put("ok", false).put("uninstalled", false).put("error", "The uninstall screen is open on the phone but Phone control (accessibility) is not connected, so NISU AI cannot press OK. Ask him to tap OK himself.")
        val labels = listOf("Uninstall", "OK", "Delete", "UNINSTALL", "আনইনস্টল", "ঠিক আছে", "ঠিক আছে", "अनइंस्टॉल", "ठीक है")
        val end = System.currentTimeMillis() + 12_000L
        var pressed = false
        while (System.currentTimeMillis() < end && !pressed) {
            if (!isInstalled(ctx, app.pkg)) break
            val pkgOnTop = try { acc.rootInActiveWindow?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
            // only press a button while the system installer's dialog is really the window on screen
            if (pkgOnTop.contains("installer", true) || pkgOnTop.contains("packageinstaller", true)) {
                for (l in labels) { if (acc.clickText(l, 0L)) { pressed = true; break } }
            }
            if (!pressed) try { Thread.sleep(400) } catch (_: InterruptedException) { break }
        }
        if (waitGone(ctx, app.pkg, 8000)) return r.put("ok", true).put("uninstalled", true).put("method", "system dialog")
        return r.put("ok", false).put("uninstalled", false)
            .put("error", if (pressed) "The button was pressed but the app is still installed (it may be protected as a device admin or by another app). Tell him honestly it is still there."
                          else "The uninstall dialog did not show an OK button NISU AI could press. Tell him honestly it is still installed, and that he can tap OK on the phone screen.")
    }

    private fun waitGone(ctx: Context, pkg: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!isInstalled(ctx, pkg)) return true
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
        return !isInstalled(ctx, pkg)
    }
}
