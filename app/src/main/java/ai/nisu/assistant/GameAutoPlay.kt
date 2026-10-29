package ai.nisu.assistant

import android.content.Context
import android.os.BatteryManager
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v2.59: Game Auto Play. Plays Free Fire Classic Squad matches one after another, by itself.
 *
 * Loop (own thread, lives as long as the foreground voice service lives):
 *   screenshot (ScreenCapture) -> Gemini (image + short memory + the user's options) -> JSON {scene, fingers, wait_ms}
 *   -> the fingers are played with ONE multi-touch gesture (NisuAccessibilityService.multiTouch) -> next screenshot.
 * The model only chooses what to touch; it never gets a tool, a shell or any other power. Purchases, top-ups, chat and friend
 * requests are forbidden in the prompt and anything that looks like login / ban / payment stops the whole thing.
 */
object GameAutoPlay {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    @Volatile private var thread: Thread? = null
    @Volatile private var run = false
    @Volatile private var thinkMode = 0      // 0 = thinkingBudget 0, 1 = thinkingLevel low (Gemini 3), 2 = no thinking config
    @Volatile private var lastErr = ""
    @Volatile private var modelIdx = 0
    private var mutedBefore = false

    private const val PKG_FF = "com.dts.freefireth"
    private const val PKG_MAX = "com.dts.freefiremax"

    fun isRunning() = run

    /** Game Auto Play is locked while it is under maintenance. Set to false to open it again. */
    const val MAINTENANCE = true

    fun start(ctx: Context) {
        if (MAINTENANCE || run) return
        val app = ctx.applicationContext
        run = true
        AppState.gameAuto.value = true
        AppState.gameMatches.value = 0
        AppState.gameStage.value = "starting"
        AppState.gameNote.value = ""
        mutedBefore = AppState.muted.value
        AppState.muted.value = true            // the game's sound must never reach the voice session
        AppState.add("Game Auto Play ON")
        val t = Thread({ loop(app) }, "nisu-game")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop(reason: String? = null) {
        if (!run && thread == null) return
        run = false
        thread?.interrupt()
    }

    private fun finish(reason: String) {
        run = false
        thread = null
        AppState.gameAuto.value = false
        AppState.gameStage.value = ""
        AppState.gameNote.value = reason
        AppState.muted.value = mutedBefore
        AppState.add("Game Auto Play OFF: $reason")
    }

    // ---------------------------------------------------------------- helpers
    private fun pkgs(ctx: Context): Pair<String, String>? {
        val pm = ctx.packageManager
        fun has(p: String) = try { pm.getLaunchIntentForPackage(p) != null } catch (_: Exception) { false }
        val pick = when (Prefs.get(ctx, Prefs.GAME_PKG, "auto")) {
            "ff" -> listOf(PKG_FF)
            "max" -> listOf(PKG_MAX)
            else -> listOf(PKG_MAX, PKG_FF)
        }
        val p = pick.firstOrNull { has(it) } ?: return null
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { if (p == PKG_MAX) "Free Fire MAX" else "Free Fire" }
        return p to label
    }

    private fun batteryLow(ctx: Context): Boolean {
        if (!Prefs.getBool(ctx, Prefs.GAME_BATTERY, true)) return false
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return false
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return pct in 1..14 && !charging
    }

    private fun stepMs(ctx: Context): Long = when (Prefs.get(ctx, Prefs.GAME_SPEED, "normal")) { "fast" -> 1200L; "slow" -> 3500L; else -> 2000L }

    private fun sleepMs(ms: Long): Boolean = try { Thread.sleep(ms.coerceAtLeast(1)); run } catch (_: InterruptedException) { false }

    // ---------------------------------------------------------------- main loop
    private fun loop(ctx: Context) {
        var why = "stopped"
        try {
            val game = pkgs(ctx)
            if (game == null) { finish("Free Fire is not installed on this phone"); return }
            if (Prefs.get(ctx, Prefs.KEY, "").isBlank()) { finish("No API key saved"); return }
            // the game must never be opened when touches are impossible: that was "the game opens and nothing happens"
            PhoneControl.accProblem(ctx)?.let { finish(it); return }
            AppState.add("Game: phone control OK, opening the game")
            val label = game.second
            var matches = 0
            var inMatch = false
            var lastScene = ""
            var sceneSince = System.currentTimeMillis()
            var lastLaunch = 0L
            var bad = 0
            var frames = 0
            var blackStreak = 0
            var sameFrames = 0
            var lastFrame: ByteArray? = null
            val memory = ArrayDeque<String>()

            fun launchGame() {
                if (System.currentTimeMillis() - lastLaunch < 15_000L) return
                lastLaunch = System.currentTimeMillis()
                AppState.gameStage.value = "opening $label"
                try { PhoneControl.openApp(ctx, label) } catch (_: Exception) {}
            }
            // v2.60: the game decides how long this runs. While Free Fire is on screen she keeps playing; once you leave it
            // (home, recents, another app, closing it) for a few seconds the whole thing switches itself OFF.
            val gamePkgs = setOf(PKG_FF, PKG_MAX)
            val startedAt = System.currentTimeMillis()
            var seenInGame = false
            var awaySince = 0L
            fun checkLeft(a: NisuAccessibilityService): String? {
                val live = try { a.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                val cur = live?.takeIf { it != "com.android.systemui" && it.isNotBlank() } ?: a.fgPkg
                val now = System.currentTimeMillis()
                if (cur in gamePkgs) { seenInGame = true; awaySince = 0L; return null }
                if (cur.isBlank()) return null
                if (!seenInGame) return if (now - startedAt > 45_000L) "the game did not open" else null
                if (awaySince == 0L) awaySince = now
                return if (now - awaySince >= 4000L) "you left the game" else null
            }
            launchGame()
            if (!sleepMs(6000)) { finish("switched off"); return }

            while (run) {
                val acc = NisuAccessibilityService.inst
                if (acc == null) { why = "Phone control (accessibility) is not connected"; break }
                val left = checkLeft(acc)
                if (left != null) { why = left; break }
                if (batteryLow(ctx)) { why = "battery is low"; break }
                if (!Prefs.getBool(ctx, Prefs.GAME_UNLIMITED, true) && matches >= Prefs.get(ctx, Prefs.GAME_MAX, "5").toIntOrNull().let { it ?: 5 }) {
                    why = "finished $matches matches"; break
                }
                val t0 = System.currentTimeMillis()
                val jpg = try { ScreenCapture.grabJpeg() } catch (_: Exception) { null }
                if (jpg == null) {
                    AppState.add("Game: no screen picture (" + ScreenCapture.lastError + ")")
                    AppState.gameStage.value = "no screen picture"
                    AppState.gameNote.value = "turn Screen Share ON (side menu) and keep phone control (accessibility) ON"
                    if (++bad >= 12) { why = "the screen could not be captured (turn Screen Share ON)"; break }
                    if (!sleepMs(1500)) break
                    continue
                }
                // an almost empty picture is a black screen (loading, or the game hides itself from screenshots): do not waste a request on it
                if (jpg.size < 5000) {
                    blackStreak++
                    ScreenCapture.preferProjection = blackStreak >= 3
                    if (blackStreak == 1 || blackStreak % 5 == 0) AppState.add("Game: screen picture is black/empty (${jpg.size} bytes)" + (if (blackStreak >= 5) " - tap 'Allow screen capture' on the Game page" else ""))
                    if (blackStreak >= 5) AppState.gameNote.value = "Screen picture is black. Tap 'Allow screen capture' on this page."
                    if (blackStreak >= 60) { why = "the screen picture stays black (allow screen capture on the Game page)"; break }
                    if (!sleepMs(1500)) break
                    continue
                }
                blackStreak = 0
                if (lastFrame != null && lastFrame!!.contentEquals(jpg)) sameFrames++ else sameFrames = 0
                if (frames++ < 3) AppState.add("Game: screen picture OK (${jpg.size / 1024} KB)")
                lastFrame = jpg

                val res = ask(ctx, label, jpg, matches, lastScene, ((System.currentTimeMillis() - sceneSince) / 1000).toInt(), sameFrames, memory.toList())
                if (res == null) {
                    // network / quota problem: wait and try again, give up after a while
                    AppState.add("Game: AI problem: $lastErr")
                    AppState.gameStage.value = "AI problem"
                    AppState.gameNote.value = lastErr
                    if (lastHttp == 401 || lastHttp == 403) { why = "the API key was rejected"; break }
                    if (lastHttp == 404) { why = "the AI model was not found ($lastErr). Set a working model in Preferences"; break }
                    if (++bad >= 15) { why = "the AI could not be reached"; break }
                    if (lastHttp == 429) { AppState.gameNote.value = "Gemini quota is used up on every model. Waiting 45 s. Use an API key with billing, or wait for the daily reset."; modelIdx = 0 }
                    if (!sleepMs(if (lastHttp == 429) 45_000L else 4000L)) break
                    continue
                }
                bad = 0
                val scene = res.optString("scene", "other").lowercase().trim()
                val note = res.optString("note", "").take(120)
                AppState.gameStage.value = scene
                AppState.gameNote.value = note
                AppState.add("Game: $scene - $note (${res.optJSONArray("fingers")?.length() ?: 0} fingers)")
                if (scene != lastScene) { lastScene = scene; sceneSince = System.currentTimeMillis() }
                memory.addLast("$scene: $note"); while (memory.size > 4) memory.removeFirst()

                when (scene) {
                    "blocked" -> { why = "the game needs you (login / ban / payment / maintenance): $note"; break }
                    "not_game" -> { if (!seenInGame) launchGame(); if (!sleepMs(2500)) break; continue }
                }
                // a match counts when the player is back out of it
                if (scene in setOf("plane", "parachute", "ground", "buy")) inMatch = true
                else if (inMatch && scene in setOf("lobby", "result", "matchmaking", "mode_select")) {
                    inMatch = false; matches++; AppState.gameMatches.value = matches
                    AppState.add("Match $matches finished")
                }

                val fingers = res.optJSONArray("fingers")
                if (checkLeft(acc) != null) { if (!sleepMs(800)) break; continue }            // left the game while she was thinking: do not touch anything, the top of the loop stops
                val moving = scene in setOf("ground", "parachute")
                var fp = fingers
                // stuck on the same picture and the AI gave no touch (an ad / event banner it cannot find the X of): try the usual close spots one by one
                if ((fp == null || fp.length() == 0) && sameFrames >= 3 && scene in setOf("popup", "other", "lobby", "result", "mode_select", "dead", "spectating")) {
                    val spots = listOf(0.95 to 0.08, 0.93 to 0.14, 0.97 to 0.05, 0.05 to 0.08, 0.5 to 0.88, 0.5 to 0.5)
                    val sp = spots[(sameFrames - 3) % spots.size]
                    fp = JSONArray().put(JSONObject().put("x", sp.first).put("y", sp.second))
                    AppState.add("Game: screen did not change, trying a close spot")
                }
                if (fp != null && fp.length() > 0) {
                    if (play(ctx, acc, fp, jpg, wait = !moving)) { touchOk++; badStreak = 0 } else { touchBad++; badStreak++ }
                    AppState.gameTouch.value = "touches ok $touchOk, failed $touchBad"
                    if (badStreak > 0) AppState.add("Game: touch FAILED (" + NisuAccessibilityService.lastGestureError + ")")
                    if (badStreak >= 6) { why = "Android blocked the touches (check that NISU AI accessibility is ON and allowed)"; break }
                }
                val wait = res.optLong("wait_ms", 600L).coerceIn(100L, 4000L)
                val spent = System.currentTimeMillis() - t0
                if (!sleepMs(maxOf(wait, stepMs(ctx) - spent))) break
            }
        } catch (e: Exception) {
            why = "error: " + (e.message ?: e.javaClass.simpleName).take(80)
        }
        finish(if (!run && why == "stopped") "switched off" else why)
    }

    /** Real size of the screen in pixels (what gesture coordinates use), whatever the rotation. */
    @Suppress("DEPRECATION")
    private fun realMetrics(ctx: Context): DisplayMetrics {
        val dm = DisplayMetrics()
        try { ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm) } catch (_: Exception) {}
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return ctx.resources.displayMetrics
        return dm
    }

    /** Plays the fingers (fractions 0..1 of the screenshot) as ONE multi-touch gesture. Pixel numbers from the model are converted too. */
    private fun play(ctx: Context, acc: NisuAccessibilityService, arr: JSONArray, shot: ByteArray, wait: Boolean): Boolean {
        val dm = realMetrics(ctx)
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(shot, 0, shot.size, o)
        val sw = o.outWidth.coerceAtLeast(1).toDouble(); val sh = o.outHeight.coerceAtLeast(1).toDouble()
        fun fx(v: Double) = ((if (v > 1.5) v / sw else v).coerceIn(0.0, 1.0) * w).toFloat()
        fun fy(v: Double) = ((if (v > 1.5) v / sh else v).coerceIn(0.0, 1.0) * h).toFloat()
        val list = ArrayList<NisuAccessibilityService.Finger>()
        val maxF = if (Prefs.getBool(ctx, Prefs.GAME_MULTI, true)) acc.maxFingers else 1
        for (i in 0 until minOf(arr.length(), maxF)) {
            val t = arr.optJSONObject(i) ?: continue
            if (!t.has("x") || !t.has("y")) continue
            val x1 = fx(t.optDouble("x")); val y1 = fy(t.optDouble("y"))
            val x2 = if (t.has("x2")) fx(t.optDouble("x2")) else x1
            val y2 = if (t.has("y2")) fy(t.optDouble("y2")) else y1
            val slide = x2 != x1 || y2 != y1
            list.add(NisuAccessibilityService.Finger(x1, y1, x2, y2, t.optLong("start_ms", 0L), t.optLong("duration_ms", if (slide) 800L else 80L).coerceIn(30L, 5000L)))
        }
        if (list.isEmpty()) return true
        val ok = try { acc.multiTouch(list, 1, 0L, wait = wait) } catch (_: Exception) { false }
        if (ok) return true
        // the accessibility gesture was refused or cancelled: with root the first finger can still be sent with the shell
        if (rootOk) {
            val f = list[0]
            val cmd = if (f.x1 == f.x2 && f.y1 == f.y2) "input tap ${f.x1.toInt()} ${f.y1.toInt()}" else "input swipe ${f.x1.toInt()} ${f.y1.toInt()} ${f.x2.toInt()} ${f.y2.toInt()} ${f.durMs}"
            val r = try { Root.exec(cmd, f.durMs + 3000L) } catch (_: Exception) { null }
            if (r != null && r.first == 0) return true
        }
        return false
    }

    private val rootOk: Boolean by lazy { try { Root.available() } catch (_: Exception) { false } }
    private var touchOk = 0
    private var touchBad = 0
    private var badStreak = 0

    // ---------------------------------------------------------------- the AI
    @Volatile private var lastHttp = 0

    private fun prompt(ctx: Context, label: String, matches: Int, scene: String, secs: Int, same: Int, mem: List<String>): String {
        val zone = Prefs.getBool(ctx, Prefs.GAME_ZONE, true)
        val loot = Prefs.getBool(ctx, Prefs.GAME_LOOT, true)
        val pop = Prefs.getBool(ctx, Prefs.GAME_POPUPS, true)
        val style = Prefs.get(ctx, Prefs.GAME_STYLE, "balanced")
        val gm = Prefs.get(ctx, Prefs.GAME_MODE, "clash")
        val clash = gm == "clash" || gm == "cs_rank"
        val ranked = gm == "cs_rank" || gm == "br_rank"
        val modeName = when (gm) { "cs_rank" -> "CLASH SQUAD RANKED (CS Rank)"; "br_rank" -> "BATTLE ROYALE RANKED (BR Rank), Squad"; "classic", "br" -> "CLASSIC / BATTLE ROYALE (casual, not ranked), Squad"; else -> "CLASH SQUAD (casual, not ranked)" }
        val styleText = when (style) {
            "safe" -> "SAFE: avoid fights. Loot quietly, keep moving toward the safe circle, fire only when an enemy is clearly shooting at you."
            "aggressive" -> "AGGRESSIVE: when you see an enemy, aim at it and fire in bursts of 1 to 2 seconds, keep moving, then continue."
            else -> "BALANCED: loot, move, and fire a few short bursts (1 or 2) when an enemy is in front of you. Do not chase enemies."
        }
        return """
You are the hands of a player. You see ONE screenshot of $label (Garena Free Fire, played in landscape) and answer with the touches to make now.
GOAL: ${if (clash) "play endless CLASH SQUAD matches (4 vs 4, rounds) by yourself: start a match, in every round buy a gun in the buy menu, run to the enemy side, find and shoot enemies, and when the match is over go back to the lobby and press START again. Never stop by yourself." else "play endless Classic Squad (Battle Royale) matches by yourself: start a match, jump from the plane, land, loot, move, fire a few shots, stay safe, and when the match is over go back to the lobby and press START again. Never stop by yourself."}
Matches finished so far: $matches. Previous scene: ${scene.ifBlank { "none" }} (for $secs s). Identical screenshots in a row: $same${if (same >= 4) " (YOU ARE STUCK: try something different, for example another button, close a popup, or tap the centre of the screen)" else ""}.
Recent steps: ${if (mem.isEmpty()) "none" else mem.joinToString(" | ")}

COORDINATES: fractions 0..1 of this screenshot (x: 0 left to 1 right, y: 0 top to 1 bottom). A finger = {"x","y"} (a tap) or with "x2","y2" (it slides from the first point to the second), "start_ms" (when it touches down) and "duration_ms" (how long it stays on the screen). Up to 10 fingers are played TOGETHER in one gesture, like a person with two thumbs: for example the left thumb holds the joystick while the right thumb fires.
Answer ONLY with JSON, nothing else:
{"scene":"...","note":"what you see and why, max 12 words","fingers":[...],"wait_ms":number}
scene is one of: lobby, mode_select, matchmaking, loading, buy, plane, parachute, ground, dead, spectating, result, popup, not_game, blocked.

WHAT TO DO IN EACH SCENE
- not_game: the phone shows something else (home screen, another app). fingers [] (the app handles it by itself).
- loading / matchmaking (loading bar, "searching", "waiting for players", countdown): fingers [], wait_ms 2500.
- lobby (character in the middle, big START button at the bottom right): ${if (clash) "the mode label near START must read exactly: $modeName. If it does not, scene mode_select: tap the mode label (or the mode / map selector next to START), choose $modeName, confirm. If it is right, tap START." else "the mode label near START must read exactly: $modeName. If it does not, scene mode_select: tap the mode label, choose $modeName, confirm. If it is right, tap START."}
- mode_select: choose $modeName${if (ranked) " (the RANKED tab / Rank label)" else " (the casual / normal one, never ranked)"} as described under lobby, then confirm / close the list.
- popup (event, news, daily reward, ad, rating request, update): ${if (pop) "close it: tap the X / Close / Later / Cancel / outside it. Never accept anything that costs money." else "scene popup, fingers [] (the user closes popups himself)"}
${if (clash) """- buy (Clash Squad buy menu at the start of a round, with weapon list, coins and a countdown): buy one good gun you can afford (SMG / assault rifle / shotgun, e.g. MP40, M1887, AK, M4A1) by tapping it once, then armor / helmet or a gloo wall if coins are left, then close the menu or wait for the round to start. These coins are match coins, not diamonds: buying with match coins is allowed here and only here. Never press anything with a diamond icon.
- ground in Clash Squad: hold the left joystick forward (slide from its centre up or toward the enemy side, 2500 to 3500 ms) and also tap the Run / Sprint button if there is one; jump over obstacles; turn the camera with the right half of the screen to look for enemies; when an enemy is on screen aim at it and press fire in bursts. Keep moving, do not stand still. If you are dead, scene dead (tap nothing that costs diamonds).""" else ""}
- plane (classic only: flying over the map, a Jump / Parachute button): wait a few seconds, then tap the jump button. Drop near the safe area, not on the busiest city if you can tell.
- parachute: hold the left joystick forward (slide up, 2000 ms) to glide toward land, away from water.
- ground (playing on the map): left side = movement joystick (about x 0.12, y 0.75; slide it from its centre in the direction to run, duration 1500 to 3000 ms so you really run), right side = fire button (about x 0.85, y 0.6) and the camera is turned by sliding a finger on the right half of the screen (slide left or right 0.1 to 0.25 to turn). Jump, crouch and reload are small buttons on the right.
  ${if (loot) "LOOT: when a pick-up prompt / item icon appears near the centre or the right, tap it. Pick a gun first, then armor / helmet, medkits. Walk into houses to find items." else "Do not stop to loot."}
  FIGHT STYLE: $styleText
  ${if (zone) "ZONE: look at the minimap (corner) and the top bar. The safe zone is the WHITE circle; the BLUE or RED area / the screen turning blue and the health dropping is the dangerous zone. NEVER walk into or stay in the dangerous zone: if you are outside the white circle or a red zone is near, turn toward the centre of the white circle and run there. Keep inside the safe circle." else "Move toward open ground and buildings."}
- dead / spectating (you died or are watching): tap Exit / Leave / Back to lobby (not Spectate, not Revive-with-diamonds).
- result (rank, stats, rewards, Continue / OK / Back to lobby): tap Continue / OK / Back. Skip any reward-for-money screens.
- blocked: login screen asking for an account or password, "account banned / suspended / blocked", maintenance, forced update, captcha, a payment screen, or a message that repeats and never goes away. fingers [].

HARD RULES
- Never tap anything that costs real money or diamonds: shop, top-up, pass, bundle, crate / spin, "watch ad to earn", gifts (the in-match weapon buy menu with match coins is the only buying allowed). Never open chat, friend, clan or report screens, never type text, never accept invites from others.
- wait_ms: how long to wait before the next screenshot (300 to 3000). In a match keep it small (400 to 800). While moving use a joystick slide of 2500 to 3500 ms so you keep running while the next screenshot is analysed.
- The note must be short. Give the JSON only.
- If you are unsure what the screen is, use scene popup with fingers [] and wait_ms 1500.
""".trimIndent()
    }

    /** One request. Returns the parsed JSON object or null when the call failed. */
    private fun ask(ctx: Context, label: String, jpg: ByteArray, matches: Int, scene: String, secs: Int, same: Int, mem: List<String>): JSONObject? {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        val chosen = Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().removePrefix("models/").ifBlank { Prefs.DEFAULT_CODE_MODEL }
        val models = listOf(chosen, "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash").distinct()
        if (modelIdx >= models.size) modelIdx = 0
        val model = models[modelIdx]
        val b64 = Base64.encodeToString(jpg, Base64.NO_WRAP)
        fun call(mode: Int): JSONObject? {
            val gen = JSONObject().put("temperature", 0.3).put("maxOutputTokens", 2048).put("responseMimeType", "application/json")
            if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            else if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt(ctx, label, matches, scene, secs, same, mem)))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                .put("generationConfig", gen)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                http.newCall(req).execute().use { resp ->
                    lastHttp = if (resp.isSuccessful) 0 else resp.code
                    val txt = resp.body?.string() ?: return null
                    if (!resp.isSuccessful) {
                        lastErr = "HTTP ${resp.code}: " + (try { JSONObject(txt).optJSONObject("error")?.optString("message", "") ?: "" } catch (_: Exception) { "" }).take(110)
                        return null
                    }
                    val parts = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts == null) { lastErr = "empty answer from the AI"; return null }
                    val sb = StringBuilder()
                    for (k in 0 until parts.length()) {
                        val p = parts.optJSONObject(k) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        sb.append(p.optString("text", ""))
                    }
                    val j = parseJson(sb.toString())
                    if (j == null) lastErr = "the AI answer was not JSON: " + sb.toString().take(60)
                    j
                }
            } catch (e: Exception) { lastHttp = -1; lastErr = "network: " + (e.message ?: e.javaClass.simpleName).take(80); null }
        }
        var r = call(thinkMode)
        // this model does not exist for this key: take the next one and keep it
        if (r == null && (lastHttp == 404 || lastHttp == 429) && modelIdx + 1 < models.size) { modelIdx++; AppState.add("Game: model $model " + (if (lastHttp == 429) "has no quota left" else "not found") + ", switching to " + models[modelIdx]); return ask(ctx, label, jpg, matches, scene, secs, same, mem) }
        // the model rejects this thinking setting: try the next one, and remember what works
        while (r == null && lastHttp == 400 && thinkMode < 2) { thinkMode++; r = call(thinkMode) }
        return r
    }

    private fun parseJson(raw: String): JSONObject? {
        var s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        s = s.substring(a, b + 1)
        return try { JSONObject(s) } catch (_: Exception) { null }
    }
}
