package ai.nisu.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Anything that can own the microphone while NISU AI sleeps and report a possible wake phrase. */
interface WakeSource {
    fun start()
    fun stop()
}

/**
 * v3.58: a one-place waiting room for sounds that still have to be checked. A newer sound replaces an older one that is still waiting
 * (the old one is handed back so it can be wiped); nothing is ever dropped just because a check is running.
 */
class LatestSlot<T : Any> {
    private val ref = java.util.concurrent.atomic.AtomicReference<T?>(null)
    /** Puts [item] in; returns the older item it replaced (or null). */
    fun offer(item: T): T? = ref.getAndSet(item)
    /** Takes the waiting item (or null). */
    fun take(): T? = ref.getAndSet(null)
    fun hasItem(): Boolean = ref.get() != null
}

/**
 * v2.27: keeps the microphone ON the whole time NISU AI sleeps (Android's SpeechRecognizer kept closing / restarting and
 * missed the wake phrase). The assistant itself stays off: nothing is streamed to the live session.
 *
 * Security layer: every short sound is held only in memory and checked once, with the strict phrase list in
 * [WakeCommandParser] (the whole utterance must be exactly "Hey Nishu/Nisu"). If it is the right phrase -> wake up.
 * If it is anything else -> the audio is overwritten with zeros and forgotten at once. Nothing is stored on disk or logged.
 * The check is a single short transcription request to Gemini with the saved API key (only sounds of 0.3 - 3 s are ever sent).
 */
class SleepMicListener(
    private val ctx: Context,
    private val onResult: (List<String>, FloatArray?) -> Unit,
    private val onProblem: (String, Boolean) -> Unit
) : WakeSource {
    /** Short live text for the status line (mic level, last check result). Set by the service; called on the main thread. */
    var onInfo: ((String) -> Unit)? = null
    private var sounds = 0
    private var lastResult = "waiting"
    @Volatile private var lvl = 0
    private fun info() { val f = onInfo ?: return; main.post { if (run.get()) f("Sleeping: say \"Hey Nishu\" | mic $lvl | sounds $sounds | $lastResult") } }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()   // a slow check must not block the next "Hey Nishu"
    private val run = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    @Volatile private var rec: AudioRecord? = null
    private var worker: Thread? = null
    private var failures = 0
    private var lastCloseAt = 0L                // v3.116: when the last close offline miss was heard
    private var prevHeard = ""
    private var prevAt = 0L
    @Volatile private var lastHttp = 0
    private val recent = ArrayDeque<Long>()      // times of the last checks: at most 20 per minute
    private val waiting = LatestSlot<ByteArray>()    // v3.58: the newest sound that waits for its check (the mic loop never stops to judge)
    private val judging = AtomicBoolean(false)

    companion object {
        /** Offline wake needs trained samples (Preferences > Wake word training); otherwise the online check needs an API key. */
        fun available(ctx: Context): Boolean = WakeTemplates.ready(ctx) || Prefs.get(ctx, Prefs.KEY, "").trim().isNotEmpty()

        /** Opens the microphone for wake use (also used by the training screen). null = permission missing / mic busy. */
        @SuppressLint("MissingPermission")
        fun openRecord(): AudioRecord? {
            val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            fun make(src: Int): AudioRecord? = try {
                val r = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 8)   // v3.58: 8x (was 4x) so a slow moment can never overrun the buffer and cut sound
                if (r.state == AudioRecord.STATE_INITIALIZED) { MicOwn.add(r.audioSessionId); r } else { try { r.release() } catch (_: Exception) {}; null }
            } catch (e: Exception) { null }
            val r = make(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: make(MediaRecorder.AudioSource.MIC) ?: return null
            try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
            return r
        }
        private const val PROMPT =
            "This is a short voice clip (about 1 to 3 seconds) from a phone. Write down exactly the words you clearly hear, nothing else. " +
            "Bengali words in Bengali script, English words in English. If it is silence, noise, music, a TV or you do not hear clear words, write NONE. " +
            "Do not guess and never complete a phrase. The assistant in this app is called Nishu (also spelled Nisu): only if you really hear that name, " +
            "or something that sounds very close to it (Neeshu, Nishoo, Neshu, নিশু, নিসু, নেশু), write it as Nishu. " +
            "A greeting such as hey / হে / হেই is written as Hey. Never add words that were not said."
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!run.compareAndSet(false, true)) return
        // Open the secure connection to Google now, so the first "Hey Nishu" does not pay for the handshake (~0.5 s).
        thread(name = "nisu-wake-warm", isDaemon = true) {
            try { http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/").head().build()).execute().close() } catch (_: Exception) {}
        }
        worker = thread(name = "nisu-sleep-mic", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            // v2.31: right after the assistant's own microphone was released the system may still report it as busy for a moment.
            // That used to count as a fatal problem and woke NISU AI up again at once ("it never really sleeps"). Try again for ~4 s.
            // v2.51: a busy / unavailable microphone NEVER wakes NISU AI up any more (it used to: "better awake than stuck"). It keeps trying until it gets the microphone.
            var r: AudioRecord? = null
            var tries = 0
            while (true) {
                if (!run.get()) return@thread
                tries++
                r = openRecord()
                if (r != null) {
                    try { r.startRecording(); if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) break } catch (_: Exception) {}
                    try { r.release() } catch (_: Exception) {}
                    r = null
                }
                if (tries == 10) main.post { onProblem("Microphone is busy or the permission is missing. NISU AI stays asleep and keeps trying.", false) }
                try { Thread.sleep(if (tries < 10) 400L else 2000L) } catch (_: InterruptedException) { return@thread }
            }
            if (r == null) return@thread
            rec = r
            if (!run.get()) { try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}; rec = null; return@thread }   // stopped while opening
            val seg = UtteranceSegmenter(22)   // v2.40: 0.44 s of quiet ends "Hey Nishu" (was 0.3 s): a short breath between "Hey" and "Nishu" no longer cuts the phrase in two
            val frame = ByteArray(UtteranceSegmenter.FRAME_BYTES)
            var have = 0
            var frames = 0
            while (run.get()) {
                val n = try { r.read(frame, have, frame.size - have) } catch (_: Exception) { -1 }
                if (n < 0) break
                have += n
                if (have < frame.size) continue
                have = 0
                val utt = seg.feed(frame)
                lvl = seg.lastLevel.toInt()
                if (++frames % 100 == 0) info()      // every 2 s: shows the mic is really open
                if (utt == null) continue
                sounds++; lastResult = "checking"; info()
                submit(utt)      // v3.58: the check (offline compare or the online request, up to 8 s) runs on its own thread; this loop keeps reading the microphone
            }
            seg.reset()
            java.util.Arrays.fill(frame, 0.toByte())
            // the recorder stopped by itself (another app took the microphone, a call, ...): ask the service to open it again, never to wake up
            if (run.get()) { run.set(false); main.post { onProblem("Microphone was taken by another app. Opening it again.", true) } }
        }
    }

    override fun stop() {
        run.set(false)
        val r = rec; rec = null
        if (r != null) {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        try { worker?.join(500) } catch (_: Exception) {}
        worker = null
        waiting.take()?.let { java.util.Arrays.fill(it, 0.toByte()) }
    }

    /** v3.58: queue a finished sound for checking; a newer sound replaces an older waiting one (which is wiped). */
    private fun submit(pcm: ByteArray) {
        waiting.offer(pcm)?.let { java.util.Arrays.fill(it, 0.toByte()) }
        kick()
    }

    private fun kick() {
        if (!judging.compareAndSet(false, true)) return
        thread(name = "nisu-wake-judge", isDaemon = true) {
            try {
                while (run.get()) { val u = waiting.take() ?: break; verify(u) }
            } finally {
                judging.set(false)
                if (waiting.hasItem() && run.get()) kick()      // a sound arrived just as the loop ended
            }
        }
    }

    /** Called on the mic thread with one finished utterance; hands it to the checker and wipes it afterwards, whatever the answer. */
    private fun verify(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (recent.size >= 20) { java.util.Arrays.fill(pcm, 0.toByte()); lastResult = "skipped (limit: 20 online checks a minute)"; info(); return }   // too many sounds in a minute: forget it (v3.58: a check that is still running no longer drops the next sound)
        if (WakeTemplates.ready(ctx)) {
            // OFFLINE: compared with the owner's trained samples, no internet. The recording is wiped right after, whatever the answer.
            val res = try { WakeTemplates.judge(ctx, pcm) } catch (_: Exception) { null }
            // v3.116: a CLOSE miss (score up to 1.45 x the limit) said twice within 8 s counts as a wake, with no internet at all. Trained samples are recorded in one quiet
            // sitting, so their spread is small and a normal "Hey Nishu" from a different place often lands just over the limit; saying it again then works.
            val closeMiss = res != null && !res.ok && res.score != Double.MAX_VALUE && res.limit > 0.0 && res.score <= res.limit * 1.45
            val again = closeMiss && now - lastCloseAt < 8000L
            if (closeMiss) lastCloseAt = if (again) 0L else now
            val ok = (res != null && res.ok) || again
            // v2.56: a CLOSE miss (a bit over the limit) used to be thrown away, so a slightly different "Hey Nishu" never woke her. If an API key
            // exists, only such a near miss is checked once online; clearly different sounds still never leave the phone.
            val near = res != null && !ok && res.score != Double.MAX_VALUE && res.limit > 0.0 && res.score <= res.limit * 1.8 &&
                Prefs.get(ctx, Prefs.KEY, "").trim().isNotEmpty()
            lastResult = if (res == null || res.score == Double.MAX_VALUE) "not the phrase (too short / different)"
                else (if (ok) "WAKE" else if (closeMiss) "close: say it once more" else if (near) "close, checking online" else "not the phrase") + " (score %.2f, limit %.2f)".format(res.score, res.limit)
            info()
            if (ok && run.get()) { java.util.Arrays.fill(pcm, 0.toByte()); main.post { if (run.get()) onResult(listOf("Hey Nishu"), null) }; return }
            // v3.136: in the early-morning window (04:00-11:59, switch ON) every other short sound is checked once online too, to hear "good morning"
            if (!near && !morningListen()) { java.util.Arrays.fill(pcm, 0.toByte()); return }
        }
        recent.addLast(now)
        checking.set(true)
        kotlin.run {      // v3.58: already on the judge thread, so the online check runs here and the next sound waits in [waiting]
            var heard: String? = null
            try { heard = transcribe(pcm) } catch (_: Exception) { }
            finally {
                java.util.Arrays.fill(pcm, 0.toByte())      // security layer: the recording is erased in every case
                checking.set(false)
            }
            val text = heard
            if (text == null) {
                lastResult = "check failed" + (if (lastHttp != 0) " (HTTP $lastHttp)" else " (internet?)"); info()
                if (++failures == 3) main.post { onProblem("Wake check failed 3 times: " + lastResult, false) }
                return@run
            }
            failures = 0
            // Only the strict phrase list decides. The text is shown once on the status line only (screen, never stored).
            // v2.40: if \"Hey\" and \"Nishu\" came as two separate sounds, the two texts together still count (within 3.5 s)
            val clean = if (text.trim().equals("NONE", true)) "" else text
            // v2.51: only a lone "Hey" followed (within 2.5 s) by a lone "Nishu" counts as one phrase; anything else must be the whole phrase in one sound
            val joined = WakeCommandParser.isHeyOnly(prevHeard) && WakeCommandParser.isNameOnly(clean) && System.currentTimeMillis() - prevAt < 2500L
            val ok = WakeCommandParser.isWakeLoose(clean) || joined
            val morning = !ok && morningListen() && WakeCommandParser.isGoodMorning(clean)   // v3.136: the service checks the clock again before it wakes
            prevHeard = clean.take(40); prevAt = System.currentTimeMillis()
            lastResult = if (ok) "WAKE" else if (morning) "GOOD MORNING" else "not the phrase: " + text.replace(Regex("\\s+"), " ").take(30)
            info()
            if ((ok || morning) && run.get()) main.post { if (run.get()) onResult(listOf(if (ok) "Hey Nishu" else "Good morning"), null) }   // the service compares with the strict list, so pass the canonical phrase
        }
    }

    /** v3.136: true in the early-morning window with the switch ON and an API key (so a sound can be transcribed). */
    private fun morningListen(): Boolean =
        SleepWords.morningWakes(ctx, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) && Prefs.get(ctx, Prefs.KEY, "").trim().isNotEmpty()

    /** Raises a quiet recording to a clear level (peak about 70 %), in place. */
    private fun normalize(pcm: ByteArray) {
        var peak = 1; var i = 0
        while (i + 1 < pcm.size) { val v = Math.abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()); if (v > peak) peak = v; i += 2 }
        val g = (23000.0 / peak).coerceIn(1.0, 30.0)
        if (g < 1.2) return
        i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            val o = (v * g).toInt().coerceIn(-32768, 32767)
            pcm[i] = o.toByte(); pcm[i + 1] = (o shr 8).toByte(); i += 2
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val n = pcm.size; val out = ByteArray(44 + n)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); i32(4, 36 + n); "WAVEfmt ".toByteArray().copyInto(out, 8)
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, 16000); i32(28, 32000); i16(32, 2); i16(34, 16)
        "data".toByteArray().copyInto(out, 36); i32(40, n); pcm.copyInto(out, 44)
        return out
    }

    @Volatile private var noThinkingCfg = true     // v2.56: ask the model not to "think" (thinking tokens used up the tiny output limit -> empty text -> never woke); dropped for good if the model rejects it

    /**
     * One short check. Returns the heard text ("" when nothing clear) or null when the check itself failed.
     * v3.115: the check no longer depends on the "Code model" setting alone. It uses the ready fast model first ([Prefs.DEFAULT_CODE_MODEL]) and only then the
     * saved code model: a code model that is a text-only, lite or heavy "thinking" model used to break "Hey Nishu" (HTTP 400 / 404 or an empty answer) while everything else still worked.
     */
    private fun transcribe(pcm: ByteArray): String? {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        if (key.isEmpty()) return null
        val saved = Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().removePrefix("models/").ifBlank { Prefs.DEFAULT_CODE_MODEL }
        val models = listOf(Prefs.DEFAULT_CODE_MODEL, saved).distinct()
        normalize(pcm)
        val w = wav(pcm)
        val b64 = Base64.encodeToString(w, Base64.NO_WRAP)
        java.util.Arrays.fill(w, 0.toByte())
        fun call(model: String, noThink: Boolean): String? {
            val gen = JSONObject().put("temperature", 0).put("maxOutputTokens", 1024)   // v3.115: 256 could be used up by thinking tokens -> empty answer -> never woke
            if (noThink) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                    .put(JSONObject().put("text", PROMPT))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64))))))
                .put("generationConfig", gen)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                lastHttp = if (resp.isSuccessful) 0 else resp.code
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string() ?: return null)
                val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
                val sb = StringBuilder()
                for (k in 0 until parts.length()) {                 // all text parts, but never the model's "thought" parts
                    val p = parts.optJSONObject(k) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    sb.append(p.optString("text", "")).append(' ')
                }
                return sb.toString().trim()
            }
        }
        var last: String? = null
        for (m in models) {
            var r = try { call(m, noThinkingCfg) } catch (e: java.io.IOException) { null }
            if (r == null && noThinkingCfg && lastHttp == 400) { noThinkingCfg = false; r = try { call(m, false) } catch (e: java.io.IOException) { null } }   // this model does not accept the setting
            if (r != null) return r
            last = null
            if (lastHttp != 400 && lastHttp != 404 && lastHttp != 403) break    // no internet / limit / server trouble: another model will not help
        }
        return last
    }
}
