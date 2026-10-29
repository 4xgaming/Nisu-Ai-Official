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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v3.131: rules of the "Jasmine Terrace" ready theme (ready theme number 3): a girl in a white lace saree on a jasmine terrace, made to feel like a short looping VIDEO.
 * Same rule as the other ready themes: only in the NISU AI modes (Care, Friend, Boss), never Jarvis / Venom, never with custom photos.
 *
 * Everything that moves is pure maths of ONE clock t (0..1 = one 24 s loop) and only uses WHOLE numbers of cycles per loop, so the loop never jumps.
 * All positions are in picture pixels of res/drawable-nodpi/nisu_lace_bg.jpg (1024 x 1536). If the picture is replaced, change IMG_W / IMG_H and the positions below.
 */
object LaceRules {
    fun showing(selected: Boolean, mode: String, customOn: Boolean): Boolean = selected && !customOn && GardenRules.nisuMode(mode)

    const val IMG_W = 1024f
    const val IMG_H = 1536f

    /** Scale of the picture on a w x h box with ContentScale.Crop (fills the box, the extra part is cut equally on both sides). */
    fun scale(w: Float, h: Float): Float = max(w / IMG_W, h / IMG_H)

    private val TWO_PI = (2.0 * PI).toFloat()
    private const val DEG = 0.017453292f
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    // ---------------------------------------------------------------------------------------------------- the bendable picture
    /** The picture is bent on a COLS x ROWS mesh. */
    const val COLS = 24
    const val ROWS = 36
    fun meshSize(): Int = (COLS + 1) * (ROWS + 1) * 2

    /** The head turns round this point (the neck). */
    private const val NECK_X = 505f
    private const val NECK_Y = 330f

    /**
     * Fills [out] with the positions (x0, y0, x1, y1 ...) of a (COLS+1) x (ROWS+1) grid laid over the picture, moved for the loop angle [p] (radians, whole cycles per loop only).
     * What moves (each one with its own rhythm, so it looks like a real breeze and not like one wobble):
     *  - the head: a very small turn and nod, the face and the flowers on her hair go with it;
     *  - the chest and shoulders: breathe (one breath every 6 s);
     *  - the long hair on both sides: sways, with a ripple running down it;
     *  - the lower saree: flows softly, more towards the floor;
     *  - the lace curtain on the right: billows the most (it is the wind of the whole scene);
     *  - the leaves and vines: flutter a little; the big blurred leaves in front, at the bottom left, move more (they are closer to the camera).
     */
    fun warpMesh(out: FloatArray, p: Float) {
        val headRot = (0.45f * sin(p * 2f) + 0.25f * sin(p * 5f + 1f)) * DEG
        val headDy = 1.4f * sin(p * 4f)
        val headDx = 1.6f * sin(p * 3f + 0.5f)
        val breathe = 0.006f * sin(p * 4f)
        val bounce = 1.8f * sin(p * 4f)
        var n = 0
        for (j in 0..ROWS) {
            val y0 = j * IMG_H / ROWS
            for (i in 0..COLS) {
                val x0 = i * IMG_W / COLS
                val wh = 1f - smooth(300f, 430f, y0)                                           // 1 at the head, 0 below the neck
                val wt = smooth(330f, 470f, y0) * (1f - smooth(700f, 950f, y0))                // chest and shoulders
                var x = NECK_X + (x0 - NECK_X) * (1f + breathe * wt)
                var y = y0 + bounce * wt
                val ang = headRot * wh
                val c = cos(ang); val sn = sin(ang)
                val dx = x - NECK_X; val dy = y - NECK_Y
                x = NECK_X + dx * c - dy * sn + headDx * wh
                y = NECK_Y + dx * sn + dy * c + headDy * wh
                // long hair, both sides of the face and the chest
                val side = smooth(70f, 140f, abs(x0 - NECK_X))
                val hair = smooth(330f, 520f, y0) * (1f - smooth(760f, 900f, y0)) * side
                x += sin(p * 3f + y0 * 0.012f + (if (x0 < NECK_X) 0f else 0.9f)) * 5f * hair
                // the lower saree flows
                val drape = smooth(900f, 1200f, y0) * smooth(250f, 330f, x0) * (1f - smooth(820f, 900f, x0))
                x += sin(p * 2f + y0 * 0.007f) * 3.5f * drape
                // the lace curtain on the right: hangs from the top, so it moves more the lower it is
                val curtain = smooth(735f, 800f, x0) * (1f - smooth(560f, 760f, y0)) * (0.2f + 0.8f * smooth(0f, 450f, y0))
                x += sin(p * 2f - y0 * 0.006f + x0 * 0.003f) * 16f * curtain
                y += cos(p * 3f + y0 * 0.005f) * 3f * curtain
                // leaves and vines, left and right
                val leftLeaves = (1f - smooth(240f, 340f, x0)) * (1f - smooth(620f, 760f, y0))
                x += sin(p * 5f + y0 * 0.006f) * 2f * leftLeaves
                y += cos(p * 4f + y0 * 0.006f) * 1.4f * leftLeaves
                val rightLeaves = smooth(760f, 820f, x0) * smooth(540f, 620f, y0) * (1f - smooth(1080f, 1180f, y0))
                x += sin(p * 4f + y0 * 0.01f + 1.2f) * 3f * rightLeaves
                y += cos(p * 5f + x0 * 0.01f) * 1.5f * rightLeaves
                // the big blurred leaves in front, bottom left: closest to the camera, so they move the most
                val front = (1f - smooth(120f, 230f, x0)) * smooth(960f, 1080f, y0)
                x += sin(p * 3f + y0 * 0.01f) * 7f * front
                y += sin(p * 4f + x0 * 0.015f) * 2f * front
                out[n++] = x; out[n++] = y
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- the face
    /** The eyes: centre x, centre y, half width, half height, tilt in degrees (picture pixels; read from the picture at 3x zoom). */
    val EYE_L = floatArrayOf(443f, 209f, 19f, 9.5f, -6f)
    val EYE_R = floatArrayOf(520f, 185f, 20f, 9.5f, -17f)
    /** A patch of plain skin below each eye: the colour the closed eyelid is painted with. */
    val SKIN_L = floatArrayOf(447f, 224f)
    val SKIN_R = floatArrayOf(516f, 213f)
    /** Size of the face parts compared with the Dusk Saree picture (its eyes are about twice as wide). */
    const val EYE_U = 0.5f

    /** A point of the HEAD (picture pixels) after the same head movement as [warpMesh], so the eyelids stay glued to the eyes. out = x, y. */
    fun headPoint(out: FloatArray, x: Float, y: Float, p: Float) {
        val headRot = (0.45f * sin(p * 2f) + 0.25f * sin(p * 5f + 1f)) * DEG
        val c = cos(headRot); val sn = sin(headRot)
        val dx = x - NECK_X; val dy = y - NECK_Y
        out[0] = NECK_X + dx * c - dy * sn + 1.6f * sin(p * 3f + 0.5f)
        out[1] = NECK_Y + dx * sn + dy * c + 1.4f * sin(p * 4f)
    }

    // ---------------------------------------------------------------------------------------------------- light, lantern, sparkles
    /** The lantern's flame (picture pixels). */
    const val LANTERN_X = 181f
    const val LANTERN_Y = 604f

    /** The candle flame in the lantern: about 0.55 .. 1.0, a quick uneven flicker (three beats that never line up). */
    fun flicker(t: Float): Float {
        val p = t * TWO_PI
        return (0.78f + 0.12f * sin(p * 29f) + 0.07f * sin(p * 47f + 1.3f) + 0.05f * sin(p * 83f + 0.4f)).coerceIn(0.4f, 1f)
    }

    /** Three soft sun beams: x at the top of the picture, half width. They lean 18 degrees and drift sideways and breathe in strength. */
    val SHAFT_X = floatArrayOf(900f, 700f, 520f)
    val SHAFT_W = floatArrayOf(95f, 70f, 110f)
    const val SHAFT_DEG = 18f
    /** Strength of beam [b] at time [t] (0..1 loop): 0.05 .. 0.16. */
    fun shaftAlpha(b: Int, t: Float): Float = 0.05f + 0.11f * (0.5f + 0.5f * sin(t * TWO_PI * 2f + b * 1.7f))
    /** How far (picture pixels) beam [b] has drifted sideways at time [t]. */
    fun shaftDrift(b: Int, t: Float): Float = 50f * sin(t * TWO_PI + b * 2.1f)

    /** Where the lace and the jasmine shine: x, y, size (picture pixels), phase, beats per loop (whole number). */
    val SPARKS = arrayOf(
        floatArrayOf(470f, 560f, 15f, 0.0f, 5f), floatArrayOf(395f, 705f, 12f, 1.3f, 7f), floatArrayOf(575f, 655f, 14f, 2.6f, 6f),
        floatArrayOf(455f, 905f, 16f, 3.9f, 4f), floatArrayOf(525f, 1060f, 13f, 5.2f, 8f), floatArrayOf(380f, 1130f, 12f, 0.7f, 5f),
        floatArrayOf(610f, 1210f, 15f, 2.0f, 6f), floatArrayOf(430f, 1310f, 13f, 3.3f, 7f), floatArrayOf(555f, 1390f, 12f, 4.6f, 4f),
        floatArrayOf(572f, 128f, 14f, 1.9f, 6f), floatArrayOf(380f, 170f, 11f, 5.6f, 5f), floatArrayOf(655f, 470f, 12f, 0.4f, 7f),
        floatArrayOf(55f, 715f, 14f, 2.9f, 6f), floatArrayOf(70f, 780f, 11f, 4.1f, 8f), floatArrayOf(940f, 800f, 15f, 3.5f, 5f),
        floatArrayOf(1000f, 925f, 12f, 1.1f, 7f)
    )
    /** How bright spark [s] is at time [t]: mostly 0, a short bright flash a few times per loop. */
    fun spark(s: Int, t: Float): Float {
        val a = SPARKS[s]
        val v = sin(t * TWO_PI * a[4] + a[3])
        return if (v <= 0f) 0f else { val v2 = v * v; val v4 = v2 * v2; v4 * v4 }
    }

    // ---------------------------------------------------------------------------------------------------- petals and floating lights (screen, 0..1 of width / height)
    const val PETALS = 16
    const val MOTES = 28
    private fun rnd(i: Int, salt: Int): Float { val v = sin((i * 12.9898f + salt * 78.233f).toDouble()) * 43758.5453; return (v - floor(v)).toFloat() }

    /**
     * Jasmine petal [i] at time [t]: out = x, y (0..1 of the screen, may be a little outside), rotation (degrees), length (fraction of the screen width),
     * flip 0.25..1 (how wide it looks while it tumbles), alpha 0..1. They blow in from the right with the curtain's wind and fall slowly towards the lower left.
     * One or two whole passes per loop, invisible at both ends, so the loop is seamless.
     */
    fun petal(i: Int, t: Float, out: FloatArray) {
        val cycles = if (rnd(i, 1) < 0.5f) 1 else 2
        val u = (t * cycles + rnd(i, 2)) % 1f
        out[0] = 1.12f - 1.3f * u + 0.035f * sin(TWO_PI * (u * 3f + rnd(i, 3))) + (rnd(i, 4) - 0.5f) * 0.3f
        out[1] = -0.1f + 1.2f * u + 0.01f * sin(TWO_PI * (u * 5f + rnd(i, 10)))
        out[2] = 360f * (rnd(i, 5) + u * (1f + rnd(i, 6)))
        out[3] = 0.012f + 0.014f * rnd(i, 8)
        out[4] = 0.25f + 0.75f * abs(cos(TWO_PI * (u * 2f + rnd(i, 7))))
        out[5] = smooth(0f, 0.08f, u) * (1f - smooth(0.9f, 1f, u)) * (0.55f + 0.4f * rnd(i, 9))
    }

    /** Floating light [i] (pollen / dust in the sun) at time [t]: out = x, y (0..1 of the screen), radius (fraction of the screen width), alpha 0..1. Rises slowly, twinkles. */
    fun mote(i: Int, t: Float, out: FloatArray) {
        val u = (t + rnd(i, 1)) % 1f
        out[0] = rnd(i, 2) + 0.03f * sin(TWO_PI * (u * 2f + rnd(i, 3)))
        out[1] = 1.05f - 1.1f * u
        out[2] = 0.002f + 0.003f * rnd(i, 4)
        out[3] = (0.35f + 0.65f * (0.5f + 0.5f * sin(TWO_PI * (u * 6f + rnd(i, 5))))) * smooth(0f, 0.08f, u) * (1f - smooth(0.9f, 1f, u))
    }
}

/**
 * v3.131: ready theme number 3 (Customization > Ready themes): "Jasmine Terrace", a girl in a white lace saree among jasmine flowers, made to feel like a looping VIDEO:
 * a slow camera (push-in, drift), the picture itself bends in the wind (the lace curtain billows, her long hair and saree flow, leaves flutter, the big leaves in front move most),
 * she breathes and blinks like a person, beams of sun drift across, the lantern flame flickers, the lace and the flowers sparkle, and jasmine petals and floating lights blow through the screen.
 * It is shown on the home screen, blurred behind every page (with a few petals), and as the round chat picture, only in the NISU AI modes.
 * Only the look changes: the displayed names, "NISU AI" and "Raju developed NISU AI" are not touched.
 * Stands still (a plain picture) when the phone's animations are switched off.
 */
object LaceTheme {
    const val NAME = "Jasmine Terrace"
    private val TWO_PI = (2.0 * PI).toFloat()
    private const val K_ON = "lace_theme"
    private val WARM = Color(0xFFFFE2A8)

    /** The saved choice (true = the person picked this theme). Whether it is SHOWN also depends on the mode, see [active]. */
    val selected = MutableStateFlow(false)

    fun sync(ctx: android.content.Context) { selected.value = Prefs.getBool(ctx, K_ON, false) }

    fun setSelected(ctx: android.content.Context, on: Boolean) {
        Prefs.putBool(ctx, K_ON, on)
        selected.value = on
    }

    /** Picks the theme. It replaces the custom photos, the Sunset Garden and the Dusk Saree (they stay saved, only switched off). */
    fun select(ctx: android.content.Context) {
        CustomTheme.setOn(ctx, false)
        GardenTheme.setSelected(ctx, false)
        SareeTheme.setSelected(ctx, false)
        setSelected(ctx, true)
    }

    /** True while the theme is really showing; recomposes the screen the moment the mode, the choice or the custom theme changes. */
    @Composable
    fun active(): Boolean {
        val sel by selected.collectAsState()
        val mode by PersonaMode.live.collectAsState()
        val c by CustomTheme.cfg.collectAsState()
        return LaceRules.showing(sel, mode, CustomThemeRules.usable(c))
    }

    /** Still picture, filling the screen (used behind the pages, blurred, when a modifier with blur is given). */
    @Composable
    fun Still(modifier: Modifier = Modifier) {
        Image(painterResource(R.drawable.nisu_lace_bg), contentDescription = NAME, contentScale = ContentScale.Crop, modifier = modifier)
    }

    // ------------------------------------------------------------------------------------------ drawing

    /** The sun beams, the lantern glow and the sparkles. Drawn in picture coordinates: (ox, oy) = where the picture starts on the screen, k = screen pixels per picture pixel. All additive light. */
    private fun DrawScope.light(t: Float, ox: Float, oy: Float, k: Float) {
        // sun beams: soft bands, leaning, drifting sideways, slowly getting stronger and weaker
        for (b in LaceRules.SHAFT_X.indices) {
            val cx = ox + (LaceRules.SHAFT_X[b] + LaceRules.shaftDrift(b, t)) * k
            val hw = LaceRules.SHAFT_W[b] * k
            val a = LaceRules.shaftAlpha(b, t)
            withTransform({ rotate(LaceRules.SHAFT_DEG, Offset(cx, oy)) }) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, WARM.copy(alpha = a), Color.Transparent), startX = cx - hw, endX = cx + hw),
                    topLeft = Offset(cx - hw, oy - 200f * k), size = Size(2f * hw, (LaceRules.IMG_H + 400f) * k), blendMode = BlendMode.Plus
                )
            }
        }
        // the candle in the lantern
        val fl = LaceRules.flicker(t)
        val lc = Offset(ox + LaceRules.LANTERN_X * k, oy + LaceRules.LANTERN_Y * k)
        val big = 95f * k * (0.92f + 0.08f * fl)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC46A).copy(alpha = 0.50f * fl), Color(0xFFFF9A3C).copy(alpha = 0.16f * fl), Color.Transparent), center = lc, radius = big), radius = big, center = lc, blendMode = BlendMode.Plus)
        val core = 15f * k
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3D0).copy(alpha = 0.55f * fl), Color.Transparent), center = lc, radius = core), radius = core, center = lc, blendMode = BlendMode.Plus)
        // sparkles on the lace and the flowers: a small star that flashes
        for (s in LaceRules.SPARKS.indices) {
            val v = LaceRules.spark(s, t)
            if (v < 0.03f) continue
            val a = LaceRules.SPARKS[s]
            val c = Offset(ox + a[0] * k, oy + a[1] * k)
            val r = a[2] * k * (0.5f + 0.5f * v)
            val col = Color(0xFFFFFBF0).copy(alpha = 0.9f * v)
            drawLine(col, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = maxOf(1f, 1.4f * k), blendMode = BlendMode.Plus)
            drawLine(col, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = maxOf(1f, 1.4f * k), blendMode = BlendMode.Plus)
            drawCircle(col, radius = r * 0.28f, center = c, blendMode = BlendMode.Plus)
        }
    }

    /** Petals and floating lights over the whole screen (not pinned to the picture). [strength] 0..1 = how many. */
    private fun DrawScope.drift(t: Float, strength: Float, tmp: FloatArray) {
        val w = size.width; val h = size.height
        val s = strength.coerceIn(0f, 1f)
        for (i in 0 until (LaceRules.MOTES * s).roundToInt()) {
            LaceRules.mote(i, t, tmp)
            if (tmp[3] < 0.02f) continue
            val c = Offset(tmp[0] * w, tmp[1] * h)
            val r = tmp[2] * w
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4D6).copy(alpha = 0.8f * tmp[3]), Color.Transparent), center = c, radius = r * 3f), radius = r * 3f, center = c, blendMode = BlendMode.Plus)
        }
        for (i in 0 until (LaceRules.PETALS * s).roundToInt()) {
            LaceRules.petal(i, t, tmp)
            if (tmp[5] < 0.02f) continue
            val len = tmp[3] * w
            val flip = tmp[4]
            val a = tmp[5]
            withTransform({ translate(tmp[0] * w, tmp[1] * h); rotate(tmp[2], Offset.Zero) }) {
                drawOval(Color(0xFFFFFAF6).copy(alpha = a), topLeft = Offset(-len, -len * 0.5f * flip), size = Size(2f * len, len * flip))
                drawOval(Color(0xFFF4C3D2).copy(alpha = a * 0.55f), topLeft = Offset(len * 0.35f, -len * 0.3f * flip), size = Size(len * 0.6f, len * 0.6f * flip))
            }
        }
    }

    /**
     * Home screen: the living picture. The camera slowly pushes in and drifts (once per 24 s loop) with a small breathing; the picture itself is bent on a mesh ([LaceRules.warpMesh]);
     * light (beams, lantern, sparkles) is added on top, pinned to the picture; her eyelids are painted on the eyes and she BLINKS like a person (random pace, now and then a double blink);
     * petals and floating lights blow across the whole screen. [emotion] only sets how often she blinks (the mood of the AI): sleepy = slower, excited = quicker.
     */
    @Composable
    fun Background(modifier: Modifier = Modifier, emotion: String = "calm") {
        val t = VenomTheme.clock()
        val face = rememberFaceAnim(emotion)
        val bmp = ImageBitmap.imageResource(R.drawable.nisu_lace_bg).asAndroidBitmap()
        val mesh = remember { FloatArray(LaceRules.meshSize()) }
        val screen = remember { FloatArray(LaceRules.meshSize()) }
        val pt = remember { FloatArray(2) }
        val tmp = remember { FloatArray(8) }
        val skin = remember(bmp) {
            arrayOf(
                sampleSkin(bmp, LaceRules.IMG_W, LaceRules.IMG_H, LaceRules.SKIN_L[0], LaceRules.SKIN_L[1]),
                sampleSkin(bmp, LaceRules.IMG_W, LaceRules.IMG_H, LaceRules.SKIN_R[0], LaceRules.SKIN_R[1])
            )
        }
        Box(modifier) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = t.value * TWO_PI
                    val breath = (sin(p * 4f) + 1f) / 2f                              // one breath every 6 s
                    val push = (sin(p - 1.57f) + 1f) / 2f                             // one slow push-in and back per loop, like a camera
                    val s = 1.075f + 0.02f * push + 0.01f * breath                    // always above 1.075, so the movement never shows an edge
                    scaleX = s; scaleY = s
                    translationX = sin(p) * 8.dp.toPx() + sin(p * 3f) * 2.dp.toPx()
                    translationY = cos(p * 2f) * 3.dp.toPx() - breath * 2.dp.toPx()
                    rotationZ = sin(p * 2f) * 0.2f
                    transformOrigin = TransformOrigin(0.5f, 0.3f)
                }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = LaceRules.scale(size.width, size.height)
                    val ox = (size.width - LaceRules.IMG_W * k) / 2f
                    val oy = (size.height - LaceRules.IMG_H * k) / 2f
                    val p = t.value * TWO_PI
                    LaceRules.warpMesh(mesh, p)
                    var n = 0
                    while (n < mesh.size) { screen[n] = ox + mesh[n] * k; screen[n + 1] = oy + mesh[n + 1] * k; n += 2 }
                    drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(bmp, LaceRules.COLS, LaceRules.ROWS, screen, 0, null, 0, null) }
                    // the blink: eyelids painted on the eyes, moving with the head
                    val a = face.lid
                    if (a > 0.02f) {
                        for ((i, e) in arrayOf(LaceRules.EYE_L, LaceRules.EYE_R).withIndex()) {
                            LaceRules.headPoint(pt, e[0], e[1], p)
                            paintEyelid(ox + pt[0] * k, oy + pt[1] * k, e[2], e[3], e[4], a, skin[i], k, 0f, 0f, if (i == 0) 1f else -1f, LaceRules.EYE_U)
                        }
                    }
                    light(t.value, ox, oy, k)
                }
            }
            // the petals and the floating lights blow over everything, not pinned to the picture
            Canvas(Modifier.fillMaxSize()) { drift(t.value, 1f, tmp) }
        }
    }

    /** A few petals and lights only (used over the blurred page backdrop). */
    @Composable
    fun Petals(modifier: Modifier = Modifier, strength: Float = 0.5f) {
        val t = VenomTheme.clock()
        val tmp = remember { FloatArray(8) }
        Canvas(modifier) { drift(t.value, strength, tmp) }
    }

    /** The round chat picture: her face, zoomed in, from the same picture. */
    @Composable
    fun Avatar(modifier: Modifier = Modifier) {
        Box(modifier) {
            Image(
                painterResource(R.drawable.nisu_lace_bg), contentDescription = NAME, contentScale = ContentScale.Crop,
                alignment = androidx.compose.ui.BiasAlignment(0f, -1f),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.7f; scaleY = 1.7f
                    transformOrigin = TransformOrigin(0.48f, 0.2f)
                    translationY = size.height * 0.2f
                }
            )
        }
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
            Image(painterResource(R.drawable.nisu_lace_thumb), contentDescription = NAME, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
