package ai.nisu.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The "hands" of NISU AI: reads the screen tree and performs taps, swipes, typing and system actions. */
class NisuAccessibilityService : AccessibilityService() {
    companion object { @Volatile var inst: NisuAccessibilityService? = null; @Volatile var lastGestureError: String = "" }

    /** Time of the last real UI change in an app window; used to wait until a screen has settled. */
    @Volatile var lastEventAt = 0L
    /** v2.60: package of the app that was last opened / switched to (system UI and NISU AI itself ignored). Game Auto Play uses it to know when you leave the game. */
    @Volatile var fgPkg = ""
        private set

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { dropCover(); if (inst === this) inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }   // never keep a dead service as "connected"
    override fun onInterrupt() {}

    /** NISU's own automation sets this so the lock gate does not fight it (the lock is for people, never for NISU). */
    @Volatile var automationUntil = 0L

    private val ui = Handler(Looper.getMainLooper())

    /**
     * Every action NISU performs keeps the gate open for a few more seconds, and when NISU has been quiet for that long the lock
     * comes back by itself. NISU therefore works on any app while the lock is closed and the screen is on, and a person never gets
     * more than a few seconds of free access out of it.
     */
    private fun busy(ms: Long = 8_000L) {
        automationUntil = maxOf(automationUntil, SystemClock.uptimeMillis() + ms)
        ui.removeCallbacks(recheck)
        ui.postDelayed(recheck, ms + 400L)
    }

    private val recheck: Runnable = Runnable {
        try {
            if (SystemClock.uptimeMillis() < automationUntil) { ui.postDelayed(recheck, 500L); return@Runnable }
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (LockStateManager.isLocked(this) && pm != null && pm.isInteractive) { showCover(); NishuLockManager.guardFromService(this) }
        } catch (_: Throwable) {}
    }

    // ---- cover: an opaque view drawn at once, so a tapped notification's app is never visible or touchable while the lock screen starts ----
    private var cover: View? = null
    @Volatile private var lastGateAt = 0L
    private val dropCoverRunnable: Runnable = Runnable { dropCover() }

    private fun showCover() {
        try {
            if (cover == null) {
                val v = View(this).apply { setBackgroundColor(0xFF05080F.toInt()); isClickable = true }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE
                )
                getSystemService(WindowManager::class.java).addView(v, lp)
                cover = v
            }
            ui.removeCallbacks(dropCoverRunnable)
            ui.postDelayed(dropCoverRunnable, 1_800L)     // safety: the cover can never stay and block the lock screen's own touches
        } catch (_: Throwable) {}
    }

    /** Removes the cover (lock screen is in front, or the phone was unlocked). */
    fun dropCover() {
        ui.removeCallbacks(dropCoverRunnable)
        val v = cover ?: return
        cover = null
        try { getSystemService(WindowManager::class.java).removeViewImmediate(v) } catch (_: Throwable) {}
    }

    /** A notification shade / quick-settings panel (Wi-Fi etc.) is a big System UI window; the thin status bar and the volume bubble are not. */
    private fun shadeOpen(): Boolean = try {
        val h = resources.displayMetrics.heightPixels
        val r = Rect()
        windows.any { w ->
            r.setEmpty(); w.getBoundsInScreen(r)
            r.height() > h * 0.3f && (w.root?.packageName?.toString() == "com.android.systemui")
        }
    } catch (_: Throwable) { false }

    private fun closeShade() {
        try {
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {}
    }

    /**
     * Lock gate: while the NISU lock is closed and the screen is on, any other app that comes to the front
     * (for example from a tapped notification) is covered at once and the lock screen opens. Notifications still show;
     * only entering needs the secret. NISU's own automation and a screen that is off are never blocked.
     */
    private fun gateIfLocked(event: AccessibilityEvent, pkg: String) {
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val cls = event.className?.toString() ?: ""
        if (pkg == packageName) { if (cls.contains("LockScreenActivity")) dropCover(); return }
        if (!LockStateManager.isLocked(this)) { dropCover(); return }
        // The lock screen is in front: the fingerprint prompt, the keyboard and the lock's own windows must never be "gated"
        // (that closed the prompt and relaunched the lock screen again and again).
        if (LockScreenActivity.visible) { dropCover(); return }
        if (SystemClock.uptimeMillis() < automationUntil) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm == null || !pm.isInteractive) return           // screen off: automation keeps working
        // Rate limit: at most one gate action per 1.5 s. The HOME press below produces a launcher event of its own; without this
        // it triggered another cover + HOME + relaunch, a loop that could freeze the phone until a reboot.
        val nowG = SystemClock.uptimeMillis()
        if (nowG - lastGateAt < 1_500L) return
        if (pkg == "com.android.systemui") {
            if (!shadeOpen()) return
            lastGateAt = nowG
            closeShade()
            NishuLockManager.guardFromService(this)
            return
        }
        // the keyboard (needed to type a password) and toasts are not "another app"
        if (cls.startsWith("android.inputmethodservice") || cls.startsWith("android.widget.Toast")) return
        val imeWindow = try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.packageName?.toString() == pkg } } catch (_: Throwable) { false }
        if (imeWindow) return
        // Without "display over other apps" the lock screen cannot open from here: never cover the screen then (an opaque cover
        // with nothing behind it looked like a frozen phone). The lock still guards NISU AI's own screens.
        if (!NishuLockManager.canShowFromBackground(this)) return
        lastGateAt = nowG
        showCover()
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        NishuLockManager.guardFromService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // a "windows changed" event often carries no package: that is how a pulled-down panel shows up
        val p = event.packageName?.toString() ?: (if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) "com.android.systemui" else return)
        try { gateIfLocked(event, p) } catch (_: Throwable) {}
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName != null && p != "com.android.systemui" && p != packageName) fgPkg = p
        if (p == "com.android.systemui") {
            // a shade / quick-settings / volume panel opening or closing IS a change; clock ticks (content changes) are not
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) lastEventAt = SystemClock.uptimeMillis()
            return
        }
        if (p == packageName) return
        if (p == YouTube.PKG) autoSkipAd(event)
        val et = event.eventType
        if (et == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || et == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            et == AccessibilityEvent.TYPE_VIEW_SCROLLED || et == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastEventAt = SystemClock.uptimeMillis()
        }
    }

    // ---- v3.96: YouTube ads (the skip button is pressed by itself when the switch is on) ----
    private val adPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val adBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var adCheckedAt = 0L
    @Volatile private var adSkippedAt = 0L

    private fun autoSkipAd(event: AccessibilityEvent) {
        val et = event.eventType
        if (et != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && et != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = SystemClock.uptimeMillis()
        if (now - adCheckedAt < 500L || now - adSkippedAt < 2_000L) return
        adCheckedAt = now
        if (!Prefs.getBool(this, Prefs.YT_AUTOSKIP, true) || LockStateManager.isLocked(this)) return
        if (!adBusy.compareAndSet(false, true)) return
        try {
            adPool.execute {
                try {
                    val skip = findSkipAd()
                    if (skip != null) { adSkippedAt = SystemClock.uptimeMillis(); clickNode(skip) }
                } catch (_: Throwable) {
                } finally { adBusy.set(false) }
            }
        } catch (_: Throwable) { adBusy.set(false) }
    }

    /** The enabled "Skip" button of a YouTube ad, or null (no ad, or an ad that cannot be skipped yet). */
    fun findSkipAd(): AccessibilityNodeInfo? {
        for (r in visibleRoots()) {
            if (r.packageName?.toString() != YouTube.PKG) continue
            val byId = try { r.findAccessibilityNodeInfosByViewId(YouTube.PKG + ":id/skip_ad_button") } catch (_: Throwable) { emptyList<AccessibilityNodeInfo>() }
            byId.firstOrNull { it.isVisibleToUser && it.isEnabled }?.let { return it }
            for (l in YtRules.SKIP_LABELS) {
                val hit = try { r.findAccessibilityNodeInfosByText(l) } catch (_: Throwable) { emptyList<AccessibilityNodeInfo>() }
                hit.firstOrNull { it.isVisibleToUser && it.isEnabled && YtRules.isSkipLabel(labelOf(it)) }?.let { return it }
            }
        }
        return null
    }

    // ---- waiting helpers (call from a background thread, never the main thread) ----

    /** The active window root, retried for up to [waitMs]: right after an app switch it is briefly null. */
    fun root(waitMs: Long = 900): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        val end = start + waitMs
        while (true) {
            (try { rootInActiveWindow } catch (_: Throwable) { null })?.let {
                try { it.refresh() } catch (_: Throwable) {}
                return it
            }
            // after ~0.18 s without an active window (a dialog or a transition has the focus), use the top app window instead
            if (SystemClock.uptimeMillis() - start >= 180L) topAppRoot()?.let { return it }
            if (SystemClock.uptimeMillis() >= end) return null
            try { Thread.sleep(35) } catch (_: InterruptedException) { return null }
        }
    }

    /** The root of the highest application window (never the keyboard or a system panel). */
    private fun topAppRoot(): AccessibilityNodeInfo? = try {
        var best: AccessibilityNodeInfo? = null
        var layer = Int.MIN_VALUE
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (w.layer >= layer) { best = r; layer = w.layer }
        }
        best?.also { try { it.refresh() } catch (_: Throwable) {} }
    } catch (_: Throwable) { null }

    /** Real screen size in pixels (largest valid x and y), also correct for a service context. */
    @Suppress("DEPRECATION")
    private fun screenMax(): Pair<Float, Float> {
        val dm = android.util.DisplayMetrics()
        try { getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm) } catch (_: Exception) {}
        if (dm.widthPixels <= 0) dm.setTo(resources.displayMetrics)
        return (dm.widthPixels - 1).coerceAtLeast(1).toFloat() to (dm.heightPixels - 1).coerceAtLeast(1).toFloat()
    }

    /** Full real screen rectangle (including areas near the bottom navigation bar so bottom tabs and buttons are never clipped). */
    private fun screenBounds(): Rect {
        val (mx, my) = screenMax()
        return Rect(0, 0, mx.toInt() + 1, my.toInt() + 1)
    }

    /** Converts fractional (0..1) or pixel coordinates into exact screen pixels. */
    private fun resolveXY(x: Float, y: Float): Pair<Float, Float> {
        val (mx, my) = screenMax()
        val frac = x in 0.0001f..1.0f && y in 0.0001f..1.0f
        val px = (if (frac) x * (mx + 1f) else x).coerceIn(0f, mx)
        val py = (if (frac) y * (my + 1f) else y).coerceIn(0f, my)
        return px to py
    }

    /** True when a whole subtree can be skipped: the node is not visible and lies completely off screen (or has no size). */
    private fun offScreen(n: AccessibilityNodeInfo, scr: Rect): Boolean {
        if (n.isVisibleToUser) return false
        val r = Rect(); n.getBoundsInScreen(r)
        return r.isEmpty || !Rect.intersects(r, scr)
    }

    /** Blocks until the UI has been quiet for [quietMs] (or [timeoutMs] passed), so we never read a half-loaded screen. */
    fun awaitStable(quietMs: Long = 80, timeoutMs: Long = 350) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            try { Thread.sleep(20) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Event driven wait after an action that started at [since] (SystemClock.uptimeMillis). Returns as soon as the screen reacted and has been
     * quiet for [quietMs]; if nothing at all changed it returns after [noChangeMs]. Returns true when the screen changed.
     */
    fun settleAfter(since: Long, quietMs: Long = 75, noChangeMs: Long = 155, timeoutMs: Long = 1000): Boolean {
        val entered = SystemClock.uptimeMillis()
        val end = entered + timeoutMs
        while (true) {
            val now = SystemClock.uptimeMillis()
            val last = lastEventAt
            if (last >= since) { if (now - last >= quietMs) return true }
            else if (now - entered >= noChangeMs) return false
            if (now >= end) return last >= since
            try { Thread.sleep(15) } catch (_: InterruptedException) { return lastEventAt >= since }
        }
    }

    // ---- gestures ----
    /** One gesture, retried once when Android cancels it (another gesture or a screen change got in the way). */
    private fun gesture(p: Path, dur: Long): Boolean {
        busy()
        for (attempt in 0 until 2) {
            val latch = CountDownLatch(1)
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
            val posted = try {
                dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { done.set(true); latch.countDown() }
                    override fun onCancelled(d: GestureDescription?) { lastGestureError = "cancelled by Android (another gesture started or the screen changed)"; latch.countDown() }
                }, null)
            } catch (e: Exception) { lastGestureError = "error: " + (e.message ?: e.javaClass.simpleName); false }
            if (posted) {
                latch.await(dur + 3000L, TimeUnit.MILLISECONDS)
                if (done.get()) { lastGestureError = ""; return true }
            } else if (lastGestureError.isBlank()) lastGestureError = "Android did not accept the gesture"
            if (attempt == 0) { try { Thread.sleep(120) } catch (_: InterruptedException) { return false } }
        }
        return false
    }

    /** One finger of a multi-touch: from (x1,y1) to (x2,y2) (same point = a tap / a hold), starting startMs after the gesture began. */
    class Finger(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val startMs: Long, val durMs: Long)

    /** v2.58: most fingers at the same time (a person has 10). Android allows more than 10 strokes; NISU AI stays at 10 like two hands. */
    val maxFingers: Int get() = minOf(10, GestureDescription.getMaxStrokeCount())

    /**
     * Several fingers at once, like a person's hands: every finger is one stroke of ONE gesture, so they really touch together.
     * [repeat] plays the whole gesture again [gapMs] later (3 fingers tapping 4 times = repeat 4).
     * [wait] false = do not wait for the result (fast game play).
     */
    fun multiTouch(fingers: List<Finger>, repeat: Int = 1, gapMs: Long = 60L, wait: Boolean = true): Boolean {
        if (fingers.isEmpty()) return false
        busy()
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        try { getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm) } catch (_: Exception) {}
        if (dm.widthPixels <= 0) dm.setTo(resources.displayMetrics)
        val mx = (dm.widthPixels - 1).coerceAtLeast(1).toFloat(); val my = (dm.heightPixels - 1).coerceAtLeast(1).toFloat()
        val use = fingers.take(maxFingers)
        var allOk = true
        for (round in 0 until repeat.coerceIn(1, 30)) {
            val b = GestureDescription.Builder()
            var total = 1L
            for (f in use) {
                val path = Path().apply {
                    moveTo(f.x1.coerceIn(0f, mx), f.y1.coerceIn(0f, my))
                    if (f.x2 != f.x1 || f.y2 != f.y1) lineTo(f.x2.coerceIn(0f, mx), f.y2.coerceIn(0f, my))
                }
                val st = f.startMs.coerceIn(0L, 10_000L); val du = f.durMs.coerceIn(1L, 10_000L)
                b.addStroke(GestureDescription.StrokeDescription(path, st, du))
                total = maxOf(total, st + du)
            }
            val latch = CountDownLatch(1)
            var ok = false
            val posted = try {
                dispatchGesture(b.build(), object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                    override fun onCancelled(d: GestureDescription?) { lastGestureError = "cancelled by Android (another gesture started or the screen changed)"; latch.countDown() }
                }, null)
            } catch (e: Exception) { lastGestureError = "error: " + (e.message ?: e.javaClass.simpleName); false }
            if (!posted) { if (lastGestureError.isBlank()) lastGestureError = "Android did not accept the gesture"; return false }
            if (wait || round < repeat - 1) { latch.await(total + 3000L, TimeUnit.MILLISECONDS); if (!ok) allOk = false }
            if (round < repeat - 1 && gapMs > 0) { try { Thread.sleep(gapMs) } catch (_: InterruptedException) { return false } }
        }
        return allOk || !wait
    }

    // ---- lock screen helpers (used by Unlocker only) ----
    /** Real screen size in pixels. */
    fun screenPx(): Pair<Int, Int> { val (mx, my) = screenMax(); return (mx.toInt() + 1) to (my.toInt() + 1) }

    /** One finger that touches the first point and slides through all the others (a lock pattern). Pixel coordinates. */
    fun drawPath(points: List<Pair<Float, Float>>, totalMs: Long): Boolean {
        if (points.size < 2) return false
        val p = Path()
        p.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) p.lineTo(points[i].first, points[i].second)
        return gesture(p, totalMs.coerceIn(200L, 6000L))
    }

    /** Bounds of the first visible node of the lock screen (SystemUI / keyguard) whose view id contains [idPart] (ignoring case). */
    fun lockNodeBounds(idPart: String): Rect? {
        val want = idPart.lowercase()
        for (r in visibleRoots()) {
            val found = ArrayList<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo, d: Int) {
                if (d > 40 || found.isNotEmpty()) return
                val id = try { n.viewIdResourceName } catch (_: Throwable) { null }
                if (id != null && id.lowercase().contains(want) && n.isVisibleToUser) { found.add(n); return }
                for (i in 0 until n.childCount) { val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue; walk(c, d + 1) }
            }
            walk(r, 0)
            if (found.isNotEmpty()) { val b = Rect(); found[0].getBoundsInScreen(b); if (!b.isEmpty) return b }
        }
        return null
    }

    /** True when the lock screen (SystemUI / keyguard) has a visible node whose view id contains [idPart]. */
    fun lockHas(idPart: String): Boolean = lockNodeBounds(idPart) != null

    /** Taps a lock screen key whose label equals [label] (a PIN digit, "Enter"), or whose view id contains [idPart]. */
    fun clickLockKey(label: String, idPart: String? = null): Boolean {
        val want = idPart?.lowercase()
        for (r in visibleRoots()) {
            var hit: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo, d: Int) {
                if (d > 40 || hit != null) return
                if (n.isVisibleToUser) {
                    val id = (try { n.viewIdResourceName } catch (_: Throwable) { null }).orEmpty().lowercase()
                    val pkgOk = (n.packageName?.toString().orEmpty()).let { it.contains("systemui") || it.contains("keyguard") || it.contains("lock") }
                    val lab = labelOf(n)
                    if (pkgOk && ((want != null && id.contains(want)) || (lab.isNotEmpty() && lab.equals(label, true) && !n.isEditable))) { hit = n; return }
                }
                for (i in 0 until n.childCount) { val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue; walk(c, d + 1) }
            }
            walk(r, 0)
            hit?.let { return clickNode(it) }
        }
        return false
    }

    fun tap(x: Float, y: Float): Boolean {
        val (px, py) = resolveXY(x, y)
        val t0 = SystemClock.uptimeMillis()
        val gOk = gesture(Path().apply { moveTo(px, py) }, 35)
        // v3.96: a tap that Android completed is ONE tap. It used to be followed by ACTION_CLICK on the node under it when no screen event had
        // arrived yet; slow apps (YouTube) reacted a moment later, so the same spot was activated twice (play / pause, like / unlike, a double tap
        // that seeks). Only a gesture Android did NOT complete falls back to ACTION_CLICK. screen_changed in the result tells the model if it worked.
        if (gOk) return true
        val hit = nodeAt(px.toInt(), py.toInt())
        if (hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return false
    }

    fun longPress(x: Float, y: Float): Boolean {
        val (px, py) = resolveXY(x, y)
        return gesture(Path().apply { moveTo(px, py) }, 620)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val (px1, py1) = resolveXY(x1, y1)
        val (px2, py2) = resolveXY(x2, y2)
        return gesture(Path().apply { moveTo(px1, py1); lineTo(px2, py2) }, ms.coerceIn(1L, 5000L))
    }

    /** Deepest smallest clickable (or editable) visible node whose bounds contain (px, py). */
    private fun nodeAt(px: Int, py: Int): AccessibilityNodeInfo? {
        val scr = screenBounds()
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        for (root in visibleRoots()) {
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 38 || offScreen(n, scr)) return
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.contains(px, py)) return
                if (n.isVisibleToUser && n.isEnabled && (n.isClickable || n.isEditable)) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (area in 1 until bestArea) { best = n; bestArea = area }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            try { walk(root, 0) } catch (_: Throwable) {}
            if (best != null) break
        }
        return best
    }

    /** Scrolls the biggest scrollable list by its own scroll action (exact, no finger needed); false when none can move that way. */
    private fun scrollNode(down: Boolean): Boolean {
        val root = root(450) ?: return false
        val id = if (down) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        val scr = screenBounds()
        var best: AccessibilityNodeInfo? = null
        var area = 0L
        fun walk(n: AccessibilityNodeInfo) {
            if (offScreen(n, scr)) return
            if (n.isScrollable && n.isVisibleToUser && n.actionList.any { it.id == id }) {
                val r = Rect(); n.getBoundsInScreen(r)
                val a = r.width().toLong() * r.height()
                if (a > area) { best = n; area = a }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return best?.performAction(id) == true
    }

    fun scroll(direction: String): Boolean {
        val dir = direction.lowercase().trim()
        busy()
        if ((dir == "down" || dir == "up") && scrollNode(dir == "down")) return true
        val (mx, my) = screenMax()
        val w = mx + 1f; val h = my + 1f
        return when (dir) {
            "down" -> swipe(w / 2, h * 0.74f, w / 2, h * 0.24f, 240)   // see content further down
            "up" -> swipe(w / 2, h * 0.24f, w / 2, h * 0.74f, 240)
            "left" -> swipe(w * 0.82f, h / 2, w * 0.18f, h / 2, 240)
            "right" -> swipe(w * 0.18f, h / 2, w * 0.82f, h / 2, 240)
            else -> false
        }
    }

    // ---- nodes ----
    fun labelOf(n: AccessibilityNodeInfo): String = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** Every window the person can see (active one first, then dialogs and popups), never the keyboard. */
    private fun visibleRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        (try { rootInActiveWindow } catch (_: Throwable) { null })?.let { roots.add(it) }
        try {
            for (w in windows.sortedByDescending { it.layer }) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val r = w.root ?: continue
                if (roots.none { it == r }) roots.add(r)
            }
        } catch (_: Throwable) {}
        return roots
    }

    /** The visible, enabled, non-text-field node whose WHOLE label equals one of [labels] (ignoring case), with the label that matched. */
    private fun exactNode(labels: List<String>): Pair<AccessibilityNodeInfo, String>? {
        for (r in visibleRoots()) {
            for (l in labels) {
                val hits = try {
                    r.findAccessibilityNodeInfosByText(l).filter { it.isVisibleToUser && it.isEnabled && !it.isEditable && labelOf(it).equals(l, true) }
                } catch (_: Throwable) { emptyList() }
                if (hits.isNotEmpty()) return Pair(hits.first(), l)
            }
        }
        return null
    }

    /** True when a button or text with exactly one of these labels is on screen (no substring matches such as "Install on more devices"). */
    fun hasExact(labels: List<String>): Boolean = exactNode(labels) != null

    /** Taps the element whose whole label equals one of [labels]; returns the label that was tapped, or null when none is there. */
    fun clickExact(labels: List<String>): String? {
        val m = exactNode(labels) ?: return null
        return if (clickNode(m.first)) m.second else null
    }

    /**
     * Clicks the node itself or its nearest enabled clickable ancestor; if synthetic ACTION_CLICK does not trigger a UI reaction within 85 ms
     * (or if the ancestor is a huge container wrapping a small target), falls back to a physical tap on the target's visible centre.
     */
    fun clickNode(n: AccessibilityNodeInfo): Boolean {
        busy()
        val scr = screenBounds()
        val scrArea = scr.width().toLong() * scr.height().toLong()
        val nr = Rect(); n.getBoundsInScreen(nr); nr.intersect(scr)
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && !(c.isClickable && c.isEnabled) && depth < 6) { c = try { c.parent } catch (_: Throwable) { null }; depth++ }
        val cr = Rect()
        if (c != null) { c.getBoundsInScreen(cr); cr.intersect(scr) }
        val ancestorTooHuge = c != null && c !== n && !nr.isEmpty && (cr.width().toLong() * cr.height().toLong() > scrArea * 40 / 100)
        if (c != null && c.isClickable && !ancestorTooHuge) {
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // v3.96: the node accepted the click: that IS the click. The old code tapped the same place again when no event came within 85 ms,
                // which clicked twice on slow screens (a toggle went on and straight off again). The caller reads the screen to see the result.
                return true
            }
        }
        val r = if (!nr.isEmpty) nr else cr
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** v3.96: one visible element, with its FULL text and description (dumpScreen cuts labels at 80 characters). Used by [YouTube]. */
    class Seen(val text: String, val desc: String, val id: String, val bounds: Rect, val clickable: Boolean, val selected: Boolean, val checked: Boolean, val node: AccessibilityNodeInfo) {
        val label: String get() = text.ifEmpty { desc }
        /** text and description together, lower case, for "contains" tests. */
        val all: String get() = (text + " | " + desc).lowercase()
    }

    /** Every visible element of every visible window (not the keyboard), top window first. Call from a background thread. */
    fun scan(maxNodes: Int = 600): List<Seen> {
        val out = ArrayList<Seen>()
        val scr = screenBounds()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= maxNodes || depth > 40) return
            if (!n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.intersect(scr) && r.width() > 0 && r.height() > 0) {
                val t = n.text?.toString()?.replace("\n", " ")?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.replace("\n", " ")?.trim().orEmpty()
                val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
                if (t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty() || n.isClickable)
                    out.add(Seen(t, d, id, r, n.isClickable && n.isEnabled, n.isSelected, n.isChecked, n))
            }
            for (i in 0 until n.childCount) { val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue; walk(c, depth + 1) }
        }
        for (r in visibleRoots()) { try { r.refresh() } catch (_: Throwable) {}; walk(r, 0) }
        return out
    }

    /** Taps the best match: exact label first, then prefix, preferring clickable, short labels, never a text field. */
    fun clickText(text: String, waitMs: Long = 600): Boolean {
        val q = text.trim()
        if (q.isEmpty()) return false
        val end = SystemClock.uptimeMillis() + waitMs
        while (true) {
            val best = bestMatch(q)
            if (best != null) return clickNode(best)
            // a screen that is still loading: give the element a moment to appear instead of failing at once
            if (SystemClock.uptimeMillis() >= end) return false
            try { Thread.sleep(35) } catch (_: InterruptedException) { return false }
        }
    }

    private fun norm(t: String): String = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim()

    private fun idHint(n: AccessibilityNodeInfo): String {
        val raw = try { n.viewIdResourceName } catch (_: Throwable) { null } ?: return ""
        val sub = raw.substringAfterLast('/').replace('_', ' ').replace('-', ' ').trim()
        return if (sub.length in 2..32 && !sub.all { it.isDigit() }) sub else ""
    }

    /** Short combined text of a clickable row/card's immediate children when the container itself has no direct text. */
    private fun childSummary(n: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 2) return ""
        val parts = ArrayList<String>(3)
        for (i in 0 until minOf(n.childCount, 6)) {
            val c = n.getChild(i) ?: continue
            val t = (c.text ?: c.contentDescription ?: c.hintText)?.toString()?.trim()
            if (!t.isNullOrEmpty()) parts.add(t)
            else if (depth < 2) {
                val sub = childSummary(c, depth + 1)
                if (sub.isNotEmpty()) parts.add(sub)
            }
            if (parts.size >= 3) break
        }
        return parts.joinToString(" ").trim()
    }

    private fun allTextOf(n: AccessibilityNodeInfo): String {
        val primary = labelOf(n)
        if (primary.isNotEmpty()) return primary
        val extra = listOfNotNull(n.hintText, if (Build.VERSION.SDK_INT >= 28) n.tooltipText else null).firstOrNull { !it.isNullOrBlank() }?.toString()?.trim()
        if (!extra.isNullOrEmpty()) return extra
        if (n.isClickable) {
            val cs = childSummary(n)
            if (cs.isNotEmpty()) return cs
        }
        return idHint(n)
    }

    /**
     * v2.97: picks the exact element the person means across both Android's text search AND a full visible-tree scan (including icon buttons,
     * bottom navigation items, and clickable cards/rows whose label is on child views). Rejects negative-score matches (editable text boxes,
     * disabled views, or long chat messages that merely contain the word) so click_text never taps the wrong place.
     */
    private fun bestMatch(q0: String): AccessibilityNodeInfo? {
        val q = norm(q0).trim('"', '\'', '\u201C', '\u201D')
        if (q.isEmpty()) return null
        val ql = q.lowercase()
        val qTokens = ql.split(' ').filter { it.length >= 2 }
        val wordRe = Regex("(^|[^\\p{L}\\p{M}\\p{N}])" + Regex.escape(q) + "($|[^\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
        fun score(n: AccessibilityNodeInfo): Int {
            val l = norm(allTextOf(n))
            if (l.isEmpty()) return -500
            val ll = l.lowercase()
            var s = 0
            if (l.equals(q, true)) s += 115
            else if (l.startsWith(q, true)) s += 55
            else if (wordRe.containsMatchIn(l)) s += 30
            else if (ll.contains(ql)) s += 12
            else if (qTokens.size >= 2 && qTokens.all { ll.contains(it) }) s += 18
            else return -500
            if (n.isClickable) s += 28
            else {   // a label inside a clickable row / card: tapping it works through the parent
                var p: AccessibilityNodeInfo? = try { n.parent } catch (_: Throwable) { null }
                var d = 0
                while (p != null && d < 5) { if (p.isClickable && p.isEnabled) { s += 18; break }; p = try { p.parent } catch (_: Throwable) { null }; d++ }
            }
            if (!n.isEnabled) s -= 120
            if (n.isEditable) s -= 250
            if (!n.isClickable && l.length > q.length * 4 + 18) s -= 90   // a long text that merely contains the words
            s -= (l.length - q.length).coerceIn(0, 35)
            return s
        }
        val roots = visibleRoots()
        val scr = screenBounds()
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0   // strictly require score > 0 so text fields, disabled items and long paragraphs are never tapped by mistake

        for ((wIdx, r) in roots.withIndex()) {
            val winBonus = if (wIdx == 0) 10 else 0
            val hits = try { r.findAccessibilityNodeInfosByText(q).filter { it.isVisibleToUser } } catch (_: Throwable) { emptyList() }
            for (h in hits) {
                val sc = score(h) + winBonus
                if (sc > bestScore) { bestScore = sc; bestNode = h }
            }
            if (bestScore >= 125) return bestNode   // exact clickable match in active window: return at once
            fun scan(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 38 || offScreen(n, scr)) return
                if (n.isVisibleToUser) {
                    val t = norm(allTextOf(n)).lowercase()
                    if (t.contains(ql) || (qTokens.size >= 2 && qTokens.all { t.contains(it) })) {
                        val sc = score(n) + winBonus
                        if (sc > bestScore) { bestScore = sc; bestNode = n }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { scan(it, depth + 1) }
            }
            try { scan(r, 0) } catch (_: Throwable) {}
            if (bestScore >= 85) return bestNode
        }
        return bestNode
    }

    fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        val scr = screenBounds()
        fun find(x: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (x.isEditable && x.isVisibleToUser) return x
            if (offScreen(x, scr)) return null
            for (i in 0 until x.childCount) { x.getChild(i)?.let { c -> find(c)?.let { return it } } }
            return null
        }
        return find(n)
    }

    /** Finds a closed search bar or fake text box (e.g. YouTube, Play Store, Chrome, WhatsApp search header) so type_text can open it automatically. */
    private fun findSearchOrInputTrigger(): AccessibilityNodeInfo? {
        val hints = listOf("search", "সার্চ", "খুঁজুন", "খোঁজ", "type a message", "message", "মেসেজ", "url", "address", "ask", "write")
        for (h in hints) {
            bestMatch(h)?.let { return it }
        }
        return null
    }

    /** Replaces the whole content of a text field. Focuses it first, because many apps ignore SET_TEXT on an unfocused field. */
    fun setNodeText(n: AccessibilityNodeInfo, text: String): Boolean {
        if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun typeText(text: String, replace: Boolean, submit: Boolean = false): Boolean {
        busy()
        var found: AccessibilityNodeInfo? = null
        var triedTrigger = false
        for (i in 0 until 6) {   // the field may appear a moment after the tap that opened it
            val root = root(400) ?: return false
            found = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root)
            if (found != null) break
            if (!triedTrigger && i == 1) {
                triedTrigger = true
                findSearchOrInputTrigger()?.let { clickNode(it); try { Thread.sleep(180) } catch (_: InterruptedException) {} }
            } else {
                try { Thread.sleep(90) } catch (_: InterruptedException) { return false }
            }
        }
        val n = found ?: return false
        val cur = if (replace || n.isShowingHintText) "" else (n.text?.toString() ?: "")
        val want = cur + text
        var typedOk = false
        if (setNodeText(n, want)) {
            if (n.isPassword) typedOk = true
            else {
                try { Thread.sleep(45) } catch (_: InterruptedException) {}
                n.refresh()
                val got = n.text?.toString().orEmpty()
                if (got == want || got.replace("\u200B", "") == want || got.contains(text)) typedOk = true
            }
        }
        if (!typedOk) typedOk = pasteText(n, want)
        if (typedOk && submit) {
            try { Thread.sleep(60) } catch (_: InterruptedException) {}
            pressEnter()
        }
        return typedOk
    }

    private fun pasteText(n: AccessibilityNodeInfo, want: String): Boolean {
        if (n.isPassword) return false
        return try {
            Clip.set(this, want)
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val b = Bundle()
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b)
            if (!n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
            try { Thread.sleep(55) } catch (_: InterruptedException) {}
            n.refresh()
            (n.text?.toString().orEmpty()) == want
        } catch (_: Throwable) { false }
    }

    fun pressEnter(): Boolean {
        busy()
        val r = root(400)
        val focused = r?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: (r?.let { findEditable(it) })
        val t0 = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= 30 && focused != null) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                val waitEnd = SystemClock.uptimeMillis() + 90L
                while (SystemClock.uptimeMillis() < waitEnd) {
                    if (lastEventAt >= t0) return true
                    try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                }
                return true
            }
        }
        // Fallback 1: tap the IME action button (Search / Go / Send / Done / Enter) inside the soft keyboard window
        try {
            val imeLabels = setOf("search", "go", "send", "done", "next", "enter", "সার্চ", "পাঠান", "যান", "সম্পন্ন")
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val ir = w.root ?: continue
                var imeBtn: AccessibilityNodeInfo? = null
                fun scanIme(n: AccessibilityNodeInfo, d: Int) {
                    if (d > 18 || imeBtn != null) return
                    val lbl = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
                    val vid = (try { n.viewIdResourceName } catch (_: Throwable) { null })?.lowercase().orEmpty()
                    if (n.isVisibleToUser && n.isEnabled && (lbl in imeLabels || vid.contains("key_pos_ime_action") || vid.contains("ime_action"))) {
                        imeBtn = n; return
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { scanIme(it, d + 1) }
                }
                scanIme(ir, 0)
                if (imeBtn != null && clickNode(imeBtn!!)) return true
            }
        } catch (_: Throwable) {}
        // Fallback 2: root KEYCODE_ENTER (66)
        if (Root.available() && Root.exec("input keyevent 66", 2000).first == 0) return true
        return false
    }

    fun key(name: String): Boolean {
        val n = name.lowercase()
        if (n != "lock_screen") busy()
        return when (n) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            "enter" -> pressEnter()
            else -> false
        }
    }

    fun openApp(name: String): String? {
        val pm = packageManager
        busy(12_000L)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
        val q = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: return null
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launch)
        return hit.loadLabel(pm).toString()
    }

    // ---- text for copy / paste between apps (password fields are never read) ----
    fun visibleText(max: Int): String {
        val root = root(400) ?: return ""
        val sb = StringBuilder()
        val scr = screenBounds()
        fun walk(n: AccessibilityNodeInfo) {
            if (sb.length >= max) return
            if (offScreen(n, scr)) return
            if (n.isVisibleToUser && !n.isPassword) {
                val t = n.text?.toString()
                if (!t.isNullOrBlank()) sb.append(t).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return sb.toString().take(max)
    }

    fun nodeTextFor(query: String): String? {
        if (query.isBlank()) return null
        val root = root() ?: return null
        val hit = root.findAccessibilityNodeInfosByText(query).firstOrNull { it.isVisibleToUser && !it.isPassword } ?: return null
        return (hit.text ?: hit.contentDescription)?.toString()
    }

    fun focusedText(): String? =
        root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { !it.isPassword }?.text?.toString()

    /** Pastes the clipboard into the focused (or first) text field; falls back to typing the given text. */
    fun pasteIntoFocus(fallback: String?): Boolean {
        busy()
        val root = root() ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        if (n.isPassword) return false
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return fallback != null && typeText(fallback, false)
    }

    /** Class names that say nothing to the model (a plain label or a layout): left out of the screen list to keep it short. */
    private val PLAIN_CLASSES = setOf("", "TextView", "View", "ViewGroup", "LinearLayout", "FrameLayout", "RelativeLayout", "ConstraintLayout", "CardView",
        "RecyclerView", "ListView", "ScrollView", "HorizontalScrollView", "ComposeView", "AppCompatTextView", "MaterialTextView", "LinearLayoutCompat", "CoordinatorLayout")

    private fun hasLabeledChild(n: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 3) return false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val l = (c.text ?: c.contentDescription ?: c.hintText)?.toString()?.trim()
            if (!l.isNullOrEmpty() || hasLabeledChild(c, depth + 1)) return true
        }
        return false
    }

    /**
     * v2.96: the screen as a clean, accurate text list: "[x,y] Class "label" click edit focused disabled scroll checked".
     * - Any label inside a clickable container/row is marked `click` directly (even when its centre differs from the parent container's centre).
     * - Unlabeled clickable wrapper containers that merely wrap labeled children are omitted so they never confuse the AI.
     * - Unlabeled icon buttons use their viewIdResourceName short name (e.g. "search", "send") when available.
     */
    fun dumpScreen(max: Int): String {
        val root = root(380) ?: return "No active window (screen may be locked or transitioning)"
        val scr = screenBounds()
        val sb = StringBuilder("screen=${scr.width()}x${scr.height()} app=${root.packageName}\n")
        var count = 0
        val seen = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo, parentClickable: Boolean) {
            if (count >= max) return
            if (offScreen(n, scr)) return
            val ownClick = n.isClickable && n.isEnabled
            val effClick = ownClick || parentClickable
            val rawLabel = (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace("\n", " ")?.trim()?.take(80)?.ifEmpty { null }
            val label = rawLabel ?: if (ownClick && !n.isEditable && !hasLabeledChild(n)) idHint(n).ifEmpty { null } else null
            val skipBareContainer = label == null && ownClick && !n.isEditable && hasLabeledChild(n)
            if (!skipBareContainer && n.isVisibleToUser && (label != null || ownClick || n.isEditable)) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.intersect(scr) && r.width() > 0 && r.height() > 0) {
                    val center = "${r.centerX()},${r.centerY()}"
                    val key = if (label != null) "${r.centerX() / 16},${r.centerY() / 16}|$label" else "$center|"
                    if (seen.add(key)) {
                        sb.append('[').append(center).append("] ")
                        val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                        if (cls !in PLAIN_CLASSES) sb.append(cls).append(' ')
                        if (label != null) sb.append('"').append(label).append('"')
                        if (effClick) sb.append(" click")
                        if (n.isEditable) sb.append(if (n.isFocused) " edit focused" else " edit")
                        if (n.isSelected) sb.append(" selected")
                        if (!n.isEnabled) sb.append(" disabled")
                        if (n.isScrollable) sb.append(" scroll")
                        if (n.isChecked) sb.append(" checked")
                        sb.append('\n'); count++
                    }
                }
            }
            val nextParentClick = effClick
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, nextParentClick) }
        }
        walk(root, false)
        return sb.toString().replace("  ", " ")
    }
}
