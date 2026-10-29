package ai.nisu.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import kotlin.math.hypot

/** Converts the dots of a pattern (0..8, row by row) to the string the credential manager stores / checks. */
fun patternToSecret(dots: List<Int>): String = dots.joinToString("")

/**
 * Standard 3x3 pattern grid (Compose, lightweight: one Canvas, no animation).
 * Drag over the dots; lifting the finger calls [onComplete] with the dots in order.
 * A dot lying exactly between two chosen dots is added too, like in the Android pattern.
 */
@Composable
fun PatternLockView(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    onComplete: (List<Int>) -> Unit
) {
    // pointerInput is not restarted when the screen moves from "set" to "confirm", so it must read the NEWEST callback
    // (the old one kept re-running the first step, which is why confirm never succeeded).
    val latestOnComplete by rememberUpdatedState(onComplete)
    val picked = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun center(i: Int): Offset {
        val cell = size.width / 3f
        return Offset((i % 3 + 0.5f) * cell, (i / 3 + 0.5f) * cell)
    }

    fun tryAdd(p: Offset) {
        val cell = size.width / 3f
        if (cell <= 0f) return
        for (i in 0..8) {
            if (i in picked) continue
            val c = center(i)
            if (hypot(p.x - c.x, p.y - c.y) <= cell * 0.36f) {
                if (picked.isNotEmpty()) {
                    val last = picked.last()
                    val dr = (i / 3) - (last / 3)
                    val dc = (i % 3) - (last % 3)
                    if (abs(dr) % 2 == 0 && abs(dc) % 2 == 0 && (abs(dr) == 2 || abs(dc) == 2)) {
                        val mid = ((last / 3) + dr / 2) * 3 + ((last % 3) + dc / 2)
                        if (mid !in picked) picked.add(mid)
                    }
                }
                picked.add(i)
                return
            }
        }
    }

    val lineColor = if (error) NisuUi.Red else NisuUi.Cyan
    Box(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // Own gesture loop: reacts on the first touch (no touch-slop delay), consumes every move so a parent scroll
                // can never steal the drag, and checks the points between two samples so a fast swipe cannot skip a dot.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    picked.clear(); finger = down.position; tryAdd(down.position)
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        val steps = (hypot(ch.position.x - last.x, ch.position.y - last.y) / (size.width / 12f).coerceAtLeast(1f)).toInt().coerceIn(1, 24)
                        for (k in 1..steps) {
                            val f = k / steps.toFloat()
                            tryAdd(Offset(last.x + (ch.position.x - last.x) * f, last.y + (ch.position.y - last.y) * f))
                        }
                        last = ch.position; finger = ch.position
                        ch.consume()
                    }
                    val result = picked.toList()
                    picked.clear(); finger = null
                    if (result.isNotEmpty()) latestOnComplete(result)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / 3f
            // lines between the chosen dots, then to the finger
            for (k in 1 until picked.size) {
                drawLine(lineColor, center(picked[k - 1]), center(picked[k]), strokeWidth = 6f, cap = StrokeCap.Round)
            }
            val f = finger
            if (picked.isNotEmpty() && f != null) drawLine(lineColor.copy(alpha = 0.6f), center(picked.last()), f, strokeWidth = 6f, cap = StrokeCap.Round)
            for (i in 0..8) {
                val on = i in picked
                val c = center(i)
                drawCircle(if (on) lineColor else NisuUi.Muted, radius = if (on) cell * 0.07f else cell * 0.045f, center = c)
                if (on) drawCircle(lineColor.copy(alpha = 0.35f), radius = cell * 0.2f, center = c, style = Stroke(width = 3f))
            }
        }
    }
}
