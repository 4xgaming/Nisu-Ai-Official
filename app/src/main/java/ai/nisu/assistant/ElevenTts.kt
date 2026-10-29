package ai.nisu.assistant

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Emotion + pronunciation layer for ElevenLabs (v2.14).
 *  - Eleven v3 understands audio tags ([happily], [sighs], [laughs] ...): they are put in front of each sentence from the current mood
 *    (set_emotion) and from the sentence itself (laughter, sighs, questions, exclamations).
 *  - Older models cannot read tags (they would speak them), so the mood moves stability / style / speed instead.
 *  - Bengali and Hindi are recognised from the script so the model is told the language and does not guess.
 */
object ElevenStyle {
    class Settings(val stability: Double, val similarity: Double, val style: Double, val speed: Double)

    fun isV3(model: String) = model.startsWith("eleven_v3")

    /** "bn", "hi" or null (English / mixed / unknown: let the model decide). */
    fun language(text: String): String? {
        var bn = 0; var hi = 0; var letters = 0
        for (ch in text) {
            when {
                ch in '\u0980'..'\u09FF' -> { bn++; letters++ }
                ch in '\u0900'..'\u097F' -> { hi++; letters++ }
                ch.isLetter() -> letters++
            }
        }
        if (letters == 0) return null
        return when {
            bn * 100 / letters >= 60 -> "bn"
            hi * 100 / letters >= 60 -> "hi"
            else -> null
        }
    }

    private val EMOJI = Regex("[\\p{So}\\p{Sk}\\u200d\\uFE0F]")
    private val LAUGH = Regex("(?i)(হা\\s?হা(\\s?হা)*|হি\\s?হি|\\bha\\s?ha(\\s?ha)*\\b|\\bhihi+\\b|हा\\s?हा(\\s?हा)*|हीही)")
    private val SIGH = Regex("(?i)(^|\\s)(উফ+|উফফ|ইশ|ওফ|uff+|ufff*|ओफ़|उफ़|उफ्फ)(?=[\\s,!.।?]|$)")

    /** "hehe" written out as a word is never spoken: it is dropped (the voice laughs, or the line just continues). */
    internal val HEHE = Regex("(?i)(হে\\s?হে(\\s?হে)*|হেহে+|\\bhe\\s?he(\\s?he)*\\b|\\bhehe+\\b|हे\\s?हे(\\s?हे)*|हेहे+)")
    fun noHehe(t: String): String = HEHE.replace(t, "").replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\s+([,.!?।])"), "$1").trim()

    /** Plain text for the speaker: no emoji or markdown. */
    fun clean(t: String): String = noHehe(t).replace(EMOJI, "").replace(Regex("[*#_`~<>|]"), "").replace(Regex("[ \\t]{2,}"), " ")

    private fun moodTag(emotion: String): String = when (emotion.lowercase()) {
        "happy" -> "[cheerful][sweetly][laughs softly]"
        "excited" -> "[excited][cheerful][fast]"
        "sad" -> "[sad][slowly][softly]"
        "angry" -> "[firm][serious]"
        "surprised" -> "[gasps][surprised][sweetly]"
        "love" -> "[sweetly][softly][warmly]"
        "jealous" -> "[playfully][teasing][softly]"
        "sulky" -> "[sulking][softly][sighs]"
        "playful" -> "[playfully][sweetly][teasing]"
        "worried" -> "[softly][warmly][concerned]"
        "shy" -> "[shy][sweetly][softly][giggles]"
        "sleepy" -> "[sleepy][softly][slowly]"
        "proud" -> "[proud][warmly][sweetly]"
        "curious" -> "[curious][sweetly][interested]"
        "bored" -> "[softly][sighs]"
        "nervous" -> "[nervous][softly][hesitant]"
        "disappointed" -> "[disappointed][quietly][softly]"
        "grateful" -> "[touched][warmly][sweetly]"
        "lonely" -> "[quietly][wistful][softly]"
        "confused" -> "[curious][softly][hesitant]"
        "scared" -> "[scared][whispers][softly]"
        "annoyed" -> "[sighs][firm]"
        "flirty" -> "[sweetly][playfully][softly][teasing]"
        "guilty" -> "[quietly][apologetic][softly]"
        "relieved" -> "[relieved][warmly][exhales]"
        "hopeful" -> "[hopeful][sweetly][softly]"
        "mischievous" -> "[mischievously][playfully][sweetly]"
        "overwhelmed" -> "[softly][breathless]"
        "nostalgic" -> "[wistful][warmly][softly]"
        "heartbroken" -> "[sad][quietly][slowly]"
        else -> "[sweetly][warmly]"
    }

    /** Eleven v3 only: the sentence with audio tags in the right places. */
    fun withTags(sentence: String, emotion: String): String {
        var s = noHehe(sentence)
        if (s.isEmpty()) return s
        var tag = moodTag(emotion)
        if (tag.isEmpty()) {
            tag = when {
                s.endsWith("?") -> "[curious]"
                s.endsWith("!") -> "[excited]"
                else -> ""
            }
        }
        s = LAUGH.replace(s) { "[laughs] " + it.value }
        s = SIGH.replace(s) { m -> m.groupValues[1] + "[sighs] " + m.groupValues[2] }
        return if (tag.isEmpty()) s else "$tag $s"
    }

    /** Eleven v3 accepts only 0.0 (Creative), 0.5 (Natural) and 1.0 (Robust) for stability. Moods use Creative so the tags really change the voice. */
    fun v3Stability(emotion: String): Double = when (emotion.lowercase()) { "calm" -> 0.5; else -> 0.0 }

    /** Models without audio tags: tuned for a sweeter, smoother, more melodious feminine voice. */
    fun classic(emotion: String): Settings = when (emotion.lowercase()) {
        "excited" -> Settings(0.32, 0.88, 0.72, 1.04)
        "happy" -> Settings(0.36, 0.88, 0.65, 1.02)
        "love" -> Settings(0.42, 0.90, 0.62, 0.96)
        "sad" -> Settings(0.52, 0.88, 0.45, 0.92)
        "angry" -> Settings(0.38, 0.86, 0.60, 1.02)
        "surprised" -> Settings(0.32, 0.88, 0.68, 1.03)
        "jealous" -> Settings(0.40, 0.88, 0.58, 0.98)
        "sulky" -> Settings(0.45, 0.88, 0.52, 0.95)
        "playful" -> Settings(0.34, 0.88, 0.68, 1.02)
        "worried" -> Settings(0.46, 0.88, 0.52, 0.96)
        "shy", "grateful", "lonely" -> Settings(0.44, 0.90, 0.55, 0.96)
        "sleepy", "bored", "disappointed" -> Settings(0.52, 0.88, 0.42, 0.92)
        "proud", "curious" -> Settings(0.36, 0.88, 0.62, 1.01)
        "nervous", "scared", "confused" -> Settings(0.38, 0.88, 0.55, 1.01)
        "annoyed" -> Settings(0.40, 0.88, 0.55, 1.0)
        "flirty", "mischievous", "hopeful" -> Settings(0.38, 0.90, 0.65, 0.99)
        "guilty", "nostalgic", "heartbroken" -> Settings(0.50, 0.88, 0.48, 0.93)
        "relieved" -> Settings(0.44, 0.88, 0.55, 0.98)
        "overwhelmed" -> Settings(0.38, 0.88, 0.56, 1.02)
        else -> Settings(0.42, 0.90, 0.58, 0.99)
    }
}

/**
 * Speaks text with ElevenLabs (text-to-speech streaming, raw 16-bit PCM at 24 kHz, the same format the speaker already plays).
 * Gemini stays the brain: its answer text arrives in small pieces through [speak], is cut into sentences and each sentence is
 * voiced in order. v2.14: the NEXT sentence is already being fetched while the current one is still playing (no gap between
 * sentences, much faster), short sentences are joined so the voice has context, and every sentence carries the current emotion.
 * [cancel] stops everything at once (user interrupted, voice switched).
 */
class ElevenTts(
    private val key: () -> String,
    private val voiceId: () -> String,
    private val model: () -> String,
    /** Receives raw PCM (24 kHz, 16-bit, mono) as it streams in. */
    private val sink: (ByteArray) -> Unit,
    /** Called after the last sentence of an answer has been fully handed to the speaker. */
    private val onEnd: () -> Unit,
    /** fatal = true: retrying cannot help (bad key, unknown voice or model, no credits). */
    private val onError: (String, Boolean) -> Unit,
    /** The current mood (happy, excited, calm, sad, love, surprised, angry). */
    private val emotion: () -> String = { "calm" },
    /** v3.42: Language Lock hint (ISO code of the locked language) or null for "All languages" (then the script detection decides). */
    private val langHint: () -> String? = { null }
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** One sentence: its audio arrives in [chunks] while an earlier sentence may still be playing. */
    private class Slot(val epoch: Int, val last: Boolean) {
        val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile var done = false
        @Volatile var call: Call? = null
    }

    private val epoch = AtomicInteger(0)
    private val q = LinkedBlockingQueue<Triple<Int, String, Boolean>>()   // (epoch, sentence, lastOfAnswer)
    private val playQ = LinkedBlockingQueue<Slot>()
    private val pending = AtomicInteger(0)
    private val active: MutableSet<Slot> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val fetchPool = Executors.newFixedThreadPool(3) { r -> Thread(r, "nisu-eleven-fetch").also { it.isDaemon = true } }
    private val buf = StringBuilder()
    private var firstOfAnswer = true
    @Volatile private var alive = true

    init {
        thread(name = "nisu-eleven", isDaemon = true) { dispatch() }
        thread(name = "nisu-eleven-play", isDaemon = true) { play() }
        // Open the TLS connection to ElevenLabs now, so the first sentence does not pay for the handshake.
        thread(name = "nisu-eleven-warm", isDaemon = true) {
            try { http.newCall(Request.Builder().url("https://api.elevenlabs.io/").head().build()).execute().close() } catch (_: Exception) {}
        }
    }

    /** True while text is still waiting to be voiced or audio is still being fetched / handed over. */
    fun busy(): Boolean = pending.get() > 0 || !q.isEmpty()

    /** A piece of the answer text. */
    fun speak(piece: String) {
        val t = ElevenStyle.clean(piece)
        if (t.isEmpty()) return
        synchronized(buf) { buf.append(t); drain(false) }
    }

    /** The answer is complete: voice whatever is left. */
    fun endTurn() {
        synchronized(buf) { drain(true); firstOfAnswer = true }
    }

    /** Drop everything that was not spoken yet and stop the sentences that are being fetched. */
    fun cancel() {
        epoch.incrementAndGet()
        q.clear()
        val dropped = ArrayList<Slot>()
        playQ.drainTo(dropped)
        pending.addAndGet(-dropped.size)
        synchronized(buf) { buf.setLength(0); firstOfAnswer = true }
        for (s in active) try { s.call?.cancel() } catch (_: Exception) {}
        for (s in dropped) try { s.call?.cancel() } catch (_: Exception) {}
    }

    fun release() { alive = false; cancel(); try { fetchPool.shutdownNow() } catch (_: Exception) {} }

    // buf is locked by the caller
    private fun drain(final: Boolean) {
        while (true) {
            val cut = cutPoint(final)
            if (cut <= 0) break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.isNotEmpty()) { q.offer(Triple(epoch.get(), s, false)); firstOfAnswer = false }
        }
        if (final) q.offer(Triple(epoch.get(), "", true))
    }

    /**
     * First words go out as soon as possible (a comma after 8+ characters). After that short sentences are joined (at least ~45
     * characters, or the end of the answer) so the voice has enough context to sound natural instead of choppy.
     */
    private fun cutPoint(final: Boolean): Int {
        val n = buf.length
        val minLen = if (firstOfAnswer) 6 else 30
        for (i in 0 until n) {
            val c = buf[i]
            val next: Char? = if (i + 1 < n) buf[i + 1] else null
            val strong = when (c) {
                '\n', '\u0964', '?', '!' -> true                                 // \u0964 is the danda used by Bengali and Hindi
                '.' -> next != null && next.isWhitespace()
                else -> false
            }
            val weak = (c == ',' || c == ';' || c == ':' || c == '\u2014') && (firstOfAnswer || i + 1 >= 75) && (next == null || next.isWhitespace())
            if ((strong || weak) && i + 1 >= minLen) return i + 1
        }
        if (n >= 180) {   // a long run without punctuation is spoken in smooth pieces so the speaker never waits
            val sp = buf.lastIndexOf(" ", 160)
            return if (sp > 35) sp + 1 else n
        }
        return if (final) n else 0
    }

    private fun dispatch() {
        while (alive) {
            val item = q.poll(300, TimeUnit.MILLISECONDS) ?: continue
            val e = item.first
            if (e != epoch.get()) continue
            val slot = Slot(e, item.third)
            val text = item.second
            pending.incrementAndGet()
            playQ.offer(slot)
            if (text.isEmpty()) { slot.done = true; continue }
            active.add(slot)
            val mood = emotion()   // the mood at the moment the sentence was said
            try { fetchPool.execute { try { fetch(slot, text, mood) } finally { slot.done = true; active.remove(slot) } } }
            catch (_: Exception) { slot.done = true; active.remove(slot) }
        }
    }

    /** Hands the audio of the sentences to the speaker strictly in order. */
    private fun play() {
        while (alive) {
            val slot = playQ.poll(300, TimeUnit.MILLISECONDS) ?: continue
            try {
                while (slot.epoch == epoch.get()) {
                    val c = slot.chunks.poll(40, TimeUnit.MILLISECONDS)
                    if (c != null) { if (slot.epoch == epoch.get()) sink(c); continue }   // a chunk taken just before cancel() must not leak out
                    if (slot.done && slot.chunks.isEmpty()) break
                }
                if (slot.last && slot.epoch == epoch.get()) onEnd()
            } catch (_: Exception) {
            } finally { pending.decrementAndGet() }
        }
    }

    private fun fetch(slot: Slot, text: String, mood: String) {
        val e = slot.epoch
        val k = key()
        val v = voiceId().ifEmpty { VoiceEngine.DEFAULT_ELEVEN_VOICE }
        if (k.isEmpty()) { onError("ElevenLabs API key is missing", true); return }
        val m = model().ifEmpty { VoiceEngine.DEFAULT_ELEVEN_MODEL }
        val lang = langHint() ?: ElevenStyle.language(text)
        // Try the chosen voice/model first (with the language hint); if ElevenLabs refuses, retry without the hint and then with safe
        // defaults before giving up, so a wrong custom id or a model the account cannot use does not silently drop to Gemini.
        val tries = ArrayList<Triple<String, String, String?>>()
        tries.add(Triple(v, m, lang))
        if (lang != null) tries.add(Triple(v, m, null))
        if (v != VoiceEngine.DEFAULT_ELEVEN_VOICE) tries.add(Triple(VoiceEngine.DEFAULT_ELEVEN_VOICE, m, null))
        if (m != VoiceEngine.FALLBACK_ELEVEN_MODEL) tries.add(Triple(VoiceEngine.DEFAULT_ELEVEN_VOICE, VoiceEngine.FALLBACK_ELEVEN_MODEL, null))
        var lastErr = ""
        for ((idx, t) in tries.withIndex()) {
            if (e != epoch.get()) return
            val r = request(slot, k, t.first, t.second, t.third, text, mood)
            if (r == null) return                       // spoken, cancelled, or a network error already reported
            lastErr = r
            val retryable = r.startsWith("HTTP 400") || r.startsWith("HTTP 404") || r.startsWith("HTTP 422") || r.startsWith("HTTP 402")
            if (!retryable || idx == tries.size - 1) break
        }
        if (e == epoch.get()) onError(lastErr, true)
    }

    /** Returns null when done (or network error already reported), else the HTTP error text. */
    private fun request(slot: Slot, k: String, v: String, m: String, lang: String?, text: String, mood: String): String? {
        val e = slot.epoch
        val v3 = ElevenStyle.isV3(m)
        val body = JSONObject().put("model_id", m)
        if (v3) {
            body.put("text", ElevenStyle.withTags(text, mood))
            body.put("voice_settings", JSONObject().put("stability", ElevenStyle.v3Stability(mood)).put("similarity_boost", 0.85))
        } else {
            val s = ElevenStyle.classic(mood)
            body.put("text", text)
            body.put("voice_settings", JSONObject().put("stability", s.stability).put("similarity_boost", s.similarity)
                .put("style", s.style).put("use_speaker_boost", true).put("speed", s.speed))
        }
        if (lang != null) body.put("language_code", lang)
        val url = "https://api.elevenlabs.io/v1/text-to-speech/" + java.net.URLEncoder.encode(v, "UTF-8") + "/stream?output_format=pcm_24000&optimize_streaming_latency=3"
        val req = Request.Builder().url(url).header("xi-api-key", k)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val c = http.newCall(req)
        slot.call = c
        try {
            c.execute().use { r ->
                if (!r.isSuccessful) {
                    val msg = try { r.body?.string().orEmpty().take(200) } catch (_: Exception) { "" }
                    val err = "HTTP " + r.code + " " + msg
                    // 401/403 = key problem, 429/5xx = temporary: no point retrying with other voices
                    if (r.code == 401 || r.code == 403) { if (e == epoch.get()) onError(err, true); return null }
                    if (r.code == 429 || r.code >= 500) { if (e == epoch.get()) onError(err, false); return null }
                    return err
                }
                val src = r.body?.source() ?: return null
                val chunk = ByteArray(4800)   // 100 ms of speech
                // Network reads end on ANY byte, but 16-bit samples are 2 bytes. Every chunk handed on must hold whole samples,
                // and a sentence that stops on an odd byte must not leak that byte into the next sentence: one byte of shift turns
                // the whole next sentence into loud radio-static noise.
                var carry = -1
                while (e == epoch.get()) {
                    val n = src.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    var b = chunk.copyOf(n)
                    if (carry >= 0) {
                        val m = ByteArray(b.size + 1)
                        m[0] = carry.toByte()
                        System.arraycopy(b, 0, m, 1, b.size)
                        b = m; carry = -1
                    }
                    if (b.size % 2 == 1) { carry = b[b.size - 1].toInt() and 0xFF; b = b.copyOf(b.size - 1) }
                    if (b.isNotEmpty()) slot.chunks.offer(b)
                }
                // a leftover odd byte at the end of the sentence is dropped on purpose
            }
            return null
        } catch (ex: Exception) {
            if (e == epoch.get()) onError(ex.message ?: ex.javaClass.simpleName, false)
            return null
        } finally { slot.call = null }
    }
}

/** Loads the FEMALE voices of an ElevenLabs account, so only female voices can ever be picked. */
object ElevenVoices {
    class V(val id: String, val name: String, val note: String)

    /** Runs on a background thread; [done] is called on the main thread with the list, or null and a message. */
    fun fetchFemale(key: String, done: (List<V>?, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        thread(name = "nisu-eleven-voices", isDaemon = true) {
            var list: List<V>? = null
            var msg = ""
            try {
                val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
                val req = Request.Builder().url("https://api.elevenlabs.io/v2/voices?page_size=100").header("xi-api-key", key).build()
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        msg = if (text.contains("missing_permissions") || text.contains("voices_read"))
                            "This API key has no 'Voices: Read' permission. Turn it on in ElevenLabs, or type a voice ID below."
                        else if (r.code == 401) "ElevenLabs rejected the API key (" + text.take(120) + ")"
                        else "ElevenLabs error HTTP " + r.code
                    } else {
                        val arr = JSONObject(text).optJSONArray("voices")
                        val out = ArrayList<V>()
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val labels = o.optJSONObject("labels")
                            val gender = labels?.optString("gender").orEmpty().lowercase()
                            if (gender != "female") continue
                            val id = o.optString("voice_id")
                            if (id.isEmpty()) continue
                            val note = listOf(labels?.optString("accent").orEmpty(), labels?.optString("description").orEmpty(), labels?.optString("use_case").orEmpty())
                                .filter { it.isNotBlank() }.joinToString(", ")
                            out.add(V(id, o.optString("name", id), note))
                        }
                        list = out
                    }
                }
            } catch (ex: Exception) {
                msg = "Could not reach ElevenLabs: " + (ex.message ?: ex.javaClass.simpleName)
            }
            main.post { done(list, msg) }
        }
    }
}
