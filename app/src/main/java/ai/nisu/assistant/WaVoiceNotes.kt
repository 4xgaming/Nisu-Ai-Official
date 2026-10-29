package ai.nisu.assistant

import android.content.Context
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v3.71: WhatsApp voice messages are understood SILENTLY.
 *
 * Before, a voice message got a fixed "I cannot hear voice messages" answer, because the notification only shows a placeholder. Now, for a voice
 * message of 0 to 2 minutes, NISU AI reads the sound file WhatsApp itself saved on the phone (nothing is played, no speaker, no microphone),
 * Gemini writes down what was said, and that text is answered like a normal message. Longer than 2 minutes: the old fixed answer stays.
 *
 * Rules kept: only WhatsApp / WhatsApp Business (Messenger keeps its answer: its files are private to Messenger); only when the owner's switch
 * "write reply drafts with Gemini" is on (the audio is sent to Gemini the same way message text is); no root (the WhatsApp agent has none): the
 * normal file access ("All files access") is used; nothing is logged or kept except the short transcript in memory for the retry of the same message.
 */
object WaVoiceNotes {
    const val MAX_SECONDS = 120.0
    private const val FILE_WAIT_MS = 15_000L        // WhatsApp downloads the voice message a moment after the notification
    private const val TOTAL_MS = 45_000L            // file wait + Gemini; the agent's own limit is 90 s
    private const val MAX_TRANSCRIPT_CHARS = 1_300  // the reply writer reads at most 1 500 characters of a message
    private const val MAX_BYTES = 1_500_000L        // 2 minutes of WhatsApp voice is about 0.3 MB; a bigger file is not a plain voice message

    @Volatile private var app: Context? = null
    fun init(c: Context) { app = c.applicationContext }

    private val done = ConcurrentHashMap<Long, String>()          // messageId -> transcript (a retry of the same message does not listen again)
    private val claimed: MutableSet<String> = ConcurrentHashMap.newKeySet()   // sound files already given to an earlier message

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    private val PROMPT =
        "This is a short WhatsApp voice message. Listen to it and write down what the speaker says, in the language the speaker uses " +
            "(Bengali in Bengali script, Hindi in Devanagari, English in English; mixed speech stays mixed). Keep every question, request, " +
            "name, number, time and place exactly. Write only the words that were said, nothing else: no title, no label, no comments. " +
            "If it is longer than about 200 words, shorten it by dropping repeated or filler words but keep everything that matters. " +
            "If there is no clear speech (silence, noise, music) write exactly NONE."

    /**
     * The text of a WhatsApp voice message, or null when it cannot be understood (too long, file not found, no clear speech, Gemini failed).
     * null always means: use the old fixed answer. Never throws except for cancellation.
     */
    suspend fun listen(m: ProcessedMessage): String? {
        val c = app ?: return null
        done[m.messageId]?.let { return it }
        val key = Prefs.get(c, Prefs.KEY, "").trim()
        if (key.isEmpty()) return null
        val text: String? = withTimeoutOrNull(TOTAL_MS) {
            val file = waitForFile(m) ?: return@withTimeoutOrNull null
            val sec = durationSeconds(file) ?: notificationSeconds(m.text)
            if (sec != null && sec > MAX_SECONDS + 0.5) return@withTimeoutOrNull TOO_LONG
            if (sec == null && file.length() > MAX_BYTES) return@withTimeoutOrNull TOO_LONG
            claimed.add(file.absolutePath)
            val bytes = try { withContext(Dispatchers.IO) { file.readBytes() } } catch (_: Exception) { return@withTimeoutOrNull null }
            withContext(Dispatchers.IO) { transcribe(c, key, bytes) }
        }
        if (text == null || text == TOO_LONG) return null
        val out = "Voice message (" + (m.text.let { notificationSeconds(it) }?.let { fmt(it) } ?: "") .ifEmpty { "heard silently" } + "): " + text
        done[m.messageId] = out
        if (done.size > 50) done.keys.toList().take(25).forEach { done.remove(it) }
        return out
    }

    private const val TOO_LONG = "\u0000TOO_LONG"

    private fun fmt(sec: Double): String { val s = sec.toInt(); return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0') }

    // ------------------------------------------------------------------ finding the sound file

    private fun dirs(business: Boolean): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return if (business) listOf(
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
            File(root, "WhatsApp Business/Media/WhatsApp Business Voice Notes")
        ) else listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes")
        )
    }

    private fun candidates(business: Boolean, since: Long): List<File> {
        val out = ArrayList<File>()
        for (d in dirs(business)) {
            if (!d.isDirectory) continue
            val top = d.listFiles() ?: continue
            for (f in top) {
                if (f.isFile) out.add(f)
                else if (f.isDirectory) f.listFiles()?.forEach { if (it.isFile) out.add(it) }   // the weekly folders (202643 ...)
            }
        }
        return out.filter {
            val n = it.name.lowercase()
            (n.endsWith(".opus") || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".aac")) &&
                it.lastModified() >= since && it.absolutePath !in claimed
        }.sortedBy { it.lastModified() }   // oldest new file first: voice messages are answered in the order they came
    }

    /** Waits (up to [FILE_WAIT_MS]) until the voice message has been downloaded and is completely written. */
    private suspend fun waitForFile(m: ProcessedMessage): File? {
        val business = m.packageName == WhatsAppKnowledge.WHATSAPP_BUSINESS_PACKAGE
        val since = m.receivedAt - 90_000L
        val end = System.currentTimeMillis() + FILE_WAIT_MS
        var lastSize = -1L; var last: File? = null
        while (true) {
            val f = withContext(Dispatchers.IO) { try { candidates(business, since).firstOrNull() } catch (_: Exception) { null } }
            if (f != null) {
                val size = f.length()
                if (size > 0 && f == last && size == lastSize && System.currentTimeMillis() - f.lastModified() > 800L) return f
                last = f; lastSize = size
            }
            if (System.currentTimeMillis() >= end) return if (f != null && f.length() > 0) f else null
            delay(700)
        }
    }

    // ------------------------------------------------------------------ how long is it

    /** "Voice message (1:23)" in the notification, when WhatsApp shows it. */
    private fun notificationSeconds(text: String): Double? {
        val r = Regex("""\((\d{1,2}):(\d{2})(?::(\d{2}))?\)""").find(text) ?: return null
        val g = r.groupValues
        return if (g[3].isNotEmpty()) (g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt()).toDouble() else (g[1].toInt() * 60 + g[2].toInt()).toDouble()
    }

    /** Exact length of an Ogg/Opus file: the last Ogg page says how many samples (48 kHz) the file holds. null = not an Ogg file. */
    private fun durationSeconds(f: File): Double? = try {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len < 64) return null
            val n = minOf(len, 65_536L).toInt()
            raf.seek(len - n)
            val b = ByteArray(n); raf.readFully(b)
            var i = n - 27
            var res: Double? = null
            while (i >= 0) {
                if (b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte()) {
                    var gp = 0L
                    for (k in 7 downTo 0) gp = (gp shl 8) or (b[i + 6 + k].toLong() and 0xFF)
                    if (gp > 0) res = gp / 48000.0
                    break
                }
                i--
            }
            res
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ Gemini: sound in, words out

    private fun transcribe(c: Context, key: String, bytes: ByteArray): String? {
        val models = listOf(
            Prefs.get(c, Prefs.CHAT_MODEL, Prefs.DEFAULT_CHAT_MODEL).trim().ifBlank { Prefs.DEFAULT_CHAT_MODEL },
            Prefs.DEFAULT_CODE_MODEL
        ).distinct()
        val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        for (model in models) {
            for (low in listOf(true, false)) {
                val r = one(key, model, data, low)
                if (r is Got) return r.text
                if (r is Rejected && low) continue    // the model refused the thinking setting: once more without it
                break                                   // another problem: next model
            }
        }
        return null
    }

    private sealed class Res
    private class Got(val text: String?) : Res()
    private object Rejected : Res()
    private object Failed : Res()

    private fun one(key: String, model: String, b64: String, lowThinking: Boolean): Res = try {
        val gen = JSONObject().put("maxOutputTokens", 2048)
        if (lowThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/ogg").put("data", b64))))))
            .put("generationConfig", gen)
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 400) { resp.body?.close(); return Rejected }
            if (!resp.isSuccessful) { resp.body?.close(); return Failed }
            val j = JSONObject(resp.body?.string().orEmpty())
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            val t = sb.toString().trim()
            when {
                t.isEmpty() || t.equals("NONE", true) || t.startsWith("NONE") && t.length < 8 -> Got(null)
                else -> Got(t.replace(Regex("\\s+"), " ").take(MAX_TRANSCRIPT_CHARS))
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Failed }
}
