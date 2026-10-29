package ai.nisu.assistant

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.sin

/*
 * v2.38: EDGE GLOW.
 *
 * A glowing light drawn all around the screen edge (like the neon phone frame). It is a real system overlay
 * (TYPE_APPLICATION_OVERLAY, not touchable, not focusable), so it keeps glowing over every other app and on the home
 * screen, even when NISU AI is not on screen, for as long as
 *     - the Edge Glow switch is on,
 *     - NISU AI is running (and awake), and
 *     - the "Display over other apps" permission is granted.
 *
 * Two modes (both customisable in the Edge Glow page): ALWAYS (steady glow while NISU AI is on) and SPEAK (the light
 * fades in slowly and follows the loudness of her voice, and fades out when she stops).
 */
object EdgeGlow {
    const val K_ON = "edge_glow_on"
    const val K_MODE = "edge_glow_mode"
    const val K_BRIGHT = "edge_glow_bright"   // 10..100
    const val K_WIDTH = "edge_glow_width"     // 2..24 dp
    const val K_COLOR = "edge_glow_color"     // rainbow | cyan | blue | purple | pink | red | orange | green | white | custom
    const val K_HUE = "edge_glow_hue"         // 0..359, used when the colour is "custom"
    const val K_SPEED = "edge_glow_speed"     // 0..100, how fast the rainbow travels around the screen

    const val MODE_ALWAYS = "always"
    const val MODE_SPEAK = "speak"

    class Cfg(val on: Boolean, val mode: String, val bright: Int, val width: Int, val color: String, val hue: Int, val speed: Int)

    @Volatile var cfg = Cfg(false, MODE_ALWAYS, 80, 8, "rainbow", 200, 40)
    /** uptime millis until which the "Test glow" preview is shown (even when NISU AI is not running). */
    @Volatile var previewUntil = 0L

    private var app: Application? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rev = MutableStateFlow(0)
    private var view: GlowView? = null

    /** v3.141: free version = the glow is always ON, fixed to "glows with her voice", default colour; it cannot be changed without a premium key. */
    private fun freeCfg() = Cfg(true, MODE_SPEAK, 80, 8, "rainbow", 200, 40)
    private var lastPremium: Boolean? = null

    fun load(c: Context): Cfg = if (!FreeTier.premium(c)) freeCfg() else Cfg(
        on = Prefs.getBool(c, K_ON, false),
        mode = Prefs.get(c, K_MODE, MODE_ALWAYS).let { if (it == MODE_SPEAK) MODE_SPEAK else MODE_ALWAYS },
        bright = Prefs.getInt(c, K_BRIGHT, 80).coerceIn(10, 100),
        width = Prefs.getInt(c, K_WIDTH, 8).coerceIn(2, 24),
        color = Prefs.get(c, K_COLOR, "rainbow"),
        hue = Prefs.getInt(c, K_HUE, 200).coerceIn(0, 359),
        speed = Prefs.getInt(c, K_SPEED, 40).coerceIn(0, 100)
    )

    /** Call after any setting changed: the glow on screen follows immediately. */
    fun reload(c: Context) {
        cfg = load(c)
        rev.value = rev.value + 1
    }

    fun canDraw(c: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    /** Started once from the Application class, so the glow also works when only the background / assistant service is alive. */
    fun init(a: Application) {
        app = a
        cfg = load(a)
        scope.launch {
            combine(AppState.running, AppState.mode, AppState.online, rev) { _, _, _, _ -> 0 }.collect { update() }
        }
    }

    /** v3.76: she just came online (woke up, reconnected or changed model): the glow lights for a second so the owner can see it. */
    fun flash(ms: Long = 1000L) {
        if (!cfg.on) return
        previewUntil = maxOf(previewUntil, SystemClock.uptimeMillis() + ms)
        main.post { update() }
        main.postDelayed({ update() }, ms + 150L)
    }

    /** Shows the glow for about 5 seconds so the owner can see the current settings. */
    fun preview(c: Context) {
        if (!canDraw(c)) return
        previewUntil = SystemClock.uptimeMillis() + 5000L
        update()
        main.postDelayed({ update() }, 5200L)
    }

    /**
     * v2.73: apps such as the Google Play Store refuse taps while ANY overlay is drawn over them ("Looks like another app is blocking access
     * to Google Play"). The glow is therefore taken off the screen for a while whenever NISU AI works in such an app, and comes back by itself.
     */
    @Volatile private var pausedUntil = 0L
    val showing: Boolean get() = view != null

    fun pause(ms: Long) {
        pausedUntil = maxOf(pausedUntil, SystemClock.uptimeMillis() + ms)
        main.post { update() }
        main.postDelayed({ update() }, ms + 250L)
    }

    fun resume() {
        pausedUntil = 0L
        main.post { update() }
    }

    private fun shouldShow(a: Application): Boolean {
        val live = cfg.on && AppState.running.value && AppState.mode.value == AssistantMode.ACTIVE && AppState.online.value   // v3.76: the glow means "she is online"
        val preview = SystemClock.uptimeMillis() < previewUntil
        return (live || preview) && canDraw(a) && SystemClock.uptimeMillis() >= pausedUntil
    }

    private fun update() {
        val a = app ?: return
        val prem = FreeTier.premium(a)
        if (lastPremium != prem) { lastPremium = prem; cfg = load(a) }   // v3.141: a key was added / expired: the glow settings follow at once
        if (shouldShow(a)) show(a) else hide(a)
    }

    private fun show(a: Application) {
        if (view != null) return
        val v = GlowView(a)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try {
            a.getSystemService(WindowManager::class.java).addView(v, lp)
            view = v
        } catch (_: Throwable) { view = null }
    }

    private fun hide(a: Application) {
        val v = view ?: return
        view = null
        v.stop()
        try { a.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    /** Colour for a named choice (ARGB). */
    fun colorOf(name: String, hue: Int): Int = when (name) {
        "cyan" -> 0xFF00E5FF.toInt()
        "blue" -> 0xFF2B6BFF.toInt()
        "purple" -> 0xFFB04BFF.toInt()
        "pink" -> 0xFFFF4DA6.toInt()
        "red" -> 0xFFFF3030.toInt()
        "orange" -> 0xFFFF9500.toInt()
        "green" -> 0xFF22FF66.toInt()
        "white" -> 0xFFFFFFFF.toInt()
        "custom" -> Color.HSVToColor(floatArrayOf(hue.toFloat(), 1f, 1f))
        else -> 0xFF00E5FF.toInt()
    }

    val RAINBOW = intArrayOf(
        0xFFFF2D95.toInt(), 0xFFFF8A00.toInt(), 0xFFFFE600.toInt(), 0xFF22FF66.toInt(),
        0xFF00E5FF.toInt(), 0xFF2B6BFF.toInt(), 0xFFB000FF.toInt(), 0xFFFF2D95.toInt()
    )

    private class GlowView(c: Context) : View(c) {
        private val dens = c.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
        private val path = Path()
        private val rect = RectF()
        private val mat = Matrix()
        private var sweep: SweepGradient? = null
        private var sw = 0
        private var sh = 0
        private var corner = 0f
        private var last = SystemClock.uptimeMillis()
        private var cur = 0f           // smoothed light level 0..1
        private var angle = 0f
        @Volatile private var dead = false

        fun stop() { dead = true }

        private val LAYER_W = floatArrayOf(6.0f, 4.6f, 3.4f, 2.5f, 1.7f, 1.1f, 0.6f)
        private val LAYER_A = floatArrayOf(0.05f, 0.08f, 0.12f, 0.18f, 0.28f, 0.50f, 0.90f)

        private fun cornerPx(): Float {
            if (Build.VERSION.SDK_INT >= 31) {
                val rc = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                if (rc != null && rc.radius > 0) return rc.radius.toFloat()
            }
            return 34f * dens
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            sw = w; sh = h
            sweep = SweepGradient(w / 2f, h / 2f, RAINBOW, null)
            corner = cornerPx()
        }

        override fun onDraw(c: Canvas) {
            if (dead) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - last).coerceIn(1L, 100L)) / 1000f
            last = now
            val cf = cfg
            val live = cf.on && AppState.running.value
            val preview = !live && now < previewUntil
            val speakMode = cf.mode == MODE_SPEAK

            // how bright the light wants to be right now
            val target = when {
                preview -> if (speakMode) 0.55f + 0.45f * sin(now / 260f) else 1f
                speakMode -> (AppState.speechLevel() * 2.4f).coerceIn(0f, 1f)
                else -> 1f
            }
            // slow fade-in with her voice (attack), even slower fade-out (release)
            val rate = if (target > cur) 6f else 2.2f
            cur += (target - cur) * (1f - exp(-rate * dt))

            if (cur < 0.01f) { postInvalidateDelayed(40L); return }

            val breathe = if (speakMode && !preview) 1f else 0.93f + 0.07f * sin(now / 900f)
            val level = cur * breathe * (cf.bright / 100f)

            val w = cf.width * dens
            val inset = w * 0.5f
            rect.set(inset, inset, sw - inset, sh - inset)
            path.reset()
            val r = (corner - inset).coerceAtLeast(0f)
            path.addRoundRect(rect, r, r, Path.Direction.CW)

            val rainbow = cf.color == "rainbow"
            if (rainbow) {
                angle = (angle + dt * cf.speed * 1.2f) % 360f
                mat.setRotate(angle, sw / 2f, sh / 2f)
                sweep?.setLocalMatrix(mat)
                paint.shader = sweep
            } else {
                paint.shader = null
                paint.color = colorOf(cf.color, cf.hue)
            }

            for (i in LAYER_W.indices) {
                paint.strokeWidth = w * LAYER_W[i]
                paint.alpha = (255f * LAYER_A[i] * level).toInt().coerceIn(0, 255)
                c.drawPath(path, paint)
            }
            // a thin bright core so the edge looks like a lit neon tube
            white.strokeWidth = w * 0.32f
            white.alpha = (255f * 0.38f * level).toInt().coerceIn(0, 255)
            c.drawPath(path, white)

            postInvalidateOnAnimation()
        }
    }
}
