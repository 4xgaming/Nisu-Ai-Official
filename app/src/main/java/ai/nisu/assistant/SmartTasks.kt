package ai.nisu.assistant

import android.Manifest
import android.app.DownloadManager
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure text rules for [SmartTasks] (no Android classes, so they are unit tested on the JVM).
 */
object SmartRules {
    private fun key(s: String) = s.lowercase().filterNot { it.isWhitespace() || it in "-_.'()" }

    /** Gallery folder a spoken word points to (English or Bengali), or null when it is not a folder word. */
    fun albumHint(text: String): String? {
        val k = key(text)
        if (k.isEmpty()) return null
        val table = listOf(
            listOf("screenshot", "screenshots", "\u09B8\u09CD\u0995\u09CD\u09B0\u09BF\u09A8\u09B6\u099F", "\u09B8\u09CD\u0995\u09CD\u09B0\u09C0\u09A8\u09B6\u099F") to "Screenshot",
            listOf("camera", "selfie", "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE", "\u09B8\u09C7\u09B2\u09AB\u09BF") to "Camera",
            listOf("download", "downloads", "\u09A1\u09BE\u0989\u09A8\u09B2\u09CB\u09A1") to "Download",
            listOf("whatsapp", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u098F\u09AA") to "WhatsApp",
            listOf("facebook", "\u09AB\u09C7\u09B8\u09AC\u09C1\u0995") to "Facebook",
            listOf("telegram", "\u099F\u09C7\u09B2\u09BF\u0997\u09CD\u09B0\u09BE\u09AE") to "Telegram"
        )
        for ((words, album) in table) if (words.any { k.contains(key(it)) }) return album
        return null
    }

    /** "latest / newest / last photo" style words (English, Bengali, Hindi). */
    fun wantsLatest(text: String): Boolean {
        val t = text.lowercase()
        return listOf("latest", "newest", "recent", "last", "new ", "\u09B8\u09B0\u09CD\u09AC\u09B6\u09C7\u09B7", "\u09B6\u09C7\u09B7", "\u09A8\u09A4\u09C1\u09A8", "\u09B2\u09C7\u099F\u09C7\u09B8\u09CD\u099F", "\u0905\u0902\u0924\u093F\u092E", "\u0928\u0908")
            .any { t.contains(it) }
    }

    /** True when a button / label asks for money (a price, Buy, or the Bengali word for buy). Such an app is never installed by itself. */
    fun looksPaid(label: String): Boolean {
        val t = label.lowercase().trim()
        if (t.isEmpty()) return false
        if (Regex("^(buy|purchase)\\b").containsMatchIn(t) || t.contains("\u0995\u09BF\u09A8\u09C1\u09A8")) return true
        return Regex("[\\u0024\\u00A3\\u20AC\\u20B9\\u09F3]\\s*\\d|\\d+[.,]\\d{2}\\s*(usd|bdt|eur|inr)?$").containsMatchIn(t)
    }

    /** A safe file name for the Download folder (no folders, no odd characters); never empty. */
    fun safeFileName(raw: String, fallback: String = "download"): String {
        val last = raw.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val cleaned = last.map { if (it.isLetterOrDigit() || it in "._- ()") it else '_' }.joinToString("").trim().trim('.')
        return cleaned.take(120).ifBlank { fallback }
    }

    fun isHttpUrl(u: String): Boolean = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(u.trim())
}

/**
 * v2.72: tasks the voice assistant used to do badly by tapping through screens step by step (and then asking the user to find the photo
 * or the app himself). Each one is now a single tool that does the whole job and reads the result back from Android:
 *  find_photos, set_wallpaper (straight from the gallery, no screens), install_app (Play Store page, Install button, waits for the
 *  package to appear), download_file (Android DownloadManager, straight into Downloads).
 */
object SmartTasks {
    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ------------------------------------------------------------------------------------------ gallery

    private class Photo(val id: Long, val name: String, val album: String, val dateSec: Long, val w: Int, val h: Int) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun hasMediaAccess(ctx: Context): Boolean {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return if (Build.VERSION.SDK_INT >= 33)
            has(Manifest.permission.READ_MEDIA_IMAGES) || (Build.VERSION.SDK_INT >= 34 && has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        else has(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun queryPhotos(ctx: Context, name: String, album: String?, limit: Int): List<Photo> {
        val out = ArrayList<Photo>()
        val sel = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!album.isNullOrBlank()) { sel.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"); args.add("%$album%") }
        if (name.isNotBlank()) {
            sel.append(" AND (${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)")
            args.add("%$name%"); args.add("%$name%")
        }
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel.toString(), args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit)
                out.add(Photo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getInt(4), c.getInt(5)))
        }
        return out
    }

    private fun photoJson(p: Photo): JSONObject {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(p.dateSec * 1000))
        return JSONObject().put("photo_id", p.id).put("name", p.name).put("album", p.album).put("date", date).put("size", "${p.w}x${p.h}")
    }

    /** Words in [raw] that name a folder (screenshot, camera, ...) become the album filter; otherwise [raw] is a part of a file name. */
    private fun splitFilter(query: String, album: String): Pair<String, String?> {
        val albumArg = album.trim()
        val fromAlbum = if (albumArg.isNotEmpty()) (SmartRules.albumHint(albumArg) ?: albumArg) else null
        val q = query.trim()
        if (fromAlbum != null) return q to fromAlbum
        val hint = SmartRules.albumHint(q)
        return if (hint != null) "" to hint else q to null
    }

    fun findPhotos(ctx: Context, a: JSONObject): JSONObject {
        if (!hasMediaAccess(ctx))
            return err("NISU AI may not read the photos yet.", "Allow 'Photos and videos' for NISU AI (Settings > Apps > NISU AI > Permissions), or run setup_self when root is available.")
        val limit = a.optInt("count", 8).coerceIn(1, 20)
        val (name, album) = splitFilter(a.optString("query", ""), a.optString("album", ""))
        return try {
            var list = queryPhotos(ctx, name, album, limit)
            var note: String? = null
            if (list.isEmpty() && (name.isNotBlank() || album != null)) {
                list = queryPhotos(ctx, "", null, limit)
                note = "Nothing matched that name or folder, so these are the newest photos on the phone. Tell him what you picked."
            }
            if (list.isEmpty()) return err("There are no photos on this phone (or Android shows none to NISU AI).", "If he chose 'selected photos only', allow all photos for NISU AI in Settings > Apps > NISU AI > Permissions.")
            val arr = JSONArray(); list.forEach { arr.put(photoJson(it)) }
            val res = JSONObject().put("ok", true).put("count", list.size).put("newest_first", true).put("photos", arr)
            if (note != null) res.put("note", note)
            res
        } catch (e: Exception) { err("Could not read the gallery: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------------------------------ wallpaper

    private fun download(ctx: Context, url: String, maxBytes: Long = 30L * 1024 * 1024): File? {
        if (!SmartRules.isHttpUrl(url)) return null
        val out = File(ctx.cacheDir, "nisu_dl_${System.currentTimeMillis()}.img")
        var con: HttpURLConnection? = null
        return try {
            con = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NISU-AI")
            }
            if (con.responseCode !in 200..299) return null
            var total = 0L
            con.inputStream.use { ins -> FileOutputStream(out).use { os ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) { out.delete(); return null }
                    os.write(buf, 0, n)
                }
            } }
            if (total > 0) out else null
        } catch (_: Exception) { out.delete(); null } finally { try { con?.disconnect() } catch (_: Exception) {} }
    }

    /** Decodes the picture a bit larger than the screen, turns it upright (EXIF) and crops the centre to the screen shape. */
    private fun decodeForScreen(ctx: Context, open: () -> InputStream?): Bitmap? {
        val dm = ctx.resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(1); val sh = dm.heightPixels.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sw * 1.3 && bounds.outHeight / (sample * 2) >= sh * 1.3) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val deg = try {
            open()?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val target = sw.toFloat() / sh
        val cur = bmp.width.toFloat() / bmp.height
        val cw: Int; val ch: Int
        if (cur > target) { ch = bmp.height; cw = (bmp.height * target).toInt() } else { cw = bmp.width; ch = (bmp.width / target).toInt() }
        if (cw <= 0 || ch <= 0 || (cw == bmp.width && ch == bmp.height)) return bmp
        return Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)
    }

    fun setWallpaper(ctx: Context, a: JSONObject): JSONObject {
        val target = a.optString("target", "both").lowercase().trim()
        val flags = when {
            target.startsWith("lock") -> WallpaperManager.FLAG_LOCK
            target.startsWith("home") || target == "system" -> WallpaperManager.FLAG_SYSTEM
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        val targetName = when (flags) { WallpaperManager.FLAG_LOCK -> "lock screen"; WallpaperManager.FLAG_SYSTEM -> "home screen"; else -> "home screen and lock screen" }
        val wm = WallpaperManager.getInstance(ctx)
        if (!wm.isWallpaperSupported) return err("This phone does not support changing the wallpaper.")
        if (!wm.isSetWallpaperAllowed) return err("Android does not allow changing the wallpaper here (a work profile or a policy blocks it).")

        var photoUri: Uri? = null
        var file: File? = null
        var label: String
        var album = ""
        var others: List<Photo> = emptyList()
        val url = a.optString("url", "").trim()
        val idArg = a.opt("photo_id")?.toString()?.trim()?.toDoubleOrNull()?.toLong()
        try {
            if (url.isNotEmpty()) {
                file = download(ctx, url) ?: return err("Could not download a picture from that link.", "Check the link, or ask him for a photo from the gallery instead.")
                label = "the picture from the link"
            } else {
                if (!hasMediaAccess(ctx))
                    return err("NISU AI may not read the photos yet.", "Allow 'Photos and videos' for NISU AI (Settings > Apps > NISU AI > Permissions), or run setup_self when root is available.")
                val p: Photo
                if (idArg != null && idArg > 0) {
                    p = queryPhotos(ctx, "", null, 2000).firstOrNull { it.id == idArg }
                        ?: return err("No photo with photo_id $idArg. Call find_photos again for a fresh list.")
                } else {
                    val (name, alb) = splitFilter(a.optString("query", ""), a.optString("album", ""))
                    val list = queryPhotos(ctx, name, alb, 5)
                    p = list.firstOrNull() ?: queryPhotos(ctx, "", null, 5).firstOrNull()
                        ?: return err("There are no photos on this phone to use.")
                    others = list.drop(1).take(3)
                }
                photoUri = p.uri; label = p.name; album = p.album
            }

            val srcFile: File? = file
            val srcUri: Uri? = photoUri
            val opener: () -> InputStream? = {
                try { if (srcFile != null) srcFile.inputStream() else if (srcUri != null) ctx.contentResolver.openInputStream(srcUri) else null } catch (_: Exception) { null }
            }
            val bmp = decodeForScreen(ctx, opener) ?: return err("That picture could not be opened (unsupported or damaged image).")
            val before = if (Build.VERSION.SDK_INT >= 24) wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) else 0
            val newId = try { wm.setBitmap(bmp, null, true, flags) }
            catch (e: SecurityException) { return openWallpaperScreen(ctx, photoUri, "Android refused: ${e.message}") }
            finally { try { bmp.recycle() } catch (_: Exception) {} }
            val after = if (Build.VERSION.SDK_INT >= 24) wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) else 0
            val changed = newId > 0 || (flags and WallpaperManager.FLAG_SYSTEM != 0 && after != before)
            if (!changed) return openWallpaperScreen(ctx, photoUri, "Android did not apply the wallpaper")
            val res = JSONObject().put("ok", true).put("wallpaper_set", true).put("target", targetName).put("photo", label)
            if (album.isNotEmpty()) res.put("album", album)
            if (others.isNotEmpty()) res.put("other_matches", JSONArray(others.map { it.name }))
            return res.put("note", "The wallpaper was set directly and checked. Tell him which photo you used; if it is the wrong one, offer to pick another from other_matches or find_photos.")
        } catch (e: Exception) {
            return err("Could not set the wallpaper: ${e.message ?: e.javaClass.simpleName}")
        } finally { try { file?.delete() } catch (_: Exception) {} }
    }

    /** Last resort: opens Android's own 'set as wallpaper' screen for the chosen photo so the normal screen tools can finish it. */
    private fun openWallpaperScreen(ctx: Context, uri: Uri?, why: String): JSONObject {
        if (uri == null) return err("$why.")
        return try {
            val i = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/*").putExtra("mimeType", "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            JSONObject().put("ok", false).put("error", "$why.").put("fallback_opened", true)
                .put("fix", "The system 'set as wallpaper' screen is open for that photo now. Use get_screen and click_text (for example 'Set as wallpaper', 'Home screen', 'Both') to finish it yourself, then confirm.")
        } catch (e: Exception) { err("$why, and the wallpaper screen could not be opened: ${e.message ?: e.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------------------------------ Play Store install

    private const val PLAY = "com.android.vending"

    @Suppress("DEPRECATION")
    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun installedSet(ctx: Context): Set<String> = try { ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet() } catch (_: Exception) { emptySet() }

    private val INSTALL_LABELS = listOf("Install", "\u0987\u09A8\u09B8\u09CD\u099F\u09B2", "\u0987\u09A8\u09B8\u09CD\u099F\u09B2 \u0995\u09B0\u09C1\u09A8", "\u0987\u09A8\u09CD\u09B8\u099F\u09B2")
    private val OPEN_LABELS = listOf("Open", "\u0996\u09CB\u09B2\u09BE", "\u0993\u09AA\u09C7\u09A8")
    private val UPDATE_LABELS = listOf("Update", "\u0986\u09AA\u09A1\u09C7\u099F")
    private val BUY_LABELS = listOf("Buy", "Purchase", "\u0995\u09BF\u09A8\u09C1\u09A8")
    private val DISMISS_LABELS = listOf("Accept", "Got it", "Not now", "No thanks", "Skip")
    private val SIGNIN_LABELS = listOf("Sign in", "\u09B8\u09BE\u0987\u09A8 \u0987\u09A8")

    /** True when the text looks like an app's own Play Store page (not a result list) and mentions the app's name. */
    private fun looksLikeAppPage(text: String, name: String): Boolean {
        val t = text.lowercase()
        val marks = listOf("about this app", "data safety", "ratings and reviews", "\u098F\u0987 \u0985\u09CD\u09AF\u09BE\u09AA \u09B8\u09AE\u09CD\u09AA\u09B0\u09CD\u0995\u09C7", "\u09A1\u09C7\u099F\u09BE \u09B8\u09C1\u09B0\u0995\u09CD\u09B7\u09BE")
        return marks.any { t.contains(it) } && t.replace(" ", "").contains(name.lowercase().replace(" ", ""))
    }

    fun installApp(ctx: Context, a: JSONObject): JSONObject {
        // the Play Store ignores taps while an overlay (such as the Edge Glow) is drawn over it
        val wait = a.optInt("wait_seconds", 45).coerceIn(10, 120)
        val showing = EdgeGlow.showing
        EdgeGlow.pause(wait * 1000L + 15_000L)
        if (showing) sleep(250)
        try { return installAppInner(ctx, a) } finally { EdgeGlow.resume() }
    }

    private fun installAppInner(ctx: Context, a: JSONObject): JSONObject {
        val name = a.optString("name", "").trim()
        val pkgArg = a.optString("package", "").trim()
        if (name.isEmpty() && pkgArg.isEmpty()) return err("Say which app: give its name (and its package when you know it).")
        val waitSec = a.optInt("wait_seconds", 45).coerceIn(10, 120)

        var pkg = pkgArg
        if (pkg.isEmpty()) pkg = PhoneControl.installedExact(ctx, name).orEmpty()
        if (pkg.isNotEmpty() && isInstalled(ctx, pkg))
            return JSONObject().put("ok", true).put("installed", true).put("already_installed", true).put("package", pkg)
                .put("note", "It is already installed. Say so and offer to open it with open_app.")

        val known = pkgArg.isNotEmpty()
        if (!isInstalled(ctx, PLAY)) {
            val link = if (known) "https://play.google.com/store/apps/details?id=$pkgArg" else "https://play.google.com/store/search?q=${Uri.encode(name)}&c=apps"
            return PhoneControl.openUrl(ctx, link).put("note", "The Play Store app is not on this phone, so the Play Store website was opened in the browser. Continue there with click_text if he wants.")
        }
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")

        val uriText = if (known) "market://details?id=${Uri.encode(pkgArg)}" else "market://search?q=${Uri.encode(name)}&c=apps"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriText)).setPackage(PLAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val rootCmd = "am start -a android.intent.action.VIEW -d '$uriText' -p $PLAY"
        val opened = PhoneControl.launch(ctx, intent, "the Play Store", rootCmd) { it == PLAY }
        if (!opened.optBoolean("ok")) return opened

        val before: Set<String> = if (known) emptySet<String>() else installedSet(ctx)
        val startedAt = SystemClock.uptimeMillis()
        val deadline = startedAt + waitSec * 1000L
        var stage = if (known) 1 else 0          // 0 = search results, 1 = the app's own page
        var installClicks = 0
        var lastInstallClick = 0L
        var lastNote = ""

        fun success(p: String?, how: String): JSONObject = JSONObject().put("ok", true).put("installed", true).put("package", p ?: JSONObject.NULL)
            .put("how", how).put("note", "The app is installed. Tell him, and offer to open it with open_app.")

        while (SystemClock.uptimeMillis() < deadline) {
            if (known && isInstalled(ctx, pkgArg)) return success(pkgArg, "package_manager")
            if (!known && installClicks > 0) {
                val added = installedSet(ctx) - before
                if (added.isNotEmpty()) return success(added.first(), "package_manager")
            }
            val fg = acc.fgPkg
            if (fg.isNotEmpty() && fg != PLAY && installClicks == 0 && SystemClock.uptimeMillis() - startedAt > 6000L) {
                lastNote = "Another app ($fg) is in front, not the Play Store."   // do not tap blindly in another app
                break
            }

            if (stage == 0) {
                // a result list has its own Install button on every row: open the result with this name first, never install from the list
                if (!acc.clickText(name, 2500)) { lastNote = "The app \"$name\" was not found in the Play Store search results."; break }
                sleep(1500)
                if (!looksLikeAppPage(acc.visibleText(6000), name)) {
                    lastNote = "The Play Store did not open the page of \"$name\" (it may be a different app). Check with get_screen."
                    break
                }
                stage = 1
                continue
            }

            if (installClicks == 0 && acc.hasExact(OPEN_LABELS))
                return JSONObject().put("ok", true).put("installed", true).put("package", if (known) pkgArg else JSONObject.NULL).put("already_installed", true)
                    .put("note", "The Play Store page shows 'Open', so the app is already installed. Offer to open it with open_app.")
            if (installClicks == 0 && acc.hasExact(UPDATE_LABELS) && !acc.hasExact(INSTALL_LABELS))
                return JSONObject().put("ok", true).put("installed", true).put("already_installed", true).put("update_available", true)
                    .put("note", "The app is installed and the Play Store offers an update. Say so; update only if he asks.")
            if (installClicks == 0 && acc.hasExact(BUY_LABELS))
                return err("This app is not free (the Play Store shows a price / Buy button). Nothing was bought.", "Tell him it is paid and ask if he really wants to buy it; buying is something he must do himself.")
            if (acc.visibleText(4000).contains("blocking access to Google Play", true))
                return err("The Play Store says another app is drawing over the screen and blocks the Install tap.",
                    "Another app on the phone has an overlay (screen filter, chat heads, floating button, screen recorder, call recorder). Ask him to switch it off for a moment (Settings > Apps > Special access > Display over other apps), then call install_app again.")
            if (installClicks < 3 && SystemClock.uptimeMillis() - lastInstallClick > 5000L) {
                if (acc.clickExact(INSTALL_LABELS) != null) { installClicks++; lastInstallClick = SystemClock.uptimeMillis(); sleep(1200); continue }
            }
            if (acc.clickExact(DISMISS_LABELS) != null) { sleep(700); continue }
            if (installClicks == 0 && acc.hasExact(SIGNIN_LABELS))
                return err("The Play Store is asking to sign in to a Google account, so the app cannot be installed yet.", "Ask him to sign in to the Play Store (that is a login, his to handle), then call install_app again.")
            sleep(1000)
        }

        val screen = acc.dumpScreen(40)
        if (known && isInstalled(ctx, pkgArg)) return success(pkgArg, "package_manager")
        return if (installClicks > 0)
            JSONObject().put("ok", true).put("installed", false).put("install_started", true).put("package", if (known) pkgArg else JSONObject.NULL)
                .put("note", "Install was tapped and the Play Store is still downloading it (big apps take a while). Tell him it is installing; do NOT say it is done. Call install_app again with the same package later to check.")
                .put("screen", screen)
        else
            err(if (lastNote.isNotEmpty()) lastNote else "Could not find an Install button on the Play Store page.",
                "Use get_screen to see the Play Store page, then click_text the right button yourself. If the app is not found, search again with a better name or the package name.")
                .put("screen", screen)
    }

    // ------------------------------------------------------------------------------------------ downloads

    fun downloadFile(ctx: Context, a: JSONObject): JSONObject {
        val url = a.optString("url", "").trim()
        if (!SmartRules.isHttpUrl(url)) return err("Give the direct http(s) link of the file.", "If he only named a website or a file, open it with open_url (browser chrome) and tap its Download button with click_text.")
        val fileName = SmartRules.safeFileName(a.optString("filename", "").ifBlank { url })
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName).setDescription("NISU AI")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            dm.enqueue(req)
        } catch (e: Exception) { return err("Android refused to start the download: ${e.message ?: e.javaClass.simpleName}", "Open the link with open_url in Chrome and tap its Download button instead.") }

        val waitSec = a.optInt("wait_seconds", 40).coerceIn(5, 120)
        val end = SystemClock.uptimeMillis() + waitSec * 1000L
        var status = DownloadManager.STATUS_PENDING
        var done = 0L; var total = -1L; var reason = 0
        while (SystemClock.uptimeMillis() < end) {
            try {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    }
                }
            } catch (_: Exception) {}
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) break
            sleep(700)
        }
        val path = "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
        return when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> JSONObject().put("ok", true).put("downloaded", true).put("file", fileName).put("saved_in", path).put("bytes", done)
                .put("note", if (fileName.endsWith(".apk", true)) "It is an APK: do not install it unless he asks; offer app_install." else "Saved in the Downloads folder.")
            DownloadManager.STATUS_FAILED -> err("The download failed (code $reason).", "Check the link and the internet. Some sites need the browser: open_url in Chrome and tap Download.")
            else -> JSONObject().put("ok", true).put("downloaded", false).put("download_started", true).put("file", fileName)
                .put("progress", if (total > 0) "${(done * 100 / total).toInt()}%" else "unknown size")
                .put("note", "The download is still running in the background (see the notification). Tell him it started; do NOT say it finished.")
        }
    }
}
