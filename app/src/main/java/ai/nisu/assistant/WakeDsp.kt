package ai.nisu.assistant

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v2.29: offline wake-phrase matching with no library and no internet. Pure Kotlin (no Android classes) so it is unit-tested on the JVM.
 * A recorded sound is turned into MFCC features (what the voice "looks like" per 10 ms) and compared with the owner's own
 * trained "Hey Nishu" samples by dynamic time warping. Only these numbers are kept; the recordings themselves are never stored.
 */
object WakeDsp {
    private const val SR = 16000.0
    private const val FRAME = 400          // 25 ms
    private const val HOP = 160            // 10 ms
    private const val NFFT = 512
    private const val NMEL = 26
    private const val NCEP = 13
    const val DIM = 13                      // 12 cepstra (c1..c12) + energy contour

    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2.0 * PI * it / (FRAME - 1)) }
    private val cosT = DoubleArray(NFFT / 2) { cos(2.0 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { -sin(2.0 * PI * it / NFFT) }
    private val bitrev = IntArray(NFFT).also { r ->
        var bits = 0; while ((1 shl bits) < NFFT) bits++
        for (i in 0 until NFFT) { var x = i; var y = 0; for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }
    private fun hz2mel(f: Double) = 2595.0 * Math.log10(1.0 + f / 700.0)
    private fun mel2hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

    private val filters: Array<DoubleArray> = run {
        val lo = hz2mel(80.0); val hi = hz2mel(7600.0)
        val pts = DoubleArray(NMEL + 2) { mel2hz(lo + (hi - lo) * it / (NMEL + 1)) }
        val bins = IntArray(NMEL + 2) { ((NFFT + 1) * pts[it] / SR).toInt() }
        Array(NMEL) { m ->
            val f = DoubleArray(NFFT / 2 + 1)
            for (k in bins[m] until bins[m + 1]) if (bins[m + 1] > bins[m]) f[k] = (k - bins[m]).toDouble() / (bins[m + 1] - bins[m])
            for (k in bins[m + 1] until bins[m + 2]) if (bins[m + 2] > bins[m + 1]) f[k] = (bins[m + 2] - k).toDouble() / (bins[m + 2] - bins[m + 1])
            f
        }
    }
    private val dct = Array(NCEP) { k -> DoubleArray(NMEL) { n -> sqrt(2.0 / NMEL) * cos(PI * k * (n + 0.5) / NMEL) } }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until NFFT) { val j = bitrev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= NFFT) {
            val half = len / 2; val step = NFFT / len
            var i = 0
            while (i < NFFT) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                }
                i += len
            }
            len *= 2
        }
    }

    /** 16-bit little-endian mono PCM -> feature frames (one DIM-vector per 10 ms), silence trimmed, loudness / microphone tilt removed. Empty if too short. */
    fun features(pcm: ByteArray): Array<DoubleArray> {
        val n = pcm.size / 2
        if (n < FRAME + HOP * 20) return emptyArray()
        val x = DoubleArray(n) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xff)).toShort().toDouble() }
        for (i in n - 1 downTo 1) x[i] -= 0.97 * x[i - 1]
        val frames = (n - FRAME) / HOP + 1
        val cep = Array(frames) { DoubleArray(NCEP) }
        val en = DoubleArray(frames)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val logMel = DoubleArray(NMEL)
        for (t in 0 until frames) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val o = t * HOP
            for (i in 0 until FRAME) re[i] = x[o + i] * window[i]
            fft(re, im)
            var mean = 0.0
            for (m in 0 until NMEL) {
                var s = 0.0; val f = filters[m]
                for (k in 0..NFFT / 2) if (f[k] != 0.0) s += f[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[m] = ln(s + 1.0); mean += logMel[m]
            }
            en[t] = mean / NMEL
            for (k in 0 until NCEP) { var s = 0.0; for (m in 0 until NMEL) s += dct[k][m] * logMel[m]; cep[t][k] = s }
        }
        // trim quiet edges (within 20 dB of the loudest frame)
        var peak = en[0]; for (e in en) if (e > peak) peak = e
        val thr = peak - 4.6
        var a = 0; while (a < frames && en[a] < thr) a++
        var b = frames - 1; while (b > a && en[b] < thr) b--
        val cnt = b - a + 1
        if (cnt < 15) return emptyArray()
        // mean removal of c1..c12 (removes the microphone / room / loudness colouring)
        val mu = DoubleArray(NCEP)
        for (t in a..b) for (k in 1 until NCEP) mu[k] += cep[t][k] / cnt
        return Array(cnt) { i ->
            val t = a + i
            DoubleArray(DIM) { d -> if (d < NCEP - 1) cep[t][d + 1] - mu[d + 1] else (en[t] - peak) * 1.5 }
        }
    }

    /** Dynamic time warping distance (average per step) between two feature sequences; Double.MAX_VALUE if the lengths are too different. */
    fun dtw(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0) return Double.MAX_VALUE
        val ratio = n.toDouble() / m
        if (ratio > 1.8 || ratio < 0.55) return Double.MAX_VALUE
        val band = max(abs(n - m), (0.3 * max(n, m)).toInt()) + 2
        val inf = Double.MAX_VALUE / 4
        var prev = DoubleArray(m + 1) { inf }; var cur = DoubleArray(m + 1) { inf }
        prev[0] = 0.0
        for (i in 1..n) {
            java.util.Arrays.fill(cur, inf)
            val center = (i.toDouble() * m / n).toInt()
            val j0 = max(1, center - band); val j1 = min(m, center + band)
            for (j in j0..j1) {
                var d = 0.0; val x = a[i - 1]; val y = b[j - 1]
                for (k in 0 until DIM) { val v = x[k] - y[k]; d += v * v }
                d = sqrt(d)
                val best = min(prev[j - 1], min(prev[j], cur[j - 1]))
                cur[j] = d + best
            }
            val t = prev; prev = cur; cur = t
        }
        val r = prev[m]
        return if (r >= inf) Double.MAX_VALUE else r / (n + m)
    }
    private fun abs(v: Int) = if (v < 0) -v else v

    /** Distance of [probe] to a set of trained samples: the mean of the two closest (one bad sample cannot spoil it). */
    fun score(probe: Array<DoubleArray>, set: List<Array<DoubleArray>>, skip: Int = -1): Double {
        val ds = ArrayList<Double>()
        for (i in set.indices) if (i != skip) ds.add(dtw(probe, set[i]))
        ds.sort()
        if (ds.isEmpty()) return Double.MAX_VALUE
        // v2.51: with many samples the mean of the three closest is steadier (a few more samples = clearly better accuracy)
        return if (ds.size >= 10) (ds[0] + ds[1] + ds[2]) / 3 else if (ds.size >= 2) (ds[0] + ds[1]) / 2 else ds[0]
    }

    /** Leave-one-out spread of the trained samples = how much the owner's own "Hey Nishu" varies; the accept limit is this times [factor]. */
    fun calibrate(set: List<Array<DoubleArray>>): Double {
        val all = ArrayList<Double>()
        for (i in set.indices) { val s = score(set[i], set, skip = i); if (s < Double.MAX_VALUE) all.add(s) }
        if (all.isEmpty()) return 0.0
        all.sort()
        // v2.51: with 8+ samples one badly recorded sample must not widen the limit for everybody: use the 85th percentile instead of the worst
        return if (all.size >= 8) all[Math.ceil(0.85 * all.size).toInt() - 1] else all.last()
    }
}
