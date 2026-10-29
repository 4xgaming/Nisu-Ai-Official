package ai.nisu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeDspTest {
    /** Rising / falling tone pattern: a stand-in for a spoken word (pitch contour decides the shape). */
    private fun tone(freqs: List<Double>, segMs: Int = 120, amp: Double = 8000.0): ByteArray {
        val per = 16 * segMs
        val out = ByteArray(freqs.size * per * 2 + 3200)
        var p = 1600
        var phase = 0.0
        for (f in freqs) for (i in 0 until per) {
            phase += 2 * PI * f / 16000.0
            val v = (sin(phase) * amp * (0.6 + 0.4 * sin(PI * i / per))).toInt()
            out[p * 2] = v.toByte(); out[p * 2 + 1] = (v shr 8).toByte(); p++
        }
        return out
    }

    @Test fun silenceOrShortGivesNoFeatures() {
        assertTrue(WakeDsp.features(ByteArray(100)).isEmpty())
        assertTrue(WakeDsp.features(ByteArray(32000)).isEmpty())
    }
    @Test fun sameSoundIsClose() {
        val a = WakeDsp.features(tone(listOf(300.0, 700.0, 500.0, 900.0)))
        val b = WakeDsp.features(tone(listOf(300.0, 700.0, 500.0, 900.0), amp = 3000.0))
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertEquals(0.0, WakeDsp.dtw(a, a), 1e-9)
        assertTrue(WakeDsp.dtw(a, b) < 1.0)
    }
    @Test fun differentSoundIsFarther() {
        val a = WakeDsp.features(tone(listOf(300.0, 700.0, 500.0, 900.0)))
        val same = WakeDsp.features(tone(listOf(310.0, 690.0, 510.0, 890.0)))
        val other = WakeDsp.features(tone(listOf(1500.0, 400.0, 1800.0, 600.0)))
        assertTrue(WakeDsp.dtw(a, other) > WakeDsp.dtw(a, same) * 2)
    }
    @Test fun veryDifferentLengthIsRejected() {
        val a = WakeDsp.features(tone(listOf(300.0, 700.0), segMs = 100))
        val b = WakeDsp.features(tone(listOf(300.0, 700.0, 300.0, 700.0, 300.0, 700.0), segMs = 100))
        assertEquals(Double.MAX_VALUE, WakeDsp.dtw(a, b), 0.0)
    }
}
