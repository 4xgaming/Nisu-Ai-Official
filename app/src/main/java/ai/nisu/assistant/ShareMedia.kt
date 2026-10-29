package ai.nisu.assistant

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import org.json.JSONObject

/**
 * Pure rules of [ShareMedia] (no Android classes, unit tested on the JVM).
 */
object ShareRules {
    class AppDef(val key: String, val label: String, val aliases: List<String>, val packages: List<String>, val chat: Boolean)

    val APPS = listOf(
        AppDef("whatsapp", "WhatsApp", listOf("whatsapp", "whats app", "wp", "wa", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA\u09C7", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u090F\u09AA", "\u0993\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA", "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u0910\u092A", "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u090F\u092A"), listOf("com.whatsapp", "com.whatsapp.w4b"), true),
        AppDef("telegram", "Telegram", listOf("telegram", "tg", "\u099F\u09C7\u09B2\u09BF\u0997\u09CD\u09B0\u09BE\u09AE", "\u099F\u09C7\u09B2\u09BF\u0997\u09CD\u09B0\u09BE\u09AE\u09C7", "\u091F\u0947\u0932\u0940\u0917\u094D\u0930\u093E\u092E"), listOf("org.telegram.messenger", "org.telegram.messenger.web"), true),
        AppDef("messenger", "Messenger", listOf("messenger", "\u09AE\u09C7\u09B8\u09C7\u099E\u09CD\u099C\u09BE\u09B0", "\u09AE\u09C7\u09B8\u09C7\u099E\u09CD\u099C\u09BE\u09B0\u09C7", "\u092E\u0948\u0938\u0947\u0902\u091C\u0930"), listOf("com.facebook.orca", "com.facebook.mlite"), true),
        AppDef("signal", "Signal", listOf("signal"), listOf("org.thoughtcrime.securesms"), true),
        AppDef("imo", "imo", listOf("imo", "\u0987\u09AE\u09CB"), listOf("com.imo.android.imoim", "com.imo.android.imoimbeta"), true),
        AppDef("facebook", "Facebook", listOf("facebook", "fb", "\u09AB\u09C7\u09B8\u09AC\u09C1\u0995", "\u09AB\u09C7\u09B8\u09AC\u09C1\u0995\u09C7"), listOf("com.facebook.katana", "com.facebook.lite"), false),
        AppDef("instagram", "Instagram", listOf("instagram", "insta", "ig", "\u0987\u09A8\u09B8\u09CD\u099F\u09BE\u0997\u09CD\u09B0\u09BE\u09AE"), listOf("com.instagram.android"), false),
        AppDef("snapchat", "Snapchat", listOf("snapchat", "snap"), listOf("com.snapchat.android"), false),
        AppDef("twitter", "X", listOf("twitter", "x.com"), listOf("com.twitter.android"), false),
        AppDef("gmail", "Gmail", listOf("gmail", "email", "mail", "\u099C\u09BF\u09AE\u09C7\u0987\u09B2"), listOf("com.google.android.gm"), false),
        AppDef("drive", "Google Drive", listOf("drive", "google drive", "gdrive"), listOf("com.google.android.apps.docs"), false)
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** "WhatsApp", "wp", "whatsapp e" ... to the app. Short aliases (wp, wa, x, tg, ig) must match the whole word. */
    fun findApp(raw: String): AppDef? {
        val t = norm(raw)
        if (t.isEmpty()) return null
        APPS.firstOrNull { it.key == t || it.aliases.any { a -> a == t } }?.let { return it }
        return APPS.firstOrNull { d -> d.aliases.any { a -> a.length >= 4 && t.contains(a) } }
    }

    /** "my status", "whatsapp status" ... mean the WhatsApp Status, not a person. */
    fun isStatusTarget(to: String): Boolean {
        val t = norm(to)
        return t in listOf("status", "my status", "whatsapp status", "wa status", "\u09B8\u09CD\u099F\u09CD\u09AF\u09BE\u099F\u09BE\u09B8", "\u09AE\u09BE\u0987 \u09B8\u09CD\u099F\u09CD\u09AF\u09BE\u099F\u09BE\u09B8", "\u0938\u094D\u091F\u0947\u091F\u0938")
    }

    sealed class Pick {
        object None : Pick()
        class Exact(val label: String) : Pick()
        class One(val label: String) : Pick()
        class Many(val labels: List<String>) : Pick()
    }

    /** Which of the visible labels is the person / group / channel he named? Exact wins, one partial match is taken, several partial matches must be asked. */
    fun pick(query: String, labels: List<String>): Pick {
        val q = norm(query)
        if (q.isEmpty()) return Pick.None
        val cands = labels.map { it.trim() }.filter { it.isNotEmpty() && norm(it).contains(q) }.distinctBy { norm(it) }
        if (cands.isEmpty()) return Pick.None
        cands.firstOrNull { norm(it) == q }?.let { return Pick.Exact(it) }
        return if (cands.size == 1) Pick.One(cands[0]) else Pick.Many(cands.take(6))
    }

    /** "3 minutes ago" for the answer, so the model can check it is the file he means. */
    fun ageText(addedSec: Long, nowSec: Long): String {
        val d = (nowSec - addedSec).coerceAtLeast(0)
        return when {
            d < 90 -> "$d seconds ago"
            d < 5400 -> "${d / 60} minutes ago"
            d < 129600 -> "${d / 3600} hours ago"
            else -> "${d / 86400} days ago"
        }
    }

    val SEND_LABELS = listOf("Send", "Send message", "Share", "Done", "OK", "\u09AA\u09BE\u09A0\u09BE\u09A8")
    val SEARCH_LABELS = listOf("Search", "Search\u2026", "Search...", "\u09B8\u09BE\u09B0\u09CD\u099A")
    val STATUS_LABELS = listOf("My status", "My Status", "Status (Contacts)", "Add to status", "Status")
}

/**
 * v3.57: share_media / share_pick. He (she) generated or took a photo or video and says "send it to Rahim on WhatsApp", "share it to the
 * family group", "post it on my status", "share it" ...: this finds the file (the newest photo or video, or a named one, or one he shared to
 * NISU AI), opens that app's Share screen with it, then finds the person, group or channel by name in the app's own picker and presses Send.
 * It never guesses between two people with similar names (it returns the choices), and it says honestly what it saw.
 */
object ShareMedia {
    const val NAME = "share_media"
    const val NAME_PICK = "share_pick"

    private class Media(val uri: Uri, val name: String, val mime: String, val video: Boolean, val addedSec: Long)

    private fun err(msg: String, fix: String? = null) = JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** The voice prompt block. No dollar signs here: it is a const. */
    const val NOTE = """
SHARING PHOTOS AND VIDEOS (share_media): you CAN share a photo or video, to WhatsApp and every other share option, and to a person, group or channel he names. Never say you cannot share. When he asks you to share / send / post a photo or video he made or has (a picture you just made, a video, a screenshot, "this", "the last one"), do the whole job with share_media and he only talks. (1) Say where it goes: app = WhatsApp / Telegram / Messenger / Instagram / Facebook / Gmail / Drive ... and to = the person, group or channel name exactly as he said it ("status" for the WhatsApp Status). If he named no app, call share_media without app: the phone's share menu opens, read it with get_screen and press the app he chose. If he named an app but no person, ask him ONE short question: to whom. (2) share_media finds the newest photo or video (file_id for one he shared to you with shared_files, or file = part of a file name; kind = photo or video when he said which), opens the share screen, finds the name in the app's own list and presses Send. A caption is only what he said (caption). (3) The result tells you what happened: sent_pressed true and likely_sent true = say you sent it; if it asks you to choose between several names (ambiguous), read him the names and let him pick, then call share_pick with the exact name; never guess between two people. If it says not found, no recent file or no permission, tell him plainly. Check file_name and file_age in the result: if the newest file is not the one he means (too old), say so and ask. (4) A picture you made inside ChatGPT (make_image) is only inside the ChatGPT app until it is saved: open the picture, press its Save / Download button with click_text, wait a moment, then call share_media; or press the app's own Share button on the screen and pick the app. Say honestly if you could not find the button. Share only what he asked, to whom he named, once.
"""

    private fun newestMedia(ctx: Context, kind: String, nameLike: String?): Media? {
        val cr = ctx.contentResolver
        val files = MediaStore.Files.getContentUri("external")
        val typeSel = when (kind) {
            "photo" -> "media_type=1"
            "video" -> "media_type=3"
            else -> "(media_type=1 OR media_type=3)"
        }
        val args = ArrayList<String>()
        var sel = typeSel
        if (!nameLike.isNullOrBlank()) { sel += " AND _display_name LIKE ?"; args.add("%" + nameLike.trim() + "%") }
        val q = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val proj = arrayOf("_id", "_display_name", "mime_type", "date_added", "media_type")
        cr.query(files, proj, q, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val name = c.getString(1) ?: "file"
            val mime = c.getString(2) ?: "image/*"
            val added = c.getLong(3)
            val video = c.getInt(4) == 3
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            return Media(ContentUris.withAppendedId(base, id), name, mime, video, added)
        }
        return null
    }

    private fun fromShared(ctx: Context, id: Int): Media? {
        val item = SharedUploads.get(ctx, id) ?: return null
        val uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileshare", item.file) } catch (_: Exception) { return null }
        return Media(uri, item.name, item.mime, item.video, item.file.lastModified() / 1000L)
    }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val app = a.optString("app", "").trim()
        val to = a.optString("to", "").trim()
        val caption = a.optString("caption", "").trim()
        val text = a.optString("text", "").trim()
        val kind = a.optString("kind", "any").trim().lowercase().let { if (it == "photo" || it == "video") it else "any" }
        val sendIt = if (a.has("send")) a.optBoolean("send", true) else true
        val acc = NisuAccessibilityService.inst
        if ((to.isNotEmpty() || app.isEmpty()) && acc == null) return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")

        // 1) which app (a known one, any installed package, or none = the phone's own share menu)
        val def = if (app.isEmpty()) null else ShareRules.findApp(app)
        val pkg: String? = when {
            app.isEmpty() -> null
            def != null -> Apps.installed(ctx, def.packages)
                ?: return err(def.label + " is not installed on this phone.", "Tell him. Offer to open the Play Store page to install it.")
            else -> if (UploadGuide.installed(ctx, app)) app
                else return err("I do not know an app called \"$app\" and no package with that name is installed.", "Ask him which app, or call share_media without app so the phone's share menu opens.")
        }

        // 2) what to share: a text, or a photo / video (named, one he shared to NISU AI, or the newest)
        val send = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var fileName = ""
        var fileAge = ""
        var fileKind = "text"
        if (a.optString("file", "").trim().equals("none", true) && text.isNotEmpty()) {
            send.type = "text/plain"; send.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val id = if (a.has("file_id")) a.optInt("file_id", 0) else 0
            val nameLike = a.optString("file", "").trim().let { if (it.isEmpty() || it.equals("latest", true) || it.equals("newest", true)) null else it }
            val m: Media? = try {
                (if (id > 0) fromShared(ctx, id) else null) ?: newestMedia(ctx, kind, nameLike)
            } catch (e: SecurityException) {
                return err("NISU AI is not allowed to read photos and videos on this phone, so it cannot find the file.",
                    "Tell him to open the phone Settings > Apps > NISU AI > Permissions and allow Photos and videos, then ask again.")
            } catch (e: Exception) { return err("Could not look in the gallery: " + (e.message ?: "")) }
            if (m == null) return err(if (nameLike != null) "No photo or video with \"$nameLike\" in its name was found." else "No photo or video was found on the phone.",
                "If the picture is only inside an app (for example ChatGPT), open it and press that app's Save / Download button first, then call share_media again.")
            send.type = m.mime
            send.putExtra(Intent.EXTRA_STREAM, m.uri)
            send.clipData = ClipData.newRawUri("", m.uri)
            if (caption.isNotEmpty() || text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, if (caption.isNotEmpty()) caption else text)
            fileName = m.name; fileKind = if (m.video) "video" else "photo"
            fileAge = ShareRules.ageText(m.addedSec, System.currentTimeMillis() / 1000L)
        }

        // 3) open the app's share screen (or the phone's share menu)
        val intent = if (pkg != null) send.setPackage(pkg) else Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) {
            return err("Could not open the share screen" + (if (def != null) " of " + def.label else "") + ": " + (e.message ?: ""))
        }
        val r = JSONObject().put("ok", true).put("opened", def?.label ?: pkg ?: "the phone's share menu")
            .put("file_name", fileName).put("file_kind", fileKind).put("file_age", fileAge)

        // 4) no app or no name: leave the screen open for him / the model
        if (pkg == null) {
            acc?.awaitStable(300, 3000)
            return r.put("sent_pressed", false).put("screen", acc?.dumpScreen(80) ?: "")
                .put("note", "The phone's share menu is open with the file. Read the screen, press the app he chose with click_text, then call share_pick with the person's name (or press Send yourself).")
        }
        if (to.isEmpty()) {
            acc?.awaitStable(400, 3500)
            return r.put("sent_pressed", false).put("screen", acc?.dumpScreen(80) ?: "")
                .put("note", "The app's share screen is open with the file. Ask him to whom it should go (one short question), then call share_pick with that name.")
        }
        acc!!.awaitStable(500, 4000)
        sleep(300)
        return pickAndSend(acc, to, sendIt, r)
    }

    /** v3.126: [VoiceMessage]: opens the chat app's Share screen with a ready audio file, picks the person / group by name and presses Send. */
    fun shareAudio(ctx: Context, file: java.io.File, mime: String, app: String, to: String): JSONObject {
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")
        val def = ShareRules.findApp(app)
        val pkg: String = if (def != null) (Apps.installed(ctx, def.packages)
                ?: return err(def.label + " is not installed on this phone.", "Tell him. Offer to open the Play Store page to install it."))
            else if (UploadGuide.installed(ctx, app)) app
            else return err("I do not know an app called \"$app\" and no package with that name is installed.", "Ask him which chat app.")
        val uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileshare", file) }
            catch (e: Exception) { return err("The voice file could not be prepared for sharing: " + (e.message ?: "").take(80)) }
        val send = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.type = mime
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri("", uri)
        send.setPackage(pkg)
        try { ctx.startActivity(send) } catch (e: Exception) {
            return err("Could not open the share screen" + (if (def != null) " of " + def.label else "") + ": " + (e.message ?: "").take(80))
        }
        val r = JSONObject().put("ok", true).put("opened", def?.label ?: pkg).put("file_kind", "voice message")
        acc.awaitStable(600, 4500)
        sleep(400)
        return pickAndSend(acc, to, true, r)
    }

    /** share_pick: the share screen is already open (after the phone's share menu or a question to him): choose the recipient and send. */
    fun pick(ctx: Context, a: JSONObject): JSONObject {
        val to = a.optString("to", "").trim()
        if (to.isEmpty()) return err("Say to whom: give the person, group or channel name in to.")
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")
        val sendIt = if (a.has("send")) a.optBoolean("send", true) else true
        acc.awaitStable(250, 2000)
        return pickAndSend(acc, to, sendIt, JSONObject().put("ok", true))
    }

    private fun labels(acc: NisuAccessibilityService): List<String> {
        val root = acc.root(400) ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isEditable) {
                n.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
                n.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        try { walk(root) } catch (_: Exception) {}
        return out
    }

    /** Clicks the label (whole-label match first, then a looser text match). */
    private fun press(acc: NisuAccessibilityService, label: String): Boolean =
        acc.clickExact(listOf(label)) != null || acc.clickText(label, 400)

    private fun pickAndSend(acc: NisuAccessibilityService, to: String, sendIt: Boolean, r: JSONObject): JSONObject {
        var chosen = ""
        if (ShareRules.isStatusTarget(to)) {
            val s = acc.clickExact(ShareRules.STATUS_LABELS)
                ?: return r.put("ok", false).put("sent_pressed", false).put("error", "The Status option was not found on this share screen.").put("screen", acc.dumpScreen(80))
            chosen = s
        } else {
            // a) maybe the name is already in the visible list; b) else use the app's own search
            var p = ShareRules.pick(to, labels(acc))
            if (p is ShareRules.Pick.None) {
                val opened = acc.clickExact(ShareRules.SEARCH_LABELS) != null || acc.clickText("Search", 400)
                if (opened) {
                    acc.awaitStable(250, 1500)
                    val box = acc.root(500)?.let { acc.findEditable(it) }
                    if (box != null && acc.setNodeText(box, to)) {
                        sleep(700); acc.awaitStable(250, 2000)
                        p = ShareRules.pick(to, labels(acc))
                    }
                }
            }
            when (p) {
                is ShareRules.Pick.None -> return r.put("ok", false).put("sent_pressed", false).put("found", false)
                    .put("error", "Nobody, no group and no channel named \"$to\" was found in the share list, so nothing was sent.")
                    .put("fix", "Tell him. Ask him to spell the name, or to say it the way it is saved on the phone, then call share_pick again.")
                    .put("screen", acc.dumpScreen(80))
                is ShareRules.Pick.Many -> return r.put("ok", true).put("sent_pressed", false).put("ambiguous", org.json.JSONArray(p.labels))
                    .put("note", "Several names match \"$to\". NOTHING was sent. Read the names to him, let him choose, then call share_pick with the exact name.")
                    .put("screen", acc.dumpScreen(60))
                is ShareRules.Pick.Exact -> chosen = p.label
                is ShareRules.Pick.One -> chosen = p.label
            }
            if (!press(acc, chosen))
                return r.put("ok", false).put("sent_pressed", false).put("error", "\"$chosen\" is on the screen but pressing it did not work, so nothing was sent.").put("screen", acc.dumpScreen(80))
        }
        r.put("chosen", chosen)
        acc.awaitStable(300, 2500)
        sleep(300)
        if (!sendIt) return r.put("sent_pressed", false).put("screen", acc.dumpScreen(80))
            .put("note", "\"$chosen\" is selected and the file is ready; Send was NOT pressed. Ask him to confirm, then press Send with click_text.")

        // send (WhatsApp shows a green arrow with the label Send; Telegram asks once more in a small box)
        val pressed = acc.clickExact(ShareRules.SEND_LABELS)
        if (pressed == null) return r.put("ok", false).put("sent_pressed", false)
            .put("error", "\"$chosen\" is selected but no Send button was found, so it is NOT sent.")
            .put("fix", "Use get_screen, find the send arrow and press it with click_text, or tell him.").put("screen", acc.dumpScreen(80))
        sleep(900)
        acc.awaitStable(300, 2500)
        // Telegram: the small confirmation box asks again
        if (acc.hasExact(listOf("Send")) && acc.hasExact(listOf("Cancel"))) { acc.clickExact(listOf("Send")); sleep(700); acc.awaitStable(250, 2000) }
        val still = acc.hasExact(listOf("Send", "Send message"))
        val screen = acc.dumpScreen(60)
        AppState.add("share_media: sent to " + chosen)
        return r.put("sent_pressed", true).put("likely_sent", !still)
            .put("screen", screen)
            .put("note", if (!still) "Send was pressed and the share screen is gone: say you sent it to \"$chosen\"; you cannot see the delivery tick, so do not promise it was read."
                else "Send was pressed but a Send button is still on the screen: it may not have gone. Check the screen before you say it is sent.")
    }
}
