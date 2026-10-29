package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v3.141: in-app update from GitHub Releases (https://github.com/4xgaming/Nisu-Ai-Official/releases).
 * Publish a Release with a tag like "v3.142" and the signed APK attached. On every app open the app checks it:
 *  - new version: a popup (5 seconds, with a Download button) and a red "1 update available" row in the side menu;
 *  - Download shows the percent, then the Android installer opens by itself;
 *  - if the installer does not finish (Play Protect), the menu offers Play Protect settings + Install again;
 *  - when the newest version is installed the row turns green: "Latest version installed".
 */
internal object Updater {
    const val REPO = "4xgaming/Nisu-Ai-Official"
    private const val P = "nisu_updater"
    private const val K_PENDING = "pending_apk"

    enum class St { UNKNOWN, LATEST, AVAILABLE, DOWNLOADING, READY }
    class Info(val tag: String, val notes: String, val apkUrl: String, val size: Long)

    val state = MutableStateFlow(St.UNKNOWN)
    val progress = MutableStateFlow(0)
    val info = MutableStateFlow<Info?>(null)
    val popup = MutableStateFlow(false)       // the 5-second "new update" popup
    val blocked = MutableStateFlow(false)     // the installer was opened but the app is still old: Play Protect (or the user) stopped it
    val error = MutableStateFlow("")

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var busy = false
    @Volatile private var lastCheck = 0L
    @Volatile private var attempted = false
    @Volatile private var popupShownFor = ""

    fun version(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }

    /** "v3.142" -> [3,142]. Only digits and dots count. */
    internal fun parts(v: String): List<Int> = v.trim().trimStart('v', 'V').split('.', '-', '_').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

    /** True when [remote] is a higher version than [local]. */
    internal fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun apk(c: Context) = File(File(c.cacheDir, "updates"), "nisu-update.apk")

    /** Asks GitHub for the latest Release. Safe to call often: it does nothing while downloading, and at most every 10 minutes unless [manual]. */
    fun check(ctx: Context, manual: Boolean = false) {
        val app = ctx.applicationContext
        val st = state.value
        if (st == St.DOWNLOADING || st == St.READY || busy) return
        if (!manual && System.currentTimeMillis() - lastCheck < 10 * 60 * 1000L && st != St.UNKNOWN) return
        busy = true
        Thread {
            var found: Info? = null
            var reached = false
            try {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "NISU-AI").build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 404) { reached = true; return@use }   // no release yet = nothing newer
                    if (!r.isSuccessful) return@use
                    reached = true
                    val j = JSONObject(r.body?.string().orEmpty())
                    val tag = j.optString("tag_name", "")
                    val assets = j.optJSONArray("assets")
                    var url = ""; var size = 0L
                    if (assets != null) for (i in 0 until assets.length()) {
                        val o = assets.getJSONObject(i)
                        if (o.optString("name").endsWith(".apk", true)) { url = o.optString("browser_download_url"); size = o.optLong("size"); break }
                    }
                    if (tag.isNotEmpty() && url.isNotEmpty() && isNewer(tag, version(app))) found = Info(tag, j.optString("body", ""), url, size)
                }
            } catch (_: Throwable) {}
            busy = false
            if (!reached) { if (manual) android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, "Could not check for updates. Check your internet.", Toast.LENGTH_LONG).show() }; return@Thread }
            lastCheck = System.currentTimeMillis()
            val f = found
            if (f == null) {
                info.value = null; state.value = St.LATEST; blocked.value = false
                try { File(app.cacheDir, "updates").deleteRecursively() } catch (_: Throwable) {}   // we are on the newest version: the old download is not needed
            } else {
                info.value = f; state.value = St.AVAILABLE
                if (popupShownFor != f.tag) { popupShownFor = f.tag; popup.value = true }
            }
        }.start()
    }

    /** Download button: downloads the APK with a percent, then opens the installer by itself. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val i = info.value ?: return
        if (state.value == St.DOWNLOADING) return
        state.value = St.DOWNLOADING; progress.value = 0; error.value = ""; blocked.value = false
        Thread {
            val f = apk(app)
            var ok = false
            try {
                f.parentFile?.apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                http.newCall(Request.Builder().url(i.apkUrl).header("User-Agent", "NISU-AI").build()).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) return@use
                    val total = if (body.contentLength() > 0) body.contentLength() else i.size
                    var got = 0L
                    body.byteStream().use { inp -> f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0) progress.value = (got * 100 / total).toInt().coerceIn(0, 100)
                        }
                    } }
                    ok = total <= 0 || got >= total
                }
            } catch (_: Throwable) { ok = false }
            if (!ok) { f.delete(); error.value = "Download failed. Check your internet and tap again."; state.value = St.AVAILABLE; return@Thread }
            progress.value = 100
            state.value = St.READY
            install(app)
        }.start()
    }

    /** Opens the Android installer for the downloaded file (also used by "Install again"). */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val f = apk(app)
        if (!f.exists()) { state.value = if (info.value != null) St.AVAILABLE else St.LATEST; return }
        val sp = app.getSharedPreferences(P, Context.MODE_PRIVATE)
        if (!app.packageManager.canRequestPackageInstalls()) {
            sp.edit().putString(K_PENDING, f.absolutePath).apply()
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, "Allow \"Install unknown apps\" for NISU AI, then come back.", Toast.LENGTH_LONG).show() }
            try { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            return
        }
        sp.edit().remove(K_PENDING).apply()
        blocked.value = false
        try {
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileshare", f)
            attempted = true
            app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) { error.value = "Could not open the installer: " + (e.message ?: "") }
    }

    /**
     * Call from onResume. (1) The user just allowed "install unknown apps": go on with the install.
     * (2) The installer was opened and we are back in the OLD app (a successful install restarts the app): it was stopped, probably by Play Protect.
     */
    fun onResume(ctx: Context) {
        val app = ctx.applicationContext
        if (state.value != St.READY) return
        val sp = app.getSharedPreferences(P, Context.MODE_PRIVATE)
        if (sp.getString(K_PENDING, null) != null && app.packageManager.canRequestPackageInstalls()) { install(app); return }
        if (attempted) { attempted = false; blocked.value = true }
    }

    /** Opens the Play Protect screen so the user can switch "Scan apps with Play Protect" off. Android does not allow an app to do it itself. */
    fun openPlayProtect(ctx: Context) {
        val tries = listOf(
            Intent().setClassName("com.google.android.gms", "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ctx.packageName)),
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        )
        for (i in tries) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {} }
    }
}
