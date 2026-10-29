package ai.nisu.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** v2.29: records ONE "Hey Nishu" sample for the offline wake check. Needs the microphone, so the assistant must not be running. */
object WakeTrainer {
    @Volatile var busy = false
        private set

    /** [done] runs on the main thread: (true, "...") when a sample was added, (false, reason) otherwise. */
    fun record(ctx: Context, startDelayMs: Long = 0L, done: (Boolean, String) -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun finish(ok: Boolean, msg: String) { busy = false; main.post { done(ok, msg) } }
        busy = true
        thread(name = "nisu-wake-train", isDaemon = true) {
            // v2.56: the sleeping listener may still be releasing the microphone: wait, then retry for ~3 s instead of failing at once
            if (startDelayMs > 0) try { Thread.sleep(startDelayMs) } catch (_: InterruptedException) {}
            var r: android.media.AudioRecord? = null
            for (t in 0 until 8) {
                r = SleepMicListener.openRecord()
                if (r != null) { try { r.startRecording(); if (r.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) break } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}; r = null }
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
            }
            if (r == null) { finish(false, "Microphone is busy or the permission is missing."); return@thread }
            val seg = UtteranceSegmenter(22)   // v3.116: the same pause rule as the sleeping listener, so a sample is cut exactly like the sound it will be compared with
            val frame = ByteArray(UtteranceSegmenter.FRAME_BYTES)
            var utt: ByteArray? = null
            try {
                val end = System.currentTimeMillis() + 15_000
                var have = 0
                while (utt == null && System.currentTimeMillis() < end) {
                    val n = r.read(frame, have, frame.size - have)
                    if (n < 0) break
                    have += n
                    if (have < frame.size) continue
                    have = 0
                    utt = seg.feed(frame)
                }
            } catch (_: Exception) {
            } finally {
                try { r.stop() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
                seg.reset(); java.util.Arrays.fill(frame, 0.toByte())
            }
            val u = utt
            if (u == null) { finish(false, "I did not hear a clear \"Hey Nishu\". Try again a little louder."); return@thread }
            val ok = try { WakeTemplates.add(app, u) } catch (_: Exception) { false }
            java.util.Arrays.fill(u, 0.toByte())      // the recording is erased at once, only the feature numbers stay
            if (ok) finish(true, "Sample saved (${WakeTemplates.count(app)}).") else finish(false, "That sound was too short or unclear. Try again.")
        }
    }
}
