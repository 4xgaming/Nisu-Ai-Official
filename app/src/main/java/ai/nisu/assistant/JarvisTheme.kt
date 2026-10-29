package ai.nisu.assistant

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * v3.89: JARVIS THEME. While Jarvis mode is on, the home screen and the page backdrops show the blue HUD picture (res/drawable-nodpi/jarvis_bg.jpg) and
 * the name shown at the top of the home screen and of the text chat is "Jarvis". Any other mode brings the normal look back.
 * The picture itself stays still (so the overlay lines up with it); what moves is drawn on top: the core pulses, light sweeps round the two blue rings
 * in opposite directions, the yellow lights blink one after the other, a scan line passes down the screen and small data specks float up. It uses the
 * same 24 s looping clock as [VenomTheme] (whole cycles only, so no jump) and stands still when the phone's animations are off.
 *
 * v3.90: JARVIS MOVES WITH HIS VOICE. While Jarvis is speaking, the sharp home picture also reacts to the loudness of his voice ([AppState.speechLevel]):
 * the core glows brighter and bigger, a ring of small bars around the lens jumps up and down like a voice equaliser, the two ring sweeps speed up and
 * the yellow lights flash harder. When he stops, everything eases back to the quiet look (fast to rise, slow to fall). Silent = exactly the old look.
 *
 * Only the DISPLAYED header name changes: "NISU AI" and "Raju developed NISU AI" stay in Brand.kt, the About screen, the app label, the notifications
 * and the identity answers.
 */
object JarvisTheme {
    const val NAME = "Jarvis"
    const val TAGLINE = "At your service"
    private val TWO_PI = (2.0 * PI).toFloat()
    private val Ice = Color(0xFF7FE3FF)

    // The picture is 768 x 1376; the reactor centre sits in the middle and its outer ring has a radius of about 350 px.
    private const val IMG_W = 768f
    private const val IMG_H = 1376f

    fun on(mode: String): Boolean = mode == PersonaMode.JARVIS

    /** Raw loudness of his voice (0..1, usually small) -> how strongly the picture reacts. Boosted the same way [EdgeGlow] does it. */
    internal fun voiceTarget(raw: Float): Float = (raw * 2.4f).coerceIn(0f, 1f)

    /** One smoothing step of the reaction: rises fast (follows each word), falls slowly (fades after the sentence). */
    internal fun voiceStep(cur: Float, target: Float, dt: Float): Float {
        val rate = if (target > cur) 16f else 4f
        return cur + (target - cur) * (1f - exp(-rate * dt))
    }

    /** Smoothed voice reaction (0..1) plus an extra spin angle (deg) that grows while he talks, so the sweeps speed up without ever jumping. */
    internal class VoiceFx {
        val level = mutableFloatStateOf(0f)
        val spin = mutableFloatStateOf(0f)
    }

    /** Follows his voice frame by frame. Stays at 0 (a still picture) when the phone has animations switched off. */
    @Composable
    internal fun voiceFx(): VoiceFx {
        val ctx = LocalContext.current
        val still = remember { try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
        val fx = remember { VoiceFx() }
        if (!still) LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    last = now
                    val next = voiceStep(fx.level.floatValue, voiceTarget(AppState.speechLevel()), dt)
                    val v = if (next < 0.004f) 0f else next
                    if (v != fx.level.floatValue) fx.level.floatValue = v
                    if (v > 0f) fx.spin.floatValue = (fx.spin.floatValue + v * 140f * dt) % 360f
                }
            }
        }
        return fx
    }

    /** The name shown in the headers: "Jarvis" in Jarvis mode, otherwise the normal brand name. */
    fun title(mode: String): String = if (on(mode)) NAME else Brand.APP_NAME

    /** True while Jarvis mode is on; recomposes the screen the moment the mode changes. */
    @Composable
    fun active(): Boolean = PersonaMode.live.collectAsState().value == PersonaMode.JARVIS

    /** Home screen: the HUD picture with the moving overlay. */
    @Composable
    fun Background(modifier: Modifier = Modifier) {
        val t = VenomTheme.clock()
        Box(modifier) {
            Image(painterResource(R.drawable.jarvis_bg), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
            Ambient(Modifier.fillMaxSize(), 1f, t, hud = true)
        }
    }

    /**
     * The moving layer. hud = true draws the parts that sit on the picture's own rings (core pulse, ring sweeps, blinking lights): only for the sharp
     * home picture. hud = false (the blurred page backdrop) keeps just the scan line and the specks.
     */
    @Composable
    fun Ambient(modifier: Modifier = Modifier, strength: Float = 1f, time: State<Float>? = null, hud: Boolean = false) {
        val t = time ?: VenomTheme.clock()
        val fx = if (hud) voiceFx() else null   // v3.90: only the sharp home picture reacts to his voice
        Canvas(modifier) {
            val p = t.value * TWO_PI
            val v = fx?.level?.floatValue ?: 0f      // 0 = silent, 1 = loud
            val spin = fx?.spin?.floatValue ?: 0f
            val w = size.width
            val h = size.height
            // where the picture is drawn: it fills the height and is cropped at the sides (ContentScale.Crop, centred)
            val scale = max(w / IMG_W, h / IMG_H)
            val ox = (IMG_W * scale - w) / 2f
            val oy = (IMG_H * scale - h) / 2f
            fun px(ix: Float) = ix * scale - ox
            fun py(iy: Float) = iy * scale - oy
            val c = Offset(px(384f), py(690f))
            val r = 350f * scale

            if (hud) {
                // 1) the core breathes with a cyan light (one beat every 4 s)
                val pulse = (sin(p * 6f) + 1f) / 2f
                val coreR = r * (1.05f + 0.08f * v)   // v3.90: bigger and brighter while he talks
                drawCircle(Brush.radialGradient(listOf(Ice.copy(alpha = ((0.05f + 0.13f * pulse) + 0.25f * v).coerceIn(0f, 1f) * strength), Color.Transparent), center = c, radius = coreR), radius = coreR, center = c)
                // 1b) v3.90: while he speaks, a light at the lens and a ring of small bars jump with his voice (the dark space between the lens and the bright inner ring)
                if (v > 0.01f) {
                    val lensR = r * 0.42f
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f * v * strength), Ice.copy(alpha = 0.16f * v * strength), Color.Transparent), center = c, radius = lensR), radius = lensR, center = c)
                    val baseR = r * 0.30f
                    drawCircle(Ice.copy(alpha = (0.45f * v * strength).coerceIn(0f, 1f)), radius = baseR + r * 0.012f * v, center = c, style = Stroke(width = 1.5.dp.toPx()))
                    val bars = 72
                    val spinRad = spin * PI.toFloat() / 180f
                    for (i in 0 until bars) {
                        val ang = i * TWO_PI / bars + spinRad
                        val wave = 0.5f + 0.5f * sin(i * TWO_PI / bars * 5f + p * 48f)   // a wave running round the ring
                        val flick = 0.5f + 0.5f * sin(p * 60f + i * 2.3f)                // each bar also jumps on its own
                        val amp = v * (0.30f + 0.40f * wave + 0.30f * flick)
                        val len = r * (0.012f + 0.16f * amp)
                        val dx = cos(ang)
                        val dy = sin(ang)
                        drawLine(Ice.copy(alpha = ((0.35f + 0.55f * amp) * strength).coerceIn(0f, 1f)),
                            start = Offset(c.x + dx * baseR, c.y + dy * baseR), end = Offset(c.x + dx * (baseR + len), c.y + dy * (baseR + len)),
                            strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                // 2) light sweeps round the blue band (clockwise, one turn every 12 s) and round the inner ring (the other way, every 24 s)
                fun sweep(radius: Float, headDeg: Float, widthDp: Float) {
                    for (k in 0 until 8) {
                        val a = (0.6f * (1f - k / 8f) * (1f + 0.5f * v) * strength).coerceIn(0f, 1f)
                        drawArc(Ice.copy(alpha = a), startAngle = headDeg - (k + 1) * 6f, sweepAngle = 6.5f, useCenter = false,
                            topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2f, radius * 2f), style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Butt))
                    }
                }
                sweep(r * 0.79f, t.value * 720f - 90f + spin, 5f)          // v3.90: + spin = faster while he talks
                sweep(r * 0.57f, -t.value * 360f - 90f - spin * 0.6f, 3f)
                // 3) the yellow lights on top of the band blink one after the other
                val dots = floatArrayOf(290f, 490f, 381f, 471f, 420f, 473f, 458f, 486f)
                for (i in 0 until 4) {
                    val on = (sin(p * 4f - i * 0.9f) + 1f) / 2f
                    drawCircle(Color(0xFFFFE066).copy(alpha = ((0.15f + 0.75f * on * on + 0.35f * v).coerceIn(0f, 1f)) * strength), radius = (5f + 2f * v).dp.toPx(), center = Offset(px(dots[i * 2]), py(dots[i * 2 + 1])))
                }
            }
            // 4) a scan line passes down the screen (every 12 s)
            val y = h * ((t.value * 2f) % 1f)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Ice.copy(alpha = 0.10f * strength), Color.Transparent), startY = y - 60.dp.toPx(), endY = y + 60.dp.toPx()),
                topLeft = Offset(0f, y - 60.dp.toPx()), size = Size(w, 120.dp.toPx()))
            drawRect(Ice.copy(alpha = 0.22f * strength), topLeft = Offset(0f, y), size = Size(w, 1.dp.toPx()))
            // 5) small data specks float up and fade
            for (i in 0 until 14) {
                val seed = i * 0.6180339f
                val x = w * ((seed * 5.7f) % 1f)
                val prog = (t.value * 2f + seed * 2.3f) % 1f
                val a = sin(prog * PI.toFloat()) * 0.30f * strength
                drawRect(Ice.copy(alpha = a), topLeft = Offset(x, h * (1f - prog)), size = Size((1.5f + (i % 3)).dp.toPx(), (1.5f + (i % 3)).dp.toPx()))
            }
        }
    }
}
