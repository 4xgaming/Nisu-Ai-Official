package ai.nisu.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

/*
 * v3.109: the painted face parts (eyelids, mouth) shared by the picture themes. Dusk Saree (v3.103 / v3.107) and Sunset Garden (v3.109) draw them
 * over their own picture. [u] = the size of the face parts compared with the Saree picture (1 = Saree's eyes, about 0.5 = Garden's): it scales every fixed pixel size.
 */

/** Mixes two colours: t = 0 gives [a], t = 1 gives [b]. */
internal fun mixColor(a: Color, b: Color, t: Float): Color = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

/**
 * One eyelid painted over an eye: [a] = 0 open ... 1 closed. The skin colour covers the eye from the top down to the lid edge, a dark lash line sits on the edge.
 * [squint] 0..1 lifts the lower lid (a smile that reaches the eyes), [slant] -1..1 tilts the upper lid (1 = angry: the end next to the nose is lowered,
 * -1 = sad: the outer end droops), [inner] = +1 for the left eye of the picture, -1 for the right eye (which end is next to the nose).
 */
internal fun DrawScope.paintEyelid(cx: Float, cy: Float, ew: Float, eh: Float, deg: Float, a: Float, skin: Color, k: Float, squint: Float = 0f, slant: Float = 0f, inner: Float = 1f, u: Float = 1f) {
    withTransform({ translate(cx, cy); rotate(deg, Offset.Zero); scale(k, k, Offset.Zero) }) {
        val yl = -eh * 1.3f + a * (eh * 1.3f + eh * 0.25f)    // the lid edge: from above the eye (open) to just under the middle (closed)
        val bulge = eh * 0.55f * a                             // a closed eye's lash line bows down a little
        val angry = maxOf(slant, 0f); val sad = maxOf(-slant, 0f)
        val yInner = yl + angry * eh * 0.9f - sad * eh * 0.2f   // the lid edge at the end next to the nose
        val yOuter = yl + sad * eh * 0.9f - angry * eh * 0.3f   // and at the far end
        val yR = if (inner > 0f) yInner else yOuter             // local +x is the right end of the eye as drawn
        val yL = if (inner > 0f) yOuter else yInner
        val almond = Path().apply {
            moveTo(-ew * 1.1f, 0f); quadraticBezierTo(0f, -eh * 2.3f, ew * 1.1f, 0f); quadraticBezierTo(0f, eh * 1.7f, -ew * 1.1f, 0f); close()
        }
        val lid = Path().apply {
            moveTo(-ew * 1.2f, -eh * 2f); lineTo(ew * 1.2f, -eh * 2f); lineTo(ew * 1.2f, yR); quadraticBezierTo(0f, (yR + yL) / 2f + bulge * 2f, -ew * 1.2f, yL); close()
        }
        clipPath(almond) {
            drawPath(lid, skin)
            if (squint > 0.03f) {
                val yb = eh * 1.4f - squint * eh * 1.15f           // the lower lid edge rises
                val low = Path().apply { moveTo(-ew * 1.2f, eh * 2.6f); lineTo(ew * 1.2f, eh * 2.6f); lineTo(ew * 1.2f, yb); quadraticBezierTo(0f, yb - squint * eh * 0.7f, -ew * 1.2f, yb); close() }
                drawPath(low, skin)
            }
        }
        val lash = Path().apply { moveTo(-ew * 0.98f, yL); quadraticBezierTo(0f, (yR + yL) / 2f + bulge * 2f, ew * 0.98f, yR) }
        drawPath(lash, Color(0xFF2A1A1E).copy(alpha = 0.5f + 0.45f * maxOf(a, angry, sad)), style = Stroke(width = maxOf(1f, (2.2f + 2.0f * a) * u), cap = StrokeCap.Round))
    }
}

/**
 * The painted mouth, over the picture's own mouth: [open] 0..1 = how far it is open (the voice), [smile] -1..1 (negative = corners down), [wide] = width factor,
 * [a] = how strongly it covers the picture's mouth (fades in, so there is never a jump), [lip] = her lip colour, [skin] = the skin around the mouth, [u] = size factor (1 = Saree).
 * Local frame: origin = the middle between the corners, x along the mouth line (tilted by [deg]). Upper lip, dark inside with teeth and a tongue, lower lip, thin dark corner lines.
 */
internal fun DrawScope.paintMouth(cx: Float, cy: Float, hw: Float, deg: Float, open: Float, smile: Float, wide: Float, a: Float, lip: Color, skin: Color, k: Float, u: Float = 1f) {
    withTransform({ translate(cx, cy); rotate(deg, Offset.Zero); scale(k, k, Offset.Zero) }) {
        val w = hw * wide * (1f - 0.1f * open)
        val cyc = -smile * 6f * u                                    // height of the corners: negative = raised (smile), positive = lowered (frown)
        val gap = (1.2f + open * 15f + maxOf(smile, 0f) * 2.2f) * u  // how far the lip lines are apart (a smile shows a little teeth)
        val mu = -open * 1.5f * u                                    // middle of the upper lip line
        val ml = mu + gap                                            // middle of the lower lip line
        fun ctl(m: Float) = 2f * m - cyc                             // control point so that the curve passes through height m in the middle
        val upperLip = mixColor(lip, skin, 0.22f)
        val dark = Color(0xFF4B2A2E)
        // 1. skin over the picture's own mouth, soft at the edge
        withTransform({ translate(0f, 5f * u); scale(1f, 0.5f, Offset.Zero) }) {
            drawCircle(Brush.radialGradient(0f to skin.copy(alpha = a), 0.72f to skin.copy(alpha = a), 1f to Color.Transparent, center = Offset.Zero, radius = 64f * u), radius = 64f * u, center = Offset.Zero)
        }
        // 2. upper lip
        val up = Path().apply { moveTo(-w, cyc); quadraticBezierTo(0f, ctl(mu), w, cyc); quadraticBezierTo(0f, ctl(mu - 7.5f * u), -w, cyc); close() }
        drawPath(up, upperLip.copy(alpha = a))
        // 3. inside of the mouth: dark, teeth under the upper lip, tongue when open
        val inside = Path().apply { moveTo(-w, cyc); quadraticBezierTo(0f, ctl(mu), w, cyc); quadraticBezierTo(0f, ctl(ml), -w, cyc); close() }
        clipPath(inside) {
            drawPath(inside, Color(0xFF4F2128).copy(alpha = a))
            val th = minOf(6.5f * u, gap * 0.6f)
            drawRect(Color(0xFFF4EEE9).copy(alpha = a), topLeft = Offset(-w, mu - 6f * u), size = Size(2f * w, 6f * u + th))
            if (open > 0.25f) {
                withTransform({ translate(0f, ml - 1.5f * u); scale(1f, 0.32f, Offset.Zero) }) {
                    drawCircle(Color(0xFFC4626D).copy(alpha = a * minOf(1f, open * 1.4f)), radius = w * 0.5f, center = Offset.Zero)
                }
            }
        }
        // 4. lower lip, with a small highlight
        val lo = Path().apply { moveTo(-w * 0.96f, cyc); quadraticBezierTo(0f, ctl(ml), w * 0.96f, cyc); quadraticBezierTo(0f, ctl(ml + (13f + open * 2f) * u), -w * 0.96f, cyc); close() }
        drawPath(lo, lip.copy(alpha = a))
        withTransform({ translate(0f, ml + 6.5f * u); scale(1f, 0.28f, Offset.Zero) }) {
            drawCircle(Color.White.copy(alpha = 0.22f * a), radius = w * 0.3f, center = Offset.Zero)
        }
        // 5. thin dark line of the lips (strong when closed, light when open) and the corners
        val line = Path().apply { moveTo(-w, cyc); quadraticBezierTo(0f, ctl(mu), w, cyc) }
        drawPath(line, dark.copy(alpha = a * (0.6f - 0.3f * open)), style = Stroke(width = maxOf(1f, 1.5f * u), cap = StrokeCap.Round))
        drawCircle(dark.copy(alpha = 0.5f * a), radius = maxOf(0.8f, 1.3f * u), center = Offset(-w, cyc))
        drawCircle(dark.copy(alpha = 0.5f * a), radius = maxOf(0.8f, 1.3f * u), center = Offset(w, cyc))
    }
}

/** The state of an animated face: the mood values (eased), the blink, the mouth opening. Shared by the themes that paint a face. */
internal class FaceAnim {
    val cur = SareeRules.mood("calm").toArray()
    val blink = SareeRules.BlinkClock(System.nanoTime())
    var lid = 0f
}

/** Runs the face: eases the mood of [emotion], blinks like a person and opens the mouth with the voice, frame by frame. Stands still when the phone's animations are off. */
@Composable
internal fun rememberFaceAnim(emotion: String): FaceAnim {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val still = remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
    val face = remember { FaceAnim() }
    val target = remember(emotion) { SareeRules.mood(emotion).toArray() }
    if (!still) LaunchedEffect(target) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                last = now
                SareeRules.ease(face.cur, target, dt)
                face.lid = face.blink.step(dt, face.cur[SareeRules.M_EVERY], face.cur[SareeRules.M_SPEED])
            }
        }
    }
    return face
}

/** The average colour of a small patch of the picture at (x, y) in picture pixels, a little darker (skin in shade), used to paint eyelids and mouth in her own colours. */
internal fun sampleSkin(bmp: android.graphics.Bitmap, imgW: Float, imgH: Float, x: Float, y: Float): Color {
    val sx = bmp.width / imgW; val sy = bmp.height / imgH
    var r = 0; var g = 0; var b = 0
    for (dy in -2..2) for (dx in -2..2) {
        val px = ((x * sx).toInt() + dx * 2).coerceIn(0, bmp.width - 1); val py = ((y * sy).toInt() + dy * 2).coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(px, py); r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
    }
    return Color((r / 25) / 255f * 0.97f, (g / 25) / 255f * 0.96f, (b / 25) / 255f * 0.96f)
}
