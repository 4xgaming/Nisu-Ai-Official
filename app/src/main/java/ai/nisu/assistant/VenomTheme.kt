package ai.nisu.assistant

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v3.87: VENOM THEME. While Venom mode is on, the screens change by themselves: the Venom photo (res/drawable-nodpi/venom_bg.jpg) replaces the
 * anime picture on the home screen and behind every page, the silver / white accent replaces the blue one on the home screen, and the name shown at
 * the top of the home screen and of the text chat is "Venom". Back in another mode everything returns to the normal NISU AI look.
 *
 * v3.88: it MOVES. Venom slowly breathes and sways (a slight zoom, drift and tilt around his head), a faint light pulses in the white eye, dark
 * tendrils wave in the dark, smoke drifts across and small specks float up. All of it comes from ONE looping clock (24 s) drawn on the GPU layer / canvas
 * only, so nothing is recomposed each frame. It stands still when the phone's animations are switched off (Developer options / Remove animations).
 *
 * Only the DISPLAYED header name changes. The brand name "NISU AI" and "Raju developed NISU AI" stay as they are in Brand.kt, the About screen,
 * the app label, the notifications and the identity answers (see the notice at the top of Brand.kt): this file never touches them.
 */
object VenomTheme {
    const val NAME = "Venom"
    const val TAGLINE = "We are Venom"
    val Silver = Color(0xFFE8EEF4)
    val SilverDim = Color(0xFF8FA3B8)

    /** Length of the one looping clock; every motion below uses a whole number of cycles per loop, so the loop never jumps. */
    const val LOOP_MS = 24_000
    private val TWO_PI = (2.0 * PI).toFloat()

    fun on(mode: String): Boolean = mode == PersonaMode.VENOM

    /** The name shown in the headers: "Venom" in Venom mode, otherwise the normal brand name. */
    fun title(mode: String): String = if (on(mode)) NAME else Brand.APP_NAME

    /** True while Venom mode is on; recomposes the screen the moment the mode changes. */
    @Composable
    fun active(): Boolean = PersonaMode.live.collectAsState().value == PersonaMode.VENOM

    /** Position in the loop, 0..1. A fixed 0 (a still picture) when the phone has animations switched off. */
    @Composable
    internal fun clock(): State<Float> {
        val ctx = LocalContext.current
        val still = remember { try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
        if (still) return remember { mutableStateOf(0f) }
        return rememberInfiniteTransition(label = "venomClock").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "venomT"
        )
    }

    /** Home screen: the living Venom picture (breathing, swaying) with the ambient effects on top. */
    @Composable
    fun Background(modifier: Modifier = Modifier) {
        val t = clock()
        Box(modifier) {
            Image(
                painterResource(R.drawable.venom_bg), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val p = t.value * TWO_PI
                    val breath = (sin(p * 4f) + 1f) / 2f                    // 4 breaths per loop = one every 6 s
                    val s = 1.06f + 0.025f * breath                         // always above 1.03 so the sway never shows an edge
                    scaleX = s; scaleY = s
                    translationX = sin(p * 3f) * 7.dp.toPx()
                    translationY = cos(p * 2f) * 5.dp.toPx() - breath * 4.dp.toPx()
                    rotationZ = sin(p) * 0.35f
                    transformOrigin = TransformOrigin(0.7f, 0.3f)           // he turns around his head, not the middle of the screen
                }
            )
            Ambient(Modifier.fillMaxSize(), 1f, t)
        }
    }

    /** A slow zoom and drift for the blurred picture behind the inner pages (apply it BEFORE the blur, so the blur is not redone each frame). */
    @Composable
    fun drift(): Modifier {
        val t = clock()
        return Modifier.graphicsLayer {
            val p = t.value * TWO_PI
            val s = 1.12f + 0.02f * ((sin(p * 2f) + 1f) / 2f)
            scaleX = s; scaleY = s
            translationX = sin(p * 2f) * 8.dp.toPx()
            translationY = cos(p) * 6.dp.toPx()
        }
    }

    /** The air around him: drifting smoke, waving dark tendrils, a pulse of light in the eye and floating specks. strength 1 = home screen, less = pages. */
    @Composable
    fun Ambient(modifier: Modifier = Modifier, strength: Float = 1f, time: State<Float>? = null) {
        val t = time ?: clock()
        Canvas(modifier) {
            val p = t.value * TWO_PI
            val w = size.width
            val h = size.height
            // 1) smoke: soft dark and faint silver-blue clouds drifting slowly
            for (i in 0 until 6) {
                val cx = w * (0.1f + 0.8f * ((i * 0.37f) % 1f)) + sin(p * (1 + i % 2) + i * 1.7f) * w * 0.14f
                val cy = h * (0.2f + 0.13f * i) + cos(p * (1 + (i + 1) % 2) + i * 2.3f) * h * 0.05f
                val r = w * (0.55f + 0.08f * (i % 3))
                val c = if (i % 2 == 0) Color(0xFF050A14).copy(alpha = 0.34f * strength) else Color(0xFF9FB3C8).copy(alpha = (0.045f + 0.03f * sin(p * 2f + i)) * strength)
                drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
            }
            // 2) dark tendrils that wave (the veins in the dark part of the picture)
            for (i in 0 until 4) {
                val y0 = h * (0.5f + 0.11f * i)
                val a = sin(p * 2f + i * 1.3f)
                val b = cos(p * 3f + i * 0.9f)
                val path = Path().apply {
                    moveTo(-10f, y0)
                    cubicTo(w * 0.25f, y0 - h * 0.05f * a, w * 0.45f, y0 + h * 0.06f * b, w * (0.6f + 0.05f * a), y0 - h * 0.04f * (1 + i * 0.3f) + h * 0.03f * b)
                }
                drawPath(path, Color(0xFF7C9BC0).copy(alpha = (0.07f + 0.04f * a) * strength), style = Stroke(width = (2 + i).dp.toPx(), cap = StrokeCap.Round))
            }
            // 3) the white eye breathes with a faint light
            val pulse = (sin(p * 4f + 0.5f) + 1f) / 2f
            val eye = Offset(w * 0.66f, h * 0.27f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = (0.04f + 0.10f * pulse) * strength), Color.Transparent), center = eye, radius = w * 0.45f), radius = w * 0.45f, center = eye)
            // 4) specks floating up; each rises twice per loop and fades in and out
            for (i in 0 until 18) {
                val seed = i * 0.6180339f
                val x = w * ((seed * 7.3f) % 1f) + sin(p * 2f + i) * w * 0.03f
                val prog = (t.value * 2f + seed * 3.1f) % 1f
                val a = sin(prog * PI.toFloat()) * 0.32f * strength
                drawCircle(Color(0xFFCFE0F0).copy(alpha = a), radius = (0.8f + (i % 3) * 0.7f).dp.toPx(), center = Offset(x, h * (1f - prog)))
            }
        }
    }
}
