package ai.nisu.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * v2.31, 2D anime character ("Live2D style") for the home page.
 *
 * The artwork (nisu_character.webp, 768 x 1376) is one flat picture. To make it move like a 2D anime / VTuber model
 * without needing a Live2D runtime, the picture is drawn as a deformable mesh (Canvas.drawBitmapMesh) and every
 * frame the mesh vertices are pushed around by small, smooth "deformers", exactly the idea Live2D uses:
 *
 *   - body sway   : the whole upper body rocks gently around the hips
 *   - breathing   : chest rises and widens, head lifts a little
 *   - hair sway   : both long side tails swing like they are caught in a breeze (more at the tips)
 *   - arms        : (v2.34) both arms hang down, the waving hand is gone
 *   - emotions    : (v2.34) the face follows the AI's mood (set_emotion): eyebrows, eyelids, mouth, blush, hearts, tears, anger mark ...
 *   - ears        : the cat ears twitch (left and right at different times)
 *   - ahoge       : the little hair antenna wobbles
 *   - blinking    : eyelids close and open (two independent timers, so it never looks mechanical)
 *   - talking     : when the assistant speaks the mouth opens and closes with her voice (lip-sync) and the head nods
 *   - cherry petals and twinkling sparkles float over the scene
 *
 * Everything is cheap (about 1,900 vertices per frame), no extra libraries, no extra assets.
 *
 * v2.93, "alive, not animated": the first version moved every part on a clean sine wave and blinked on two fixed timers, and a mood only changed the
 * face. A real person is never periodic and the whole body shows the mood, so now:
 *   - nothing repeats: blinks come at random intervals (sometimes double, slower when sleepy, one with every big glance and every mood change),
 *     the ears twitch at random, breathing has an occasional sigh, sway and head wander slowly on mixed-up waves
 *   - the eyes move: quick glances (saccades) around a resting gaze that depends on the mood; she looks up and aside while the AI is thinking and
 *     at you while it listens; the head follows the eyes a little
 *   - every mood has a posture: head tilt / dip, ears perked or flat, ahoge up or drooping, breathing speed and depth, trembling (anger, fear),
 *     bouncing (joy), nodding off (sleepy). Posture eases with a small spring, so it settles with a little overshoot like hand-drawn animation
 *   - follow-through: the hair lags behind the head and swings back; the head dips on each syllable; the mouth shape changes per syllable
 *   - the face eases towards a mood at a different speed per part (eyes quick, blush slow), all independent of the frame rate
 */

// v2.37: the background around the character slowly drifts through soft colours (pink > lavender > sky > mint > peach > rose)
private val TINTS = arrayOf(Color(0xFFFF9EC8), Color(0xFFB69CFF), Color(0xFF7FD8FF), Color(0xFF8CF0C8), Color(0xFFFFC98A), Color(0xFFFF8FA3))
private const val TINT_HOLD = 8f      // seconds per colour, the change itself takes about 5 of them

private fun tintAt(time: Float): Color {
    val u = time / TINT_HOLD
    val i = u.toInt()
    val a = TINTS[i % TINTS.size]
    val b = TINTS[(i + 1) % TINTS.size]
    return colorLerp(a, b, sm(0.35f, 1f, u - i))
}

// v2.74: the artwork is now a 688 x 1530 close-up portrait. All the motion numbers below were tuned on the old 768 x 1376 picture
// ("old space"); (x_new = A * x_old + BX, y_new = A * y_old + BY) maps them onto the new picture, measured from the eyes, ears and ahoge.
private const val IMG_W = 688f
private const val IMG_H = 1530f
private const val A = 1.96f
private const val BX = -413f
private const val BY = 99.2f
private const val COLS = 32
private const val ROWS = 57
private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

// eyelid skin tones, sampled from the artwork around the eyes
private val SKIN_TOP = Color(0xFFF9D5C8)
private val SKIN_BOT = Color(0xFFF6B8AE)
private val LASH = Color(0xFF6B3036)

private fun sm(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun bell(v: Float, c: Float, w: Float): Float {
    val d = (v - c) / w
    return exp(-d * d)
}

/** 1 inside the ellipse, smoothly 0 outside. */
private fun ell(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
    val a = (x - cx) / rx
    val b = (y - cy) / ry
    return 1f - sm(0.8f, 1.3f, sqrt(a * a + b * b))
}

/** v2.34: how much of the 24 px square around every mesh vertex is pink hair (0..1), smoothed, so hair can swing on its own. */
private fun hairWeights(bmp: android.graphics.Bitmap): FloatArray {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val g = FloatArray((COLS + 1) * (ROWS + 1))
    for (r in 0..ROWS) {
        for (c in 0..COLS) {
            val cx = (w * c / COLS).coerceIn(0, w - 1)
            val cy = (h * r / ROWS).coerceIn(0, h - 1)
            var n = 0
            var tot = 0
            var dy = -18
            while (dy <= 18) {
                var dx = -18
                while (dx <= 18) {
                    val xx = (cx + dx).coerceIn(0, w - 1)
                    val yy = (cy + dy).coerceIn(0, h - 1)
                    val p = px[yy * w + xx]
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    tot++
                    if (rr > 190 && bb > gg + 6 && bb < rr - 10 && gg < 208) n++
                    dx += 6
                }
                dy += 6
            }
            g[r * (COLS + 1) + c] = n.toFloat() / tot
        }
    }
    // soften with a 3 x 3 average so the swing fades out smoothly at the edge of the hair
    val o = FloatArray(g.size)
    for (r in 0..ROWS) for (c in 0..COLS) {
        var a = 0f
        var k = 0
        for (rr in (r - 1)..(r + 1)) for (cc in (c - 1)..(c + 1)) {
            if (rr in 0..ROWS && cc in 0..COLS) { a += g[rr * (COLS + 1) + cc]; k++ }
        }
        o[r * (COLS + 1) + c] = sm(0.15f, 0.7f, a / k)
    }
    return o
}

/** Smooth irregular value in -1..1 that does not repeat visibly (three waves with unrelated speeds). */
private fun wander(t: Float, seed: Float): Float =
    sin(t * 0.61f + seed * 7.1f) * 0.5f + sin(t * 1.07f + seed * 3.3f + 1.3f) * 0.3f + sin(t * 1.93f + seed * 5.9f + 2.1f) * 0.2f

// ---------------------------------------------------------------------------------------------------------------
// v2.93 body language: every mood has its own posture and way of moving.
// ---------------------------------------------------------------------------------------------------------------
private const val B_BREATH = 0    // breathing speed (x)
private const val B_DEPTH = 1     // breathing depth (x)
private const val B_SWAY = 2      // body sway size (x)
private const val B_SWAYS = 3     // body sway speed (x)
private const val B_TILT = 4      // head tilt in radians, + = clockwise on screen
private const val B_DIP = 5       // head dip in picture units, + = down (sad, shy), - = chin up (proud)
private const val B_TURN = 6      // head slide sideways, + = right
private const val B_DROOP = 7     // ears: + = flat / back, - = perked
private const val B_AHOGE = 8     // ahoge angle offset in radians (+ droops)
private const val B_HAIR = 9      // hair energy (x)
private const val B_BLINK = 10    // blink rate (x)
private const val B_GX = 11       // resting gaze, -1 left .. 1 right
private const val B_GY = 12       // resting gaze, -1 up .. 1 down
private const val B_GSPREAD = 13  // how far the eyes wander around the resting gaze (0 = fixed stare, 1 = darting)
private const val B_SHAKE = 14    // trembling (anger, fear)
private const val B_BOB = 15      // bouncing (joy)
private const val B_SLOW = 16     // slow heavy blinks and nodding off (sleepy)
private const val B_N = 17

private fun bodyTarget(emotion: String): FloatArray {
    val f = floatArrayOf(1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, 0.5f, 0f, 0f, 0f)   // calm
    fun s(vararg kv: Pair<Int, Float>) { for ((k, v) in kv) f[k] = v }
    when (emotion.trim().lowercase()) {
        "happy" -> s(B_BREATH to 1.1f, B_SWAY to 1.3f, B_TILT to 0.05f, B_DIP to -1f, B_DROOP to -0.3f, B_HAIR to 1.2f, B_BOB to 1.2f, B_GSPREAD to 0.6f)
        "excited" -> s(B_BREATH to 1.6f, B_DEPTH to 1.2f, B_SWAY to 1.7f, B_SWAYS to 1.5f, B_TILT to 0.04f, B_DROOP to -0.8f, B_HAIR to 1.8f, B_BLINK to 1.1f, B_BOB to 3f, B_GSPREAD to 0.8f)
        "sad" -> s(B_BREATH to 0.75f, B_DEPTH to 1.3f, B_SWAY to 0.5f, B_SWAYS to 0.7f, B_TILT to -0.06f, B_DIP to 7f, B_DROOP to 0.9f, B_AHOGE to 0.45f, B_HAIR to 0.6f, B_BLINK to 0.8f, B_GY to 0.6f, B_GSPREAD to 0.25f)
        "love" -> s(B_SWAY to 1.1f, B_SWAYS to 0.8f, B_TILT to 0.08f, B_DIP to 1f, B_DROOP to -0.2f, B_HAIR to 1.1f, B_BLINK to 0.9f, B_GY to 0.1f, B_BOB to 0.3f, B_GSPREAD to 0.3f)
        "surprised" -> s(B_BREATH to 1.6f, B_DEPTH to 0.6f, B_SWAY to 0.4f, B_DIP to -2f, B_DROOP to -1f, B_AHOGE to -0.12f, B_HAIR to 1.4f, B_BLINK to 0.3f, B_GSPREAD to 0.1f, B_SHAKE to 0.3f)
        "angry" -> s(B_BREATH to 1.5f, B_DEPTH to 1.3f, B_SWAY to 0.5f, B_TILT to -0.03f, B_DIP to 2f, B_DROOP to 0.8f, B_HAIR to 1.5f, B_BLINK to 0.5f, B_GY to 0.05f, B_GSPREAD to 0.15f, B_SHAKE to 0.9f)
        "jealous" -> s(B_BREATH to 1.2f, B_SWAY to 0.6f, B_TILT to -0.06f, B_TURN to -3f, B_DIP to 2f, B_DROOP to 0.5f, B_BLINK to 0.9f, B_GX to 0.9f, B_GSPREAD to 0.2f)
        "sulky" -> s(B_SWAY to 0.4f, B_TILT to 0.1f, B_TURN to 4f, B_DIP to 3f, B_DROOP to 0.6f, B_BLINK to 0.8f, B_GX to -0.9f, B_GY to 0.1f, B_GSPREAD to 0.2f)
        "playful" -> s(B_SWAY to 1.4f, B_SWAYS to 1.3f, B_TILT to 0.12f, B_DROOP to -0.4f, B_HAIR to 1.4f, B_BLINK to 1.2f, B_BOB to 1.6f, B_GSPREAD to 0.8f)
        "worried" -> s(B_BREATH to 1.3f, B_DEPTH to 0.8f, B_SWAY to 0.7f, B_TILT to 0.06f, B_DIP to 2f, B_DROOP to 0.5f, B_AHOGE to 0.3f, B_BLINK to 1.3f, B_GSPREAD to 0.7f, B_SHAKE to 0.35f)
        "shy" -> s(B_BREATH to 1.2f, B_SWAY to 0.6f, B_TILT to 0.1f, B_DIP to 4f, B_TURN to 3f, B_DROOP to 0.4f, B_BLINK to 1.3f, B_GX to -0.8f, B_GY to 0.55f, B_GSPREAD to 0.35f)
        "sleepy" -> s(B_BREATH to 0.6f, B_DEPTH to 1.4f, B_SWAY to 0.5f, B_SWAYS to 0.6f, B_TILT to 0.08f, B_DIP to 4f, B_DROOP to 0.6f, B_AHOGE to 0.35f, B_HAIR to 0.5f, B_BLINK to 0.6f, B_GY to 0.5f, B_GSPREAD to 0.1f, B_SLOW to 1f)
        "proud" -> s(B_BREATH to 0.9f, B_SWAY to 0.6f, B_TILT to -0.03f, B_DIP to -3f, B_DROOP to -0.5f, B_AHOGE to -0.08f, B_BLINK to 0.8f, B_GSPREAD to 0.15f)
        "curious" -> s(B_BREATH to 1.1f, B_SWAY to 0.8f, B_TILT to 0.13f, B_DROOP to -0.9f, B_AHOGE to -0.15f, B_BLINK to 0.9f, B_GX to 0.4f, B_GY to -0.35f, B_GSPREAD to 0.6f)
        "bored" -> s(B_BREATH to 0.8f, B_SWAY to 0.6f, B_TILT to 0.09f, B_DIP to 2f, B_DROOP to 0.3f, B_BLINK to 0.7f, B_GX to 0.8f, B_GY to 0.1f, B_GSPREAD to 0.5f)
        "nervous" -> s(B_BREATH to 1.5f, B_DEPTH to 0.7f, B_SWAY to 0.8f, B_SWAYS to 1.4f, B_TILT to 0.03f, B_DIP to 1f, B_DROOP to 0.5f, B_BLINK to 1.6f, B_GSPREAD to 1f, B_SHAKE to 0.45f)
        "disappointed" -> s(B_BREATH to 0.85f, B_SWAY to 0.6f, B_TILT to -0.05f, B_DIP to 4f, B_DROOP to 0.7f, B_AHOGE to 0.3f, B_HAIR to 0.7f, B_GY to 0.5f, B_GSPREAD to 0.3f)
        "grateful" -> s(B_BREATH to 0.95f, B_SWAY to 0.8f, B_TILT to 0.08f, B_DIP to 3f, B_DROOP to -0.2f, B_GSPREAD to 0.2f)
        "lonely" -> s(B_BREATH to 0.75f, B_SWAY to 0.5f, B_TILT to 0.05f, B_DIP to 5f, B_DROOP to 0.8f, B_AHOGE to 0.4f, B_HAIR to 0.6f, B_GX to -0.5f, B_GY to 0.6f, B_GSPREAD to 0.35f)
        "confused" -> s(B_BREATH to 1.1f, B_SWAY to 0.8f, B_TILT to 0.16f, B_DROOP to 0.1f, B_BLINK to 1.3f, B_GX to 0.5f, B_GY to -0.2f, B_GSPREAD to 0.8f)
        "scared" -> s(B_BREATH to 1.9f, B_DEPTH to 0.6f, B_SWAY to 0.5f, B_SWAYS to 1.6f, B_DIP to 1f, B_DROOP to 1f, B_AHOGE to 0.2f, B_HAIR to 1.5f, B_BLINK to 1.4f, B_GSPREAD to 1f, B_SHAKE to 0.8f)
        "annoyed" -> s(B_BREATH to 1.2f, B_SWAY to 0.6f, B_TILT to -0.05f, B_TURN to -2f, B_DROOP to 0.5f, B_BLINK to 0.8f, B_GX to 0.7f, B_GSPREAD to 0.2f)
        "flirty" -> s(B_SWAY to 1.2f, B_SWAYS to 0.8f, B_TILT to 0.1f, B_DIP to 1f, B_DROOP to -0.3f, B_HAIR to 1.1f, B_BLINK to 0.9f, B_GX to 0.3f, B_GY to 0.1f, B_GSPREAD to 0.3f)
        "guilty" -> s(B_BREATH to 1.1f, B_SWAY to 0.5f, B_TILT to 0.06f, B_DIP to 5f, B_DROOP to 0.6f, B_BLINK to 1.2f, B_GX to -0.5f, B_GY to 0.6f, B_GSPREAD to 0.5f)
        "relieved" -> s(B_BREATH to 0.8f, B_DEPTH to 1.6f, B_SWAY to 0.8f, B_TILT to 0.04f, B_DIP to 2f, B_BLINK to 0.8f)
        "hopeful" -> s(B_BREATH to 1.1f, B_TILT to 0.07f, B_DIP to -1f, B_DROOP to -0.5f, B_AHOGE to -0.1f, B_GY to -0.4f, B_GSPREAD to 0.4f)
        "mischievous" -> s(B_SWAY to 1.3f, B_SWAYS to 1.2f, B_TILT to -0.1f, B_DROOP to -0.2f, B_HAIR to 1.3f, B_BLINK to 0.9f, B_GX to 0.6f, B_GSPREAD to 0.5f)
        "overwhelmed" -> s(B_BREATH to 1.7f, B_DEPTH to 0.8f, B_SWAY to 0.7f, B_SWAYS to 1.4f, B_TILT to 0.05f, B_DIP to 3f, B_DROOP to 0.8f, B_HAIR to 1.3f, B_BLINK to 1.5f, B_GSPREAD to 1f, B_SHAKE to 0.5f)
        "nostalgic" -> s(B_BREATH to 0.8f, B_DEPTH to 1.2f, B_SWAY to 0.7f, B_SWAYS to 0.7f, B_TILT to 0.07f, B_DIP to 2f, B_DROOP to 0.1f, B_BLINK to 0.8f, B_GX to 0.2f, B_GY to -0.25f, B_GSPREAD to 0.2f)
        "heartbroken" -> s(B_BREATH to 0.8f, B_DEPTH to 1.4f, B_SWAY to 0.4f, B_TILT to -0.08f, B_DIP to 8f, B_DROOP to 1f, B_AHOGE to 0.5f, B_HAIR to 0.5f, B_BLINK to 0.7f, B_GY to 0.7f, B_GSPREAD to 0.2f, B_SHAKE to 0.2f)
        else -> { }
    }
    return f
}

/** The posture goal for a mood, plus what the AI is doing right now (mode 2 = thinking, 1 = listening). Recomputed only when something changes. */
private class BodyGoal {
    private var lastEmotion = ""
    private var lastMode = -1
    private var arr = bodyTarget("calm")
    fun get(emotion: String, mode: Int): FloatArray {
        if (emotion != lastEmotion || mode != lastMode) {
            lastEmotion = emotion; lastMode = mode
            val g = bodyTarget(emotion)
            when (mode) {
                2 -> { g[B_GX] = -0.7f; g[B_GY] = -0.6f; g[B_GSPREAD] = 0.2f; g[B_TILT] += 0.06f }          // thinking: eyes up and aside, head tilted
                1 -> { g[B_GX] *= 0.35f; g[B_GY] *= 0.35f; g[B_GSPREAD] *= 0.6f; g[B_TILT] += 0.02f }        // listening: mostly looks at you
            }
            arr = g
        }
        return arr
    }
}

/** How fast every face number eases towards the mood (per second): eyes and brows quick, blush and tears slow. */
private val FACE_RATE = floatArrayOf(11f, 9f, 10f, 9f, 9f, 8f, 8f, 12f, 10f, 6f, 8f, 2.8f, 7f, 6f, 2.2f, 9f, 4f, 14f, 3f, 12f)

private class Pose {
    private val rnd = kotlin.random.Random(System.nanoTime())
    var t = 0f
    var dt = 0.016f
    var emotion = ""
    /** Posture (see B_*), eased with a small spring so a change settles with a little overshoot. */
    val body = bodyTarget("calm")
    private val vel = FloatArray(B_N)

    var breath = 0f
    var ahoge = 0f
    var earL = 0f
    var earR = 0f
    var talk = 0f
    var mouth = 0f
    var vowel = 0.5f
    var gx = 0f
    var gy = 0f
    var react = 0f
    var tiltDeg = 0f
    var dx = 0f
    var dy = 0f

    private var inited = false
    private var lastT = 0f
    private var lastMode = -1
    private var breathPh = 0f
    private var swayPh = 0f
    private var bobPh = 0f
    private var hairPh = 0f
    private var hairPh2 = 0f
    private var ahogePh = 0f
    private var talkSlow = 0f
    private var accent = 0f
    private var vowelT = 0.5f
    private var nextVowel = 0f
    private var gTx = 0f
    private var gTy = 0f
    private var nextGaze = 1f
    private var blinkStart = -10f
    private var bClose = 0.065f
    private var bHold = 0.025f
    private var bOpen = 0.13f
    private var nextBlink = 2.2f
    private var earLStart = -10f
    private var earRStart = -10f
    private var nextEarL = 3f
    private var nextEarR = 5.5f
    private var sighU = -1f
    private var nextSigh = 12f
    private var lagX = 0f
    private var lagY = 0f
    // values for the current frame, used by warp()
    private var fSway = 0f
    private var fShake = 0f
    private var fBob = 0f
    private var fTurn = 0f
    private var fDip = 0f
    private var fTilt = 0f
    private var fDroop = 0f
    private var fEarAmp = 1f
    private var fAhogeBase = 0f
    private var fHair = 1f
    private var fHairLagX = 0f
    private var fHairLagY = 0f
    private var fGazeX = 0f
    private var fGazeY = 0f

    private fun m(i: Int): Float = body[i].coerceAtLeast(0.15f)

    fun onEmotionChange(time: Float) {
        react = 1f                 // a small head dip and a blink when the mood changes, the way a person "takes it in"
        requestBlink(time)
    }

    fun update(time: Float, talkLevel: Float, mode: Int, goal: FloatArray) {
        t = time
        dt = (time - lastT).coerceIn(0.001f, 0.08f)
        lastT = time

        // 1. posture: a spring towards the mood's goal
        for (i in 0 until B_N) {
            vel[i] += (42f * (goal[i] - body[i]) - 7.5f * vel[i]) * dt
            body[i] += vel[i] * dt
        }
        if (mode != lastMode) {                      // thinking / listening started or ended: look somewhere new at once
            lastMode = mode
            nextGaze = time
            if (mode == 2) requestBlink(time)
        }

        // 2. breathing: quicker in, slower out, and now and then a sigh
        breathPh += dt * TAU / 3.6f * m(B_BREATH)
        if (sighU < 0f) { if (time >= nextSigh) sighU = 0f }
        else { sighU += dt / 2.8f; if (sighU >= 1f) { sighU = -1f; nextSigh = time + 14f + rnd.nextFloat() * 14f } }
        val sigh = if (sighU >= 0f) sin(sighU * PI_F) else 0f
        breath = sin(breathPh - 0.4f * sin(breathPh)) * (m(B_DEPTH) + 1.1f * sigh)

        // 3. sway (slow, never the same twice), trembling and bouncing
        swayPh += dt * TAU / 5.2f * m(B_SWAYS)
        fSway = (0.85f * sin(swayPh) + 0.35f * wander(time, 5f)) * m(B_SWAY)
        fShake = body[B_SHAKE] * (sin(time * 43f) + 0.6f * sin(time * 29.5f + 1f)) * 0.5f
        bobPh += dt * TAU * 1.6f * m(B_SWAYS)
        fBob = body[B_BOB] * abs(sin(bobPh))

        // 4. voice: lip-sync, nod accents on syllable onsets, a new mouth shape every syllable
        val lvl = talkLevel.coerceIn(0f, 1f)
        talk += (lvl - talk) * (1f - exp(-11.9f * dt))
        talkSlow += (lvl - talkSlow) * (1f - exp(-dt / 0.25f))
        accent += ((lvl - talkSlow).coerceAtLeast(0f) - accent) * (1f - exp(-dt / 0.07f))
        val target = (lvl * 2.2f).coerceIn(0f, 1f)
        mouth += (target - mouth) * (1f - exp(-(if (target > mouth) 63f else 21f) * dt))
        if (mouth < 0.01f && target == 0f) mouth = 0f
        if (mouth > 0.06f && time >= nextVowel) { vowelT = rnd.nextFloat(); nextVowel = time + 0.07f + rnd.nextFloat() * 0.10f }
        vowel += (vowelT - vowel) * (1f - exp(-28f * dt))
        val talking = talk > 0.08f

        // 5. eyes and blinking
        updateGaze(time, body[B_GX], body[B_GY], body[B_GSPREAD], talking)
        updateBlink(time, m(B_BLINK), talking)
        fGazeX = gx + 0.05f * wander(time, 4f)      // tiny drift while the eyes rest
        fGazeY = gy + 0.05f * wander(time, 6f)

        // 6. ears (random twitches), ahoge, hair
        if (time >= nextEarL) { earLStart = time; nextEarL = time + (3f + rnd.nextFloat() * 6f) * (if (body[B_DROOP] > 0.3f) 1.6f else 1f) }
        if (time >= nextEarR) { earRStart = time; nextEarR = time + (3.5f + rnd.nextFloat() * 6f) * (if (body[B_DROOP] > 0.3f) 1.6f else 1f) }
        earL = twitch(time - earLStart)
        earR = twitch(time - earRStart)
        fDroop = body[B_DROOP].coerceIn(-1.2f, 1.2f)
        fEarAmp = (1f - 0.5f * fDroop).coerceIn(0.4f, 1.5f)
        fAhogeBase = body[B_AHOGE]
        fHair = m(B_HAIR).coerceAtMost(2f)
        ahogePh += dt * TAU / 2.4f * (0.6f + 0.4f * fHair)
        ahoge = sin(ahogePh)
        hairPh += dt * TAU / 3.1f * (0.7f + 0.3f * fHair)
        hairPh2 += dt * TAU / 1.7f * (0.7f + 0.3f * fHair)

        // 7. head: slide, dip and tilt (the eyes lead, the head follows a little)
        val ph9 = (time % 9f) / 9f
        val nodOff = body[B_SLOW].coerceIn(0f, 1f) * (if (ph9 < 0.75f) sm(0f, 0.75f, ph9) else 1f - sm(0.75f, 0.8f, ph9))   // sleepy: slowly sinks, then jerks up
        react *= exp(-5f * dt)
        fTurn = body[B_TURN] + gx * 0.9f + 0.9f * talk * wander(time, 2f)
        fDip = body[B_DIP] + react * 1.6f + 8f * accent + nodOff * 6f + 1.5f * sigh
        fTilt = body[B_TILT] + gx * 0.02f + 0.02f * talk * wander(time, 3f) + 0.012f * wander(time, 1f)
        tiltDeg = fTilt * 57.2958f

        // 8. follow-through: the hair is slower than the head, so it trails behind and swings back
        val headX = fSway * 7f + fTurn + fShake + fTilt * 60f
        val headY = -breath * 3.5f + fDip - fBob
        if (!inited) { inited = true; lagX = headX; lagY = headY }
        val k = 1f - exp(-dt / 0.22f)
        lagX += (headX - lagX) * k
        lagY += (headY - lagY) * k
        fHairLagX = ((lagX - headX) * 1.6f).coerceIn(-14f, 14f)
        fHairLagY = ((lagY - headY) * 1.2f).coerceIn(-10f, 10f)
    }

    private fun twitch(u: Float): Float = if (u in 0f..0.45f) sin(u / 0.45f * TAU * 1.5f) * (1f - u / 0.45f) else 0f

    private fun updateGaze(time: Float, restX: Float, restY: Float, spread: Float, talking: Boolean) {
        if (time >= nextGaze) {
            val centre = rnd.nextFloat() < (if (talking) 0.5f else 0.3f)     // often back to the person, a quick glance away in between
            if (centre) { gTx = restX * 0.25f; gTy = restY * 0.25f }
            else {
                gTx = (restX + (rnd.nextFloat() * 2f - 1f) * spread).coerceIn(-1f, 1f)
                gTy = (restY + (rnd.nextFloat() * 2f - 1f) * spread * 0.6f).coerceIn(-1f, 1f)
            }
            nextGaze = time + (0.6f + rnd.nextFloat() * 2.6f) / (0.5f + spread)   // anxious eyes move more often
            if (abs(gTx - gx) > 0.6f && rnd.nextFloat() < 0.5f) requestBlink(time)   // people often blink with a big glance
        }
        val a = 1f - exp(-22f * dt)      // a saccade is a quick snap
        gx += (gTx - gx) * a
        gy += (gTy - gy) * a
    }

    private fun startBlink(time: Float) {
        blinkStart = time
        val sl = body[B_SLOW].coerceIn(0f, 1f)       // sleepy blinks are slow and heavy
        bClose = 0.065f + 0.17f * sl
        bHold = 0.025f + 0.16f * sl
        bOpen = 0.13f + 0.17f * sl
    }

    fun requestBlink(time: Float) { if (time - blinkStart > 0.4f) startBlink(time) }

    private fun updateBlink(time: Float, rate: Float, talking: Boolean) {
        if (time >= nextBlink && time - blinkStart > 0.3f) {
            startBlink(time)
            nextBlink = if (rnd.nextFloat() < 0.16f) time + 0.34f        // now and then a double blink
            else time + (1.8f + rnd.nextFloat() * 4.4f) / (if (talking) rate * 1.2f else rate)
        }
    }

    /** 0 = open, 1 = closed: closes fast, holds a moment, opens a little slower. */
    fun blinkAmount(time: Float): Float {
        val u = time - blinkStart
        if (u < 0f) return 0f
        if (u < bClose) { val x = u / bClose; return 1f - (1f - x) * (1f - x) }
        if (u < bClose + bHold) return 1f
        if (u < bClose + bHold + bOpen) return 1f - sm(0f, 1f, (u - bClose - bHold) / bOpen)
        return 0f
    }

    private fun rot(x: Float, y: Float, px: Float, py: Float, ang: Float, w: Float) {
        if (w <= 0f || ang == 0f) return
        val rx = x - px
        val ry = y - py
        val c = cos(ang)
        val s = sin(ang)
        dx += ((rx * c - ry * s) - rx) * w
        dy += ((rx * s + ry * c) - ry) * w
    }

    /** Displacement (dx, dy) of the picture point (x, y), in picture pixels. [gaze] false = without the eye movement (the lids are drawn on top of the eyes and must not follow the iris). */
    fun warp(xn: Float, yn: Float, hairW: Float = 1f, gaze: Boolean = true) {
        val x = (xn - BX) / A          // v2.74: point in old space
        val y = (yn - BY) / A
        dx = 0f; dy = 0f

        // 1. body sway: rotation around the hips, legs stay planted
        val wb = sm(1180f, 520f, y)
        dx += fSway * 0.008f * (1180f - y) * wb
        dy += fSway * 0.008f * (x - 384f) * wb

        // 1b. trembling and bouncing of the upper body
        val wu = sm(720f, 300f, y)
        dx += fShake * wu
        dy -= fBob * wu

        // 2. breathing: upper body lifts, chest widens
        dy -= breath * 3.5f * sm(1000f, 400f, y)
        dx += breath * 0.010f * (x - 384f) * bell(y, 560f, 170f)

        // 3. head: slide, dip (nod) and tilt around the neck
        val wh = sm(410f, 320f, y)
        if (wh > 0f) {
            dx += fTurn * wh
            dy += (fDip + talk * 1.5f) * wh
            rot(x, y, 386f, 345f, fTilt, wh)
        }

        // 4. hair: the two long tails swing, more towards the tips; only real hair moves (hairW = how much hair is under this point)
        val hw = sm(180f, 600f, y) * (1f - 0.5f * sm(640f, 780f, y)) * hairW
        val left = sm(345f, 255f, x)
        val right = sm(440f, 530f, x) * (1f - sm(630f, 690f, x))
        val ph = if (x < 384f) 0f else 1.7f
        val swing = sin(hairPh - y * 0.0045f + ph) + 0.35f * sin(hairPh2 - y * 0.006f + ph * 2f)
        val hs = hw * (left + right)
        dx += swing * 5.5f * fHair * hs + fHairLagX * hs
        dy += swing * 1.2f * fHair * hs + fHairLagY * hs

        // 5. cat ears: random twitches on top of the mood's resting angle (flat when sad / scared, perked when curious)
        val droop = fDroop * 0.20f
        rot(x, y, 330f, 170f, earL * 0.17f * fEarAmp - droop, ell(x, y, 308f, 158f, 28f, 30f))
        rot(x, y, 425f, 165f, -earR * 0.17f * fEarAmp + droop, ell(x, y, 435f, 138f, 26f, 32f))

        // 6. ahoge (hair antenna)
        rot(x, y, 366f, 136f, ahoge * 0.12f * fHair + fAhogeBase, ell(x, y, 368f, 112f, 26f, 28f))

        // 7. eyes: the iris slides inside the eye (a small smooth warp around each eye)
        if (gaze) {
            val gw = max(ell(x, y, 351f, 260f, 15f, 11f), ell(x, y, 420f, 251f, 16f, 12f))
            if (gw > 0f) { dx += fGazeX * 2.2f * gw; dy += fGazeY * 1.6f * gw }
        }

        // keep the picture's own border still so no gap ever shows
        val edge = sm(0f, 40f, xn) * sm(IMG_W, IMG_W - 40f, xn) * sm(0f, 40f, yn) * sm(IMG_H, IMG_H - 40f, yn)
        dx *= edge * A                 // back to new-picture pixels
        dy *= edge * A
    }
}

private fun DrawScope.drawEyelid(sx: Float, sy: Float, w: Float, h: Float, tilt: Float, c: Float) {
    if (c <= 0.03f) return
    withTransform({ translate(sx, sy); rotate(tilt, Offset.Zero) }) {
        // the lid is painted as 3 soft layers (big + faint -> small + solid) so its edge melts into the cheek
        val layers = floatArrayOf(1.28f, 0.18f, 1.14f, 0.42f, 1.0f, 1.0f)
        var li = 0
        while (li < layers.size) {
            val sc = layers[li]
            val al = layers[li + 1]
            li += 2
            val ow = w * sc
            val oh = h * sc
            val oval = Path().apply { addOval(Rect(-ow, -oh, ow, oh)) }
            val top = -oh - 1f
            val bottom = top + (2f * oh + 2f) * c
            clipPath(oval) {
                drawRect(
                    Brush.verticalGradient(listOf(SKIN_TOP.copy(alpha = al), SKIN_BOT.copy(alpha = al)), startY = -oh, endY = oh),
                    topLeft = Offset(-ow - 2f, top), size = Size(2f * ow + 4f, bottom - top)
                )
            }
        }
        val bottom = -h - 1f + (2f * h + 2f) * c
        val a = sm(0.25f, 0.6f, c)
        if (a > 0f) {
            val ly = if (bottom < h * 0.35f) bottom else h * 0.35f
            val p = Path().apply {
                moveTo(-w * 0.90f, ly - h * 0.20f)
                quadraticBezierTo(0f, ly + h * 0.50f, w * 0.90f, ly - h * 0.20f)
            }
            drawPath(p, LASH.copy(alpha = a), style = Stroke(width = max(2.4f, h * 0.17f), cap = StrokeCap.Round))
        }
    }
}

// v2.95: the patch that hides the painted smile is now cut from the artwork itself. v2.94 painted a flat colour gradient over the mouth;
// that is closer to the skin than v2.93 but still a guess, and on a different phone / brightness / skin shading it still showed as a pale
// oval. Now the skin above and below the lips is sampled from the real picture, column by column, and blended between the two, so the
// patch is the artwork's own skin and has no colour of its own to get wrong. The patch is built once, in the mouth's own (rotated) frame.
private const val PATCH_W = 96            // patch size in picture pixels
private const val PATCH_H = 44
private const val PATCH_CX = 351.4f       // mouth centre in picture pixels = A * 390 + BX
private const val PATCH_CY = 683.28f      // = A * 298 + BY
private const val PATCH_ROT = -7f         // the mouth frame is turned by -7 degrees, like in drawMouth
private const val PATCH_RX = 44f          // soft oval of the patch (alpha 1 inside, fades to 0 at the rim)
private const val PATCH_RY = 17f
private const val PATCH_V_TOP = -14f      // rows (in the mouth frame) that are plain skin, above and below the lips
private const val PATCH_V_BOT = 16f

private fun buildMouthPatch(bmp: Bitmap): Bitmap? {
    return try {
        val w = bmp.width
        val h = bmp.height
        if (w < 400 || h < 800) return null
        val sx = w / IMG_W
        val sy = h / IMG_H
        val rad = PATCH_ROT * PI_F / 180f
        val cr = cos(rad)
        val sr = sin(rad)
        // bilinear colour of the artwork at picture position (x, y) -> r, g, b
        fun sample(x: Float, y: Float, out: FloatArray) {
            val fx = (x * sx).coerceIn(0f, w - 1.001f)
            val fy = (y * sy).coerceIn(0f, h - 1.001f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val ax = fx - x0
            val ay = fy - y0
            val p00 = bmp.getPixel(x0, y0)
            val p10 = bmp.getPixel(x0 + 1, y0)
            val p01 = bmp.getPixel(x0, y0 + 1)
            val p11 = bmp.getPixel(x0 + 1, y0 + 1)
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                val v00 = (p00 shr sh) and 255
                val v10 = (p10 shr sh) and 255
                val v01 = (p01 shr sh) and 255
                val v11 = (p11 shr sh) and 255
                out[c] = v00 * (1f - ax) * (1f - ay) + v10 * ax * (1f - ay) + v01 * (1f - ax) * ay + v11 * ax * ay
            }
        }
        val top = Array(PATCH_W) { FloatArray(3) }
        val bot = Array(PATCH_W) { FloatArray(3) }
        val tmp = FloatArray(3)
        val dvs = floatArrayOf(0f, 1.5f, 3f)
        for (i in 0 until PATCH_W) {
            val u = i + 0.5f - PATCH_W / 2f
            for (dv in dvs) {
                var v = PATCH_V_TOP - dv
                sample(PATCH_CX + u * cr - v * sr, PATCH_CY + u * sr + v * cr, tmp)
                for (c in 0 until 3) top[i][c] += tmp[c] / 3f
                v = PATCH_V_BOT + dv
                sample(PATCH_CX + u * cr - v * sr, PATCH_CY + u * sr + v * cr, tmp)
                for (c in 0 until 3) bot[i][c] += tmp[c] / 3f
            }
        }
        // smooth sideways so no single noisy pixel makes a streak
        fun blur(a: Array<FloatArray>): Array<FloatArray> {
            val o = Array(PATCH_W) { FloatArray(3) }
            for (i in 0 until PATCH_W) {
                for (j in (i - 3)..(i + 3)) {
                    val jj = j.coerceIn(0, PATCH_W - 1)
                    for (c in 0 until 3) o[i][c] += a[jj][c] / 7f
                }
            }
            return o
        }
        val tb = blur(top)
        val bb = blur(bot)
        val px = IntArray(PATCH_W * PATCH_H)
        for (j in 0 until PATCH_H) {
            val v = j + 0.5f - PATCH_H / 2f
            val t = ((v - PATCH_V_TOP) / (PATCH_V_BOT - PATCH_V_TOP)).coerceIn(0f, 1f)
            for (i in 0 until PATCH_W) {
                val u = i + 0.5f - PATCH_W / 2f
                val a = (u / PATCH_RX)
                val b = (v / PATCH_RY)
                val al = 1f - sm(0.65f, 1f, sqrt(a * a + b * b))
                val r = (tb[i][0] * (1f - t) + bb[i][0] * t + 0.5f).toInt().coerceIn(0, 255)
                val g = (tb[i][1] * (1f - t) + bb[i][1] * t + 0.5f).toInt().coerceIn(0, 255)
                val bl = (tb[i][2] * (1f - t) + bb[i][2] * t + 0.5f).toInt().coerceIn(0, 255)
                px[j * PATCH_W + i] = ((al * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        Bitmap.createBitmap(px, PATCH_W, PATCH_H, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) { null }
}

// v2.94: the colours of the real skin around the mouth, measured on the artwork (above / below the lips). The patch that hides the painted
// smile used to be a lighter colour (FFE6D6), which showed as a pale oval around the mouth as soon as the AI was on.
private val SKIN_MOUTH_TOP = Color(0xFFF8D9C9)
private val SKIN_MOUTH_BOT = Color(0xFFFAD5C6)
private val MOUTH_IN = Color(0xFF7B2B38)
private val MOUTH_EDGE = Color(0xFF4E1D26)
private val TONGUE = Color(0xFFEE8C98)

/**
 * Mouth. Units are picture pixels, (sx, sy) = mouth centre on screen, s = picture-to-screen scale.
 * [open] = how far the voice opens it (lip-sync); [f] = the current face numbers (mood) which choose the closed-mouth shape
 * (smile, frown, grit, pout, cat smile ...) and a resting open amount (surprise).
 */
private fun DrawScope.drawMouth(sx: Float, sy: Float, s: Float, open: Float, t: Float, f: FloatArray, vowel: Float, tiltDeg: Float, patch: Bitmap?, patchPaint: Paint) {
    val eo = max(open, f[F_OPEN])
    val shape = f[F_SHAPE]
    val fadeAll = max(sm(0.03f, 0.14f, eo), shape)
    if (fadeAll <= 0.03f) return
    withTransform({ translate(sx, sy); rotate(-7f + tiltDeg, Offset.Zero); scale(s, s, Offset.Zero) }) {
        // 1. hide the drawn smile line. v2.95: with the artwork's own skin (see buildMouthPatch); the flat-colour patch below is only the
        //    fallback if the patch could not be built.
        if (patch != null) {
            patchPaint.alpha = (fadeAll.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val hw = PATCH_W / 2f / A
            val hh = PATCH_H / 2f / A
            drawContext.canvas.nativeCanvas.drawBitmap(patch, null, RectF(-hw, -hh, hw, hh), patchPaint)
        } else {
            val flat = floatArrayOf(1.3f, 0.30f, 1.15f, 0.65f, 1.0f, 1.0f)
            var i = 0
            while (i < flat.size) {
                val sc = flat[i]
                val al = flat[i + 1] * fadeAll
                i += 2
                val skin = Brush.verticalGradient(listOf(SKIN_MOUTH_TOP, SKIN_MOUTH_BOT), startY = -6f * sc, endY = 6f * sc)
                drawOval(skin, Offset(-16f * sc, -6f * sc), Size(32f * sc, 12f * sc), alpha = al)
            }
        }

        // 2. closed mouth shape of the current mood (fades out while the mouth is open)
        val closed = (1f - sm(0.03f, 0.2f, eo)) * shape
        if (closed > 0.03f) {
            val w = f[F_MW]
            val c = f[F_CURVE]
            val wavy = f[F_WAVY]
            val cat = f[F_CAT]
            val teeth = f[F_TEETH]
            val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
            if (teeth > 0.05f) {
                val a = teeth * closed
                drawRoundRect(Color.White.copy(alpha = 0.95f * a), Offset(-w * 0.8f, -2.4f), Size(w * 1.6f, 4.8f), androidx.compose.ui.geometry.CornerRadius(1.6f, 1.6f))
                drawRoundRect(MOUTH_EDGE.copy(alpha = a), Offset(-w * 0.8f, -2.4f), Size(w * 1.6f, 4.8f), androidx.compose.ui.geometry.CornerRadius(1.6f, 1.6f), style = Stroke(width = 1.2f))
                drawLine(MOUTH_EDGE.copy(alpha = 0.8f * a), Offset(-w * 0.8f, 0f), Offset(w * 0.8f, 0f), strokeWidth = 0.9f)
                var tx = -w * 0.4f
                while (tx < w * 0.8f) { drawLine(MOUTH_EDGE.copy(alpha = 0.7f * a), Offset(tx, -2.4f), Offset(tx, 2.4f), strokeWidth = 0.8f); tx += w * 0.4f }
            } else {
                val line = Path().apply {
                    moveTo(-w, 0f)
                    cubicTo(-w * 0.5f, c * 2f - 3f * wavy, w * 0.5f, c * 2f + 3f * wavy, w, 0f)
                }
                drawPath(line, MOUTH_LINE.copy(alpha = closed * (1f - cat)), style = stroke)
            }
            if (cat > 0.05f) {
                val cp = Path().apply {
                    moveTo(-w * 0.9f, 0f)
                    quadraticBezierTo(-w * 0.45f, c * 1.6f, 0f, 0f)
                    quadraticBezierTo(w * 0.45f, c * 1.6f, w * 0.9f, 0f)
                }
                drawPath(cp, MOUTH_LINE.copy(alpha = closed * cat), style = stroke)
            }
        }

        // 3. the open mouth: width and height follow the voice, a little wobble makes "a / o / e" shapes
        val fadeIn = sm(0.03f, 0.14f, eo)
        if (fadeIn <= 0.02f) return@withTransform
        val speaking = open >= f[F_OPEN]
        val wobAmt = if (speaking) 0.10f else 0.03f
        val wob = 1f + wobAmt * (0.6f * sin(t * 9.3f) + 0.4f * sin(t * 14.7f + 1f))
        val vv = if (speaking) vowel else 0.5f      // while she speaks the mouth shape (wide "e" .. round "o") changes with every syllable
        val w = (4.5f + 5.5f * eo) * wob * (0.78f + 0.44f * vv)
        val hh = (0.9f + 6.5f * eo) * (1.18f - 0.36f * vv)
        val mouth = Path().apply {
            moveTo(-w, 0f)
            quadraticBezierTo(0f, -hh * 0.9f, w, 0f)
            quadraticBezierTo(0f, hh * 2.1f, -w, 0f)
            close()
        }
        drawPath(mouth, MOUTH_IN.copy(alpha = fadeIn))
        clipPath(mouth) {
            if (hh > 2.2f) drawOval(TONGUE.copy(alpha = fadeIn), Offset(-w * 0.55f, hh * 0.30f), Size(w * 1.1f, hh * 1.3f))
            if (hh > 3.0f) drawRect(Color.White.copy(alpha = 0.92f * fadeIn), Offset(-w * 0.7f, -hh * 0.42f), Size(w * 1.4f, 1.5f))
        }
        drawPath(mouth, MOUTH_EDGE.copy(alpha = fadeIn), style = Stroke(width = 1.1f, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// v2.34 emotions. The AI calls set_emotion(happy|excited|calm|sad|love|surprised|angry|jealous|sulky|playful|worried|shy|sleepy|proud|curious|bored|nervous|disappointed|grateful|lonely|confused|scared|annoyed|flirty|guilty|relieved|hopeful|mischievous|overwhelmed|nostalgic|heartbroken);
// every mood is a small set of numbers (eyebrows, eyelids, mouth shape, blush ...) and the face eases towards them.
// ---------------------------------------------------------------------------------------------------------------
private const val F_BROW = 0      // eyebrow opacity (the bangs hide natural brows, so they are only drawn when the mood needs them)
private const val F_BTILT = 1     // + = inner end up (sad / worried), - = inner end down (angry)
private const val F_BY = 2        // eyebrow height, - = raised
private const val F_LID = 3       // steady eyelid closure 0..1 (sleepy / narrowed eyes)
private const val F_LTILT = 4     // eyelid slant in degrees, + = inner corner lower (angry), - = outer corner lower (sad)
private const val F_CURVE = 5     // mouth curve, + = smile, - = frown
private const val F_MW = 6        // mouth half width
private const val F_OPEN = 7      // resting open amount of the mouth (surprise, laugh)
private const val F_TEETH = 8     // gritted teeth (anger)
private const val F_WAVY = 9      // wobbly mouth (worried, jealous)
private const val F_CAT = 10      // cat smile "w"
private const val F_BLUSH = 11
private const val F_HEART = 12    // heart eyes
private const val F_SPARK = 13    // sparkling eyes
private const val F_TEAR = 14
private const val F_ANGER = 15    // anger vein mark
private const val F_SWEAT = 16
private const val F_WINK = 17
private const val F_GLOOM = 18    // dark gloom lines on the forehead (jealous)
private const val F_SHAPE = 19    // 1 = draw our own mouth instead of the smile that is painted in the artwork
private const val F_N = 20

private fun faceTarget(emotion: String): FloatArray {
    val f = FloatArray(F_N)
    when (emotion.trim().lowercase()) {
        "happy" -> { f[F_LID] = 0.12f; f[F_CURVE] = 6f; f[F_MW] = 11f; f[F_BLUSH] = 0.45f; f[F_SPARK] = 0.5f; f[F_SHAPE] = 1f }
        "excited" -> { f[F_BROW] = 0.5f; f[F_BY] = -6f; f[F_BTILT] = 2f; f[F_LID] = 0.04f; f[F_CURVE] = 7f; f[F_MW] = 12f; f[F_OPEN] = 0.45f
            f[F_BLUSH] = 0.55f; f[F_SPARK] = 1f; f[F_SHAPE] = 1f }
        "sad" -> { f[F_BROW] = 0.85f; f[F_BTILT] = 7f; f[F_LID] = 0.30f; f[F_LTILT] = -10f; f[F_CURVE] = -5f; f[F_MW] = 7f
            f[F_TEAR] = 1f; f[F_SHAPE] = 1f }
        "love" -> { f[F_LID] = 0.08f; f[F_CURVE] = 6f; f[F_MW] = 9f; f[F_BLUSH] = 1f; f[F_HEART] = 1f; f[F_SHAPE] = 1f }
        "surprised" -> { f[F_BROW] = 0.8f; f[F_BY] = -9f; f[F_BTILT] = 1f; f[F_OPEN] = 0.55f; f[F_SPARK] = 0.3f; f[F_SHAPE] = 1f }
        "angry" -> { f[F_BROW] = 1f; f[F_BTILT] = -7f; f[F_LID] = 0.32f; f[F_LTILT] = 13f; f[F_CURVE] = -1.5f; f[F_MW] = 9f
            f[F_TEETH] = 1f; f[F_ANGER] = 1f; f[F_BLUSH] = 0.15f; f[F_SHAPE] = 1f }
        "jealous" -> { f[F_BROW] = 0.8f; f[F_BTILT] = -3f; f[F_LID] = 0.30f; f[F_LTILT] = 4f; f[F_CURVE] = -2f; f[F_MW] = 7f
            f[F_WAVY] = 0.3f; f[F_BLUSH] = 0.45f; f[F_GLOOM] = 1f; f[F_SHAPE] = 1f }
        "sulky" -> { f[F_BROW] = 0.7f; f[F_BTILT] = -2f; f[F_LID] = 0.20f; f[F_LTILT] = 5f; f[F_CURVE] = -3f; f[F_MW] = 5f
            f[F_BLUSH] = 0.8f; f[F_SHAPE] = 1f }
        "playful" -> { f[F_CURVE] = 3f; f[F_MW] = 9f; f[F_CAT] = 1f; f[F_WINK] = 1f; f[F_BLUSH] = 0.4f; f[F_SPARK] = 0.4f; f[F_SHAPE] = 1f }
        "worried" -> { f[F_BROW] = 0.9f; f[F_BTILT] = 8f; f[F_LID] = 0.10f; f[F_CURVE] = -2f; f[F_MW] = 7f; f[F_WAVY] = 1f
            f[F_SWEAT] = 1f; f[F_SHAPE] = 1f }
        "shy" -> { f[F_BROW] = 0.4f; f[F_BTILT] = 4f; f[F_LID] = 0.15f; f[F_CURVE] = 3f; f[F_MW] = 7f; f[F_BLUSH] = 1f; f[F_SHAPE] = 1f }
        "sleepy" -> { f[F_LID] = 0.65f; f[F_CURVE] = 1f; f[F_MW] = 6f; f[F_OPEN] = 0.15f; f[F_BLUSH] = 0.2f; f[F_SHAPE] = 1f }
        "proud" -> { f[F_BROW] = 0.3f; f[F_BY] = -2f; f[F_LID] = 0.18f; f[F_CURVE] = 6f; f[F_MW] = 10f; f[F_SPARK] = 0.6f; f[F_BLUSH] = 0.3f; f[F_SHAPE] = 1f }
        "curious" -> { f[F_BROW] = 0.7f; f[F_BY] = -5f; f[F_BTILT] = 3f; f[F_CURVE] = 1.5f; f[F_MW] = 7f; f[F_SPARK] = 0.7f; f[F_SHAPE] = 1f }
        "bored" -> { f[F_LID] = 0.5f; f[F_LTILT] = -3f; f[F_CURVE] = -1f; f[F_MW] = 6f; f[F_SHAPE] = 1f }
        "nervous" -> { f[F_BROW] = 0.8f; f[F_BTILT] = 6f; f[F_CURVE] = -1f; f[F_MW] = 7f; f[F_WAVY] = 0.6f; f[F_SWEAT] = 1f; f[F_BLUSH] = 0.3f; f[F_SHAPE] = 1f }
        "disappointed" -> { f[F_BROW] = 0.7f; f[F_BTILT] = 5f; f[F_LID] = 0.3f; f[F_LTILT] = -6f; f[F_CURVE] = -3.5f; f[F_MW] = 7f; f[F_SHAPE] = 1f }
        "grateful" -> { f[F_LID] = 0.15f; f[F_CURVE] = 6f; f[F_MW] = 10f; f[F_BLUSH] = 0.7f; f[F_SPARK] = 1f; f[F_SHAPE] = 1f }
        "lonely" -> { f[F_BROW] = 0.7f; f[F_BTILT] = 6f; f[F_LID] = 0.35f; f[F_LTILT] = -8f; f[F_CURVE] = -3f; f[F_MW] = 6f; f[F_TEAR] = 0.4f; f[F_SHAPE] = 1f }
        "confused" -> { f[F_BROW] = 0.8f; f[F_BY] = -4f; f[F_BTILT] = 3f; f[F_CURVE] = -1f; f[F_MW] = 6f; f[F_WAVY] = 0.3f; f[F_SWEAT] = 0.4f; f[F_SHAPE] = 1f }
        "scared" -> { f[F_BROW] = 1f; f[F_BY] = -7f; f[F_BTILT] = 8f; f[F_OPEN] = 0.3f; f[F_WAVY] = 1f; f[F_SWEAT] = 1f; f[F_SPARK] = 0.2f; f[F_SHAPE] = 1f }
        "annoyed" -> { f[F_BROW] = 0.8f; f[F_BTILT] = -4f; f[F_LID] = 0.35f; f[F_LTILT] = 7f; f[F_CURVE] = -2f; f[F_MW] = 8f; f[F_BLUSH] = 0.1f; f[F_SHAPE] = 1f }
        "flirty" -> { f[F_LID] = 0.2f; f[F_CURVE] = 5f; f[F_MW] = 9f; f[F_CAT] = 1f; f[F_WINK] = 1f; f[F_BLUSH] = 0.7f; f[F_SPARK] = 0.5f; f[F_SHAPE] = 1f }
        "guilty" -> { f[F_BROW] = 0.8f; f[F_BTILT] = 6f; f[F_LID] = 0.2f; f[F_CURVE] = -1.5f; f[F_MW] = 6f; f[F_BLUSH] = 0.4f; f[F_SWEAT] = 0.6f; f[F_SHAPE] = 1f }
        "relieved" -> { f[F_LID] = 0.35f; f[F_CURVE] = 4f; f[F_MW] = 9f; f[F_BLUSH] = 0.3f; f[F_SPARK] = 0.3f; f[F_SHAPE] = 1f }
        "hopeful" -> { f[F_BROW] = 0.5f; f[F_BY] = -3f; f[F_BTILT] = 3f; f[F_CURVE] = 3f; f[F_MW] = 8f; f[F_SPARK] = 0.9f; f[F_SHAPE] = 1f }
        "mischievous" -> { f[F_BROW] = 0.5f; f[F_BTILT] = -3f; f[F_LID] = 0.25f; f[F_LTILT] = 3f; f[F_CURVE] = 5f; f[F_MW] = 10f; f[F_CAT] = 1f; f[F_BLUSH] = 0.3f; f[F_SPARK] = 0.4f; f[F_SHAPE] = 1f }
        "overwhelmed" -> { f[F_BROW] = 0.9f; f[F_BTILT] = 7f; f[F_LID] = 0.2f; f[F_CURVE] = -2f; f[F_MW] = 7f; f[F_WAVY] = 0.5f; f[F_SWEAT] = 1f; f[F_SHAPE] = 1f }
        "nostalgic" -> { f[F_LID] = 0.3f; f[F_CURVE] = 3f; f[F_MW] = 8f; f[F_BLUSH] = 0.3f; f[F_SPARK] = 0.5f; f[F_TEAR] = 0.25f; f[F_SHAPE] = 1f }
        "heartbroken" -> { f[F_BROW] = 1f; f[F_BTILT] = 9f; f[F_LID] = 0.4f; f[F_LTILT] = -12f; f[F_CURVE] = -6f; f[F_MW] = 7f; f[F_TEAR] = 1f; f[F_SHAPE] = 1f }
        else -> { }   // calm: the artwork's own gentle smile
    }
    return f
}

private val BROW_COL = Color(0xFF8C4A58)
private val MOUTH_LINE = Color(0xFF8E4651)

private fun DrawScope.drawBrow(sx: Float, sy: Float, s: Float, left: Boolean, tilt: Float, alpha: Float) {
    if (alpha <= 0.03f) return
    val dir = if (left) 1f else -1f                       // the inner end of the left brow is on its right
    val innerX = sx + dir * 13f * s
    val innerY = sy - tilt * s
    val outerX = sx - dir * 13f * s
    val outerY = sy + tilt * 0.45f * s
    val p = Path().apply {
        moveTo(outerX, outerY)
        quadraticBezierTo(sx, sy - tilt * 0.2f * s - 2.2f * s, innerX, innerY)
    }
    drawPath(p, BROW_COL.copy(alpha = alpha * 0.7f), style = Stroke(width = 2.8f * s, cap = StrokeCap.Round))
}

private fun DrawScope.drawBlush(cx: Float, cy: Float, s: Float, a: Float, col: Color) {
    if (a <= 0.03f) return
    val rx = 23f * s
    withTransform({ scale(1f, 0.55f, Offset(cx, cy)) }) {
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a * 0.55f), Color.Transparent), center = Offset(cx, cy), radius = rx), rx, Offset(cx, cy))
    }
}

private fun heartPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx, cy + r * 0.9f)
    cubicTo(cx - r * 1.6f, cy - r * 0.2f, cx - r * 0.7f, cy - r * 1.4f, cx, cy - r * 0.45f)
    cubicTo(cx + r * 0.7f, cy - r * 1.4f, cx + r * 1.6f, cy - r * 0.2f, cx, cy + r * 0.9f)
    close()
}

private fun DrawScope.drawHeartEye(cx: Float, cy: Float, s: Float, a: Float, t: Float) {
    if (a <= 0.03f) return
    val pulse = 1f + 0.10f * sin(t * 6f)
    drawCircle(Color(0xFFFFD9E6).copy(alpha = 0.80f * a), 12.5f * s, Offset(cx, cy))
    drawPath(heartPath(cx, cy, 7.5f * s * pulse), Color(0xFFFF3D87).copy(alpha = a))
    drawCircle(Color.White.copy(alpha = 0.9f * a), 1.6f * s, Offset(cx - 3f * s, cy - 3f * s))
}

private fun DrawScope.drawStar(cx: Float, cy: Float, r: Float, a: Float) {
    if (a <= 0.03f) return
    val col = Color.White.copy(alpha = a)
    drawLine(col, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = r * 0.45f, cap = StrokeCap.Round)
    drawLine(col, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = r * 0.45f, cap = StrokeCap.Round)
    drawCircle(col, r * 0.42f, Offset(cx, cy))
}

private fun DrawScope.drawTear(sx: Float, sy: Float, s: Float, t: Float, a: Float, phase: Float) {
    if (a <= 0.03f) return
    val prog = ((t + phase) % 2.4f) / 2.4f
    val y = sy + prog * 46f * s
    val al = a * sm(0f, 0.1f, prog) * (1f - sm(0.7f, 1f, prog))
    if (al <= 0.02f) return
    drawLine(Color(0xFFBFE3FF).copy(alpha = 0.35f * al), Offset(sx, sy), Offset(sx, y), strokeWidth = 1.6f * s, cap = StrokeCap.Round)
    drawCircle(Color(0xFFBFE3FF).copy(alpha = 0.85f * al), 2.8f * s, Offset(sx, y))
    drawCircle(Color.White.copy(alpha = 0.9f * al), 1.0f * s, Offset(sx - 0.8f * s, y - 0.8f * s))
}

private fun DrawScope.drawSweat(cx: Float, cy0: Float, s: Float, t: Float, a: Float) {
    if (a <= 0.03f) return
    val cy = cy0 + sin(t * 3f) * 2f * s
    val p = Path().apply {
        moveTo(cx, cy - 9f * s)
        cubicTo(cx + 7f * s, cy - 1f * s, cx + 6f * s, cy + 6f * s, cx, cy + 6f * s)
        cubicTo(cx - 6f * s, cy + 6f * s, cx - 7f * s, cy - 1f * s, cx, cy - 9f * s)
        close()
    }
    drawPath(p, Color(0xFFB7E4FF).copy(alpha = 0.88f * a))
    drawPath(p, Color(0xFF6AAAD8).copy(alpha = a), style = Stroke(width = 1.1f * s))
    drawCircle(Color.White.copy(alpha = 0.9f * a), 1.4f * s, Offset(cx - 2f * s, cy + 1f * s))
}

private fun DrawScope.drawAnger(cx: Float, cy: Float, s: Float, t: Float, a: Float) {
    if (a <= 0.03f) return
    val r = 7f * s * (1f + 0.12f * sin(t * 9f))
    val col = Color(0xFFE53935).copy(alpha = a)
    for (qx in floatArrayOf(-1f, 1f)) {
        for (qy in floatArrayOf(-1f, 1f)) {
            val p = Path().apply {
                moveTo(cx + qx * r, cy + qy * r * 0.2f)
                quadraticBezierTo(cx + qx * r * 0.25f, cy + qy * r * 0.25f, cx + qx * r * 0.2f, cy + qy * r)
            }
            drawPath(p, col, style = Stroke(width = 2.3f * s, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawGloom(sx: Float, sy: Float, s: Float, a: Float) {
    if (a <= 0.03f) return
    val col = Color(0xFF5B3A8C).copy(alpha = 0.38f * a)
    for (i in 0 until 7) {
        val x = sx + i * 15f * s
        val len = (14f + (i % 3) * 7f) * s
        drawLine(col, Offset(x, sy), Offset(x + 1.5f * s, sy + len), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
    }
}

private val SPARKS = arrayOf(
    floatArrayOf(0.12f, 0.30f), floatArrayOf(0.22f, 0.17f), floatArrayOf(0.86f, 0.44f), floatArrayOf(0.78f, 0.28f),
    floatArrayOf(0.09f, 0.60f), floatArrayOf(0.91f, 0.68f), floatArrayOf(0.34f, 0.09f), floatArrayOf(0.66f, 0.10f)
)

/**
 * The animated character. Fills its parent: the artwork is fitted inside (never cropped, never stretched), the
 * edges melt into [fade] (the app background colour) and petals / sparkles float over everything.
 *
 * @param emotion the AI's current mood (set_emotion): happy, excited, calm, sad, love, surprised, angry, jealous, sulky, playful, worried, shy, sleepy, proud, curious, bored, nervous, disappointed, grateful, lonely, confused, scared, annoyed, flirty, guilty, relieved, hopeful, mischievous, overwhelmed, nostalgic, heartbroken.
 * @param speech 0..1 loudness of the assistant's own voice right now (0 = silent). Drives the lip-sync and the head nod.
 */
@Composable
fun AnimeCharacter(
    resId: Int, modifier: Modifier = Modifier, speech: () -> Float = { 0f }, emotion: String = "calm", fade: Color = NisuUi.Bg,
    /** true while the AI is working on an answer: she looks up and aside, head tilted. */
    thinking: () -> Boolean = { false },
    /** true while the AI is listening to the person: she mostly looks at you. */
    attentive: () -> Boolean = { false }
) {
    val ctx = LocalContext.current
    val bmp = remember(resId) {
        try {
            BitmapFactory.decodeResource(ctx.resources, resId, BitmapFactory.Options().apply { inScaled = false })
        } catch (e: Throwable) { null }
    } ?: return
    val pose = remember { Pose() }
    val face = remember { FloatArray(F_N) }
    val live = remember { FloatArray(F_N) }       // the mood numbers plus tiny life-like variation, what is really drawn
    val goalBody = remember { BodyGoal() }
    val hairW = remember(bmp) { try { hairWeights(bmp) } catch (e: Throwable) { FloatArray((COLS + 1) * (ROWS + 1)) { 1f } } }
    val verts = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val mouthPatch = remember(bmp) { buildMouthPatch(bmp) }        // v2.95: the artwork's own skin, hides the painted smile
    val patchPaint = remember { Paint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val paint = remember { Paint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val tintPaint = remember { Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) } }
    // alpha mask of the background (1 = background, 0 = the character), made from the artwork; the same mesh moves it, so it follows the sway
    val maskBmp = remember(resId) {
        try {
            val id = ctx.resources.getIdentifier("nisu_bg_mask", "drawable", ctx.packageName)
            if (id == 0) null else BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
        } catch (e: Throwable) { null }
    }

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withFrameNanos { n ->
                if (start < 0L) start = n
                tick = n - start
            }
        }
    }

    Canvas(modifier.clipToBounds()) {
        val time = tick / 1_000_000_000f
        val mode = if (thinking()) 2 else if (attentive()) 1 else 0
        if (emotion != pose.emotion) { if (pose.emotion.isNotEmpty()) pose.onEmotionChange(time); pose.emotion = emotion }
        pose.update(time, speech(), mode, goalBody.get(emotion, mode))
        // ease every face number towards the current mood, each part at its own speed and independent of the frame rate
        val goal = faceTarget(emotion)
        for (q in 0 until F_N) face[q] += (goal[q] - face[q]) * (1f - exp(-FACE_RATE[q] * pose.dt))
        // a living face is never perfectly still: tiny irregular changes on top of the mood
        for (q in 0 until F_N) live[q] = face[q]
        live[F_CURVE] += wander(time, 11f) * 0.3f
        live[F_BY] += wander(time, 12f) * 0.7f - pose.react * 2.2f
        live[F_BTILT] += wander(time, 13f) * 0.6f
        live[F_BLUSH] = (live[F_BLUSH] * (1f + 0.12f * wander(time, 14f))).coerceIn(0f, 1.2f)
        live[F_LID] = (live[F_LID] + pose.body[B_SLOW].coerceIn(0f, 1f) * 0.12f * sin(time * 0.9f)).coerceIn(0f, 1f)   // sleepy eyes fight to stay open

        // fit the artwork inside the canvas, centred
        val s = maxOf(size.width / IMG_W, size.height / IMG_H)   // v2.74: fills the screen, no side bars
        val offX = (size.width - IMG_W * s) / 2f
        val offY = (size.height - IMG_H * s) / 2f

        // ---- deformed mesh
        var k = 0
        for (r in 0..ROWS) {
            val y = IMG_H * r / ROWS
            for (c in 0..COLS) {
                val x = IMG_W * c / COLS
                pose.warp(x, y, hairW[r * (COLS + 1) + c])
                verts[k++] = offX + (x + pose.dx) * s
                verts[k++] = offY + (y + pose.dy) * s
            }
        }
        drawContext.canvas.nativeCanvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint)

        // ---- slowly changing colour filter on the background only
        if (maskBmp != null) {
            val nc = drawContext.canvas.nativeCanvas
            val layer = nc.saveLayerAlpha(null, 84)
            nc.drawBitmapMesh(maskBmp, COLS, ROWS, verts, 0, null, 0, paint)
            tintPaint.color = tintAt(time).toArgb()
            nc.drawRect(0f, 0f, size.width, size.height, tintPaint)
            nc.restoreToCount(layer)
        }

        // ---- face: everything below follows the deformed picture, so it moves with the head
        val fs = s * A   // face parts are sized in old-picture units, so scale them up with the new picture
        fun scr(ox: Float, oy: Float): Offset {
            val px = A * ox + BX
            val py = A * oy + BY
            pose.warp(px, py, 1f, false)
            return Offset(offX + (px + pose.dx) * s, offY + (py + pose.dy) * s)
        }
        val bl = pose.blinkAmount(time)
        val lidBase = live[F_LID] + (1f - live[F_LID]) * bl
        val lidL = lidBase
        val wp = time % 3.4f
        val lidR = max(lidBase, live[F_WINK] * sm(0f, 0.15f, wp) * (1f - sm(0.9f, 1.1f, wp)))
        val eyeL = scr(351f, 260f)
        val eyeR = scr(420f, 251f)

        // gloom lines (jealous) sit on the forehead, under everything else
        val gl = scr(333f, 202f)
        drawGloom(gl.x, gl.y, fs, live[F_GLOOM])

        // blush on the cheeks
        val warm = Color(0xFFFF6F8F)
        val bc = Color(0xFFFF5470).copy(alpha = 1f)
        val chL = scr(335f, 282f)
        val chR = scr(432f, 273f)
        drawBlush(chL.x, chL.y, fs, live[F_BLUSH], if (live[F_ANGER] > 0.5f) bc else warm)
        drawBlush(chR.x, chR.y, fs, live[F_BLUSH], if (live[F_ANGER] > 0.5f) bc else warm)

        // sparkling or heart eyes (under the eyelids, so blinks still close over them)
        val eyeOpen = 1f - lidBase * 1.2f
        if (live[F_HEART] > 0.03f) {
            drawHeartEye(eyeL.x, eyeL.y, fs, live[F_HEART] * eyeOpen.coerceIn(0f, 1f), time)
            drawHeartEye(eyeR.x, eyeR.y, fs, live[F_HEART] * eyeOpen.coerceIn(0f, 1f), time)
        }
        if (live[F_SPARK] > 0.03f) {
            val pk = live[F_SPARK] * eyeOpen.coerceIn(0f, 1f)
            val r1 = (3.6f + 1.6f * sin(time * 5f)) * fs
            val r2 = (3.6f + 1.6f * sin(time * 5f + 1.7f)) * fs
            drawStar(eyeL.x + 5f * fs, eyeL.y - 5f * fs, r1, pk)
            drawStar(eyeR.x + 5f * fs, eyeR.y - 5f * fs, r2, pk)
        }

        // eyelids: blinking + the mood'fs own droop / slant
        drawEyelid(eyeL.x, eyeL.y, 16.5f * fs, 12.5f * fs, 4f + live[F_LTILT] + pose.tiltDeg, lidL)
        drawEyelid(eyeR.x, eyeR.y, 17.5f * fs, 13.5f * fs, -8f - live[F_LTILT] + pose.tiltDeg, lidR)

        // eyebrows
        val bL = scr(350f, 232f + live[F_BY])
        val bR = scr(423f, 224f + live[F_BY])
        drawBrow(bL.x, bL.y, fs, true, live[F_BTILT], live[F_BROW])
        drawBrow(bR.x, bR.y, fs, false, live[F_BTILT], live[F_BROW])

        // mouth (mood shape + lip-sync)
        val mp = scr(390f, 298f)
        drawMouth(mp.x, mp.y, fs, pose.mouth, time, live, pose.vowel, pose.tiltDeg, mouthPatch, patchPaint)

        // extras: tears, sweat drop, anger mark
        val tL = scr(349f, 275f)
        val tR = scr(421f, 267f)
        drawTear(tL.x, tL.y, fs, time, live[F_TEAR], 0f)
        drawTear(tR.x, tR.y, fs, time, live[F_TEAR], 1.1f)
        val sw = scr(455f, 232f)
        drawSweat(sw.x, sw.y, fs, time, live[F_SWEAT])
        val an = scr(452f, 198f)
        drawAnger(an.x, an.y, fs, time, live[F_ANGER])

        // ---- melt the borders into the app background
        val imgW = IMG_W * s
        val imgH = IMG_H * s
        val fv = imgH * 0.10f
        drawRect(Brush.verticalGradient(listOf(fade, Color.Transparent), startY = offY, endY = offY + fv), Offset(0f, offY), Size(size.width, fv))
        val fb = imgH * 0.10f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, fade), startY = offY + imgH - fb, endY = offY + imgH), Offset(0f, offY + imgH - fb), Size(size.width, fb))
        if (offX > 1f) {
            val fh = imgW * 0.06f
            drawRect(Brush.horizontalGradient(listOf(fade, Color.Transparent), startX = offX, endX = offX + fh), Offset(offX, offY), Size(fh, imgH))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, fade), startX = offX + imgW - fh, endX = offX + imgW), Offset(offX + imgW - fh, offY), Size(fh, imgH))
            drawRect(fade, Offset(0f, 0f), Size(offX, size.height))
            drawRect(fade, Offset(offX + imgW, 0f), Size(size.width - offX - imgW, size.height))
        }

        // ---- cherry petals drifting down from the blossom tree (top right)
        for (i in 0 until 16) {
            val fx = (i * 0.6180339f) % 1f
            val speed = 0.030f + 0.028f * ((i * 7) % 5) / 5f
            val prog = (time * speed + fx * 1.7f) % 1.15f
            val py = (prog - 0.08f) * size.height * 0.9f
            val px = size.width * (0.42f + 0.62f * fx) - prog * size.width * 0.24f + sin(time * 0.9f + i) * 14.dp.toPx()
            val a = sm(0f, 0.08f, prog) * sm(1.15f, 1.0f, prog)
            val rr = (3.4f + (i % 3) * 1.4f).dp.toPx()
            withTransform({ translate(px, py); rotate(time * 38f * (1 + i % 3) + i * 40f, Offset.Zero) }) {
                drawOval(Color(0xFFFFB3CE).copy(alpha = a * 0.85f), Offset(-rr, -rr * 0.55f), Size(2f * rr, 1.1f * rr))
                drawOval(Color.White.copy(alpha = a * 0.45f), Offset(-rr * 0.5f, -rr * 0.3f), Size(rr, rr * 0.45f))
            }
        }

        // ---- twinkling sparkles
        for (i in SPARKS.indices) {
            val v = sin(time * 1.6f + i * 1.3f)
            val a = if (v > 0f) v * v else 0f
            if (a < 0.02f) continue
            val cx = size.width * SPARKS[i][0]
            val cy = size.height * SPARKS[i][1]
            val rr = (3f + 5f * a).dp.toPx()
            val col = Color.White.copy(alpha = a * 0.9f)
            drawLine(col, Offset(cx - rr, cy), Offset(cx + rr, cy), strokeWidth = 1.4f.dp.toPx(), cap = StrokeCap.Round)
            drawLine(col, Offset(cx, cy - rr), Offset(cx, cy + rr), strokeWidth = 1.4f.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFFBFE6FF).copy(alpha = a * 0.5f), rr * 0.45f, Offset(cx, cy))
        }
    }
}
