package ai.nisu.assistant

import kotlin.math.sqrt

/**
 * v2.27: pure (no Android classes, JVM-testable) cutter that turns a continuous 16 kHz / 16-bit mono stream into short
 * utterances. Only a sound that is 0.3 s .. 3.0 s long (the length of "Hey Nishu") is returned; shorter noises and longer
 * speech (a conversation, TV) are thrown away AND wiped from memory without ever being looked at.
 */
class UtteranceSegmenter(
    /** Quiet frames (20 ms each) that close an utterance. The sleeping wake listener uses a shorter value so the answer comes sooner. */
    private val endSilence: Int = END_SILENCE_FRAMES
) {
    companion object {
        const val FRAME_BYTES = 640                  // 20 ms
        const val MIN_SPEECH_FRAMES = 15             // 0.3 s
        const val MAX_SPEECH_FRAMES = 150            // 3.0 s
        const val END_SILENCE_FRAMES = 30            // 0.6 s of quiet closes the utterance
        const val PREROLL_FRAMES = 12                // 0.24 s kept from before the start so the first syllable is not cut
        const val START_FRAMES = 2
        const val MIN_RMS = 140.0
        private const val CAP_BYTES = (PREROLL_FRAMES + MAX_SPEECH_FRAMES + END_SILENCE_FRAMES + 8) * FRAME_BYTES
    }

    private val buf = ByteArray(CAP_BYTES)
    private var len = 0
    private var floor = 56.0          // v2.56: was 150 (start threshold 375): a quiet first "Hey Nishu" right after Record / start was ignored
    private var loud = 0
    private var speech = 0
    private var quiet = 0
    private var inUtt = false
    private var tooLong = false
    /** Level of the last frame and the current start threshold (shown on screen so a too-quiet / too-loud microphone is visible). */
    var lastLevel = 0.0; private set
    var threshold = MIN_RMS; private set

    private fun rms(f: ByteArray, n: Int): Double {
        var s = 0.0; var i = 0; var c = 0
        while (i + 1 < n) { val v = ((f[i + 1].toInt() shl 8) or (f[i].toInt() and 0xff)).toShort().toInt(); s += v.toDouble() * v; i += 2; c++ }
        return if (c == 0) 0.0 else sqrt(s / c)
    }

    private fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); len = 0 }

    private fun push(f: ByteArray, n: Int) {
        if (len + n > buf.size) { // keep only the newest pre-roll while idle
            val keep = PREROLL_FRAMES * FRAME_BYTES
            if (!inUtt && len > keep) { System.arraycopy(buf, len - keep, buf, 0, keep); java.util.Arrays.fill(buf, keep, buf.size, 0.toByte()); len = keep }
            if (len + n > buf.size) return
        }
        System.arraycopy(f, 0, buf, len, n); len += n
    }

    private fun trimPreroll() {
        val keep = PREROLL_FRAMES * FRAME_BYTES
        if (len > keep) { System.arraycopy(buf, len - keep, buf, 0, keep); java.util.Arrays.fill(buf, keep, buf.size, 0.toByte()); len = keep }
    }

    /** Feed one 20 ms frame. Returns the finished utterance's PCM (caller must wipe it after use) or null. */
    fun feed(frame: ByteArray, n: Int = frame.size): ByteArray? {
        val level = rms(frame, n)
        val thr = maxOf(MIN_RMS, floor * 2.5)
        val isLoud = level > thr
        lastLevel = level; threshold = thr
        if (!inUtt) {
            if (!isLoud) floor = floor * 0.98 + level * 0.02
            push(frame, n)
            if (!isLoud) { loud = 0; if (len > (PREROLL_FRAMES + 1) * FRAME_BYTES) trimPreroll(); return null }
            if (++loud >= START_FRAMES) { inUtt = true; speech = loud; quiet = 0; tooLong = false; loud = 0 }
            return null
        }
        // inside a sound
        if (tooLong) { // already far too long: nothing is kept, just wait for it to end
            if (isLoud) quiet = 0 else if (++quiet >= endSilence) { inUtt = false; quiet = 0; speech = 0; wipe() }
            return null
        }
        push(frame, n)
        if (isLoud) { speech++; quiet = 0 } else quiet++
        if (speech > MAX_SPEECH_FRAMES) { tooLong = true; wipe(); return null }
        if (quiet >= endSilence) {
            val ok = speech in MIN_SPEECH_FRAMES..MAX_SPEECH_FRAMES
            val out = if (ok) buf.copyOf(len) else null
            inUtt = false; quiet = 0; speech = 0; loud = 0
            wipe()
            return out
        }
        return null
    }

    /** Throws everything held away (used on stop). */
    fun reset() { wipe(); inUtt = false; tooLong = false; loud = 0; speech = 0; quiet = 0 }
}

