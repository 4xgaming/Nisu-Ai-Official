package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Package names of the chat apps NISU AI knows about. */
object Apps {
    val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")
    val MESSENGER = listOf("com.facebook.orca", "com.facebook.mlite")

    fun installed(ctx: Context, pkgs: List<String>): String? = pkgs.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }
    fun isChat(pkg: String?): Boolean { val p = pkg ?: return false; return p in WHATSAPP || p in MESSENGER }
    fun label(pkg: String?): String { val p = pkg ?: return "?"; return if (p in WHATSAPP) "WhatsApp" else if (p in MESSENGER) "Messenger" else p }
    fun byName(name: String): List<String>? {
        val n = name.lowercase()
        return when { "whats" in n -> WHATSAPP; "messenger" in n || "facebook" in n || n == "fb" -> MESSENGER; else -> null }
    }
}

/**
 * Reliable reading and sending in WhatsApp / Messenger through the accessibility tree.
 * Every step waits for the screen to settle and every send is verified, so results can be reported honestly.
 */
object Chat {
    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    // ---------- small node helpers ----------
    private fun rect(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, f) }
    }
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
    private fun digits(s: String) = s.filter { it.isDigit() }
    private fun screenH(acc: NisuAccessibilityService) = acc.resources.displayMetrics.heightPixels

    /** The message box: the lowest visible editable field in the bottom half of the screen. */
    private fun composer(root: AccessibilityNodeInfo, h: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        walk(root) { n ->
            if (n.isEditable && n.isVisibleToUser && !n.isPassword) {
                val t = rect(n).top
                if (t > h * 0.45 && t > bestTop) { best = n; bestTop = t }
            }
        }
        return best
    }

    private fun composerText(n: AccessibilityNodeInfo?): String =
        if (n == null || n.isShowingHintText) "" else n.text?.toString().orEmpty()

    private fun largestScrollable(root: AccessibilityNodeInfo, h: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var area = 0L
        walk(root) { n ->
            if (n.isScrollable && n.isVisibleToUser) {
                val r = rect(n)
                val a = r.width().toLong() * r.height()
                if (r.height() > h * 0.3 && a > area) { best = n; area = a }
            }
        }
        return best
    }

    // ---------- reading ----------
    class Msg(var who: String, val text: String, var time: String = "")   // who: me | them | ? | date
    class Page(val msgs: List<Msg>, val title: String, val hasComposer: Boolean)
    private class Raw(val top: Int, val left: Int, val right: Int, val text: String)

    private val TIME = Regex("""^\d{1,2}[:.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?$""")
    private val DATE = Regex(
        """^(today|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d{1,2}\s\w+\s\d{4}|\w+\s\d{1,2},?\s\d{4}|\d{1,2}/\d{1,2}/\d{2,4})$""",
        RegexOption.IGNORE_CASE)
    private val STATUS = setOf("read", "delivered", "sent", "pending", "seen", "sending", "message sent",
        "message delivered", "message read", "message seen", "starred")
    private val CHROME = setOf("scroll to bottom", "back", "navigate up", "more options", "attach", "camera", "emoji",
        "gif", "sticker", "voice message", "voice call", "video call", "search", "menu", "open photo gallery",
        "like", "microphone", "record voice message", "add", "call", "video chat", "audio call", "info", "type a message", "message")
    private val HEAD_SKIP = CHROME + setOf("settings", "conversation information", "chat info", "profile photo")

    private fun readOnce(acc: NisuAccessibilityService, root: AccessibilityNodeInfo, deep: Boolean): Page {
        val dm = acc.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels; val d = dm.density
        val comp = composer(root, h)
        val composerTop = comp?.let { rect(it).top } ?: h
        val headerBottom = (92 * d).toInt()
        val body = ArrayList<Raw>()
        val head = ArrayList<Raw>()
        walk(root) { n ->
            if (!n.isVisibleToUser || n.isEditable || n.isPassword) return@walk
            val r = rect(n)
            if (r.width() <= 0 || r.height() <= 0) return@walk
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                txt.isNotEmpty() -> txt
                desc.isNotEmpty() && (n.childCount == 0 || deep) -> desc
                else -> return@walk
            }
            if (r.top < headerBottom) { head.add(Raw(r.top, r.left, r.right, label)); if (r.bottom <= headerBottom) return@walk }
            if (r.top >= composerTop || r.bottom <= headerBottom) return@walk
            body.add(Raw(r.top, r.left, r.right, label))
        }
        body.sortWith(compareBy({ it.top }, { it.left }))
        head.sortWith(compareBy({ it.top }, { it.left }))
        val title = head.firstOrNull { it.text.length >= 2 && it.text.lowercase() !in HEAD_SKIP && !TIME.matches(it.text) }?.text.orEmpty()

        val out = ArrayList<Msg>()
        val tol = (16 * d).toInt()
        for (r in body) {
            val t = r.text
            val low = t.lowercase()
            when {
                low in CHROME || "end-to-end encrypted" in low -> continue
                TIME.matches(t) -> out.lastOrNull()?.let { if (it.who != "date" && it.time.isEmpty()) it.time = t }
                low in STATUS -> out.lastOrNull()?.let { if (it.who != "date") it.who = "me" }
                t.length <= 24 && DATE.matches(t) -> out.add(Msg("date", t))
                else -> {
                    val last = out.lastOrNull()
                    if (last != null && last.who != "date" && last.text == t) continue   // same text exposed twice by nested nodes
                    val lg = r.left; val rg = w - r.right
                    val who = if (rg + tol < lg) "me" else if (lg + tol < rg) "them" else "?"
                    out.add(Msg(who, t))
                }
            }
        }
        return Page(out, title, comp != null)
    }

    private fun sig(m: Msg) = m.who + "|" + m.text

    /** Joins two overlapping screenfuls (older first) without duplicating the overlap. */
    private fun merge(older: List<Msg>, newer: List<Msg>): List<Msg> {
        val max = minOf(older.size, newer.size)
        for (k in max downTo 1) {
            if ((0 until k).all { sig(older[older.size - k + it]) == sig(newer[it]) }) return older + newer.drop(k)
        }
        return older + Msg("date", "…") + newer   // no overlap found: something is missing in between
    }

    private fun line(m: Msg): String {
        val who = when (m.who) { "me" -> "ME"; "them" -> "THEM"; "date" -> "---"; else -> "?" }
        val t = m.text.take(600)
        return if (m.who == "date") "--- $t ---" else "$who: $t" + (if (m.time.isNotEmpty()) "  (${m.time})" else "")
    }

    private fun readPage(acc: NisuAccessibilityService, root: AccessibilityNodeInfo): Page {
        val p = readOnce(acc, root, false)
        return if (p.msgs.isEmpty() && p.hasComposer) readOnce(acc, root, true) else p
    }

    /** Reads the open chat. pages = how many extra screens to scroll up for older context (0-3). */
    fun read(acc: NisuAccessibilityService, pages: Int): JSONObject {
        acc.awaitStable(250, 2500)
        val root = acc.root() ?: return err("No active window. Is the screen on and unlocked?")
        val app = Apps.label(root.packageName?.toString())
        val first = readPage(acc, root)
        if (!first.hasComposer)
            return err("No open chat found in $app (no message box on screen). Use open_chat first, or get_screen to see where you are.")
        val h = screenH(acc)
        val shots = arrayListOf(first)
        var scrolled = 0
        for (i in 0 until pages.coerceIn(0, 3)) {
            val list = largestScrollable(acc.root() ?: break, h) ?: break
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
            scrolled++
            acc.awaitStable(300, 2500)
            shots.add(readPage(acc, acc.root() ?: break))
        }
        repeat(scrolled) {   // put the chat back where it was
            acc.root()?.let { r -> largestScrollable(r, h)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            acc.awaitStable(200, 1500)
        }
        var all: List<Msg> = shots.last().msgs
        for (i in shots.size - 2 downTo 0) all = merge(all, shots[i].msgs)
        all = all.takeLast(45)
        if (all.none { it.who != "date" }) return err("The chat screen is open but no message text could be read. Use get_screen and read it manually.")

        val unanswered = ArrayList<String>()
        for (m in all.reversed()) { if (m.who == "me") break; if (m.who != "date") unanswered.add(0, m.text.take(600)) }
        val o = JSONObject().put("ok", true).put("app", app).put("chat_title", first.title)
            .put("thread", JSONArray(all.map { line(it) }))
            .put("unanswered_from_them", JSONArray(unanswered))
            .put("how_to_read", "ME = sent by the user, THEM = sent by the other person, ? = direction unsure (judge from content). Oldest first, newest last.")
        if (all.any { it.who == "?" }) o.put("warning", "Some lines have unsure direction; do not assume who wrote them.")
        return o
    }

    /** Last few messages, used to double-check a send. */
    private fun tail(acc: NisuAccessibilityService, n: Int): JSONArray {
        return try {
            val r = acc.root(800) ?: return JSONArray()
            JSONArray(readPage(acc, r).msgs.filter { it.who != "date" }.takeLast(n).map { line(it) })
        } catch (_: Exception) { JSONArray() }
    }

    // ---------- sending ----------
    private val SEND_LABELS = setOf("send", "send message", "send button", "পাঠান", "পাঠাও", "भेजें", "भेजे", "enviar", "envoyer", "senden", "invia", "kirim", "gönder")

    private fun findSend(root: AccessibilityNodeInfo, h: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1
        walk(root) { n ->
            if (n.isVisibleToUser && !n.isEditable) {
                val r = rect(n)
                if (r.centerY() > h * 0.4 && r.width() > 0) {
                    val id = (n.viewIdResourceName ?: "").lowercase()
                    val lbl = ((n.contentDescription ?: n.text)?.toString() ?: "").trim().lowercase()
                    var s = 0
                    if (id.endsWith("/send") || id.contains("send_btn") || id.contains("send_button")) s += 3
                    if (lbl in SEND_LABELS) s += 2
                    if (s > 0 && (n.isClickable || n.isEnabled)) { if (n.isClickable) s += 1; if (s > bestScore) { best = n; bestScore = s } }
                }
            }
        }
        return best
    }

    /** Types [text] into the open chat's message box and presses Send, then verifies the box emptied. Sends exactly once. */
    fun send(ctx: Context, acc: NisuAccessibilityService, text: String): JSONObject {
        if (text.isBlank()) return err("The message is empty.")
        acc.awaitStable(250, 2500)
        val h = screenH(acc)
        val root = acc.root() ?: return err("No active window. Is the screen on and unlocked?")
        var box = composer(root, h) ?: return err("No message box on screen. Open the chat first with open_chat.")

        // 1) put the text in (replacing any old draft), verify it really is there
        var typed = false
        if (acc.setNodeText(box, text)) {
            Thread.sleep(350)
            box.refresh()
            typed = norm(composerText(box)) == norm(text)
        }
        if (!typed) {   // fallback: select all + paste from clipboard
            Clip.set(ctx, text)
            box = acc.root()?.let { composer(it, h) } ?: box
            box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val b = Bundle()
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, box.text?.length ?: 0)
            box.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b)
            box.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Thread.sleep(400)
            box.refresh()
            typed = norm(composerText(box)) == norm(text)
        }
        if (!typed) return err("Could not put the text into the message box, so NOTHING was sent. The box currently holds: '${composerText(box).take(80)}'.")

        // 2) find and press Send (the button only appears once there is text)
        acc.awaitStable(200, 1500)
        val root2 = acc.root() ?: return err("Lost the window after typing. Nothing was sent.")
        val btn = findSend(root2, h)
            ?: return err("Typed the text but found no Send button, so it was NOT sent. Check with get_screen.").put("screen", acc.dumpScreen(40))
        if (!acc.clickNode(btn)) return err("Could not press the Send button. The text is still in the box and was NOT sent.")

        // 3) verify: the message box must empty out
        val end = SystemClock.uptimeMillis() + 3500
        var cleared = false
        var inChat = true
        while (SystemClock.uptimeMillis() < end) {
            Thread.sleep(250)
            val r = acc.root() ?: continue
            val c = composer(r, h)
            if (c == null) { inChat = false; break }
            if (composerText(c).isBlank()) { cleared = true; break }
        }
        val o = JSONObject().put("ok", cleared).put("sent", if (cleared) true else "unknown")
        if (cleared) { Thread.sleep(300); o.put("last_messages", tail(acc, 3)) }
        else o.put("error", if (inChat) "Pressed Send but the text is still in the box; it probably was NOT sent. Do not press send again blindly; check with read_chat."
                            else "Pressed Send but the chat screen changed; verify with read_chat before saying it was sent.")
        return o
    }

    // ---------- opening a chat ----------
    private fun resolveContact(ctx: Context, q: String): JSONObject {
        val r = Device.findContact(ctx, q)
        if (r.has("error")) return r
        val arr = r.optJSONArray("contacts") ?: JSONArray()
        if (arr.length() == 0) return JSONObject().put("error", "No saved contact matches '$q'.")
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
        val distinct = rows.map { digits(it.optString("number")) }.distinct()
        if (distinct.size == 1) return JSONObject().put("number", rows[0].optString("number")).put("name", rows[0].optString("name"))
        val exact = rows.filter { it.optString("name").equals(q, true) }
        if (exact.map { digits(it.optString("number")) }.distinct().size == 1)
            return JSONObject().put("number", exact[0].optString("number")).put("name", exact[0].optString("name"))
        return JSONObject().put("ambiguous", arr)
    }

    /** Number in the international format wa.me wants (digits only, country code first), or null if the country is unknown. */
    private fun waNumber(raw: String, cc: String): String? {
        val t = raw.trim()
        val d = digits(t)
        if (d.length < 7) return null
        if (t.startsWith("+")) return d
        if (d.startsWith("00")) return d.drop(2)
        val c = digits(cc)
        if (d.startsWith("0")) return if (c.isNotEmpty()) c + d.trimStart('0') else null
        return if (c.isNotEmpty() && !d.startsWith(c) && d.length <= 10) c + d else d
    }

    private fun title(acc: NisuAccessibilityService): String =
        try { acc.root(800)?.let { readOnce(acc, it, false).title }.orEmpty() } catch (_: Exception) { "" }

    private fun inChat(acc: NisuAccessibilityService): Boolean =
        acc.root(800)?.let { composer(it, screenH(acc)) != null } == true

    /**
     * Opens a chat with [contact] in WhatsApp or Messenger. WhatsApp with a resolvable number uses a direct link
     * (no screen navigation, far fewer errors); everything else uses the app's own search. [text] is only pre-filled, never sent.
     */
    fun open(ctx: Context, acc: NisuAccessibilityService?, app: String, contact: String, text: String?): JSONObject {
        val pkgs = Apps.byName(app) ?: return err("Unknown app '$app'. Use WhatsApp or Messenger.")
        val pkg = Apps.installed(ctx, pkgs) ?: return err("${pkgs.firstOrNull()?.let { Apps.label(it) }} is not installed.")
        val who = contact.trim()
        if (who.isEmpty()) return err("Who should I open the chat with?")

        if (pkgs === Apps.WHATSAPP) {
            var num: String? = null
            var shown = who
            if (digits(who).length >= 7) num = who
            else {
                val c = resolveContact(ctx, who)
                if (c.has("ambiguous")) return err("More than one contact matches '$who'. Ask the user which one.").put("candidates", c.get("ambiguous"))
                if (c.has("number")) { num = c.getString("number"); shown = c.optString("name", who) }
            }
            if (num != null) {
                val intl = waNumber(num, Prefs.get(ctx, Prefs.COUNTRY, ""))
                    ?: return err("I need the country code for that number. Ask the user for it, or set 'Country code' in NISU AI settings.")
                val q = if (!text.isNullOrBlank()) "?text=" + Uri.encode(text) else ""
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl$q")).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(i) } catch (e: Exception) { return err("Could not open WhatsApp: ${e.message}") }
                if (acc == null) return JSONObject().put("ok", true).put("via", "direct_link").put("note", "Accessibility is off, so I cannot verify the chat opened.")
                Thread.sleep(700); acc.awaitStable(500, 5000)
                val seen = acc.visibleText(1500).lowercase()
                if ("not on whatsapp" in seen || "isn't on whatsapp" in seen || "invalid" in seen && "phone number" in seen)
                    return err("$shown does not seem to be on WhatsApp (WhatsApp said so).")
                return if (inChat(acc)) JSONObject().put("ok", true).put("via", "direct_link").put("chat_title", title(acc))
                    .put("prefilled", !text.isNullOrBlank()).put("note", "Chat is open. Nothing has been sent yet.")
                else err("WhatsApp opened but not on the chat screen. Use get_screen to see what is showing.")
            }
        }
        if (who.lowercase().startsWith("m.me/") && pkgs === Apps.MESSENGER) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$who")).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { return err("Could not open Messenger: ${e.message}") }
            acc?.awaitStable(600, 5000)
            return JSONObject().put("ok", acc?.let { inChat(it) } ?: true).put("via", "direct_link").put("chat_title", acc?.let { title(it) } ?: "")
        }
        if (acc == null) return err("Phone control (Accessibility) is OFF, so I cannot search for the chat. Ask the user to enable it.")
        return openViaSearch(ctx, acc, pkg, who, text)
    }

    private fun openViaSearch(ctx: Context, acc: NisuAccessibilityService, pkg: String, name: String, text: String?): JSONObject {
        val h = screenH(acc)
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return err("Cannot launch ${Apps.label(pkg)}.")
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(800); acc.awaitStable(500, 5000)
        var backs = 0
        while (inChat(acc) && backs < 2) { acc.key("back"); backs++; acc.awaitStable(400, 2500) }   // get to the chat list

        // open the search field
        val root = acc.root() ?: return err("The app window did not appear.")
        var field: AccessibilityNodeInfo? = null
        walk(root) { n -> if (field == null && n.isEditable && n.isVisibleToUser && rect(n).top < h * 0.3) field = n }
        if (field == null) {
            var s: AccessibilityNodeInfo? = null
            walk(root) { n ->
                val l = acc.labelOf(n).lowercase()
                if (s == null && n.isVisibleToUser && rect(n).top < h * 0.3 && (l == "search" || l.startsWith("search ") || l == "ask meta ai or search")) s = n
            }
            val sn = s ?: return err("Could not find the search box in ${Apps.label(pkg)}. Use get_screen and click_text manually.")
            acc.clickNode(sn); acc.awaitStable(400, 2500)
            field = acc.root()?.let { acc.findEditable(it) }
        }
        val f = field ?: return err("Search box did not open. Use get_screen and try manually.")
        if (!acc.setNodeText(f, name)) return err("Could not type into the search box.")
        Thread.sleep(1200); acc.awaitStable(500, 3000)

        // pick the best result: exact name first, else shortest label containing it
        val r2 = acc.root() ?: return err("Lost the window while searching.")
        val fTop = rect(f).bottom
        val q = name.lowercase()
        var hit: AccessibilityNodeInfo? = null
        var hitScore = Int.MIN_VALUE
        walk(r2) { n ->
            if (n.isVisibleToUser && !n.isEditable && rect(n).top > fTop) {
                val l = acc.labelOf(n)
                if (l.isNotEmpty() && l.lowercase().contains(q)) {
                    val s = (if (l.equals(name, true)) 100 else 0) - l.length
                    if (s > hitScore) { hit = n; hitScore = s }
                }
            }
        }
        val h2 = hit ?: return err("No search result for '$name' in ${Apps.label(pkg)}. It may be spelled differently; ask the user.")
        val picked = acc.labelOf(h2)
        acc.clickNode(h2); Thread.sleep(500); acc.awaitStable(600, 4000)
        if (!inChat(acc)) return err("Tapped '$picked' but the chat did not open. Use get_screen to see what is showing.")
        if (!text.isNullOrBlank()) acc.root()?.let { r -> composer(r, h)?.let { acc.setNodeText(it, text) } }
        val t = title(acc)
        val o = JSONObject().put("ok", true).put("via", "search").put("picked_result", picked).put("chat_title", t)
            .put("note", "Chat is open. Nothing has been sent yet.")
        if (t.isNotEmpty() && !t.lowercase().contains(q) && !q.contains(t.lowercase()))
            o.put("warning", "The chat title '$t' does not clearly match '$name'. Confirm with the user before sending.")
        return o
    }
}
