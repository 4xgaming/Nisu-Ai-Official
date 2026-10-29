package ai.nisu.assistant

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Pure text rules for [ImageMaker] (no Android classes, so they are unit tested on the JVM).
 */
object ImageRules {
    const val MAX_PROMPT = 1500

    /** Labels of the ChatGPT app's "new chat" button, "send" button and "stop" button (English and Bengali), compared whole and ignoring case. */
    val NEW_CHAT_LABELS = listOf("New chat", "New Chat", "\u09A8\u09A4\u09C1\u09A8 \u099A\u09CD\u09AF\u09BE\u099F")
    val SEND_LABELS = listOf("Send message", "Send prompt", "Send", "\u09AA\u09BE\u09A0\u09BE\u09A8")
    val STOP_LABELS = listOf("Stop streaming", "Stop generating", "Stop", "Stop response")
    val INPUT_HINTS = listOf("Ask anything", "Message ChatGPT", "Message")

    /** Pop-ups and tips that cover the message box on a fresh start of the app (whole label, ignoring case). Never "Continue with ...": only these exact words. */
    val DISMISS_LABELS = listOf("Not now", "Maybe later", "Skip", "No thanks", "Got it", "Dismiss", "Later", "Close", "Okay", "OK", "Done", "Stay logged out")

    private val BAD_SEND_WORDS = listOf("voice", "mic", "speak", "attach", "add ", "camera", "photo", "file", "plus", "tool", "think", "search", "upload", "dictat", "record", "menu", "more", "model", "stop")

    /** A button label that is clearly NOT the send arrow (voice mode, attach, camera ...). An empty label is allowed (the send arrow often has none). */
    fun isBadSendLabel(label: String): Boolean { val t = label.lowercase(); return BAD_SEND_WORDS.any { t.contains(it) } }

    class Cand(val l: Int, val t: Int, val r: Int, val b: Int, val label: String)

    /**
     * Fallback when no button is called "Send": the send arrow sits at the right end of the message row, right of the middle of the box and
     * level with it. The rightmost such button that is not clearly voice / attach / camera ... is taken. Returns its index or null.
     */
    fun pickSendByPosition(boxL: Int, boxT: Int, boxR: Int, boxB: Int, cands: List<Cand>): Int? {
        val cx = (boxL + boxR) / 2
        val h = (boxB - boxT).coerceAtLeast(1)
        var best = -1
        var bestX = -1
        cands.forEachIndexed { i, c ->
            val ccx = (c.l + c.r) / 2
            val ccy = (c.t + c.b) / 2
            if (ccx > cx && ccy >= boxT - h && ccy <= boxB + h && c.r - c.l < (boxR - boxL).coerceAtLeast(1) && !isBadSendLabel(c.label) && ccx > bestX) { best = i; bestX = ccx }
        }
        return if (best < 0) null else best
    }

    private val LOGIN_WORDS = listOf("log in", "sign up", "continue with google", "continue with apple", "continue with microsoft", "welcome back")
    private val BUSY_WORDS = listOf("creating image", "generating image", "getting started", "thinking", "analyzing", "making image", "creating your image")
    private val LIMIT_WORDS = listOf("reached the limit", "hit the limit", "limit reached", "image generation limit", "try again later", "upgrade to plus", "upgrade to go")
    private val REFUSAL_WORDS = listOf("can't create", "cannot create", "can\u2019t create", "unable to create", "can't generate", "can\u2019t generate", "content policy", "violates our", "i'm sorry, but i can't", "i\u2019m sorry, but i can\u2019t")

    private val STARTS_LIKE_REQUEST = Regex("^\\s*(please\\s+)?(create|generate|make|draw|design|render|produce|paint|show me)\\b.*\\b(image|picture|photo|illustration|artwork|logo|poster|wallpaper|drawing|portrait|painting|sketch|icon|banner)\\b", RegexOption.IGNORE_CASE)

    /** Cleans the prompt the model wrote (whitespace, length) and makes sure ChatGPT is told to MAKE a picture, not to describe one. */
    fun buildPrompt(raw: String): String {
        val t = raw.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (t.isEmpty()) return ""
        val cut = if (t.length > MAX_PROMPT) t.take(MAX_PROMPT).trimEnd() else t
        return if (STARTS_LIKE_REQUEST.containsMatchIn(cut.take(160))) cut else "Create an image: $cut"
    }

    fun looksLoggedOut(screen: String): Boolean {
        val s = screen.lowercase()
        // a signed-in chat screen never shows the sign-up buttons together with a message box; two login words at once is a login page
        return LOGIN_WORDS.count { s.contains(it) } >= 2 || (s.contains("log in") && s.contains("sign up"))
    }

    fun looksBusy(screen: String): Boolean { val s = screen.lowercase(); return BUSY_WORDS.any { s.contains(it) } }
    fun hitLimit(screen: String): Boolean { val s = screen.lowercase(); return LIMIT_WORDS.any { s.contains(it) } }
    fun looksRefused(screen: String): Boolean { val s = screen.lowercase(); return REFUSAL_WORDS.any { s.contains(it) } }

    /** How long the model may ask us to wait for the picture: 20 to 150 seconds, default 75. */
    fun waitSeconds(requested: Int?): Int = (requested ?: 75).coerceIn(20, 150)
}

/**
 * v3.50: make_image. When he asks for a picture, the voice model writes the prompt from what he described and this tool does the rest in
 * the ChatGPT app: opens it, starts a new chat (or continues the last one for a change), types the prompt, presses Send and waits until
 * ChatGPT stops working. It never logs in, never buys or upgrades anything, and says honestly what it could and could not see:
 * the phone cannot judge the picture itself, it only sees the screen text and the app's buttons.
 */
object ImageMaker {
    const val NAME = "make_image"
    const val CHATGPT_PKG = "com.openai.chatgpt"

    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** The voice prompt block. No dollar signs here: it is a const. */
    const val NOTE = """
MAKING PICTURES (make_image): when he asks you to make, create, draw or generate a picture / photo / image / logo / poster / art (ছবি বানাও, ফটো বানিয়ে দাও, ইমেজ জেনারেট করো, image banao, photo banao), you do the whole job with the make_image tool in the ChatGPT app. Never ask him to open ChatGPT and never tap through it step by step. (1) LISTEN to how the picture should look. If he already said enough (what is in it, and a style or mood), do not ask more. If it is vague, ask ONE short question about the thing that matters most (what is in it, the style, the mood or the colours) and wait. (2) Write the prompt yourself in English from his exact words: the subject, the style (realistic photo, anime, 3D, painting...), the light, the colours, the mood, the framing, and a format only if he said one. Keep every wish he gave and never add things that go against it. (3) Call make_image with that prompt. It opens ChatGPT, types the prompt, presses Send and waits. (4) Say honestly what the result says: sent true and finished true = tell him the picture is ready on the screen in ChatGPT; finished false = it is still working, say so and check again with get_screen; if it reports a login page, a limit, a refusal or that ChatGPT is not installed, tell him that plainly (if it is not installed, offer to install it with install_app, package com.openai.chatgpt). You cannot judge the picture itself, so never say it looks good or exactly right; offer to change it. (5) For a change to the same picture ("make it darker", "add a moon") call make_image again with new_chat false and only the change as the prompt. Logins, payments and upgrades are his: never type a password or buy anything.
"""

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val prompt = ImageRules.buildPrompt(a.optString("prompt", ""))
        if (prompt.isEmpty()) return err("The prompt is empty. Ask him what the picture should look like, then call make_image with the English prompt.")
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")
        val newChat = a.optBoolean("new_chat", true)
        val waitSec = ImageRules.waitSeconds(if (a.has("wait_seconds")) a.optInt("wait_seconds") else null)
        val t0 = SystemClock.uptimeMillis()

        // 1) open the ChatGPT app and get to the message box. A cold start can take 10 s and may show a tip, a pop-up or the wrong screen first:
        //    this waits, closes pop-ups, tries the closed "Ask anything" bar and, if it is still stuck, starts again once from the home screen.
        var box: AccessibilityNodeInfo? = null
        var screen = ""
        for (attempt in 1..2) {
            if (attempt == 2) { acc.key("home"); sleep(700) }
            val opened = PhoneControl.openApp(ctx, CHATGPT_PKG)
            if (!opened.optBoolean("ok")) {
                return err("The ChatGPT app is not installed on this phone or could not be opened.",
                    "Tell him. Offer to install it with install_app (name ChatGPT, package $CHATGPT_PKG), then call make_image again.")
            }
            acc.awaitStable(300, 3500)
            sleep(400)
            screen = acc.dumpScreen(60)
            if (ImageRules.looksLoggedOut(screen))
                return err("ChatGPT is asking to log in, so nothing was typed.", "Tell him to sign in to ChatGPT himself (a login is his to do), then ask again.").put("screen", screen)
            // 2) a new chat, so an old conversation does not get mixed in (skipped for a change to the last picture)
            if (newChat && acc.clickExact(ImageRules.NEW_CHAT_LABELS) != null) { acc.awaitStable(250, 2000); sleep(250) }
            box = reachComposer(ctx, acc, 14_000L)
            if (box != null) break
            screen = acc.dumpScreen(60)
            if (ImageRules.looksLoggedOut(screen))
                return err("ChatGPT is asking to log in, so nothing was typed.", "Tell him to sign in to ChatGPT himself (a login is his to do), then ask again.").put("screen", screen)
        }
        if (box == null)
            return err("ChatGPT opened but its message box never appeared (even after waiting and starting it again), so nothing was sent.",
                "Say so honestly. Use get_screen to see where ChatGPT is, close any pop-up with click_text, then call make_image once more; if it fails again tell him.")
                .put("screen", acc.dumpScreen(60))

        // 4) type the prompt (replacing any old draft) and check it really is there
        var typed = false
        if (acc.setNodeText(box, prompt)) {
            sleep(300); box.refresh()
            typed = box.text?.toString().orEmpty().replace("\u200B", "").let { it.contains(prompt.take(24)) }
        }
        if (!typed) typed = acc.typeText(prompt, true, false)
        if (!typed)
            return err("Could not put the prompt into the ChatGPT message box, so NOTHING was sent.", "Check with get_screen, tap the box with click_text, then try again.")
                .put("screen", acc.dumpScreen(60))

        // 5) press Send (the button only appears once there is text)
        acc.awaitStable(200, 1500)
        sleep(150)
        val pressed = acc.clickExact(ImageRules.SEND_LABELS) ?: pressSendByPosition(acc)
            ?: return err("The prompt is typed but no Send button was found, so it was NOT sent.", "Use get_screen, find the Send button (an arrow) and press it with click_text, or tell him.")
                .put("screen", acc.dumpScreen(60))

        // 6) it counts as sent only when the box empties or ChatGPT starts working
        val sendEnd = SystemClock.uptimeMillis() + 4000
        var sent = false
        while (SystemClock.uptimeMillis() < sendEnd) {
            sleep(250)
            val b = findBox(acc)
            val left = b?.text?.toString().orEmpty()
            if (b == null || b.isShowingHintText || left.isBlank() || !left.contains(prompt.take(24)) || acc.hasExact(ImageRules.STOP_LABELS)) { sent = true; break }
        }
        if (!sent)
            return err("Pressed Send ($pressed) but the prompt is still in the box; it probably was NOT sent. Do not press Send again blindly.", "Check with get_screen first.")
                .put("screen", acc.dumpScreen(60))

        // 7) wait until ChatGPT stops working (the stop button and the "creating image" text are gone for three checks in a row)
        val minWaitUntil = SystemClock.uptimeMillis() + 6000
        val end = SystemClock.uptimeMillis() + waitSec * 1000L   // v3.59: the wait for the picture starts AFTER the app start and the send, a slow start no longer eats it
        var calm = 0
        var finished = false
        while (SystemClock.uptimeMillis() < end) {
            sleep(1000)
            screen = acc.dumpScreen(80)
            val working = acc.hasExact(ImageRules.STOP_LABELS) || ImageRules.looksBusy(screen)
            if (ImageRules.hitLimit(screen)) break
            calm = if (working) 0 else calm + 1
            if (calm >= 3 && SystemClock.uptimeMillis() >= minWaitUntil) { finished = true; break }
        }
        screen = acc.dumpScreen(80)
        val r = JSONObject().put("ok", true).put("sent", true).put("finished", finished)
            .put("waited_seconds", ((SystemClock.uptimeMillis() - t0) / 1000L).toInt()).put("screen", screen)
        when {
            ImageRules.hitLimit(screen) -> r.put("finished", false).put("note", "ChatGPT shows a limit or an upgrade message. Tell him plainly; do not buy or upgrade anything.")
            ImageRules.looksRefused(screen) -> r.put("note", "ChatGPT seems to have refused or failed to make this picture (read the screen text). Tell him what it says and offer a different description.")
            finished -> r.put("note", "ChatGPT stopped working. The picture should be on the screen, but you cannot see it yourself: say it is ready in ChatGPT, never judge how it looks.")
            else -> r.put("note", "ChatGPT is still working. Say it is still being made; check again later with get_screen. Do not say it is done.")
        }
        AppState.add("make_image: " + (if (finished) "finished" else "sent"))
        return r
    }

    private fun findBox(acc: NisuAccessibilityService) = acc.root(500)?.let { acc.findEditable(it) }

    /**
     * Waits (up to [budgetMs]) until ChatGPT shows its message box. Meanwhile: closes a tip or pop-up, taps a closed "Ask anything" bar,
     * and on a wrong screen goes back (never leaving the app: if back closed ChatGPT it is opened again).
     */
    private fun reachComposer(ctx: Context, acc: NisuAccessibilityService, budgetMs: Long): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + budgetMs
        var round = 0
        while (SystemClock.uptimeMillis() < end) {
            round++
            findBox(acc)?.let { return it }
            if (ImageRules.looksLoggedOut(acc.dumpScreen(40))) return null
            if (acc.clickExact(ImageRules.DISMISS_LABELS) != null) { acc.awaitStable(200, 1200); sleep(300); continue }
            for (h in ImageRules.INPUT_HINTS) {
                if (acc.clickText(h, 250)) { acc.awaitStable(200, 1200); findBox(acc)?.let { return it }; break }
            }
            if (round == 5 || round == 9) {
                acc.key("back"); sleep(600)
                if (acc.fgPkg != CHATGPT_PKG) { PhoneControl.openApp(ctx, CHATGPT_PKG); acc.awaitStable(300, 3000) }
            }
            sleep(700)
        }
        return findBox(acc)
    }

    /** No button is named Send: press the rightmost plain button at the right end of the message row (see [ImageRules.pickSendByPosition]). */
    private fun pressSendByPosition(acc: NisuAccessibilityService): String? {
        val root = acc.root(400) ?: return null
        val box = acc.findEditable(root) ?: return null
        val br = Rect().also { box.getBoundsInScreen(it) }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val cands = ArrayList<ImageRules.Cand>()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser && n.isClickable && n.isEnabled && !n.isEditable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    nodes.add(n)
                    cands.add(ImageRules.Cand(r.left, r.top, r.right, r.bottom, (n.contentDescription ?: n.text)?.toString().orEmpty()))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        try { walk(root) } catch (_: Exception) {}
        val i = ImageRules.pickSendByPosition(br.left, br.top, br.right, br.bottom, cands) ?: return null
        return if (acc.clickNode(nodes[i])) "send button by position" else null
    }
}
