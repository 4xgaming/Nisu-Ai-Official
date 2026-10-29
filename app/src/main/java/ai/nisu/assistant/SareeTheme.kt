package ai.nisu.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** v3.101: rules of the "Dusk Saree" ready theme (a still picture). Same rule as the Sunset Garden: only in the NISU AI modes (Care, Friend, Boss), never Jarvis / Venom, never with custom photos. */
object SareeRules {
    fun showing(selected: Boolean, mode: String, customOn: Boolean): Boolean = selected && !customOn && GardenRules.nisuMode(mode)

    /** Size of res/drawable-nodpi/nisu_saree_bg.jpg. If the picture is replaced, change these two numbers and the positions below. */
    const val IMG_W = 941f
    const val IMG_H = 1672f

    /** Scale of the picture on a w x h box with ContentScale.Crop (fills the box, the extra part is cut equally on both sides). */
    fun scale(w: Float, h: Float): Float = kotlin.math.max(w / IMG_W, h / IMG_H)

    // ---- she talks (pure maths, unit tested). All numbers are in picture pixels. The picture is bent on a COLS x ROWS mesh.
    const val COLS = 20
    const val ROWS = 34
    fun meshSize(): Int = (COLS + 1) * (ROWS + 1) * 2

    // Where things are in the picture. Change these if the picture is ever replaced.
    private const val NECK_X = 480f
    private const val NECK_Y = 640f
    private const val JAW_X = 490f
    private const val JAW_Y = 515f
    /** How far (picture pixels) the lower face drops at full voice. Raise it for a wider mouth movement, lower it if it looks odd. */
    const val JAW_DROP = 6f
    private const val DEG = 0.017453292f

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /**
     * Fills [out] with the positions (x0, y0, x1, y1 ...) of a (COLS+1) x (ROWS+1) grid laid over the picture, moved for this moment.
     * [speech] = her voice loudness 0..1 (0 = quiet: only a light breathing and sway), [p] = loop angle in radians (whole cycles per 24 s only).
     * Head: a small nod and tilt with every word, turning round the neck. Shoulders: breathe. Long hair: sways, more while she talks.
     * Lower face: drops a few pixels with the voice, like a jaw. The bottom of the picture never moves.
     */
    fun warpMesh(out: FloatArray, speech: Float, p: Float, dip: Float = 0f, tiltDeg: Float = 0f) {
        val s = speech.coerceIn(0f, 1f)
        val headRot = (0.4f * sin(p * 2f) + s * 0.9f * sin(p * 36f) + tiltDeg.coerceIn(-1.5f, 1.5f)) * DEG
        val headDy = 1.2f * sin(p * 4f) + s * (5f + 4f * sin(p * 56f)) + dip.coerceIn(-6f, 9f)
        val bounce = 1.5f * sin(p * 4f) + s * 2.5f * sin(p * 56f + 0.8f)
        val breathe = 0.005f * sin(p * 4f) + 0.005f * s
        val jawDy = s * JAW_DROP * (0.55f + 0.45f * sin(p * 56f))
        var n = 0
        for (j in 0..ROWS) {
            val y0 = j * IMG_H / ROWS
            for (i in 0..COLS) {
                val x0 = i * IMG_W / COLS
                val wh = 1f - smooth(600f, 780f, y0)                                   // 1 at the head, 0 below the neck
                val wt = smooth(560f, 760f, y0) * (1f - smooth(1000f, 1300f, y0))    // shoulders and chest
                var x = NECK_X + (x0 - NECK_X) * (1f + breathe * wt)
                var y = y0 + bounce * wt
                val ang = headRot * wh
                val c = cos(ang); val sn = sin(ang)
                val dx = x - NECK_X; val dy = y - NECK_Y
                x = NECK_X + dx * c - dy * sn
                y = NECK_Y + dx * sn + dy * c + headDy * wh
                val side = smooth(150f, 300f, abs(x0 - NECK_X))
                val ramp = smooth(700f, 1050f, y0) * (1f - smooth(1150f, 1450f, y0))
                x += sin(p * 3f + y0 * 0.008f) * (4f + 5f * s) * ramp * side         // the long hair sways
                val gx = x0 - JAW_X; val gy = y0 - JAW_Y
                y += jawDy * exp(-(gx * gx + gy * gy) / (2f * 48f * 48f))
                out[n++] = x; out[n++] = y
            }
        }
    }


    // ---------------------------------------------------------------------------------------- the face (v3.103): eyes, blink, mood
    /** Where the eyes and cheeks are in the picture (picture pixels): centre x, centre y, half width, half height, tilt in degrees. Change them if the picture is replaced. */
    val EYE_L = floatArrayOf(375f, 418f, 38f, 20f, -2f)
    val EYE_R = floatArrayOf(538f, 380f, 39f, 20f, -14f)
    val CHEEK_L = floatArrayOf(362f, 458f)
    val CHEEK_R = floatArrayOf(548f, 424f)
    val FACE = floatArrayOf(480f, 430f)
    /** The mouth in the picture (picture pixels): the middle between the two corners, half the distance between the corners, and its tilt in degrees (read from the picture at 4x zoom). */
    val MOUTH = floatArrayOf(483f, 502f, 35f, -14.8f)
    /** How far the mouth is open (0..1) for the voice loudness [raw] (0..1, usually small): every syllable opens it, silence closes it. */
    fun mouthTarget(raw: Float, wobble: Float): Float = (raw * 3.4f).coerceIn(0f, 1f) * (0.72f + 0.28f * wobble.coerceIn(-1f, 1f))
    /** One smoothing step of the mouth: opens fast (follows each syllable), closes a little slower. */
    fun mouthStep(cur: Float, target: Float, dt: Float): Float {
        val rate = if (target > cur) 30f else 14f
        val v = cur + (target - cur) * (1f - exp(-rate * dt.coerceIn(0f, 0.1f)))
        return if (v < 0.01f) 0f else v
    }
    /** How strongly the painted mouth is shown 0..1: 0 = the picture's own mouth (calm, silent), grows smoothly with opening, a different smile or a narrower mouth. */
    fun mouthShow(open: Float, smile: Float, wide: Float): Float = ((open + abs(smile - NATURAL_SMILE) * 1.6f + (1f - wide) * 1.5f) * 9f).coerceIn(0f, 1f)

    /** A point of the HEAD (picture pixels) after the same head movement as [warpMesh] (nod, tilt round the neck), so the eyelids and blush stay glued to the face. out = x, y. */
    fun headPoint(out: FloatArray, x: Float, y: Float, speech: Float, p: Float, dip: Float = 0f, tiltDeg: Float = 0f) {
        val s = speech.coerceIn(0f, 1f)
        val headRot = (0.4f * sin(p * 2f) + s * 0.9f * sin(p * 36f) + tiltDeg.coerceIn(-1.5f, 1.5f)) * DEG
        val headDy = 1.2f * sin(p * 4f) + s * (5f + 4f * sin(p * 56f)) + dip.coerceIn(-6f, 9f)
        val c = cos(headRot); val sn = sin(headRot)
        val dx = x - NECK_X; val dy = y - NECK_Y
        out[0] = NECK_X + dx * c - dy * sn
        out[1] = NECK_Y + dx * sn + dy * c + headDy
    }

    /**
     * The mood of the face. blush 0..1 (red cheeks), tint = light colour over the face (rgb) with strength tintA, droop 0..0.5 (how far the upper lids are lowered all the time:
     * sleepy / sad / sultry), dip = how far the head is lowered (px, negative = raised), tilt = head tilt in degrees (small), every = seconds between blinks, speed = blink speed.
     */
    class SareeMood(
        val blush: Float = 0f, val tint: Int = 0xFFFFFF, val tintA: Float = 0f, val droop: Float = 0f,
        val dip: Float = 0f, val tilt: Float = 0f, val every: Float = 4.2f, val speed: Float = 1f,
        /** v3.107 expression: smile -1 (frown) .. 1 (broad smile), 0.35 = the picture's own gentle smile; open = how far the mouth is open at rest (surprise); wide = mouth width 0.5..1.1;
         *  squint 0..1 = lower lids lifted (a smile that reaches the eyes); slant -1 (sad: outer lids droop) .. 1 (angry: inner lids lowered). */
        val smile: Float = NATURAL_SMILE, val open: Float = 0f, val wide: Float = 1f, val squint: Float = 0f, val slant: Float = 0f
    ) {
        fun toArray(): FloatArray = floatArrayOf(blush, ((tint shr 16) and 255) / 255f, ((tint shr 8) and 255) / 255f, (tint and 255) / 255f, tintA, droop, dip, tilt, every, speed,
            smile, open, wide, squint, slant)
    }
    const val NATURAL_SMILE = 0.35f
    const val MOOD_N = 15   // size of SareeMood.toArray()
    const val M_SMILE = 10; const val M_OPEN = 11; const val M_WIDE = 12; const val M_SQUINT = 13; const val M_SLANT = 14
    const val M_BLUSH = 0; const val M_R = 1; const val M_G = 2; const val M_B = 3; const val M_TINTA = 4; const val M_DROOP = 5; const val M_DIP = 6; const val M_TILT = 7; const val M_EVERY = 8; const val M_SPEED = 9

    private val WARM = 0xFFC88C; private val PINK = 0xFF7896; private val COOL = 0x6E82BE; private val RED = 0xE63C32; private val DIM = 0x28283C; private val PALE = 0xC8C8F0

    /** The mood for an emotion name of set_emotion (happy, sad, shy ...). Unknown names = calm. */
    fun mood(emotion: String): SareeMood = when (emotion.lowercase().trim()) {
        "happy", "grateful", "relieved", "hopeful" -> SareeMood(blush = 0.35f, tint = WARM, tintA = 0.05f, dip = -2f, tilt = 0.8f, every = 4.5f, smile = 0.85f, squint = 0.2f)
        "excited" -> SareeMood(blush = 0.4f, tint = WARM, tintA = 0.07f, dip = -3f, tilt = 1.2f, every = 3.2f, speed = 1.15f, smile = 1f, open = 0.22f, squint = 0.3f)
        "love" -> SareeMood(blush = 0.8f, tint = PINK, tintA = 0.07f, droop = 0.12f, dip = 2f, tilt = 1.2f, every = 3.6f, smile = 0.7f, squint = 0.12f)
        "shy", "guilty" -> SareeMood(blush = 1f, tint = PINK, tintA = 0.05f, droop = 0.1f, dip = 6f, tilt = -0.9f, every = 2.6f, smile = 0.22f, wide = 0.85f)
        "flirty" -> SareeMood(blush = 0.6f, tint = PINK, tintA = 0.05f, droop = 0.22f, tilt = 1.2f, every = 4f, speed = 0.85f, smile = 0.65f, squint = 0.1f)
        "playful", "mischievous", "proud" -> SareeMood(blush = 0.3f, tint = WARM, tintA = 0.03f, dip = -2f, tilt = 1.2f, every = 3.4f, smile = 0.9f, squint = 0.15f)
        "sad", "heartbroken", "lonely", "disappointed", "nostalgic" -> SareeMood(tint = COOL, tintA = 0.12f, droop = 0.3f, dip = 8f, tilt = -0.6f, every = 5.5f, speed = 0.7f, smile = -0.6f, wide = 0.9f, slant = -0.6f)
        "angry" -> SareeMood(blush = 0.2f, tint = RED, tintA = 0.09f, droop = 0.22f, dip = 2f, every = 6f, speed = 1.1f, smile = -0.55f, wide = 0.9f, slant = 0.85f)
        "annoyed", "jealous", "sulky" -> SareeMood(blush = 0.15f, tint = RED, tintA = 0.05f, droop = 0.18f, dip = 4f, tilt = -1f, every = 5f, smile = -0.35f, wide = 0.9f, slant = 0.45f)
        "surprised" -> SareeMood(blush = 0.1f, dip = -4f, every = 7f, speed = 1.2f, smile = 0f, open = 0.6f, wide = 0.6f)
        "scared", "nervous", "worried", "overwhelmed" -> SareeMood(tint = PALE, tintA = 0.08f, dip = 2f, every = 2.2f, speed = 1.2f, smile = -0.25f, open = 0.12f, wide = 0.85f, slant = -0.4f)
        "sleepy" -> SareeMood(tint = DIM, tintA = 0.1f, droop = 0.45f, dip = 6f, every = 3f, speed = 0.6f, smile = 0.05f, open = 0.08f)
        "bored" -> SareeMood(droop = 0.3f, dip = 3f, every = 5f, speed = 0.8f, smile = -0.1f)
        "curious", "confused" -> SareeMood(tilt = 1.2f, every = 4f, smile = 0.1f, wide = 0.92f)
        else -> SareeMood()
    }

    /** Moves the current mood values [cur] toward [target] (about 1.5 s to settle), so a mood change fades instead of jumping. */
    fun ease(cur: FloatArray, target: FloatArray, dt: Float) {
        val f = 1f - exp(-2.2f * dt.coerceIn(0f, 0.1f))
        for (i in cur.indices) cur[i] += (target[i] - cur[i]) * f
    }

    /** Eyelid closure 0..1..0 over one blink (u 0..1): quick close (40 %), slower open (60 %), like a real blink. */
    fun blinkCurve(u: Float): Float = if (u < 0.4f) smooth(0f, 0.4f, u) else 1f - smooth(0.4f, 1f, u)

    /**
     * Blinks like a person: every [step] call gives the closure 0..1. A blink takes about 0.2 s (slower when [speed] < 1), the wait to the next one is random
     * (0.6 to 1.5 times `every` seconds), and now and then (15 %) two quick blinks in a row.
     */
    class BlinkClock(seed: Long) {
        private val rnd = kotlin.random.Random(seed)
        private var wait = 1.5f
        private var u = -1f
        private var again = false
        var closure = 0f
            private set

        fun step(dt: Float, every: Float, speed: Float): Float {
            val d = dt.coerceIn(0f, 0.1f)
            if (u < 0f) {
                wait -= d
                if (wait <= 0f) u = 0f
                closure = 0f
            } else {
                u += d * speed.coerceIn(0.3f, 2f) / 0.2f
                if (u >= 1f) {
                    u = -1f; closure = 0f
                    if (!again && rnd.nextFloat() < 0.15f) { again = true; wait = 0.12f }
                    else { again = false; wait = every.coerceIn(1f, 12f) * (0.6f + 0.9f * rnd.nextFloat()) }
                } else closure = blinkCurve(u)
            }
            return closure
        }
    }
}

/**
 * v3.101: ready theme number 2 (Customization > Ready themes): a picture of a girl in a black saree at sunset, with light movement: breathing and sway, and on the home screen she talks (head, jaw, hair move with the AI's voice).
 * It is shown on the home screen, blurred behind every page, and as the round chat picture, only in the NISU AI modes.
 * Only the look changes: the displayed names, "NISU AI" and "Raju developed NISU AI" are not touched.
 */
object SareeTheme {
    const val NAME = "Dusk Saree"
    private val TWO_PI = (2.0 * PI).toFloat()
    private const val K_ON = "saree_theme"

    /** The saved choice (true = the person picked this theme). Whether it is SHOWN also depends on the mode, see [active]. */
    val selected = MutableStateFlow(false)

    fun sync(ctx: android.content.Context) { selected.value = Prefs.getBool(ctx, K_ON, false) }

    fun setSelected(ctx: android.content.Context, on: Boolean) {
        Prefs.putBool(ctx, K_ON, on)
        selected.value = on
    }

    /** Picks the theme. It replaces the custom photos and the Sunset Garden (they stay saved, only switched off). */
    fun select(ctx: android.content.Context) {
        CustomTheme.setOn(ctx, false)
        GardenTheme.setSelected(ctx, false)
        LaceTheme.setSelected(ctx, false)   // v3.131: only one ready theme at a time
        setSelected(ctx, true)
    }

    /** True while the theme is really showing; recomposes the screen the moment the mode, the choice or the custom theme changes. */
    @Composable
    fun active(): Boolean {
        val sel by selected.collectAsState()
        val mode by PersonaMode.live.collectAsState()
        val c by CustomTheme.cfg.collectAsState()
        return SareeRules.showing(sel, mode, CustomThemeRules.usable(c))
    }

    /** Still picture, filling the screen (used behind the pages, blurred, when a modifier with blur is given). */
    @Composable
    fun Still(modifier: Modifier = Modifier) {
        Image(painterResource(R.drawable.nisu_saree_bg), contentDescription = NAME, contentScale = ContentScale.Crop, modifier = modifier)
    }

    /** The state of her face: the mood values (eased), the blink, and the skin colours the eyelids are painted with. */
    internal class Face {
        val cur = SareeRules.mood("calm").toArray()
        val blink = SareeRules.BlinkClock(System.nanoTime())
        var lid = 0f
        var open = 0f      // v3.107: how far her mouth is open with the voice (0..1)
        var wph = 0f       // phase of the small syllable wobble
    }

    /**
     * Home screen: the picture, full screen, alive. It breathes and sways a little all the time and she BLINKS like a person (random pace, now and then a double blink).
     * Her MOOD (the emotion the AI sets, `emotion`) changes her face: red cheeks, a light tint on the face, how open the eyes are (sleepy / sad lids), how the head is held
     * (lowered when shy or sad, raised when happy or proud, a small tilt), and how often she blinks. Changes fade in about a second and a half.
     * While the AI speaks, her head nods and tilts with every word, her jaw moves with the voice, her shoulders and hair move softly.
     * Drawn on one bendable mesh ([SareeRules.warpMesh]) plus eyelids painted on the eyes, on the canvas / GPU layer only (no recomposition per frame).
     * Stands still (no blink, no mood movement) when the phone's animations are switched off.
     */
    @Composable
    fun Background(modifier: Modifier = Modifier, emotion: String = "calm") {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val still = remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
        val t = VenomTheme.clock()
        val bmp = ImageBitmap.imageResource(R.drawable.nisu_saree_bg).asAndroidBitmap()
        val mesh = remember { FloatArray(SareeRules.meshSize()) }
        val screen = remember { FloatArray(SareeRules.meshSize()) }
        val pt = remember { FloatArray(2) }
        Canvas(
            modifier.graphicsLayer {
                val p = t.value * TWO_PI
                val breath = (sin(p * 4f) + 1f) / 2f                    // one breath every 6 s
                val s = 1.06f + 0.01f * breath                          // always above 1.06, so the movement never shows an edge
                scaleX = s; scaleY = s
                translationX = sin(p * 3f) * 3.dp.toPx()
                translationY = cos(p * 2f) * 2.dp.toPx() - breath * 2.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 0.3f)
            }
        ) {
            val k = SareeRules.scale(size.width, size.height)
            val ox = (size.width - SareeRules.IMG_W * k) / 2f
            val oy = (size.height - SareeRules.IMG_H * k) / 2f
            val p = t.value * TWO_PI
            val sp = 0f   // v3.110: she does not move with the voice
            val dip = 0f; val tilt = 0f
            SareeRules.warpMesh(mesh, sp, p, dip, tilt)
            var n = 0
            while (n < mesh.size) { screen[n] = ox + mesh[n] * k; screen[n + 1] = oy + mesh[n + 1] * k; n += 2 }
            drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(bmp, SareeRules.COLS, SareeRules.ROWS, screen, 0, null, 0, null) }
        }
    }

    /** The round chat picture: her face, from the same picture. */
    @Composable
    fun Avatar(modifier: Modifier = Modifier) {
        Image(
            painterResource(R.drawable.nisu_saree_bg), contentDescription = NAME, contentScale = ContentScale.Crop,
            alignment = androidx.compose.ui.BiasAlignment(0f, -0.7f), modifier = modifier
        )
    }

    /** The small picture in Customization > Ready themes: tap it to use the theme. */
    @Composable
    fun ThemeCell(selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier.aspectRatio(0.75f).clip(shape).background(NisuUi.Card)
                .border(if (selected) 2.dp else 1.dp, if (selected) NisuUi.Cyan else NisuUi.Line, shape)
                .clickable(onClick = onSelect)
        ) {
            Image(painterResource(R.drawable.nisu_saree_thumb), contentDescription = NAME, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                NAME, fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (selected) Text(
                "✓", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}
