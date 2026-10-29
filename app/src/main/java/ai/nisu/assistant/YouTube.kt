package ai.nisu.assistant

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/*
 * v3.96 YOUTUBE MASTER.
 *
 * One tool, `youtube(action, ...)`, that does everything inside the YouTube app in ONE verified step instead of the model tapping coordinates:
 * search, play a song, play a trending song by itself, skip ads, pause / resume, next / previous, seek, like, subscribe, save, share, comments,
 * fullscreen, minimize, captions, speed, quality, autoplay, tabs, Shorts, status, and a full button / feature guide (action guide).
 *
 * How it stays steady:
 *  - It reads the screen itself (NisuAccessibilityService.scan: full labels, ids, selected / checked) and clicks a node ONCE (clickNode, which no longer
 *    taps twice). Search results open through a real YouTube link, so there is no typing and no keyboard.
 *  - Every action ends by reading the screen again and says what it really saw (player playing / paused, ad showing, screen kind). A result that could not be
 *    confirmed says verified false; the model must not claim success then.
 *  - Ads: a skippable ad's Skip button is pressed (also by itself in the background, Prefs.YT_AUTOSKIP). An ad that cannot be skipped yet is waited out up to a limit
 *    and reported honestly.
 *
 * HONEST LIMITS: YouTube changes its screens often and labels follow the phone's language (this reads the English labels; the ids are language free). The label
 * rules here come from the app's known accessibility labels and were NOT run on a real phone from this build. Where a step is a best guess the result says
 * verified false. YouTube Premium features and sign-in screens are not handled.
 */

/** Pure rules (no Android classes): unit tested on the JVM. */
object YtRules {
    val SKIP_LABELS = listOf("Skip", "Skip ad", "Skip ads", "Skip Ad", "Skip Ads")
    const val MIN_SONG_SEC = 120
    const val MAX_SONG_SEC = 600

    fun isSkipLabel(l: String): Boolean = SKIP_LABELS.any { it.equals(l.trim(), true) }

    /** "You can skip in 5", "Skip ad in 3": the ad is still counting down. */
    fun isSkipCountdown(all: String): Boolean {
        val t = all.lowercase()
        return t.contains("skip in") || t.contains("can skip") || t.contains("skip ad in") || t.contains("skip ads in")
    }

    private val PLAYER_AD = Regex("visit advertiser|(^|[^a-z])ad \\d+ of \\d+|(^|[^a-z])ad [\\u00B7\\u2022]|skip in|can skip|skip ads? in")

    /** Text that only a video ad on the PLAYER shows. "Sponsored" is left out on purpose: sponsored cards sit in the feed and the Up next list of a normal video. */
    fun isPlayerAd(allLower: String): Boolean = PLAYER_AD.containsMatchIn(allLower)

    private val AD_WORDS = Regex("sponsored|visit advertiser|why this ad|my ad center|(^|[^a-z])ad \\d+ of \\d+|(^|[^a-z])ad [\\u00B7\\u2022]")
    fun isAdText(allLower: String): Boolean = AD_WORDS.containsMatchIn(allLower)

    /** A search / feed result that opens a normal video (its description ends with "play video"). Shorts and ads are not. */
    fun isVideoResult(desc: String): Boolean {
        val d = desc.lowercase().trim()
        return (d.endsWith("play video") || d.contains(" - play video")) && !d.contains("play short")
    }

    fun isShortResult(desc: String): Boolean = desc.lowercase().contains("play short")

    fun titleOf(desc: String): String = desc.substringBefore(" - ").trim()

    private val DUR = Regex("^(\\d+ hours?)?[, ]*(\\d+ minutes?)?[, ]*(\\d+ seconds?)?$", RegexOption.IGNORE_CASE)

    /** Length in seconds from a result description such as "Title - 3 minutes, 20 seconds - Channel - 1M views - play video"; null when none is found. */
    fun durationSec(desc: String): Int? {
        for (seg in desc.split(" - ").drop(1)) {
            val s = seg.trim()
            if (s.isEmpty() || !s.any { it.isDigit() } || s.contains("ago", true) || !DUR.matches(s)) continue
            var total = 0
            Regex("(\\d+) hours?", RegexOption.IGNORE_CASE).find(s)?.let { total += it.groupValues[1].toInt() * 3600 }
            Regex("(\\d+) minutes?", RegexOption.IGNORE_CASE).find(s)?.let { total += it.groupValues[1].toInt() * 60 }
            Regex("(\\d+) seconds?", RegexOption.IGNORE_CASE).find(s)?.let { total += it.groupValues[1].toInt() }
            return total
        }
        return null
    }

    fun searchUrl(q: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8")

    /** The query for "any trending song": language and genre are optional words; month and year keep the results fresh. */
    fun trendingQuery(language: String, genre: String, month: String, year: Int): String =
        listOf(language.trim(), genre.trim(), "trending songs", month.trim(), year.toString()).filter { it.isNotEmpty() }.joinToString(" ")

    /** A song-like result: a known length between two and ten minutes (unknown length is accepted). */
    fun isSongLength(sec: Int?): Boolean = sec == null || (sec in MIN_SONG_SEC..MAX_SONG_SEC)

    /** 1-based pick from [n] results: "first" / blank = 1, a number = that one (kept inside 1..n), "random" = [rnd] mod n. */
    fun pickIndex(pick: String, n: Int, rnd: Int): Int {
        if (n <= 0) return -1
        val p = pick.trim().lowercase()
        if (p == "random") return Math.floorMod(rnd, n)
        val k = p.toIntOrNull() ?: return 0
        return (k - 1).coerceIn(0, n - 1)
    }

    /** Speed labels: 1.0 is shown as "Normal" in the YouTube menu. */
    fun speedLabel(v: Double): String = if (v == 1.0) "Normal" else (if (v == Math.floor(v)) v.toInt().toString() else v.toString()) + "x"
}

object YouTube {
    const val TOOL = "youtube"
    const val PKG = "com.google.android.youtube"
    private val rnd = java.util.Random()

    private fun err(msg: String, hint: String = ""): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (hint.isNotEmpty()) it.put("hint", hint) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val action = a.optString("action", "").trim().lowercase().replace(' ', '_').replace('-', '_')
        if (action == "guide") return JSONObject().put("ok", true).put("guide", YtKnowledge.guide(a.optString("topic", "")))
        if (action.isEmpty()) return err("Say which action: search, play, play_trending, skip_ad, pause, resume, next, previous, seek, like, subscribe, save, download, share, comments, fullscreen, minimize, captions, speed, quality, autoplay, tab, shorts_next, shorts_prev, status, back, guide.")
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")
        return try { dispatch(ctx, acc, action, a) } catch (e: Exception) { err("The YouTube action stopped with an error (" + e.javaClass.simpleName + ").") }
    }

    private fun dispatch(ctx: Context, acc: NisuAccessibilityService, action: String, a: JSONObject): JSONObject = when (action) {
        "open" -> openYoutube(ctx, acc) ?: finish(acc, action)
        "search" -> search(ctx, acc, a.optString("query"), play = false, pick = "first", song = false)
        "play" -> search(ctx, acc, a.optString("query"), play = true, pick = a.optString("pick", "first"), song = false)
        "play_trending", "trending" -> trending(ctx, acc, a)
        "skip_ad", "skip" -> { val s = handleAd(acc, 12_000L); finish(acc, action).put("ad", s) }
        "pause" -> toggle(acc, wantPlaying = false)
        "resume", "continue", "unpause" -> toggle(acc, wantPlaying = true)
        "next" -> step(acc, "Next video", "next")
        "previous", "prev" -> step(acc, "Previous video", "previous")
        "seek" -> seek(acc, a.optInt("seconds", 10))
        "like" -> like(acc, dislike = false)
        "dislike" -> like(acc, dislike = true)
        "subscribe" -> subscribe(acc)
        "save", "add_to_playlist", "watch_later" -> save(acc, a.optString("playlist", "Watch later").ifBlank { "Watch later" })
        "share" -> clickLabel(acc, action, listOf("Share"), startsWith = emptyList())
        "comments" -> clickLabel(acc, action, emptyList(), startsWith = listOf("Comments"))
        "fullscreen" -> fullscreen(acc, a.optBoolean("on", true))
        "minimize" -> minimize(acc)
        "captions", "subtitles" -> captions(acc)
        "speed" -> playerMenu(acc, "Playback speed", YtRules.speedLabel(a.optDouble("value", 1.0)))
        "quality" -> playerMenu(acc, "Quality", a.optString("value", "").trim())
        "autoplay" -> autoplay(acc, a.optBoolean("on", true))
        "tab" -> tab(acc, a.optString("name"))
        "shorts_next" -> swipeShort(acc, up = true)
        "shorts_prev" -> swipeShort(acc, up = false)
        "back" -> { acc.key("back"); acc.awaitStable(150, 700); finish(acc, action) }
        "status" -> finish(acc, action).put("labels", labels(acc.scan(), 18))
        "download" -> download(ctx, acc, a)
        "ad_auto" -> { val on = a.optBoolean("on", true); Prefs.putBool(ctx, Prefs.YT_AUTOSKIP, on); JSONObject().put("ok", true).put("auto_skip_ads", on) }
        else -> err("Unknown YouTube action \"$action\". Use action guide to see them all.")
    }

    // ---------------------------------------------------------------- reading the screen

    private fun exact(n: List<NisuAccessibilityService.Seen>, vararg labels: String): NisuAccessibilityService.Seen? =
        n.firstOrNull { s -> labels.any { s.text.equals(it, true) || s.desc.equals(it, true) } }

    private fun starts(n: List<NisuAccessibilityService.Seen>, vararg prefixes: String): NisuAccessibilityService.Seen? =
        n.firstOrNull { s -> prefixes.any { s.desc.startsWith(it, true) || s.text.startsWith(it, true) } }

    private fun isWatch(n: List<NisuAccessibilityService.Seen>): Boolean =
        n.any { it.id.contains("watch_player") || it.id.contains("player_view") || it.id.contains("watch_while") || it.id.contains("player_overlays") } ||
            exact(n, "Minimize", "Pause video", "Play video", "Replay video", "Enter fullscreen", "Exit fullscreen") != null

    private fun isShorts(n: List<NisuAccessibilityService.Seen>): Boolean = n.any { it.id.contains("reel") }

    private fun adShowing(n: List<NisuAccessibilityService.Seen>): Boolean =
        n.any { it.id == "skip_ad_button" || YtRules.isSkipLabel(it.label) || (isWatch(n) && YtRules.isPlayerAd(it.all)) }

    private fun playerState(n: List<NisuAccessibilityService.Seen>): String? = when {
        exact(n, "Pause video") != null -> "playing"
        exact(n, "Play video") != null -> "paused"
        exact(n, "Replay video") != null -> "ended"
        else -> null
    }

    private fun kind(n: List<NisuAccessibilityService.Seen>): String = when {
        isShorts(n) -> "shorts"
        isWatch(n) -> "watch"
        n.any { YtRules.isVideoResult(it.desc) } -> "list"
        else -> "other"
    }

    private fun results(n: List<NisuAccessibilityService.Seen>): List<NisuAccessibilityService.Seen> =
        n.filter { YtRules.isVideoResult(it.desc) && !YtRules.isAdText(it.all) }.sortedBy { it.bounds.top }

    private fun labels(n: List<NisuAccessibilityService.Seen>, max: Int): JSONArray {
        val out = JSONArray(); val seen = HashSet<String>()
        for (s in n) { val l = s.label.take(70); if (l.isNotEmpty() && seen.add(l)) { out.put(l); if (out.length() >= max) break } }
        return out
    }

    /** What the screen shows right now, for every result. */
    private fun finish(acc: NisuAccessibilityService, action: String): JSONObject {
        val n = acc.scan()
        val o = JSONObject().put("ok", true).put("action", action).put("youtube_in_front", acc.fgPkg == PKG).put("screen_kind", kind(n))
        playerState(n)?.let { o.put("player", it) }
        o.put("ad_showing", adShowing(n))
        return o
    }

    private fun waitFor(acc: NisuAccessibilityService, ms: Long, pred: (List<NisuAccessibilityService.Seen>) -> Boolean): List<NisuAccessibilityService.Seen>? {
        val end = SystemClock.uptimeMillis() + ms
        while (true) {
            val s = acc.scan()
            if (pred(s)) return s
            if (SystemClock.uptimeMillis() >= end) return null
            sleep(250)
        }
    }

    /** null = YouTube is in front; otherwise the error to return. */
    private fun openYoutube(ctx: Context, acc: NisuAccessibilityService): JSONObject? {
        if (acc.fgPkg == PKG) return null
        val r = PhoneControl.openApp(ctx, "youtube")
        if (!r.optBoolean("ok")) return r
        acc.awaitStable(300, 3000)
        return null
    }

    /** The player's controls on screen. If they are hidden ONE tap on the video shows them (never during an ad: that would open the advertiser). */
    private fun controls(acc: NisuAccessibilityService): List<NisuAccessibilityService.Seen> {
        var n = acc.scan()
        if (exact(n, "Pause video", "Play video", "Replay video") != null || adShowing(n)) return n
        val (w, h) = acc.screenPx()
        val b = n.firstOrNull { it.id.contains("watch_player") || it.id.contains("player_view") }?.bounds
        acc.tap(b?.exactCenterX() ?: (w / 2f), b?.exactCenterY() ?: (h * 0.2f))
        sleep(450)
        n = acc.scan()
        return n
    }

    private fun clickSeen(acc: NisuAccessibilityService, s: NisuAccessibilityService.Seen): Boolean = acc.clickNode(s.node)

    // ---------------------------------------------------------------- ads

    /** Waits up to [maxMs] for an ad to end or be skipped. Returns none | skipped | ad_playing_unskippable | ad_countdown. */
    private fun handleAd(acc: NisuAccessibilityService, maxMs: Long): String {
        val end = SystemClock.uptimeMillis() + maxMs
        var skipped = false
        while (true) {
            val n = acc.scan()
            if (!adShowing(n)) return if (skipped) "skipped" else "none"
            val skip = acc.findSkipAd()
            if (skip != null) { acc.clickNode(skip); skipped = true; sleep(800); continue }
            if (SystemClock.uptimeMillis() >= end) return if (n.any { YtRules.isSkipCountdown(it.all) }) "ad_countdown" else "ad_playing_unskippable"
            sleep(400)
        }
    }

    // ---------------------------------------------------------------- search and play

    private fun search(ctx: Context, acc: NisuAccessibilityService, query: String, play: Boolean, pick: String, song: Boolean): JSONObject {
        val q = query.trim()
        if (q.isEmpty()) return err("The query is empty. Ask him which song or video, or use play_trending.")
        val opened = PhoneControl.openUrl(ctx, YtRules.searchUrl(q), "youtube")
        if (!opened.optBoolean("ok")) return opened
        acc.awaitStable(300, 3000)
        val n = waitFor(acc, 9000) { results(it).isNotEmpty() }
            ?: return finish(acc, "search").put("ok", false).put("error", "No video results appeared for \"$q\" (YouTube may show a sign-in, consent or error screen).")
                .put("labels", labels(acc.scan(), 14))
        val list = results(n)
        val items = JSONArray()
        list.take(8).forEach { s ->
            items.put(JSONObject().put("title", YtRules.titleOf(s.desc).take(90)).put("seconds", YtRules.durationSec(s.desc) ?: JSONObject.NULL))
        }
        if (!play) return finish(acc, "search").put("query", q).put("results", items)
        val pool = if (song) list.filter { YtRules.isSongLength(YtRules.durationSec(it.desc)) }.ifEmpty { list } else list
        val idx = YtRules.pickIndex(pick, minOf(pool.size, if (pick.trim().lowercase() == "random") 5 else pool.size), rnd.nextInt(1000))
        val chosen = pool[idx.coerceAtLeast(0)]
        if (!clickSeen(acc, chosen)) return err("Could not press the video result.", "Use get_screen and click_text.")
        val watching = waitFor(acc, 8000) { isWatch(it) }
        if (watching == null) return finish(acc, "play").put("ok", false).put("error", "The video did not open after pressing it.").put("labels", labels(acc.scan(), 14))
        val ad = handleAd(acc, 12_000L)
        val after = controls(acc)
        val o = finish(acc, "play").put("query", q).put("title", YtRules.titleOf(chosen.desc).take(120)).put("ad", ad)
        YtRules.durationSec(chosen.desc)?.let { o.put("seconds", it) }
        val st = playerState(after)
        o.put("verified", st == "playing")
        if (st != "playing") o.put("note", "The player did not report playing (state: " + (st ?: "unknown") + "). If an ad is showing say so; otherwise check with youtube status before saying it plays.")
        return o
    }

    private fun trending(ctx: Context, acc: NisuAccessibilityService, a: JSONObject): JSONObject {
        val now = java.time.LocalDate.now()
        val month = now.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val q = YtRules.trendingQuery(a.optString("language"), a.optString("genre"), month, now.year)
        return search(ctx, acc, q, play = true, pick = "random", song = true).put("trending_query", q)
    }

    // ---------------------------------------------------------------- player controls

    private fun toggle(acc: NisuAccessibilityService, wantPlaying: Boolean): JSONObject {
        val n = controls(acc)
        val name = if (wantPlaying) "resume" else "pause"
        val state = playerState(n) ?: return finish(acc, name).put("ok", false).put("error", "No player controls are visible (no video is open, or an ad covers them).")
        if ((state == "playing") == wantPlaying && state != "ended") return finish(acc, name).put("already", true)
        val btn = exact(n, "Pause video", "Play video", "Replay video") ?: return err("The play / pause button vanished.")
        clickSeen(acc, btn)
        sleep(450)
        val after = playerState(acc.scan())
        return finish(acc, name).put("verified", after == (if (wantPlaying) "playing" else "paused"))
    }

    private fun step(acc: NisuAccessibilityService, label: String, name: String): JSONObject {
        val n = controls(acc)
        val b = exact(n, label) ?: return finish(acc, name).put("ok", false).put("error", "The \"$label\" button is not there (no next / previous video in this list).").put("hint", "Use youtube play with a query instead.")
        if (!b.clickable) return finish(acc, name).put("ok", false).put("error", "The \"$label\" button is off.")
        clickSeen(acc, b)
        sleep(1200)
        val ad = handleAd(acc, 10_000L)
        return finish(acc, name).put("ad", ad)
    }

    /** +n / -n seconds. Double-tap on the right / left of the video: the first double tap is 10 s, every further single tap another 10 s. */
    private fun seek(acc: NisuAccessibilityService, seconds: Int): JSONObject {
        val n0 = acc.scan()
        if (!isWatch(n0)) return finish(acc, "seek").put("ok", false).put("error", "No video is open.")
        if (adShowing(n0)) return finish(acc, "seek").put("ok", false).put("error", "An ad is showing; seeking is not possible.")
        val steps = (Math.abs(seconds) / 10).coerceIn(1, 12)
        val (w, h) = acc.screenPx()
        val b = n0.firstOrNull { it.id.contains("watch_player") || it.id.contains("player_view") }?.bounds
        val left = b?.left ?: 0
        val width = b?.width() ?: w
        val x = if (seconds >= 0) left + width * 0.82f else left + width * 0.18f
        val y = b?.exactCenterY() ?: (h * 0.2f)
        acc.tap(x, y); sleep(90); acc.tap(x, y)
        for (i in 1 until steps) { sleep(220); acc.tap(x, y) }
        sleep(500)
        return finish(acc, "seek").put("requested_seconds", steps * 10 * (if (seconds >= 0) 1 else -1)).put("verified", false)
            .put("note", "Done with double taps on the video; the new position cannot be read back, so do not quote it.")
    }

    // ---------------------------------------------------------------- like / subscribe / save / menus

    private fun like(acc: NisuAccessibilityService, dislike: Boolean): JSONObject {
        val name = if (dislike) "dislike" else "like"
        val n = acc.scan()
        if (!isWatch(n)) return finish(acc, name).put("ok", false).put("error", "Open a video first.")
        val node = if (dislike) starts(n, "Dislike this video", "Dislike") else starts(n, "like this video", "Like this video", "Like")
        val undo = if (dislike) exact(n, "Remove dislike") else exact(n, "Remove like", "Unlike")
        if (undo != null || (node != null && (node.selected || node.checked))) return finish(acc, name).put("already", true)
        if (node == null) return finish(acc, name).put("ok", false).put("error", "The $name button is not on screen (scroll the video page a little, or the video has it off).")
        clickSeen(acc, node)
        sleep(500)
        val a2 = acc.scan()
        val done = (if (dislike) exact(a2, "Remove dislike") else exact(a2, "Remove like", "Unlike")) != null ||
            (if (dislike) starts(a2, "Dislike this video", "Dislike") else starts(a2, "like this video", "Like this video", "Like"))?.let { it.selected || it.checked } == true
        return finish(acc, name).put("verified", done)
    }

    private fun subscribe(acc: NisuAccessibilityService): JSONObject {
        val n = acc.scan()
        if (starts(n, "Subscribed to", "Subscribed") != null) return finish(acc, "subscribe").put("already", true)
        val b = starts(n, "Subscribe to", "Subscribe") ?: return finish(acc, "subscribe").put("ok", false).put("error", "The Subscribe button is not on screen.")
        clickSeen(acc, b)
        sleep(600)
        return finish(acc, "subscribe").put("verified", starts(acc.scan(), "Subscribed to", "Subscribed") != null)
    }

    private fun save(acc: NisuAccessibilityService, playlist: String): JSONObject {
        val n = acc.scan()
        val b = exact(n, "Save", "Save to playlist") ?: starts(n, "Save to") ?: return finish(acc, "save").put("ok", false).put("error", "The Save button is not on screen.")
        clickSeen(acc, b)
        val sheet = waitFor(acc, 3500) { s -> s.any { it.label.equals(playlist, true) || it.label.startsWith(playlist, true) } }
            ?: return finish(acc, "save").put("ok", false).put("error", "The playlist \"$playlist\" was not in the Save menu.").put("labels", labels(acc.scan(), 14))
        val row = sheet.first { it.label.equals(playlist, true) || it.label.startsWith(playlist, true) }
        clickSeen(acc, row)
        sleep(500)
        exact(acc.scan(), "Done")?.let { clickSeen(acc, it); sleep(300) }
        return finish(acc, "save").put("playlist", playlist).put("verified", false).put("note", "Pressed the playlist row; YouTube shows a short confirmation that cannot be read reliably.")
    }

    // ---------------------------------------------------------------- v3.113 download
    /** Where the download goes: "app" = inside the YouTube app, "storage" = internal storage through a downloader web page in Chrome. null = not said yet. */
    fun downloadWhere(raw: String): String? {
        val t = raw.lowercase().replace('_', ' ').trim()
        return when {
            t.isEmpty() -> null
            t.contains("app") || t.contains("youtube") || t == "yt" -> "app"
            t.contains("storage") || t.contains("internal") || t.contains("phone") || t.contains("gallery") || t.contains("file") -> "storage"
            else -> null
        }
    }
    /** mp3 (song as audio) or video; null = not said yet. */
    fun downloadFormat(raw: String): String? {
        val t = raw.lowercase().trim()
        return when {
            t.isEmpty() -> null
            t.contains("mp3") || t.contains("audio") || t.contains("song") || t.contains("music") || t.contains("gan") -> "mp3"
            t.contains("video") || t.contains("mp4") -> "video"
            else -> null
        }
    }

    @Volatile private var askedWhereAt = 0L
    @Volatile private var askedFormatAt = 0L
    private const val ASK_WINDOW_MS = 300_000L

    /** True when what he said in this turn clearly names a place: the YouTube app or the internal storage. (The word "youtube" alone does not count.) */
    internal fun saidWhere(text: String): Boolean = Regex("storage|internal|gallery|\\bapps?\\b|\u09B8\u09CD\u099F\u09CB\u09B0\u09C7\u099C|\u0987\u09A8\u09CD\u099F\u09BE\u09B0\u09A8\u09BE\u09B2|\u0985\u09CD\u09AF\u09BE\u09AA|\u098F\u09AA", RegexOption.IGNORE_CASE).containsMatchIn(text)
    /** True when he named the format in this turn: mp3 / audio / mp4 ("video" alone does not count: every request says video). */
    internal fun saidFormat(text: String): Boolean = Regex("mp3|mp4|audio|\u0985\u09A1\u09BF\u0993|\u098F\u09AE\u09AA\u09BF", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** The downloader pages, in the order they are tried. Only these pages are used, y2mate first. His own address (yt_dl_site), when he set one, goes before them. */
    internal fun downloaderSites(custom: String, @Suppress("UNUSED_PARAMETER") format: String): List<String> {
        val ready = listOf("https://v34.www-y2mate.com/", "https://en1.savefrom.net/", "https://app.ytdown.to/", "https://www.snapyt.app/", "https://www.ytultra.com/", "https://youtubetoolkit.com/")
        return (listOf(custom.trim()).filter { it.isNotEmpty() } + ready).distinct()
    }

    /**
     * Download the video that is playing. `where` = app | storage and, for storage, `format` = mp3 | video: he must say them; they are asked when he has not
     * (this is enforced here, the model cannot skip the question). app: presses YouTube's own Download button. storage: copies the link in YouTube, then opens the
     * downloader pages one after the other in Chrome until one takes the pasted link.
     */
    private fun download(ctx: Context, acc: NisuAccessibilityService, a: JSONObject): JSONObject {
        val turn = WebBuilder.currentTurn
        val tryNo = a.optInt("try", 0).coerceAtLeast(0)
        val whereGiven = downloadWhere(a.optString("where"))
        val whereOk = whereGiven != null && (tryNo > 0 || System.currentTimeMillis() - askedWhereAt < ASK_WINDOW_MS || saidWhere(turn))
        if (!whereOk) {
            askedWhereAt = System.currentTimeMillis()
            return JSONObject().put("ok", false).put("need", "where").put("ask", "Ask him ONE short question in his language: should it download inside the YouTube app, or to the internal storage? Wait for his answer, then call youtube download again with where. Do nothing else yet.")
        }
        val where = whereGiven!!
        val formatGiven = downloadFormat(a.optString("format"))
        if (where == "storage") {
            val fmtOk = formatGiven != null && (tryNo > 0 || System.currentTimeMillis() - askedFormatAt < ASK_WINDOW_MS || saidFormat(turn))
            if (!fmtOk) {
                askedFormatAt = System.currentTimeMillis()
                return JSONObject().put("ok", false).put("need", "format").put("ask", "Ask him ONE short question in his language: mp3 (song only) or the video? Wait for his answer, then call youtube download again with where storage and format. Do not guess the format.")
            }
        }
        val format = formatGiven ?: "video"
        if (tryNo == 0 && !isWatch(acc.scan())) {
            val q = a.optString("query").trim()
            if (q.isEmpty()) return err("No video is open.", "Ask which video, or call youtube download with query = the video or song name.")
            val r = search(ctx, acc, q, play = true, pick = a.optString("pick", "first"), song = false)
            if (!r.optBoolean("ok", true) || !isWatch(acc.scan())) return r.put("ok", false).put("error", "Could not open the video to download.")
        }
        if (where == "app") {
            askedWhereAt = 0L
            val n = acc.scan()
            val b = exact(n, "Download", "Download video") ?: starts(n, "Download")
                ?: return finish(acc, "download").put("ok", false).put("verified", false).put("error", "The Download button is not visible under the video.")
                    .put("hint", "Scroll the row of buttons under the video sideways or open the three-dot menu and look for Download. In YouTube, downloads need YouTube Premium in many countries. If the button is missing or asks for Premium, offer him the internal storage way (where storage): it needs no Premium.")
            clickSeen(acc, b)
            acc.awaitStable(200, 1200)
            val after = acc.scan()
            val pick = exact(after, "Download", "Download video") // a quality sheet may ask once more
            if (pick != null && after.any { it.all.contains("quality", true) || it.all.contains("720p") || it.all.contains("480p") }) { clickSeen(acc, pick); acc.awaitStable(200, 1000) }
            return finish(acc, "download").put("where", "app").put("verified", false)
                .put("note", "The Download button was pressed. Say it was requested; check status or the Library > Downloads tab to confirm. If a Premium or sign-in screen shows, tell him honestly.")
        }
        // storage: copy the link in YouTube (first try only: the link then stays on the clipboard for the next pages), then the downloader pages
        if (tryNo == 0 && !copyLink(acc)) return finish(acc, "download").put("ok", false).put("link_copied", false).put("error", "Could not copy the video link from YouTube (the Share or Copy link button did not show).")
            .put("hint", "Look at the screen with get_screen: if the video is in fullscreen or minimized, bring it to the normal watch page; press Share under the video with click_text, then Copy link, then call youtube download again with try 0... or tell him honestly that the link could not be copied.")
        askedWhereAt = 0L; askedFormatAt = 0L
        return openDownloader(ctx, acc, format, tryNo)
    }

    /** Share > Copy link in the YouTube app, with retries: the share sheet can take a moment, and the video may be in fullscreen. true = the Copy link button was pressed. */
    private fun copyLink(acc: NisuAccessibilityService): Boolean {
        // v3.141: the labels also in Bengali and Hindi (YouTube follows the phone language), and the phone's own share sheet "Copy" button.
        val copyLabels = arrayOf("Copy link", "Copy", "Copy URL", "Copy video link", "Copy to clipboard", "Copy link to clipboard",
            "\u09B2\u09BF\u0999\u09CD\u0995 \u0995\u09AA\u09BF \u0995\u09B0\u09C1\u09A8", "\u09B2\u09BF\u0982\u0995 \u0995\u09AA\u09BF \u0995\u09B0\u09C1\u09A8", "\u0995\u09AA\u09BF",
            "\u0932\u093F\u0902\u0915 \u0915\u0949\u092A\u0940 \u0915\u0930\u0947\u0902", "\u0915\u0949\u092A\u0940 \u0932\u093F\u0902\u0915", "\u0915\u0949\u092A\u0940 \u0915\u0930\u0947\u0902")
        val shareLabels = arrayOf("Share", "\u09B6\u09C7\u09AF\u09BC\u09BE\u09B0 \u0995\u09B0\u09C1\u09A8", "\u09B6\u09C7\u09AF\u09BC\u09BE\u09B0", "\u0936\u0947\u092F\u0930 \u0915\u0930\u0947\u0902", "\u0936\u0947\u092F\u0930")
        repeat(4) { round ->
            var n = acc.scan()
            exact(n, "Exit fullscreen")?.let { clickSeen(acc, it); acc.awaitStable(200, 1200); n = acc.scan() }
            // the buttons under the video can sit further right: one swipe on the button row brings Share into view on later tries
            val share = exact(n, *shareLabels) ?: starts(n, "Share")
            if (share != null) {
                clickSeen(acc, share)
                repeat(10) {
                    sleep(400)
                    val s = acc.scan()
                    val c = exact(s, *copyLabels) ?: starts(s, "Copy link", "Copy URL")
                    if (c != null) { clickSeen(acc, c); acc.awaitStable(150, 800); return true }
                }
                acc.key("back"); acc.awaitStable(150, 700)    // close the sheet and try again
            } else {
                if (round == 1) { val w = acc.scan().firstOrNull { it.all.contains("like") }; if (w != null) { val y = w.bounds.centerY().toFloat(); acc.swipe(w.bounds.centerX() + 300f, y, w.bounds.centerX() - 600f, y, 250L) } }
                sleep(600)
            }
        }
        return false
    }

    /**
     * Opens the downloader pages from [startAt] on, one after the other in Chrome, until one has the pasted YouTube link in its box. For each page: open it, wait for its
     * text box, tap it, paste, check that the box now holds a youtube link (else long-press > Paste), then press the page's own Start / Download / Convert button.
     * A page whose box never shows, or that refuses the paste, is skipped at once and the next one opens.
     */
    private fun openDownloader(ctx: Context, acc: NisuAccessibilityService, format: String, startAt: Int): JSONObject {
        val sites = downloaderSites(Prefs.get(ctx, Prefs.YT_DL_SITE, ""), format)
        val tried = JSONArray()
        var i = startAt
        while (i < sites.size) {
            val site = sites[i]
            PhoneControl.openUrl(ctx, site, "chrome")
            val got = pasteOnPage(acc)
            tried.put(JSONObject().put("page", site).put("result", got))
            if (got == "pasted") {
                val fmtText = if (format == "mp3") "AUDIO only (mp3)" else "VIDEO (the normal video, mp4)"
                val more = if (i + 1 < sites.size) "If this page fails to give a download (error, blank result, only ads, nothing saves), call youtube download again with where storage, format $format and try ${i + 1}: it opens the next page and pastes the link there." else "This was the last page: if it fails, tell him honestly and offer the YouTube app download."
                return JSONObject().put("ok", true).put("where", "storage").put("format", format).put("link_pasted", true).put("downloader", site).put("try", i).put("pages_tried", tried).put("pages_left", sites.size - i - 1).put("verified", false)
                    .put("next_steps", "The link is pasted into the box of this page and its start button was pressed. Now: 1) get_screen; 2) choose $fmtText (a tab, button or list named Audio / MP3, or Video / MP4); 3) press the Download button of that result; 4) if an ad or pop-up shows, close it and come back to Chrome, never tap ads; 5) when Chrome asks to save, accept: the file goes to the Download folder of the internal storage. $more Tell him honestly what happened. Keep talking to him while you do it. AFTER it is saved, if he asks you to play it: call file_search (kind video or music, newest first, folder Download), then file_open on the newest file that matches, and say which file you opened; play only when he asked for it.")
            }
            i++
        }
        return JSONObject().put("ok", false).put("pages_tried", tried).put("error", "None of the download pages took the link.").put("tell_him", "Tell him honestly that none of the download pages worked right now (the link could not be pasted) and offer the YouTube app download instead.")
    }

    /** v3.141: how likely an input box is the one meant for the video link (hint / text / description). */
    private fun linkBoxScore(s: NisuAccessibilityService.Seen): Int {
        val t = (s.text + " " + s.desc + " " + (s.node.hintText?.toString() ?: "")).lowercase()
        var sc = 0
        if (Regex("paste|link|url|youtu|video").containsMatchIn(t)) sc += 10
        if (t.contains("search")) sc += 2
        if (Regex("email|e-mail|password|name|phone|comment|message").containsMatchIn(t)) sc -= 10
        sc += (s.bounds.width() / 150).coerceAtMost(6)
        return sc
    }

    /** Cookie / consent banners cover the box of many downloader pages. */
    private val CONSENT = arrayOf("Accept", "Accept all", "Accept All", "I agree", "I Agree", "Agree", "Got it", "Consent", "Allow all cookies", "AGREE", "ACCEPT")

    /**
     * One page: wait for its text box, paste the clipboard into it, press its start button. Returns "pasted" or the reason it failed.
     * v3.141: closes consent banners, tries the most likely boxes in order (not only the first one), checks every paste by reading the box again,
     * and uses the Paste item of Chrome's long-press menu (also in Bengali / Hindi) when the direct paste is refused.
     */
    private fun pasteOnPage(acc: NisuAccessibilityService): String {
        fun boxes() = acc.scan().filter { it.node.isEditable && it.node.isVisibleToUser }.sortedByDescending { linkBoxScore(it) }
        var list = emptyList<NisuAccessibilityService.Seen>()
        for (k in 0 until 28) {          // up to about 14 s for the page to load
            sleep(500)
            if (acc.fgPkg != "com.android.chrome" && k > 6) return "chrome_not_in_front"
            if (k % 3 == 2) acc.scan().firstOrNull { !it.node.isEditable && it.clickable && CONSENT.any { l -> it.text.equals(l, true) || it.desc.equals(l, true) } }?.let { clickSeen(acc, it); sleep(400) }
            list = boxes()
            if (list.isNotEmpty()) break
        }
        if (list.isEmpty()) return "no_text_box"
        fun holdsLink(): Boolean = ((acc.focusedText() ?: "") + " " + acc.scan().filter { it.node.isEditable }.joinToString(" ") { it.text }).contains("youtu", true)
        val pasteLabels = arrayOf("Paste", "Paste from clipboard", "\u09AA\u09C7\u09B8\u09CD\u099F", "\u092A\u0947\u0938\u094D\u091F \u0915\u0930\u0947\u0902", "\u092A\u0947\u0938\u094D\u091F")
        var done = false
        for (b in list.take(3)) {
            clickSeen(acc, b); sleep(350)
            acc.pasteIntoFocus(null); sleep(350)
            if (holdsLink()) { done = true; break }
            acc.longPress(b.bounds.centerX().toFloat(), b.bounds.centerY().toFloat()); sleep(600)   // Chrome's own menu
            val p = exact(acc.scan(), *pasteLabels)
            if (p != null) { clickSeen(acc, p); sleep(500) }
            if (holdsLink()) { done = true; break }
        }
        if (!done) return "paste_refused"
        val goLabels = listOf("Start", "Download", "Convert", "Go", "Search", "Get Link", "Get link", "Submit", "Download Now", "Fetch", "Analyze", "Next")
        val go = acc.scan().firstOrNull { s -> !s.node.isEditable && goLabels.any { l -> s.text.equals(l, true) || s.desc.equals(l, true) } }
        if (go != null) clickSeen(acc, go) else acc.key("enter")
        acc.awaitStable(300, 2500)
        return "pasted"
    }

    private fun clickLabel(acc: NisuAccessibilityService, name: String, exactLabels: List<String>, startsWith: List<String>): JSONObject {
        val n = acc.scan()
        val b = (if (exactLabels.isNotEmpty()) exact(n, *exactLabels.toTypedArray()) else null) ?: (if (startsWith.isNotEmpty()) starts(n, *startsWith.toTypedArray()) else null)
            ?: return finish(acc, name).put("ok", false).put("error", "The $name button is not on screen.")
        clickSeen(acc, b)
        acc.awaitStable(150, 900)
        return finish(acc, name).put("labels", labels(acc.scan(), 12))
    }

    private fun fullscreen(acc: NisuAccessibilityService, on: Boolean): JSONObject {
        val n = controls(acc)
        val enter = exact(n, "Enter fullscreen", "Full screen"); val exit = exact(n, "Exit fullscreen")
        if (on && enter == null && exit != null) return finish(acc, "fullscreen").put("already", true)
        if (!on && exit == null && enter != null) return finish(acc, "fullscreen").put("already", true)
        val b = (if (on) enter else exit) ?: return finish(acc, "fullscreen").put("ok", false).put("error", "The fullscreen button is not visible.")
        clickSeen(acc, b)
        acc.awaitStable(200, 1200)
        return finish(acc, "fullscreen")
    }

    private fun minimize(acc: NisuAccessibilityService): JSONObject {
        val n = controls(acc)
        val b = exact(n, "Minimize")
        if (b != null) clickSeen(acc, b) else acc.key("back")
        acc.awaitStable(200, 1000)
        return finish(acc, "minimize")
    }

    private fun captions(acc: NisuAccessibilityService): JSONObject {
        val n = controls(acc)
        val b = n.firstOrNull { it.clickable && (it.desc.contains("caption", true) || it.desc.contains("subtitle", true)) }
            ?: return finish(acc, "captions").put("ok", false).put("error", "The captions button is not visible (this video may have none).")
        clickSeen(acc, b)
        sleep(400)
        return finish(acc, "captions").put("verified", false).put("note", "Pressed the captions button; look at the screen to see if text appeared.")
    }

    private fun autoplay(acc: NisuAccessibilityService, on: Boolean): JSONObject {
        val n = controls(acc)
        val b = n.firstOrNull { it.desc.contains("autoplay", true) } ?: return finish(acc, "autoplay").put("ok", false).put("error", "The autoplay switch is not visible.")
        val isOn = b.desc.contains("is on", true) || b.checked
        if (isOn == on) return finish(acc, "autoplay").put("already", true)
        clickSeen(acc, b)
        sleep(450)
        val b2 = acc.scan().firstOrNull { it.desc.contains("autoplay", true) }
        return finish(acc, "autoplay").put("verified", b2 != null && ((b2.desc.contains("is on", true) || b2.checked) == on))
    }

    /** Playback speed / Quality: open the player's menu, open [item], pick [value]. Best effort: the result says verified false. */
    private fun playerMenu(acc: NisuAccessibilityService, item: String, value: String): JSONObject {
        val name = if (item.startsWith("Playback")) "speed" else "quality"
        val n = controls(acc)
        val (_, h) = acc.screenPx()
        val gear = listOf("Settings", "More options", "More").firstNotNullOfOrNull { l -> n.filter { it.clickable && it.desc.equals(l, true) && it.bounds.top < h * 0.45 }.minByOrNull { it.bounds.top } }
            ?: return finish(acc, name).put("ok", false).put("error", "The player's settings button is not visible.")
        clickSeen(acc, gear)
        val menu = waitFor(acc, 3000) { s -> s.any { it.label.startsWith(item, true) } }
            ?: return finish(acc, name).put("ok", false).put("error", "The \"$item\" entry is not in the menu.").put("labels", labels(acc.scan(), 14))
        clickSeen(acc, menu.first { it.label.startsWith(item, true) })
        if (value.isEmpty()) return finish(acc, name).put("labels", labels(acc.scan(), 16)).put("note", "Menu opened; no value was given.")
        var opts = waitFor(acc, 3000) { s -> s.any { it.label.startsWith(value, true) } }
        if (opts == null) { exact(acc.scan(), "Advanced")?.let { clickSeen(acc, it); sleep(500) }; opts = waitFor(acc, 2500) { s -> s.any { it.label.startsWith(value, true) } } }
        val pick = opts?.firstOrNull { it.label.startsWith(value, true) } ?: return finish(acc, name).put("ok", false).put("error", "\"$value\" is not offered.").put("labels", labels(acc.scan(), 16))
        clickSeen(acc, pick)
        sleep(500)
        return finish(acc, name).put("value", value).put("verified", false).put("note", "Chose the entry; the menu closes by itself.")
    }

    private fun tab(acc: NisuAccessibilityService, name: String): JSONObject {
        val want = when (name.trim().lowercase()) {
            "home" -> listOf("Home"); "shorts" -> listOf("Shorts"); "subscriptions", "subs" -> listOf("Subscriptions")
            "library", "you" -> listOf("You", "Library"); else -> return err("tab must be home, shorts, subscriptions or library.")
        }
        val n = acc.scan()
        val b = n.filter { s -> s.clickable && want.any { s.label.equals(it, true) } }.maxByOrNull { it.bounds.top }   // the bottom bar is the lowest
            ?: return finish(acc, "tab").put("ok", false).put("error", "The bottom bar is not visible (a video may be full screen). Press back or minimize first.")
        clickSeen(acc, b)
        acc.awaitStable(200, 1200)
        return finish(acc, "tab").put("tab", name)
    }

    private fun swipeShort(acc: NisuAccessibilityService, up: Boolean): JSONObject {
        val n = acc.scan()
        if (!isShorts(n)) return finish(acc, "shorts").put("ok", false).put("error", "No Short is open. Use youtube tab with name shorts.")
        val (w, h) = acc.screenPx()
        val x = w / 2f
        if (up) acc.swipe(x, h * 0.78f, x, h * 0.28f, 220) else acc.swipe(x, h * 0.28f, x, h * 0.78f, 220)
        acc.awaitStable(250, 1200)
        return finish(acc, if (up) "shorts_next" else "shorts_prev")
    }
}

/** The full YouTube guide the model reads on demand (youtube action guide), so the voice prompt stays small. No dollar signs or triple quotes here. */
object YtKnowledge {
    private val BUTTONS = """
WHERE THE BUTTONS ARE (YouTube Android app, phone upright; names are the screen-reader labels, in English):
BOTTOM BAR: Home, Shorts, Create (the + button), Subscriptions, You (library). Tap again on the active tab to scroll up / refresh.
TOP BAR (Home): YouTube logo, Cast, Notifications (bell), Search (magnifier). Under it a row of chips (All, Music, Gaming, ...), swipe sideways for more.
SEARCH RESULTS: a list of videos. Each result is one big row; its screen-reader label is: title - length - channel - views - age - play video. Filters are under the three dots / filter icon at the top (Upload date, Type, Duration, Features, Sort by).
WATCH PAGE (a video is open): the player is at the top.
 - Tap the video ONCE to show the controls (they hide after about 3 seconds). A second tap hides them again.
 - Controls: top-left Minimize (down arrow, makes the mini player); top-right Autoplay switch, Captions (CC), Settings / More options (gear or three dots: Captions, Playback speed, Quality, Loop video, Sleep timer, Stable volume, Help); centre Previous video, Pause video / Play video / Replay video, Next video; bottom-left time; bottom the seek bar; bottom-right Enter fullscreen / Exit fullscreen.
 - Double tap the right side of the video: forward 10 seconds. Double tap the left side: back 10 seconds. More taps in a row keep adding 10 seconds.
 - Swipe down on the player: mini player. Swipe up on the mini player: back to full. In the mini player: Play / Pause and Close (X). Pinch out on the video: zoom to fill.
 - Under the player: the TITLE (tap it to open the description, chapters and transcript), the channel row (picture, name, subscribers, Subscribe button and the bell for notifications), then a row of buttons: Like (with the count), Dislike, Share, Remix / Thanks, Download, Save, Clip, Report (some are under a sideways scroll).
 - Below that: a Comments card (tap to open; sort by Top or Newest) and the Up next list of suggested videos. In a playlist an extra bar shows the playlist and the next videos.
SHORTS: swipe UP for the next Short, swipe DOWN for the previous one. Right side buttons from top: Like, Dislike, Comments, Share, Remix. Bottom: channel name with Subscribe, the sound. Top right: Search and the three dots (captions, quality, report).
YOU (library): History, Your videos, Playlists, Downloads, Your movies, Watch later, Liked videos; the profile picture opens Account / Settings.
SETTINGS (You > profile > Settings): General (Autoplay, Playback in feeds, Appearance), Video quality preferences, Captions, Data saving, Downloads, Notifications, Privacy (watch history, search history), Time watched, Live chat, Accessibility.
"""

    private val FEATURES = """
FEATURES OF YOUTUBE (what it can do):
- Search with text or voice (the mic in the search bar), filters, and search history suggestions.
- Watch: speed (0.25x to 2x), quality (Auto, 144p up to 4K / 1080p Premium), captions and auto translated captions, chapters, transcript, loop video, sleep timer, stable volume, ambient mode, picture in picture (a video keeps playing in a small window when you leave the app, for most videos), background play and downloads (the YouTube app's own Download button needs YouTube Premium in many countries; NISU AI can also download a video or song to the internal storage through its own downloader pages, with no Premium: say youtube download).
- Queue and playlists: Save to Watch later or a playlist; three dots on a video > Add to queue / Play next in queue; Create a new playlist; Liked videos is automatic when you like.
- Reactions and community: Like, Dislike, Subscribe + bell (All / Personalized / None), comments (add, reply, heart, pin by the creator), Share, Clip (cut a 5-60 second part), Thanks, Super Chat in live streams.
- Live streams and Premieres: live chat, live replay; Premiere countdown.
- Shorts: vertical videos up to 3 minutes, swipe feed, remix, sounds.
- Channels: the tabs Home, Videos, Shorts, Live, Playlists, Community, About; the Subscribe bell.
- Library: History (pause or clear it), Downloads, Playlists, Watch later.
- NISU AI downloads: youtube action download. where app = YouTube's Download button; where storage = the video link is copied and pasted into a downloader page in Chrome (y2mate, savefrom, ytdown, snapyt, ytultra, youtubetoolkit, one after the other), as mp3 or video, saved in the Download folder.
- Account: switch account, Incognito mode (profile picture > Turn on Incognito), Restricted mode, Time watched, Take a break and bedtime reminders.
- Cast to TV (the Cast icon), Connect to TV with a code.
"""

    private val ADS = """
ADS ON YOUTUBE (what they are and what NISU AI does):
- Skippable video ad: plays first for about 5 seconds, then a Skip button (also called Skip ad / Skip) appears at the bottom right of the video. NISU AI presses it by itself when the switch Preferences > YouTube ads is on, or when you call youtube skip_ad.
- Non-skippable ad (15-20 seconds) and Bumper ad (6 seconds): there is NO Skip button and no way to skip. NISU AI waits (youtube skip_ad waits up to about 12 seconds) and then reports ad_playing_unskippable or ad_countdown. Tell him honestly that it must play; never say it was skipped.
- Banner / overlay ad on the video: a small X closes it; it does not stop the video.
- Sponsored cards in search results and Home are labelled Sponsored: NISU AI never opens them when picking a video.
- Sponsored segments inside a video are part of the video; they cannot be skipped by a button (he can seek forward).
- NISU AI never taps an ad's Visit advertiser button. During an ad it does not tap the video (that would open the advertiser).
"""

    private val SONGS = """
HOW TO HANDLE SONG REQUESTS (follow exactly):
1. He names a song or artist ("play Tum Hi Ho", "Arijit Singh gaan chalao"): youtube action play with query = the song and artist as he said. Never ask again.
2. He says only "search a song" / "gaan search koro" and names nothing: ask ONE short question in his language: which song? (and say that if he does not mind, you will pick a trending one). Then do 1 with his answer.
3. He says any / whatever / you choose / "jekono" / "tomar moto ekta": youtube action play_trending. Pass language when you know the one he likes or speaks (Bangla, Hindi, English ...), and genre if he said one. It picks a random song of a normal song length from the top results of the trending search and plays it. Then tell him in one short sentence which song started (the result title).
4. An ad starts: it is skipped by itself or by the result (ad: skipped). If ad is ad_playing_unskippable or ad_countdown, say you are waiting for the ad to finish; later call youtube status and, if the ad is gone, confirm the song.
5. He says next / skip: youtube next. Previous: youtube previous. Pause / stop: youtube pause. Continue: youtube resume. Louder / softer: set_volume (not a YouTube button).
6. Say a song is playing ONLY when the result has verified true (player playing). If verified is false, say what you saw (ad, loading, paused) and check with youtube status.
"""

    private val TROUBLE = """
IF SOMETHING FAILS:
- Do not repeat the same action more than twice. Read the result: error, hint, labels (what is on screen), screen_kind (list, watch, shorts, other).
- screen_kind other and no video: YouTube may show a sign-in, a consent page or an update prompt: tell him; sign-in and passwords are his to do.
- Buttons missing on the watch page: the controls are hidden (youtube does one tap to show them) or the video is full screen (use youtube fullscreen on false) or an ad covers them.
- The label names follow the phone language. If the phone is not in English the names differ: use get_screen and click_text with the words you see.
- Never use tap with count 2 for a single action. A double tap is only for seeking, and youtube seek does it.
"""

    fun guide(topic: String): String = when (topic.trim().lowercase()) {
        "buttons", "ui", "where" -> BUTTONS
        "features" -> FEATURES
        "ads", "ad" -> ADS
        "songs", "song", "music" -> SONGS
        "trouble", "troubleshooting", "problems" -> TROUBLE
        else -> BUTTONS + FEATURES + ADS + SONGS + TROUBLE
    }.trim()
}
