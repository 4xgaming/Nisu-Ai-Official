package ai.nisu.assistant

/**
 * v3.56: steadier phone control (written for 3.8 Live, active for every model).
 * Two guards that stop the model from doing the same thing twice or tapping on a screen it has not read:
 *  1. A REPEAT of a one-shot action (the same tap, click, typing, open ... with the same arguments) that arrives within 1.5 s of the same
 *     action finishing OK is not done again: it means the model fired it again without reading the result. The model gets the current
 *     screen instead. Actions that are meant to be repeated (scroll, swipe, back / home keys, wait, get_screen) are never blocked.
 *  2. A coordinate tap or long press after the APP ON SCREEN CHANGED since the model last read the screen is not done (the coordinates
 *     belong to another screen). The model gets the new screen and taps again from it. (The pure rule is [staleApp].)
 */
object ToolSteady {
    /** Actions where doing it twice in a row is almost always a mistake (typing twice, opening twice, tapping the same spot twice). */
    val ONE_SHOT = setOf("click_text", "type_text", "open_app", "open_url", "app_do", "tap", "long_press", "send_message", "open_chat",
        "call_number", "install_app", "make_image", "share_media", "share_pick", "paste_text", "set_wallpaper", "download_file", "reply_notification", "send_sms", "unlock_phone")
    const val WINDOW_MS = 1500L

    /** Pure rule: true when [name] with the same argument text [key] ran OK just before ([prevKey], finished at [prevDoneAt]) and is repeated at [now]. */
    fun isRepeat(prevKey: String?, prevDoneAt: Long, prevOk: Boolean, name: String, key: String, now: Long): Boolean =
        prevKey != null && prevOk && name in ONE_SHOT && key == prevKey && now - prevDoneAt in 0..WINDOW_MS

    /** Pure rule: the screen the model read belonged to [shown]; the app on screen is now [now]. Blank on either side means unknown, never stale. */
    fun staleApp(shown: String, now: String): Boolean = shown.isNotBlank() && now.isNotBlank() && shown != now

    /** v3.96: loop guard. The same one-shot action (same arguments) this many times inside [LOOP_WINDOW_MS] is a loop, not progress. */
    const val LOOP_COUNT = 3
    const val LOOP_WINDOW_MS = 45_000L

    /** Pure rule: [times] are the moments the same action key ran before; true when LOOP_COUNT of them are inside the window and [name] is a one-shot action. */
    fun isLoop(name: String, times: List<Long>, now: Long): Boolean = name in ONE_SHOT && times.count { now - it in 0..LOOP_WINDOW_MS } >= LOOP_COUNT

    private val history = ArrayList<Pair<String, Long>>()   // key to time, newest last, at most 24

    /** true = this call is the 4th of the same one-shot action in 45 s; the caller does not run it. The old entries are dropped so one retry can pass later. */
    @Synchronized fun looping(name: String, argsText: String, nowMs: Long): Boolean {
        val key = name + "|" + argsText
        val hit = isLoop(name, history.filter { it.first == key }.map { it.second }, nowMs)
        if (hit) history.removeAll { it.first == key }
        return hit
    }

    private var prevKey: String? = null
    private var prevDoneAt = 0L
    private var prevOk = false

    @Synchronized fun repeated(name: String, argsText: String, nowMs: Long): Boolean =
        isRepeat(prevKey, prevDoneAt, prevOk, name, name + "|" + argsText, nowMs)

    @Synchronized fun record(name: String, argsText: String, ok: Boolean, nowMs: Long) {
        prevKey = name + "|" + argsText; prevDoneAt = nowMs; prevOk = ok
        history.add((name + "|" + argsText) to nowMs); while (history.size > 24) history.removeAt(0)
    }

    /** A new session or a new spoken turn starts clean. */
    @Synchronized fun reset() { prevKey = null; prevOk = false; prevDoneAt = 0L; history.clear() }
}
