package ai.nisu.assistant

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * v2.29: the owner's trained "Hey Nishu" samples (feature numbers only, never audio) and the offline decision.
 * Stored in filesDir/wake_templates.bin. The matching of a sleeping assistant's microphone sound uses these instead of the internet.
 */
object WakeTemplates {
    const val NEED = 10                // v2.51: samples to record for good accuracy (it already works from 3)
    const val MAX = 20                 // v2.51: up to 20 samples are kept (oldest replaced first)
    const val SENS = "wake_sensitivity" // 0 strict, 1 normal, 2 relaxed
    private val factors = doubleArrayOf(1.1, 1.3, 1.55)   // v2.51: tighter (was 1.2 / 1.4 / 1.65) = fewer false wakes
    private val lock = Any()
    private var cache: MutableList<Array<DoubleArray>>? = null
    private var cachedLimitBase = 0.0

    private fun file(c: Context) = File(c.filesDir, "wake_templates.bin")

    private fun load(c: Context): MutableList<Array<DoubleArray>> = synchronized(lock) {
        cache?.let { return it }
        val out = ArrayList<Array<DoubleArray>>()
        try {
            val f = file(c)
            if (f.exists()) DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt()
                repeat(n) { out.add(Array(s.readInt()) { DoubleArray(WakeDsp.DIM) { s.readFloat().toDouble() } }) }
            }
        } catch (_: Exception) { out.clear() }
        cache = out; cachedLimitBase = if (out.size >= 3) WakeDsp.calibrate(out) else 0.0
        out
    }

    private fun save(c: Context, list: List<Array<DoubleArray>>) {
        try {
            DataOutputStream(file(c).outputStream().buffered()).use { s ->
                s.writeInt(list.size)
                for (t in list) { s.writeInt(t.size); for (fr in t) for (v in fr) s.writeFloat(v.toFloat()) }
            }
        } catch (_: Exception) {}
    }

    fun count(c: Context): Int = load(c).size
    fun ready(c: Context): Boolean = count(c) >= 3

    /** Adds one recording (wiped by the caller afterwards). False if the sound was too short / unclear. */
    fun add(c: Context, pcm: ByteArray): Boolean = synchronized(lock) {
        val f = WakeDsp.features(pcm)
        if (f.isEmpty()) return false
        val l = load(c)
        if (l.size >= MAX) l.removeAt(0)
        l.add(f)
        cachedLimitBase = if (l.size >= 3) WakeDsp.calibrate(l) else 0.0
        save(c, l)
        true
    }

    fun clear(c: Context) = synchronized(lock) { cache = ArrayList(); cachedLimitBase = 0.0; try { file(c).delete() } catch (_: Exception) {} }

    fun sensitivity(c: Context): Int = Prefs.getInt(c, SENS, 1).coerceIn(0, 2)
    fun cycleSensitivity(c: Context): Int { val n = (sensitivity(c) + 1) % 3; Prefs.putInt(c, SENS, n); return n }
    fun sensitivityName(c: Context) = arrayOf("Strict", "Normal", "Relaxed")[sensitivity(c)]

    class Result(val ok: Boolean, val score: Double, val limit: Double)

    /** Offline decision for one sound. The recording itself is not kept. */
    fun judge(c: Context, pcm: ByteArray): Result {
        val set = load(c)
        val limit = synchronized(lock) { cachedLimitBase } * factors[sensitivity(c)]
        val f = WakeDsp.features(pcm)
        if (f.isEmpty() || set.size < 3 || limit <= 0.0) return Result(false, Double.MAX_VALUE, limit)
        val s = WakeDsp.score(f, set)
        return Result(s <= limit, s, limit)
    }
}
