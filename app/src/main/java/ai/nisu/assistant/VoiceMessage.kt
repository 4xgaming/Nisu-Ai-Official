package ai.nisu.assistant

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * v3.126: SEND A VOICE MESSAGE (WhatsApp, Messenger, Telegram and other chat apps). Only while the owner's "Voice messages" switch is ON.
 *
 * send_voice_message(app, to, text, user_confirmed):
 *   1. the words are spoken into an audio file (her own voice: ElevenLabs when that engine is chosen and set up, otherwise Gemini speech),
 *   2. the file is turned into Opus (.ogg, Android 10+; WhatsApp and Telegram usually show it like a voice note), or AAC (.m4a), or WAV if the
 *      phone's encoder fails,
 *   3. the app's own Share screen is opened with the file, the person / group is picked by name and Send is pressed ([ShareMedia.shareAudio]).
 * Nothing is sent without his spoken yes to the exact words (user_confirmed). Never to a Status. The files live in the cache and are deleted after a day.
 */
object VoiceMessage {
    const val NAME = "send_voice_message"
    private const val MAX_CHARS = 700
    private val GEMINI_TTS_MODELS = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    fun enabled(c: Context): Boolean = Prefs.getBool(c, Prefs.VOICE_MSG, false)

    private fun err(msg: String, fix: String? = null) = JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    /** No dollar signs: it is a const. */
    const val NOTE = """
VOICE MESSAGES (send_voice_message, only when the owner's Voice messages switch is ON): you CAN send a voice message in WhatsApp, Messenger, Telegram and other chat apps, in your own voice, to a person or group he names. When he asks for a voice message (voice message pathao, voice e bolo, voice note), say the exact words you will send in one short line, get his yes, then call send_voice_message with app, to, text and user_confirmed true. Write the words the way he would speak them, in the language of the chat. Report honestly: say it is sent only when the result says likely_sent true. If the tool says the switch is off, tell him to turn Voice messages ON in Preferences. Never use it for a Status and never on your own.
"""

    fun run(ctx: Context, a: JSONObject): JSONObject {
        if (!enabled(ctx)) return err("Voice messages are switched OFF, so nothing was sent.", "Tell him to turn Voice messages ON in Preferences, then ask again.")
        val text = a.optString("text", "").trim()
        val app = a.optString("app", "").trim()
        val to = a.optString("to", "").trim()
        if (text.isEmpty()) return err("Say what the voice message should say (text).")
        if (app.isEmpty() || to.isEmpty()) return err("Say the app (WhatsApp, Messenger, Telegram) and the person or group (to).")
        if (text.length > MAX_CHARS) return err("That is too long for one voice message (about " + MAX_CHARS + " characters at most). Split it into two shorter voice messages.")
        if (ShareRules.isStatusTarget(to)) return err("Voice messages are only sent to a person or group, not to a Status.")
        if (!a.optBoolean("user_confirmed", false)) return err("NOT sent. Say the exact words to him in one short line, get his clear yes, then call again with user_confirmed true.")
        val def = ShareRules.findApp(app)
        if (def != null && !def.chat) return err(def.label + " is not a chat app, so a voice message cannot be sent there.")
        val acc = NisuAccessibilityService.inst ?: return err(PhoneControl.accProblem(ctx) ?: "Phone control is not connected.")

        cleanOld(ctx)
        val sp = speak(ctx, text) ?: return err("The voice for the message could not be made (no internet, or the API key / ElevenLabs refused). Nothing was sent.",
            "Tell him honestly. Check the Gemini API key (or the ElevenLabs key) and the internet, then try again.")
        val (file, mime, kind) = encode(ctx, sp.first, sp.second)
        AppState.add("Voice message: " + kind + ", " + (file.length() / 1024) + " KB, to " + to)
        val r = ShareMedia.shareAudio(ctx, file, mime, app, to)
        r.put("voice_format", kind)
        if (kind == "wav") r.put("format_note", "The phone could not make a compressed voice file, so a plain audio file was sent; it plays fine but may look like an audio file, not a voice note.")
        else if (kind == "m4a") r.put("format_note", "It was sent as an audio file; the chat app may show it as an audio clip instead of a voice note.")
        return r
    }

    private fun dir(ctx: Context): File = File(File(ctx.cacheDir, "shared_uploads"), "voice").also { it.mkdirs() }

    private fun cleanOld(ctx: Context) {
        try { val limit = System.currentTimeMillis() - 24L * 3600_000L; dir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() } } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------------------------------------------- speech (PCM 16 bit mono)

    /** (pcm, sampleRate) or null. */
    private fun speak(ctx: Context, text: String): Pair<ByteArray, Int>? {
        if (VoiceEngine.saved(ctx) == VoiceEngine.ELEVEN && VoiceEngine.elevenReady(ctx)) elevenPcm(ctx, text)?.let { return it to 24000 }
        return geminiPcm(ctx, text)
    }

    private fun elevenPcm(ctx: Context, text: String): ByteArray? = try {
        val k = Prefs.get(ctx, Prefs.ELEVEN_KEY, "").trim()
        val m = Prefs.get(ctx, Prefs.ELEVEN_MODEL, "").trim().ifEmpty { VoiceEngine.DEFAULT_ELEVEN_MODEL }
        val body = JSONObject().put("model_id", m).put("text", ElevenStyle.clean(text))
        val url = "https://api.elevenlabs.io/v1/text-to-speech/" + java.net.URLEncoder.encode(VoiceEngine.elevenVoice(ctx), "UTF-8") + "?output_format=pcm_24000"
        http.newCall(Request.Builder().url(url).header("xi-api-key", k).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size > 2000 }
        }
    } catch (_: Exception) { null }

    private fun geminiPcm(ctx: Context, text: String): Pair<ByteArray, Int>? {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        if (key.isEmpty()) return null
        val voice = Voice.saved(ctx)
        for (model in GEMINI_TTS_MODELS) {
            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "Say this as a real young woman would say it in a voice message to someone she knows, warm, natural and relaxed, in the same language as the words: " + text)))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val j = JSONObject(r.body?.string().orEmpty())
                    val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@use
                    for (i in 0 until parts.length()) {
                        val inl = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        val data = inl.optString("data")
                        if (data.isEmpty()) continue
                        val rate = Regex("rate=(\\d+)").find(inl.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                        val pcm = Base64.decode(data, Base64.DEFAULT)
                        if (pcm.size > 2000) return pcm to rate
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------------------- encoding

    private data class Out(val file: File, val mime: String, val kind: String)
    private operator fun Out.component1() = file
    private operator fun Out.component2() = mime
    private operator fun Out.component3() = kind

    private fun encode(ctx: Context, pcm: ByteArray, rate: Int): Out {
        val stamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 29) {
            val f = File(dir(ctx), "voice_$stamp.ogg")
            if (encodeWith(pcm, rate, "audio/opus", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, f, 32000)) return Out(f, "audio/ogg", "ogg-opus")
            f.delete()
        }
        val m4a = File(dir(ctx), "voice_$stamp.m4a")
        if (encodeWith(pcm, rate, "audio/mp4a-latm", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, m4a, 48000)) return Out(m4a, "audio/mp4", "m4a")
        m4a.delete()
        val wav = File(dir(ctx), "voice_$stamp.wav")
        writeWav(pcm, rate, wav)
        return Out(wav, "audio/wav", "wav")
    }

    private fun encodeWith(pcm: ByteArray, rate: Int, mime: String, container: Int, out: File, bitrate: Int): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            val format = MediaFormat.createAudioFormat(mime, rate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (mime == "audio/mp4a-latm") format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, container)
            val info = MediaCodec.BufferInfo()
            var inOff = 0; var inDone = false; var outDone = false; var track = -1
            val deadline = System.currentTimeMillis() + 30_000L
            while (!outDone && System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        var n = minOf(buf.remaining(), pcm.size - inOff)
                        n -= n % 2
                        val pts = inOff / 2L * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { buf.put(pcm, inOff, n); inOff += n; codec.queueInputBuffer(i, 0, n, pts, 0) }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(track, ob, info) }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            outDone && started
        } catch (_: Throwable) { false }
        finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { if (started) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }.let { ok -> ok && out.length() > 800 }
    }

    private fun writeWav(pcm: ByteArray, rate: Int, f: File) {
        RandomAccessFile(f, "rw").use { w ->
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            w.setLength(0)
            w.writeBytes("RIFF"); w.write(le32(36 + pcm.size)); w.writeBytes("WAVEfmt "); w.write(le32(16)); w.write(le16(1)); w.write(le16(1))
            w.write(le32(rate)); w.write(le32(rate * 2)); w.write(le16(2)); w.write(le16(16)); w.writeBytes("data"); w.write(le32(pcm.size)); w.write(pcm)
        }
    }
}
