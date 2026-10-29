package ai.nisu.assistant

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * v3.76: NISU AI's own FILE MANAGER (engine). No other file manager is needed.
 *  - The owner switches it on in the Files page (toggle + the phone's "All files access"). Off = the AI cannot touch any file.
 *  - A file index of the whole shared storage is kept in memory and on disk, so a search takes a few milliseconds. The first scan of a
 *    big phone takes a few seconds, after that the index only refreshes in the background.
 *  - Everything the AI does here is silent (no screen of ours opens). Opening a file hands it to the phone's own gallery / music / video player.
 *  - zip and 7z are opened when they have NO password; a password protected archive is reported, never guessed or cracked.
 */
object FileHub {
    const val KEY_ON = "files_on"

    class Entry(val path: String, val size: Long, val mtime: Long, val dir: Boolean, val kind: String) {
        val name: String = path.substringAfterLast('/')
        val lower: String = name.lowercase()
    }

    @Volatile private var index: Array<Entry> = emptyArray()
    @Volatile private var loaded = false
    @Volatile var scanning = false
    @Volatile var lastScan = 0L
    val count: Int get() = index.size

    fun root(): File = Environment.getExternalStorageDirectory()

    // ---------------------------------------------------------------- permission / switch
    fun hasAllFiles(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun wanted(c: Context): Boolean = Prefs.getBool(c, KEY_ON, false)
    fun enabled(c: Context): Boolean = wanted(c) && hasAllFiles(c)

    fun setWanted(c: Context, on: Boolean) {
        Prefs.putBool(c, KEY_ON, on)
        if (on) { if (!hasAllFiles(c)) askPermission(c) else refresh(c) }
    }

    fun askPermission(c: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(i) } catch (_: Exception) { c.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- kinds
    private val PHOTO = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tif", "tiff", "dng", "raw")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v", "ts", "mpg", "mpeg")
    private val MUSIC = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid", "midi")
    private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "epub")
    private val TEXT = setOf("txt", "md", "log", "json", "xml", "html", "htm", "ini", "conf", "yml", "yaml", "java", "kt", "kts", "py", "js", "css", "srt", "vtt", "sh", "properties")
    private val ARCHIVE = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
    private val APK = setOf("apk", "apks", "xapk")

    fun kindOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            in PHOTO -> "photo"; in VIDEO -> "video"; in MUSIC -> "music"; in DOC -> "document"
            in TEXT -> "text"; in ARCHIVE -> "archive"; in APK -> "apk"; else -> "other"
        }
    }

    // ---------------------------------------------------------------- index
    private fun mk(f: File, dir: Boolean): Entry = Entry(f.path, if (dir) 0L else f.length(), f.lastModified(), dir, if (dir) "folder" else kindOf(f.name))

    private fun indexFile(c: Context) = File(c.filesDir, "file_index_v1.tsv")

    private fun ensureLoaded(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = indexFile(c)
                if (f.exists()) {
                    val out = ArrayList<Entry>()
                    f.bufferedReader().useLines { lines ->
                        for (l in lines) {
                            val p = l.split('\t')
                            if (p.size < 4) continue
                            val dir = p[3] == "1"
                            out.add(Entry(p[0], p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L, dir, if (dir) "folder" else kindOf(p[0].substringAfterLast('/'))))
                        }
                    }
                    index = out.toTypedArray()
                    lastScan = f.lastModified()
                }
            } catch (_: Exception) { }
            loaded = true
        }
    }

    private fun saveIndex(c: Context) {
        try {
            indexFile(c).bufferedWriter().use { w ->
                for (e in index) { w.write(e.path); w.write("\t"); w.write(e.size.toString()); w.write("\t"); w.write(e.mtime.toString()); w.write("\t"); w.write(if (e.dir) "1" else "0"); w.write("\n") }
            }
        } catch (_: Exception) { }
    }

    /** Scans the whole shared storage in the background (a few seconds on a big phone). Searches keep using the old index meanwhile. */
    fun refresh(c: Context) {
        if (scanning) return
        scanning = true
        val app = c.applicationContext
        Thread {
            try {
                ensureLoaded(app)
                val rootDir = root()
                val skip = setOf(rootDir.path + "/Android/data", rootDir.path + "/Android/obb")
                val out = ArrayList<Entry>(if (index.isEmpty()) 60_000 else index.size + 2_000)
                val stack = ArrayList<File>()
                stack.add(rootDir)
                while (stack.isNotEmpty()) {
                    val d = stack.removeAt(stack.size - 1)
                    val list = try { d.listFiles() } catch (_: Exception) { null } ?: continue
                    for (f in list) {
                        val isDir = f.isDirectory
                        if (isDir) {
                            if (f.path in skip) continue
                            if (try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { false }) continue
                            stack.add(f)
                        }
                        out.add(mk(f, isDir))
                    }
                    if (out.size > 2_000_000) break
                }
                index = out.toTypedArray()
                lastScan = System.currentTimeMillis()
                saveIndex(app)
            } catch (_: Throwable) { } finally { scanning = false }
        }.start()
    }

    /** Called at the start of a search: if there is no index yet, scan now and wait a little. */
    private fun ensureIndex(c: Context): Boolean {
        ensureLoaded(c)
        if (index.isEmpty()) {
            refresh(c)
            val t0 = System.currentTimeMillis()
            while (scanning && System.currentTimeMillis() - t0 < 12_000) Thread.sleep(60)
        } else if (System.currentTimeMillis() - lastScan > 10 * 60_000L) refresh(c)   // old index: a fresh one is built silently for the next search
        return index.isNotEmpty()
    }

    private fun reindex(c: Context, f: File) {
        val p = f.path
        val keep = ArrayList<Entry>(index.size + 16)
        for (e in index) if (e.path != p && !e.path.startsWith("$p/")) keep.add(e)
        if (f.exists()) {
            val stack = ArrayList<File>(); stack.add(f)
            while (stack.isNotEmpty()) {
                val x = stack.removeAt(stack.size - 1)
                val d = x.isDirectory
                keep.add(mk(x, d))
                if (d) (try { x.listFiles() } catch (_: Exception) { null })?.let { stack.addAll(it) }
            }
        }
        index = keep.toTypedArray()
        Thread { saveIndex(c.applicationContext) }.start()
    }

    fun search(c: Context, query: String, kind: String, folder: String?, limit: Int): List<Entry> {
        ensureIndex(c)
        val q = query.trim().lowercase()
        val toks = q.split(' ', '_', '-', '.').filter { it.isNotBlank() }
        val fo = folder?.trimEnd('/')
        val res = ArrayList<Entry>()
        for (e in index) {
            if (kind != "any") { if (e.kind != kind) continue } else if (e.dir && toks.isEmpty()) continue
            if (fo != null && !e.path.startsWith("$fo/")) continue
            if (toks.isNotEmpty() && !toks.all { it in e.lower }) continue
            res.add(e)
        }
        return res.sortedWith(compareByDescending<Entry> { q.isNotEmpty() && (it.lower == q || it.lower.startsWith(q)) }.thenByDescending { it.mtime }).take(limit)
    }

    fun listDir(path: String): List<Entry> {
        val d = File(path)
        val arr = try { d.listFiles() } catch (_: Exception) { null } ?: return emptyList()
        return arr.map { mk(it, it.isDirectory) }.sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.lower })
    }

    // ---------------------------------------------------------------- helpers
    fun sizeText(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }
    fun dateText(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun err(m: String, hint: String = ""): JSONObject = JSONObject().put("ok", false).put("error", m).also { if (hint.isNotEmpty()) it.put("hint", hint) }

    /** A path the AI or the page gave: "/sdcard/..." and relative paths are understood; only shared storage / SD cards are allowed. */
    fun resolve(p: String): File? {
        var s = p.trim()
        if (s.isEmpty()) return null
        val r = root().path
        if (s.startsWith("/sdcard")) s = r + s.removePrefix("/sdcard")
        else if (!s.startsWith("/")) s = "$r/$s"
        val f = File(s)
        val canon = try { f.canonicalPath } catch (_: Exception) { return null }
        return if (canon == r || canon.startsWith("$r/") || canon.startsWith("/storage/")) File(canon) else null
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun mimeOf(f: File): String {
        val e = f.extension.lowercase()
        return if (e in APK) "application/vnd.android.package-archive" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "*/*"
    }

    private fun uriOf(c: Context, f: File): Uri? = try { FileProvider.getUriForFile(c, c.packageName + ".fileshare", f) } catch (_: Exception) { null }

    // ---------------------------------------------------------------- operations (used by the tools and by the Files page)
    fun open(c: Context, f: File): String? {
        if (!f.exists()) return "That file does not exist any more."
        val uri = uriOf(c, f) ?: return "Could not prepare the file for opening."
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { c.startActivity(i); null } catch (e: Exception) { "No app on this phone can open this kind of file." }
    }

    fun copy(c: Context, src: File, to: File, cut: Boolean): String? {
        if (!src.exists()) return "The source does not exist."
        val dst = uniqueIn(if (to.isDirectory) to else (to.parentFile ?: return "Bad destination."), if (to.isDirectory) src.name else to.name)
        if (src.isDirectory && (dst.path == src.path || dst.path.startsWith(src.path + "/"))) return "A folder cannot be put inside itself."
        val ok = if (cut) (src.renameTo(dst) || (src.copyRecursively(dst, false) && src.deleteRecursively())) else src.copyRecursively(dst, false)
        if (!ok) return "The " + (if (cut) "move" else "copy") + " did not work (not enough space, or the file is in use)."
        if (cut) reindex(c, src)
        reindex(c, dst)
        return null
    }

    fun rename(c: Context, src: File, newName: String): String? {
        if (newName.isBlank() || newName.contains('/')) return "Give just the new name, without a folder."
        val dst = File(src.parentFile, newName)
        if (dst.exists()) return "A file with that name already exists."
        if (!src.renameTo(dst)) return "Could not rename it."
        reindex(c, src); reindex(c, dst)
        return null
    }

    fun mkdir(c: Context, parent: File, name: String): String? {
        if (name.isBlank() || name.contains('/')) return "Give just the folder name."
        val d = File(parent, name)
        if (d.exists()) return "That folder already exists."
        if (!d.mkdirs()) return "Could not create the folder."
        reindex(c, d)
        return null
    }

    /** Deleting is for ever. Refuses the storage root and the Android folder. */
    fun delete(c: Context, f: File): String? {
        val r = root()
        if (f.path == r.path || (f.parentFile?.path == r.path && f.name == "Android")) return "That folder is protected."
        if (!f.exists()) return "It does not exist."
        if (!f.deleteRecursively()) return "Could not delete everything (some part is in use)."
        reindex(c, f)
        return null
    }

    fun readText(f: File, maxChars: Int): String? {
        if (!f.isFile) return null
        f.reader(Charsets.UTF_8).use { r ->
            val buf = CharArray(maxChars.coerceIn(100, 12_000))
            val n = r.read(buf)
            if (n <= 0) return ""
            val s = String(buf, 0, n)
            return if (s.contains('\u0000')) null else s
        }
    }

    // ---------------------------------------------------------------- archives (zip, 7z), NO password handling at all
    class Archive(val entries: List<Pair<String, Long>>, val error: String?)

    /** true when any entry of the zip is marked encrypted (general purpose flag bit 0 of the central directory). */
    private fun zipEncrypted(f: File): Boolean {
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val tail = minOf(len, 66_000L).toInt()
            val buf = ByteArray(tail)
            r.seek(len - tail); r.readFully(buf)
            var p = tail - 22
            while (p >= 0 && !(buf[p] == 0x50.toByte() && buf[p + 1] == 0x4b.toByte() && buf[p + 2] == 0x05.toByte() && buf[p + 3] == 0x06.toByte())) p--
            if (p < 0) return false
            fun u16(o: Int) = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8)
            fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
            val total = u16(p + 10)
            var off = u32(p + 16)
            var k = 0
            val hdr = ByteArray(46)
            while (k < total && k < 100_000) {
                r.seek(off); r.readFully(hdr)
                if (!(hdr[0] == 0x50.toByte() && hdr[1] == 0x4b.toByte() && hdr[2] == 0x01.toByte() && hdr[3] == 0x02.toByte())) return false
                if ((hdr[8].toInt() and 1) != 0) return true
                val n = (hdr[28].toInt() and 0xff) or ((hdr[29].toInt() and 0xff) shl 8)
                val m = (hdr[30].toInt() and 0xff) or ((hdr[31].toInt() and 0xff) shl 8)
                val q = (hdr[32].toInt() and 0xff) or ((hdr[33].toInt() and 0xff) shl 8)
                off += 46L + n + m + q
                k++
            }
        }
        return false
    }

    private const val PW = "This archive is password protected, so it cannot be opened without the password."

    fun archiveList(f: File, max: Int = 300): Archive {
        val ext = f.extension.lowercase()
        return try {
            when (ext) {
                "zip" -> {
                    if (zipEncrypted(f)) return Archive(emptyList(), PW)
                    ZipFile(f).use { z ->
                        val out = ArrayList<Pair<String, Long>>()
                        val en = z.entries()
                        while (en.hasMoreElements() && out.size < max) { val e = en.nextElement(); out.add((if (e.isDirectory) e.name else e.name) to e.size) }
                        Archive(out, null)
                    }
                }
                "7z" -> {
                    val out = ArrayList<Pair<String, Long>>()
                    org.apache.commons.compress.archivers.sevenz.SevenZFile(f).use { z ->
                        var e = z.nextEntry
                        while (e != null && out.size < max) { out.add(e.name to e.size); e = z.nextEntry }
                    }
                    Archive(out, null)
                }
                "rar" -> Archive(emptyList(), "RAR files are not supported yet. zip and 7z are.")
                else -> Archive(emptyList(), "Only zip and 7z archives can be opened here.")
            }
        } catch (e: Exception) {
            val m = (e.message ?: "") + e.javaClass.simpleName
            Archive(emptyList(), if (m.contains("assword", true) || m.contains("ncrypt", true)) PW else "Could not read the archive: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Extracts into dest (a new folder next to the archive when null). Returns the folder, or an error text. */
    fun archiveExtract(c: Context, f: File, dest: File?): Pair<File?, String?> {
        val ext = f.extension.lowercase()
        val out = dest ?: uniqueIn(f.parentFile ?: root(), f.nameWithoutExtension)
        if (!out.exists() && !out.mkdirs()) return null to "Could not create the destination folder."
        val outCanon = out.canonicalPath
        var written = 0L
        val free = out.usableSpace
        fun target(name: String): File? {
            val t = File(out, name)
            return if (t.canonicalPath.startsWith("$outCanon/")) t else null   // no escaping out of the folder
        }
        try {
            when (ext) {
                "zip" -> {
                    if (zipEncrypted(f)) return null to PW
                    ZipFile(f).use { z ->
                        val en = z.entries()
                        while (en.hasMoreElements()) {
                            val e = en.nextElement()
                            val t = target(e.name) ?: continue
                            if (e.isDirectory) { t.mkdirs(); continue }
                            t.parentFile?.mkdirs()
                            z.getInputStream(e).use { ins -> t.outputStream().use { o ->
                                val b = ByteArray(65536); var n = ins.read(b)
                                while (n > 0) { o.write(b, 0, n); written += n; if (written > free - 100_000_000L) return null to "Not enough free space."; n = ins.read(b) }
                            } }
                        }
                    }
                }
                "7z" -> {
                    org.apache.commons.compress.archivers.sevenz.SevenZFile(f).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val t = target(e.name)
                            if (t != null) {
                                if (e.isDirectory) t.mkdirs() else {
                                    t.parentFile?.mkdirs()
                                    t.outputStream().use { o ->
                                        val b = ByteArray(65536); var n = z.read(b)
                                        while (n > 0) { o.write(b, 0, n); written += n; if (written > free - 100_000_000L) return null to "Not enough free space."; n = z.read(b) }
                                    }
                                }
                            }
                            e = z.nextEntry
                        }
                    }
                }
                else -> return null to "Only zip and 7z archives can be opened here."
            }
        } catch (e: Exception) {
            val m = (e.message ?: "") + e.javaClass.simpleName
            return null to (if (m.contains("assword", true) || m.contains("ncrypt", true)) PW else "Could not extract: " + (e.message ?: e.javaClass.simpleName))
        }
        reindex(c, out)
        return out to null
    }

    // ---------------------------------------------------------------- tool declarations and dispatch (the AI)
    fun declarations(): List<JSONObject> = listOf(
        Tools.fn("file_search", "Find files on the phone INSTANTLY from the built-in file index (photos, videos, music, documents, zip / 7z, text, apk, folders). Silent: nothing opens. Use it before any other file tool. Results are newest first.",
            mapOf("query" to ("STRING" to "part of the name, several words allowed; blank = newest files of that kind"),
                "kind" to ("STRING" to "any | photo | video | music | document | text | archive | apk | folder"),
                "folder" to ("STRING" to "optional: only inside this folder"),
                "limit" to ("NUMBER" to "how many, default 8, max 30"))),
        Tools.fn("file_list", "List what is inside a folder (folders first). Path like /sdcard/Download. Default: the main storage.",
            mapOf("path" to ("STRING" to "folder path"))),
        Tools.fn("file_open", "Open a file in the phone's own app for it: photo in the gallery, music in the music player, video in the video player, pdf in the reader. Give the path, OR query (+ kind) to open the best / newest match in one step.",
            mapOf("path" to ("STRING" to "full path"), "query" to ("STRING" to "name words, when no path"), "kind" to ("STRING" to "photo | video | music | document | text | archive | apk"))),
        Tools.fn("file_share", "Send a file to a social app or the share menu. Give the path OR query (+ kind). app = WhatsApp, Telegram, Messenger, Facebook, Instagram, ... (blank = the phone's share menu). With to = the person or group, it also picks that recipient and sends.",
            mapOf("path" to ("STRING" to "full path"), "query" to ("STRING" to "name words, when no path"), "kind" to ("STRING" to "photo | video | music | document | text | archive | apk"),
                "app" to ("STRING" to "target app"), "to" to ("STRING" to "recipient name, optional"))),
        Tools.fn("file_op", "Copy, move, rename, delete or create a folder. action: copy | move | rename | delete | new_folder. path = the file or folder. to = destination folder (copy / move), the NEW NAME only (rename), the new folder name (new_folder, path = parent). delete is for ever: first call WITHOUT user_confirmed (nothing is deleted), ask him in plain words, and only after his yes call again with user_confirmed true.",
            mapOf("action" to ("STRING" to "copy | move | rename | delete | new_folder"), "path" to ("STRING" to "file or folder"), "to" to ("STRING" to "destination / new name"),
                "user_confirmed" to ("BOOLEAN" to "true only after he said yes to this delete")), listOf("action", "path")),
        Tools.fn("file_read", "Read the text inside a text file (txt, md, json, log, srt ...). Returns the first part.",
            mapOf("path" to ("STRING" to "full path"), "max_chars" to ("NUMBER" to "default 3000, max 12000")), listOf("path")),
        Tools.fn("archive", "Look inside or unpack a zip or 7z file that has NO password. action: list | extract. extract puts the files in a new folder next to it (or in to). A password protected archive is reported and cannot be opened.",
            mapOf("action" to ("STRING" to "list | extract"), "path" to ("STRING" to "archive path"), "to" to ("STRING" to "optional destination folder")), listOf("action", "path"))
    )

    val TOOL_NAMES: Set<String> = setOf("file_search", "file_list", "file_open", "file_share", "file_op", "file_read", "archive")

    private fun entryJson(e: Entry): JSONObject =
        JSONObject().put("name", e.name).put("path", e.path).put("kind", e.kind).put("size", if (e.dir) "" else sizeText(e.size)).put("modified", dateText(e.mtime))

    private fun pickFile(c: Context, a: JSONObject): Pair<File?, JSONObject?> {
        val p = a.optString("path", "").trim()
        if (p.isNotEmpty()) {
            val f = resolve(p) ?: return null to err("That path is not in the phone storage.")
            return if (f.exists()) f to null else null to err("That file does not exist.", "Use file_search to find it.")
        }
        val q = a.optString("query", "").trim()
        val kind = a.optString("kind", "any").trim().lowercase().ifEmpty { "any" }
        if (q.isEmpty() && kind == "any") return null to err("Give a path, or a query / kind.")
        val hit = search(c, q, kind, null, 1).firstOrNull { !it.dir } ?: return null to err("No matching file was found.", "Try other words, or call file_search without a query to see the newest files.")
        return File(hit.path) to null
    }

    fun tool(c: Context, name: String, a: JSONObject): JSONObject {
        if (!wanted(c)) return err("File access is switched off. He can switch it on in the Files page of the app.", "Tell him that. Do not try other ways.")
        if (!hasAllFiles(c)) { askPermission(c); return err("The phone's \"All files access\" is not allowed yet. I opened that settings page.", "Tell him to allow it for NISU AI, then ask again.") }
        return try {
            when (name) {
                "file_search" -> {
                    val lim = a.optInt("limit", 8).coerceIn(1, 30)
                    val kind = a.optString("kind", "any").trim().lowercase().ifEmpty { "any" }
                    val folder = a.optString("folder", "").trim().ifEmpty { null }?.let { resolve(it)?.path }
                    val t0 = System.currentTimeMillis()
                    val r = search(c, a.optString("query", ""), kind, folder, lim)
                    val arr = JSONArray(); r.forEach { arr.put(entryJson(it)) }
                    JSONObject().put("ok", true).put("found", r.size).put("files", arr).put("indexed", index.size).put("ms", System.currentTimeMillis() - t0)
                }
                "file_list" -> {
                    val d = resolve(a.optString("path", "").ifBlank { root().path }) ?: return err("That path is not in the phone storage.")
                    if (!d.isDirectory) return err("That is not a folder.")
                    val l = listDir(d.path)
                    val arr = JSONArray(); l.take(60).forEach { arr.put(entryJson(it)) }
                    JSONObject().put("ok", true).put("folder", d.path).put("count", l.size).put("items", arr)
                }
                "file_open" -> {
                    val (f, e) = pickFile(c, a); if (f == null) return e!!
                    if (f.isDirectory) return err("That is a folder. Use file_list.")
                    open(c, f)?.let { return err(it) }
                    JSONObject().put("ok", true).put("opened", f.name).put("path", f.path)
                }
                "file_share" -> {
                    val (f, e) = pickFile(c, a); if (f == null) return e!!
                    if (!f.isFile) return err("Only a single file can be shared.")
                    val uri = uriOf(c, f) ?: return err("Could not prepare the file for sharing.")
                    val app = a.optString("app", "").trim()
                    val to = a.optString("to", "").trim()
                    val def = if (app.isEmpty()) null else ShareRules.findApp(app)
                    val pkg: String? = when {
                        app.isEmpty() -> null
                        def != null -> Apps.installed(c, def.packages) ?: return err(def.label + " is not installed on this phone.")
                        else -> if (UploadGuide.installed(c, app)) app else return err("I do not know an app called \"$app\".", "Ask which app, or call again without app to open the share menu.")
                    }
                    val send = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.type = mimeOf(f); send.putExtra(Intent.EXTRA_STREAM, uri); send.clipData = ClipData.newRawUri("", uri)
                    val intent = if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { c.startActivity(intent) } catch (ex: Exception) { return err("Could not open the share screen: " + (ex.message ?: "")) }
                    val r = JSONObject().put("ok", true).put("file", f.name).put("opened", def?.label ?: pkg ?: "the phone's share menu")
                    if (to.isNotEmpty() && pkg != null) {
                        Thread.sleep(1500)
                        ShareMedia.pick(c, JSONObject().put("to", to)).put("file", f.name)
                    } else r.put("note", "The share screen is open with the file. If he named a person, call share_pick with that name.")
                }
                "file_op" -> {
                    val action = a.optString("action", "").trim().lowercase()
                    val f = resolve(a.optString("path", "")) ?: return err("That path is not in the phone storage.")
                    if (!f.exists()) return err("That does not exist.", "Use file_search to find the right path.")
                    val toS = a.optString("to", "").trim()
                    when (action) {
                        "copy", "move" -> {
                            val dst = resolve(toS) ?: return err("Give the destination folder in to.")
                            if (!dst.isDirectory) return err("The destination must be an existing folder.")
                            copy(c, f, dst, action == "move")?.let { return err(it) }
                            JSONObject().put("ok", true).put("done", action).put("name", f.name).put("into", dst.path)
                        }
                        "rename" -> { rename(c, f, toS)?.let { return err(it) }; JSONObject().put("ok", true).put("done", "rename").put("new_name", toS) }
                        "new_folder" -> {
                            if (!f.isDirectory) return err("path must be the parent folder.")
                            mkdir(c, f, toS)?.let { return err(it) }; JSONObject().put("ok", true).put("done", "new_folder").put("name", toS)
                        }
                        "delete" -> {
                            if (!a.optBoolean("user_confirmed", false)) {
                                val n = if (f.isDirectory) (f.walkTopDown().count() - 1) else 1
                                return JSONObject().put("ok", false).put("needs_confirmation", true)
                                    .put("what", f.name + (if (f.isDirectory) " (folder with $n items)" else " (" + sizeText(f.length()) + ")"))
                                    .put("note", "Nothing is deleted yet. Ask him in plain words whether to delete this for ever and wait for his yes. Only then call file_op again with user_confirmed true.")
                            }
                            delete(c, f)?.let { return err(it) }
                            JSONObject().put("ok", true).put("done", "delete").put("name", f.name)
                        }
                        else -> err("action must be copy, move, rename, delete or new_folder.")
                    }
                }
                "file_read" -> {
                    val f = resolve(a.optString("path", "")) ?: return err("That path is not in the phone storage.")
                    if (!f.isFile) return err("That is not a file.")
                    val s = readText(f, a.optInt("max_chars", 3000)) ?: return err("That is not a text file.")
                    JSONObject().put("ok", true).put("name", f.name).put("text", s).put("truncated", f.length() > s.toByteArray().size)
                }
                "archive" -> {
                    val f = resolve(a.optString("path", "")) ?: return err("That path is not in the phone storage.")
                    if (!f.isFile) return err("That is not a file.")
                    when (a.optString("action", "list").trim().lowercase()) {
                        "extract" -> {
                            val dest = a.optString("to", "").trim().ifEmpty { null }?.let { resolve(it) }
                            val (out, ex) = archiveExtract(c, f, dest)
                            if (out == null) return err(ex ?: "Could not extract.")
                            JSONObject().put("ok", true).put("extracted_to", out.path)
                        }
                        else -> {
                            val ar = archiveList(f)
                            if (ar.error != null) return err(ar.error)
                            val arr = JSONArray(); ar.entries.take(60).forEach { arr.put(it.first + (if (it.second > 0) " (" + sizeText(it.second) + ")" else "")) }
                            JSONObject().put("ok", true).put("entries", arr).put("shown", minOf(60, ar.entries.size)).put("total_listed", ar.entries.size)
                        }
                    }
                }
                else -> err("Unknown file tool.")
            }
        } catch (e: Exception) { err("File error: " + (e.message ?: e.javaClass.simpleName)) }
    }
}
