package ai.nisu.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/*
 * v3.57: POST A PHOTO OR VIDEO BY VOICE. He shares a photo / video to NISU AI from any app's Share menu. NISU AI asks which platform, asks the
 * title / caption and every setting that platform has (public or private, made for kids or not, ...), hands the file to the app and then
 * fills those settings in by driving the screen (the normal phone tools). Before the last Post / Upload button she reads everything back and
 * waits for his spoken yes. Nothing is posted without that yes.
 */

/** The files he shared (copied into the app's own cache so they stay readable for the other app). */
object SharedUploads {
    class Item(val id: Int, val name: String, val mime: String, val file: File, val at: Long) {
        val video get() = mime.startsWith("video")
    }

    private val items = CopyOnWriteArrayList<Item>()
    private val seq = AtomicInteger(0)
    @Volatile var onNew: ((String) -> Unit)? = null
    @Volatile private var unannounced: String? = null

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "shared_uploads").also { it.mkdirs() }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) { "mp4", "mov", "mkv", "3gp", "webm" -> "video/*"; else -> "image/*" }
    }

    /** After a restart of the app the files are still there: list them again. */
    private fun load(ctx: Context) {
        if (items.isNotEmpty()) return
        dir(ctx).listFiles()?.sortedBy { it.lastModified() }?.forEach { f ->
            if (f.isFile) items.add(Item(seq.incrementAndGet(), f.name, mimeOf(f.name), f, f.lastModified()))
        }
    }

    fun list(ctx: Context): List<Item> { load(ctx); return items.toList() }

    fun get(ctx: Context, id: Int?): Item? { load(ctx); return if (id == null || id <= 0) items.lastOrNull() else items.firstOrNull { it.id == id } }

    /** Copies one shared file. Call on a worker thread. */
    fun add(ctx: Context, uri: Uri, mimeHint: String?): Item? {
        load(ctx)
        val cr = ctx.contentResolver
        var name = try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "shared"
        name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "shared" }
        val type = (cr.getType(uri) ?: mimeHint ?: mimeOf(name)).let { if (it == "*/*") mimeOf(name) else it }
        if (!name.contains('.')) { val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type); if (ext != null) name = "$name.$ext" }
        val d = dir(ctx)
        val old = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        d.listFiles()?.forEach { if (it.lastModified() < old) { it.delete(); items.removeAll { x -> x.file == it } } }
        var out = File(d, name)
        if (out.exists()) out = File(d, System.currentTimeMillis().toString() + "_" + name)
        try {
            cr.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null
        } catch (_: Exception) { out.delete(); return null }
        val it = Item(seq.incrementAndGet(), out.name, type, out, System.currentTimeMillis())
        items.add(it)
        return it
    }

    fun announce(note: String) {
        val cb = onNew
        if (cb != null) cb(note) else unannounced = note
    }

    /** A share that arrived while NISU AI was not running: told as soon as she connects. */
    fun takePending(): String? { val n = unannounced; unannounced = null; return n }
}

/** The Share-menu target ("NISU AI" in the Share sheet of any app). */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val uris = ArrayList<Uri>()
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> streamOne()?.let { uris.add(it) }
                Intent.ACTION_SEND_MULTIPLE -> streamMany().let { uris.addAll(it) }
            }
        } catch (_: Exception) {}
        val hint = intent?.type
        if (uris.isEmpty()) { Toast.makeText(app, "NISU AI: nothing to upload was shared", Toast.LENGTH_SHORT).show(); finish(); return }
        Toast.makeText(app, "NISU AI: getting your file ready…", Toast.LENGTH_SHORT).show()
        thread(name = "nisu-share") {
            val got = uris.take(10).mapNotNull { SharedUploads.add(app, it, hint) }
            runOnUiThread {
                if (got.isEmpty()) Toast.makeText(app, "NISU AI could not read the shared file", Toast.LENGTH_LONG).show()
                else {
                    val vids = got.count { it.video }; val pics = got.size - vids
                    val what = listOfNotNull(if (vids > 0) "$vids video" else null, if (pics > 0) "$pics photo" else null).joinToString(" and ")
                    val note = "[SYSTEM NOTICE, not spoken by the user] He just shared $what with you from the Share menu (" + got.joinToString(", ") { it.name.take(40) } + "). " +
                        "Tell him in one short sentence that you got it and ask which platform he wants it on (YouTube, Facebook, Instagram or TikTok). Then follow the UPLOAD HELPER rules."
                    SharedUploads.announce(note)
                    if (AppState.running.value) Toast.makeText(app, "NISU AI: tell her which app to upload to", Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(app, "NISU AI: start NISU AI, then say which app to upload to", Toast.LENGTH_LONG).show()
                        try { startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    }
                }
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOne(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamMany(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}

/** What every platform asks for, and the hand-over of the file to the platform's app. */
object UploadGuide {
    class Platform(val key: String, val label: String, val aliases: List<String>, val packages: List<String>, val photos: Boolean, val ask: String, val how: String)

    val ALL = listOf(
        Platform("youtube", "YouTube", listOf("youtube", "yt", "you tube", "ইউটিউব", "ইউটিউবে"), listOf("com.google.android.youtube"), false,
            "1) Title (up to 100 characters; offer to write one). 2) Description (optional; offer to write it, hashtags allowed). 3) Visibility: Public, Unlisted or Private (or schedule). " +
            "4) AUDIENCE: is it made for kids, yes or no. This is a legal question: ALWAYS ask him, never guess and never pick for him. 5) Only if he says so: age restriction, comments on or off, a playlist.",
            "The share opens the YouTube upload screen with the video loaded. Tap the title box and type the title; open the description and type it; tap the visibility line and pick Public / Unlisted / Private; " +
            "open Audience and choose 'No, it's not made for kids' or 'Yes, it's made for kids'; then tap Next / Upload (top right). A vertical video up to 3 minutes goes out as a Short by itself. YouTube takes only videos here: for a photo say it is not possible."),
        Platform("facebook", "Facebook", listOf("facebook", "fb", "face book", "ফেসবুক", "ফেসবুকে", "ফেইসবুক"), listOf("com.facebook.katana", "com.facebook.lite"), true,
            "1) Where: his own profile, or a Page or Group (only if he says so). 2) For a video: a normal post or a Reel (ask if it is vertical and short). 3) Caption / text (offer to write it). 4) Who can see it: Public, Friends or Only me. 5) Only if he says so: tag people, add a location.",
            "The share opens the Facebook composer with the file. Choose Feed or Reels if it asks. Tap the text box ('Say something about this...' / 'Write something...') and type the caption; " +
            "tap the audience chip near his name and choose Public / Friends / Only me; then tap POST / SHARE / SHARE NOW."),
        Platform("instagram", "Instagram", listOf("instagram", "insta", "ig", "ইনস্টাগ্রাম", "ইন্সটাগ্রাম", "ইনস্টা", "ইন্সটা"), listOf("com.instagram.android"), true,
            "1) Where: Post, Reel or Story. 2) Caption with hashtags (offer to write it). 3) For a Story: everyone or Close Friends. 4) For a Reel or Post: also share to Facebook, yes or no. 5) Tell him the public / private setting of a post follows his account (Settings > Privacy), it cannot be changed per post.",
            "The share opens Instagram with a choice: Reel / Post / Story (or 'Your story'). Pick the one he wanted; go Next until the last screen; tap the caption box ('Write a caption...') and type it; set the audience or 'Also share to' toggles; then tap Share."),
        Platform("tiktok", "TikTok", listOf("tiktok", "tik tok", "টিকটক", "টিক টক", "টিকটকে"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), true,
            "1) Description / caption with hashtags (offer to write it). 2) Who can watch: Everyone, Friends or Only me. 3) Allow comments, Duet and Stitch: ask only if he cares (default on). 4) Is it made with AI, yes or no (the AI-generated label). 5) A photo becomes a photo post.",
            "The share opens the TikTok editor with the file. Tap Next until the Post screen. Tap the description box and type it; open 'Who can watch this video' and choose; set the toggles; then tap Post.")
    )

    fun find(raw: String): Platform? {
        val t = raw.trim().lowercase()
        return ALL.firstOrNull { p -> p.key == t || p.aliases.any { it == t || t.contains(it) } }
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }

    fun listFiles(ctx: Context): JSONObject {
        val a = JSONArray()
        SharedUploads.list(ctx).forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", if (it.video) "video" else "photo").put("size_kb", it.file.length() / 1024)) }
        return JSONObject().put("ok", true).put("files", a).put("note", if (a.length() == 0) "Nothing has been shared yet. Ask him to share the photo or video with NISU AI from the Share menu." else "The last one is used when he does not say which.")
    }

    fun guide(platformRaw: String): JSONObject {
        val p = find(platformRaw) ?: return err("Unknown platform. Known: YouTube, Facebook, Instagram, TikTok. For another app give its package name to upload_start.")
        return JSONObject().put("ok", true).put("platform", p.label).put("photos_ok", p.photos).put("ask_him", p.ask).put("how_to_do_it_on_screen", p.how)
            .put("note", "Ask one thing at a time, short and natural. When he says 'you decide', write a good title / caption yourself, say it, and ask for his yes.")
    }

    /** Opens the platform's app with the file loaded (Android share). */
    fun start(ctx: Context, platformRaw: String, id: Int?): JSONObject {
        val item = SharedUploads.get(ctx, id) ?: return err("No shared file yet. Ask him to share the photo or video with NISU AI from the Share menu.")
        val p = find(platformRaw)
        val candidates = p?.packages ?: listOf(platformRaw.trim())
        val pkg = candidates.firstOrNull { installed(ctx, it) }
            ?: return err((p?.label ?: platformRaw) + " is not installed on this phone. Tell him, and offer to open the Play Store page to install it.")
        if (!item.video && p != null && !p.photos) return err(p.label + " does not take photos this way. Tell him and ask what he wants instead.")
        val uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileshare", item.file) } catch (e: Exception) { return err("Could not prepare the file: " + (e.message ?: "")) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ctx.startActivity(send)
            JSONObject().put("ok", true).put("opened", p?.label ?: pkg).put("file", item.name)
                .put("note", "The app opened with his file. Wait a moment, read the screen (get_screen) and follow how_to_do_it_on_screen. Fill title / caption with type_text, set each setting he chose, and read everything back and wait for his spoken yes BEFORE the final Post / Upload / Share button.")
        } catch (e: Exception) {
            // the app does not take a share: put the file in the gallery and open the app, he (she) picks the newest item
            val saved = saveToGallery(ctx, item)
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) try { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            JSONObject().put("ok", launch != null).put("opened", p?.label ?: pkg).put("file", item.name).put("fallback", true)
                .put("note", (if (saved) "The app does not take a direct share, so the file was saved in the gallery (Movies or Pictures / NISU AI). " else "The app does not take a direct share. ") +
                    "The app is open: tap its create / + / upload button, choose the NEWEST item in the gallery, then continue as in how_to_do_it_on_screen and ask for his yes before the final button.")
        }
    }

    private fun saveToGallery(ctx: Context, item: SharedUploads.Item): Boolean {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, (if (item.video) "Movies" else "Pictures") + "/NISU AI")
            }
            val base = if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val u = ctx.contentResolver.insert(base, cv) ?: return false
            ctx.contentResolver.openOutputStream(u)?.use { o -> item.file.inputStream().use { i -> i.copyTo(o) } }
            true
        } catch (_: Exception) { false }
    }

    /** Added to the voice persona. */
    const val NOTE = """

UPLOAD HELPER (posting his photos and videos for him): when he shares a file to you or asks you to upload / post something to YouTube, Facebook, Instagram or TikTok, you do the whole job and he only talks. (1) shared_files shows what he shared. (2) If he did not say the platform, ask in one short sentence. (3) upload_guide tells what that platform asks for; ask him one thing at a time, short and natural: the title or caption (offer to write it if he wants), then each setting of that platform (public or private, kids or not kids, who can watch, comments...). Never guess a setting, and never guess the made-for-kids answer. (4) upload_start opens the app with his file; then use get_screen, click_text and type_text to type the title / caption and set each option exactly as he chose. (5) BEFORE the last Post / Upload / Share / Publish button, read the title, caption and settings back in one or two short sentences and wait for his spoken yes; without a clear yes do not press it. (6) Tell him when it is posted or uploading. Never type or ask for a password; if the app needs a login or a permission, tell him and let him do it. If the app is not installed, say so."""
}
