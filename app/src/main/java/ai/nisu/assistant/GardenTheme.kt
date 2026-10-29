package ai.nisu.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
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
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * v3.91: the plain rules of the Sunset Garden theme (no Android UI classes, so they can be unit tested on the JVM).
 * The theme is shown only in the NISU AI modes (Care, Friend, Boss). Jarvis and Venom always keep their own look. It is also never shown while the
 * person's own custom photos (Customization > CUSTOM) are selected: the two themes exclude each other.
 */
object GardenRules {
    /** Size of res/drawable-nodpi/nisu_garden_bg.jpg. If the picture is replaced, change these two numbers and the positions in [GardenTheme]. */
    const val IMG_W = 940f
    const val IMG_H = 1672f

    /** NISU AI modes = every mode except Jarvis and Venom. */
    fun nisuMode(mode: String): Boolean = mode == PersonaMode.CARE || mode == PersonaMode.BOSS || mode == PersonaMode.FRIEND

    /** The theme is really showing: it is selected, the mode is a NISU AI mode, and the custom photos are not selected. */
    fun showing(selected: Boolean, mode: String, customOn: Boolean): Boolean = selected && !customOn && nisuMode(mode)

    /** Scale of the picture on a w x h box with ContentScale.Crop (fills the box, the extra part is cut equally on both sides). */
    fun scale(w: Float, h: Float): Float = max(w / IMG_W, h / IMG_H)

    // ---- the body talks with her voice (pure maths, so it is unit tested). All numbers are in picture pixels. lvl = smoothed voice loudness 0..1, ph = talking phase (rad).
    const val NECK_Y = 520f

    fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Vertical shift at picture height y: the head dips with every word (loud = deeper); the shoulders and chest stay still (v3.108). Zero when silent. */
    fun shiftY(y: Float, lvl: Float, ph: Float): Float {
        // v3.108: only the head moves with the voice. The shoulders and chest used to lift with every word: removed, they stay still (the head fades out at the neck).
        return (lvl * 7f + kotlin.math.sin(ph * 1.7f) * lvl * 2.5f) * (1f - smooth(460f, 580f, y))
    }

    /** Sideways shift at picture height y: the head tilts from the neck, the hair and body sway softly lower down, the feet stay put. Zero when silent. */
    fun shiftX(y: Float, lvl: Float, ph: Float): Float {
        val tilt = kotlin.math.sin(ph) * lvl * 9f * ((NECK_Y - y) / 330f).coerceIn(0f, 1.2f)
        val sway = kotlin.math.sin(ph * 0.9f + y * 0.006f) * lvl * 5f * ((y - 760f) / 500f).coerceIn(0f, 1f) * (1f - smooth(1250f, 1500f, y))   // v3.108: starts below the chest (was 500)
        return tilt + sway
    }

    // ---- the face (v3.109): where her eyes, cheeks and mouth are in the picture (picture pixels), read from the picture at 6x zoom with a grid.
    // Eyes: centre x, centre y, half width, half height, tilt in degrees. Mouth: middle between the corners x, y, half width, tilt. If the picture is replaced, change these.
    val EYE_L = floatArrayOf(412f, 371f, 18.5f, 11.5f, -7f)
    val EYE_R = floatArrayOf(485f, 341f, 17f, 11.5f, -24f)
    val CHEEK_L = floatArrayOf(418f, 386f)
    val CHEEK_R = floatArrayOf(490f, 360f)
    val FACE = floatArrayOf(452f, 365f)
    val MOUTH = floatArrayOf(466f, 401.5f, 11f, -18f)
    /** Size of the eyelids and of the mouth compared with the Dusk Saree picture (whose eyes are about twice as big). */
    const val EYE_U = 0.55f
    const val MOUTH_U = 0.31f

    /** Where a point of the head (picture pixels) is drawn while she talks: the same shift the picture strips get at that height ([shiftX] / [shiftY]), so the lids and the mouth stay glued to the face. out = x, y. */
    fun headPoint(out: FloatArray, x: Float, y: Float, lvl: Float, ph: Float) {
        out[0] = x + shiftX(y, lvl, ph)
        out[1] = y + shiftY(y, lvl, ph)
    }

    /** A point of the picture (in picture pixels) mapped to the screen with the same cropping as ContentScale.Crop + Alignment.Center. */
    fun mapX(x: Float, w: Float, h: Float): Float = x * scale(w, h) + (w - IMG_W * scale(w, h)) / 2f
    fun mapY(y: Float, w: Float, h: Float): Float = y * scale(w, h) + (h - IMG_H * scale(w, h)) / 2f

    // ---------------------------------------------------------------------------------------- the body talks (v3.92)
    /** The picture is bent on a grid of COLS x ROWS cells (a mesh), so her head, shoulders, hair and jaw move with her voice. */
    const val COLS = 20
    const val ROWS = 34
    fun meshSize(): Int = (COLS + 1) * (ROWS + 1) * 2

    // Where things are in the picture (picture pixels). Change these if the picture is ever replaced.
    private const val NECK_X = 480f
    private const val JAW_X = 485f
    private const val JAW_Y = 440f
    /** How far (picture pixels) the lower face drops at full voice. Raise it for a wider mouth movement, lower it if it looks odd. */
    const val JAW_DROP = 4.5f
    private const val DEG = 0.017453292f   // one degree in radians

    /**
     * Fills [out] with the positions (x0, y0, x1, y1 ...) of a (COLS+1) x (ROWS+1) grid laid over the picture, moved for this moment.
     * [speech] is her voice loudness 0..1 (0 = quiet: only a very small breathing and sway), [p] the loop angle in radians (whole cycles per 24 s only).
     * Head: nods and tilts a little with every word, turning round the neck. Shoulders / chest: breathe and bounce a little. Long hair: sways more while
     * she talks. Lower face: drops a few pixels with the voice, like the jaw. The feet and the bottom of the picture never move.
     */
    fun warpMesh(out: FloatArray, speech: Float, p: Float) {
        val s = speech.coerceIn(0f, 1f)
        val headRot = (0.5f * sin(p * 2f) + s * 1.0f * sin(p * 36f)) * DEG        // radians; at most 1.5 degrees
        val headDy = 1.5f * sin(p * 4f) + s * (6f + 5f * sin(p * 56f))              // nods down on the loud part of each word
        val bounce = 2f * sin(p * 4f)                       // v3.108: the quiet breathing only; the voice no longer moves the chest
        val breathe = 0.006f * sin(p * 4f)
        val jawDy = s * JAW_DROP
        var n = 0
        for (j in 0..ROWS) {
            val y0 = j * IMG_H / ROWS
            for (i in 0..COLS) {
                val x0 = i * IMG_W / COLS
                val wh = 1f - smooth(470f, 580f, y0)                                   // 1 at the head, 0 below the neck
                val wt = smooth(420f, 620f, y0) * (1f - smooth(1000f, 1300f, y0))    // chest and shoulders
                var x = NECK_X + (x0 - NECK_X) * (1f + breathe * wt)
                var y = y0 + bounce * wt
                val ang = headRot * wh
                val c = cos(ang); val sn = sin(ang)
                val dx = x - NECK_X; val dy = y - NECK_Y
                x = NECK_X + dx * c - dy * sn
                y = NECK_Y + dx * sn + dy * c + headDy * wh
                val side = smooth(150f, 300f, abs(x0 - NECK_X))
                val ramp = smooth(600f, 1000f, y0) * (1f - smooth(1100f, 1400f, y0))
                x += sin(p * 3f + y0 * 0.008f) * (5f + 6f * s) * ramp * side       // the long hair sways, more while she talks
                val gx = x0 - JAW_X; val gy = y0 - JAW_Y
                y += jawDy * exp(-(gx * gx + gy * gy) / (2f * 55f * 55f))
                out[n++] = x; out[n++] = y
            }
        }
    }
}

/**
 * v3.91: SUNSET GARDEN THEME (the owner's own picture: the girl on the garden terrace at sunset, res/drawable-nodpi/nisu_garden_bg.jpg).
 * Chosen in Customization > Ready themes (a small picture: tap it and it is applied). It works ONLY in the NISU AI modes (Care, Friend, Boss);
 * Jarvis and Venom keep their own look, and the theme comes back by itself when a NISU AI mode is on again.
 *
 * It MOVES. Home screen: the picture slowly breathes and sways around her head; the sun pulses; both lanterns flicker; the city lights twinkle;
 * small lights float between the flowers; a soft band of sunset light passes over the picture; pink petals fall; and while she speaks, a warm halo
 * around her head follows the loudness of her voice. Behind the other pages the blurred picture sits under a dark violet scrim with a few petals.
 * One looping clock of 24 s ([VenomTheme.clock], whole cycles only, so no jump) drawn on the GPU layer / canvas only; it stands still when the phone's
 * animations are switched off.
 *
 * Only the look changes: the displayed names, "NISU AI" and "Raju developed NISU AI" are not touched.
 */
object GardenTheme {
    const val NAME = "Sunset Garden"
    private const val K_ON = "garden_theme"
    private val TWO_PI = (2.0 * PI).toFloat()
    private val PI_F = PI.toFloat()

    /** The saved choice (true = the person picked this theme). Whether it is SHOWN also depends on the mode, see [active]. */
    val selected = MutableStateFlow(false)

    fun sync(ctx: android.content.Context) { selected.value = Prefs.getBool(ctx, K_ON, false) }

    fun setSelected(ctx: android.content.Context, on: Boolean) {
        Prefs.putBool(ctx, K_ON, on)
        selected.value = on
    }

    /** Picks the theme. It replaces the custom photos (they stay saved, only switched off). */
    fun select(ctx: android.content.Context) {
        CustomTheme.setOn(ctx, false)
        SareeTheme.setSelected(ctx, false)   // v3.101: only one ready theme at a time
        LaceTheme.setSelected(ctx, false)    // v3.131
        setSelected(ctx, true)
    }

    /** True while the theme is really showing; recomposes the screen the moment the mode, the choice or the custom theme changes. */
    @Composable
    fun active(): Boolean {
        val sel by selected.collectAsState()
        val mode by PersonaMode.live.collectAsState()
        val c by CustomTheme.cfg.collectAsState()
        return GardenRules.showing(sel, mode, CustomThemeRules.usable(c))
    }

    private fun Color.a(x: Float): Color = copy(alpha = x.coerceIn(0f, 1f))

    // ------------------------------------------------------------------------------------------ screens

    /** How loud she is speaking right now (smoothed, 0..1) and a talking phase that runs faster the louder she is. Still (0) when the phone has animations off. */
    internal class Talk {
        val lvl = mutableFloatStateOf(0f)
        val ph = mutableFloatStateOf(0f)
    }

    @Composable
    internal fun talk(): Talk {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val still = remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
        val tk = remember { Talk() }
        if (!still) LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    last = now
                    val next = JarvisTheme.voiceStep(tk.lvl.floatValue, JarvisTheme.voiceTarget(AppState.speechLevel()), dt)
                    val v = if (next < 0.004f) 0f else next
                    if (v != tk.lvl.floatValue) tk.lvl.floatValue = v
                    if (v > 0f) tk.ph.floatValue = (tk.ph.floatValue + dt * (1.6f + 3f * v)) % (2f * PI_F * 20f)
                }
            }
        }
        return tk
    }

    private const val STRIPS = 48

    /**
     * The picture drawn in 48 thin horizontal strips whose edges follow one smooth shift curve ([GardenRules.shiftY] / [shiftX]), so while she speaks
     * her head dips and tilts with every word and her hair and lower body sway (the shoulders and chest stay still since v3.108), without any seam. Silent = exactly the plain picture.
     */
    private fun DrawScope.body(img: ImageBitmap, lvl: Float, ph: Float) {
        val w = size.width
        val h = size.height
        val k = GardenRules.scale(w, h)
        val x0 = (w - GardenRules.IMG_W * k) / 2f
        val y0 = (h - GardenRules.IMG_H * k) / 2f
        val iw = img.width
        val ih = img.height
        for (i in 0 until STRIPS) {
            val a = ih * i / STRIPS
            val b = ih * (i + 1) / STRIPS
            val ya = a * GardenRules.IMG_H / ih
            val yb = b * GardenRules.IMG_H / ih
            val top = y0 + (ya + GardenRules.shiftY(ya, lvl, ph)) * k
            val bot = y0 + (yb + GardenRules.shiftY(yb, lvl, ph)) * k
            val dx = x0 + GardenRules.shiftX((ya + yb) / 2f, lvl, ph) * k
            drawImage(
                img, srcOffset = IntOffset(0, a), srcSize = IntSize(iw, b - a),
                dstOffset = IntOffset(dx.roundToInt(), top.roundToInt()),
                dstSize = IntSize((GardenRules.IMG_W * k).roundToInt(), (bot - top).roundToInt() + 2),   // 2 px overlap: no hairline between strips
                filterQuality = FilterQuality.Medium
            )
        }
    }

    /** Home screen: the living picture (breathing, swaying, and TALKING with her voice) with the lights, petals and the voice halo on top. */
    @Composable
    fun Background(modifier: Modifier = Modifier, emotion: String = "calm") {
        val t = VenomTheme.clock()
        val img = ImageBitmap.imageResource(R.drawable.nisu_garden_bg)
        Box(modifier) {
            // the picture and everything that is pinned to a place in it move together
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = t.value * TWO_PI
                    val breath = (sin(p * 4f) + 1f) / 2f                    // 4 breaths per loop = one every 6 s
                    val s = 1.045f + 0.02f * breath                         // always above 1, so the sway never shows an edge
                    scaleX = s; scaleY = s
                    translationX = sin(p * 3f) * 5.dp.toPx()
                    translationY = cos(p * 2f) * 4.dp.toPx() - breath * 3.dp.toPx()
                    rotationZ = sin(p) * 0.25f
                    transformOrigin = TransformOrigin(0.5f, 0.3f)           // she turns around her head
                }
            ) {
                Canvas(Modifier.fillMaxSize()) { body(img, 0f, 0f) }
                Canvas(Modifier.fillMaxSize()) { scene(t.value, 0f) }
            }
            // the petals fall over everything, not pinned to the picture
            Canvas(Modifier.fillMaxSize()) { petals(t.value, 1f) }
        }
    }

    /**
     * v3.109: her face is alive. Painted over the picture and glued to the head: she BLINKS like a person (random pace, now and then a double blink), her mouth opens and closes
     * with every syllable of the AI's voice, and her MOOD (the emotion the AI sets) shows: red cheeks, a light colour tint on the face, a smile or a frown, a round mouth when surprised,
     * lower lids lifted when she smiles, upper lids slanted when she is angry or sad, lids kept lower when she is sleepy. Changes fade in about 1.5 s.
     */
    private fun DrawScope.face(f: FaceAnim, skin: Array<Color>, pt: FloatArray, lvl: Float, ph: Float) {
        val w = size.width
        val h = size.height
        val k = GardenRules.scale(w, h)
        val m = f.cur
        fun at(x: Float, y: Float): Offset { GardenRules.headPoint(pt, x, y, lvl, ph); return Offset(GardenRules.mapX(pt[0], w, h), GardenRules.mapY(pt[1], w, h)) }

        // eyes: the lids close with every blink and stay a little lowered in a sleepy / sad mood; a smile lifts the lower lids, anger and sadness slant the upper lids
        val a = f.lid   // v3.110: the blink only, nothing else is painted on the face
        val sq = 0f; val sl = 0f
        if (a > 0.02f) {
            for ((i, e) in arrayOf(GardenRules.EYE_L, GardenRules.EYE_R).withIndex()) {
                val c = at(e[0], e[1])
                paintEyelid(c.x, c.y, e[2], e[3], e[4], a, skin[i], k, sq, sl, if (i == 0) 1f else -1f, GardenRules.EYE_U)
            }
        }
    }

    /** A few falling petals only (used over the blurred page backdrop). */
    @Composable
    fun Petals(modifier: Modifier = Modifier, strength: Float = 0.5f) {
        val t = VenomTheme.clock()
        Canvas(modifier) { petals(t.value, strength) }
    }

    /** The round chat picture: her face, from the same picture. */
    @Composable
    fun Avatar(modifier: Modifier = Modifier) {
        Image(
            painterResource(R.drawable.nisu_garden_bg), contentDescription = NAME, contentScale = ContentScale.Crop,
            alignment = androidx.compose.ui.BiasAlignment(0f, -0.75f), modifier = modifier
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
            Image(painterResource(R.drawable.nisu_garden_thumb), contentDescription = NAME, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                NAME, fontSize = 11.sp, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (selected) Text(
                "\u2713", fontSize = 13.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }

    // ------------------------------------------------------------------------------------------ the moving parts

    /** The lights pinned to the picture: sun, lanterns, city lights, floating lights, a sweep of sunset light, and the voice halo. */
    private fun DrawScope.scene(tt: Float, voice: Float) {
        val p = tt * TWO_PI
        val w = size.width
        val h = size.height
        val k = GardenRules.scale(w, h)
        fun sx(x: Float) = GardenRules.mapX(x, w, h)
        fun sy(y: Float) = GardenRules.mapY(y, w, h)

        // 1) the sun on the horizon breathes
        val sun = Offset(sx(865f), sy(508f))
        val pulse = (sin(p * 4f) + 1f) / 2f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB27A).a(0.16f + 0.16f * pulse), Color.Transparent), center = sun, radius = 240f * k), radius = 240f * k, center = sun)

        // 2) the two lanterns flicker (whole numbers of cycles per loop, so the loop never jumps)
        val lamps = arrayOf(Offset(sx(197f), sy(285f)), Offset(sx(908f), sy(662f)))
        for (i in lamps.indices) {
            val f = 0.75f + 0.15f * sin(p * (13f + 4f * i) + i * 2f) + 0.10f * sin(p * (29f + 6f * i) + 1f)
            val c = lamps[i]
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC46B).a(0.38f * f), Color.Transparent), center = c, radius = 140f * k), radius = 140f * k, center = c)
            drawCircle(Color(0xFFFFE6A8).a(0.55f * f), radius = 14f * k, center = c)
        }

        // 3) windows of the city twinkle
        for (i in 0 until 28) {
            val seed = i * 0.618034f
            val x = sx(668f + ((seed * 7.1f) % 1f) * 262f)
            val y = sy(496f + ((seed * 13.7f) % 1f) * 112f)
            val s = (sin(p * (3f + (i % 5)) + i * 1.9f) + 1f) / 2f
            drawCircle(Color(0xFFFFE2A6).a(0.6f * s * s), radius = max(1.3f, (1.2f + (i % 3) * 0.5f) * k), center = Offset(x, y))
        }

        // 4) small lights drift between the flowers on the left
        for (i in 0 until 10) {
            val seed = i * 0.618034f
            val x = sx(24f + ((seed * 9.1f) % 1f) * 240f + sin(p * 2f + i) * 18f)
            val y = sy(90f + ((seed * 5.3f) % 1f) * 760f + cos(p * 3f + i * 1.3f) * 14f)
            val a = 0.15f + 0.7f * max(0f, sin(p * (2f + (i % 3)) + i * 2.1f))
            drawCircle(Color(0xFFE6C8FF).a(a * 0.25f), radius = 8f * k, center = Offset(x, y))
            drawCircle(Color(0xFFF3E4FF).a(a), radius = max(1.4f, 2f * k), center = Offset(x, y))
        }

        // 5) one soft band of sunset light passes over the picture each loop (invisible at its start and end)
        val env = sin(tt * PI_F)
        if (env > 0.01f) {
            val cx = -0.3f * w + 1.6f * w * tt
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color(0xFFFFD2A8).a(0.10f * env), Color.Transparent),
                    start = Offset(cx - 0.25f * w, 0f), end = Offset(cx + 0.25f * w, 0.25f * h)
                )
            )
        }

        // 6) while she speaks, a warm halo around her head follows her voice
        if (voice > 0.01f) {
            val head = Offset(sx(480f), sy(400f))
            val r = (260f + 70f * voice) * k
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB6D9).a(0.30f * voice), Color.Transparent), center = head, radius = r), radius = r, center = head)
        }
    }

    /** Pink petals falling slowly and turning; each fades in and out, so the restart of its fall is never seen. strength 1 = home screen. */
    private fun DrawScope.petals(tt: Float, strength: Float) {
        val w = size.width
        val h = size.height
        val n = if (strength >= 0.9f) 14 else 7
        for (i in 0 until n) {
            val seed = i * 0.618034f
            val cycles = 1 + (i % 2)                                   // 24 s or 12 s for one fall
            val prog = (tt * cycles + seed) % 1f
            val x = w * ((seed * 7.3f) % 1f) + prog * w * 0.12f + sin(prog * TWO_PI * 2f + i) * w * 0.04f
            val y = -0.05f * h + prog * 1.1f * h
            val a = sin(prog * PI_F) * 0.8f * strength
            val r = (5 + (i % 3) * 2).dp.toPx()
            val flip = 0.4f + 0.6f * abs(cos(prog * TWO_PI * 3f + i))
            rotate(degrees = prog * 360f * (1 + i % 3) + i * 40f, pivot = Offset(x, y)) {
                drawOval(Color(0xFFFFB3D9).a(a), topLeft = Offset(x - r, y - r * 0.55f * flip), size = Size(2f * r, 1.1f * r * flip))
            }
        }
    }
}
