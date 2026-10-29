package ai.nisu.assistant

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Voice Changer: the user uploads a voice sample (one or more audio files); ElevenLabs "Instant Voice Cloning" turns it into a
 * voice, and NISU AI speaks with it through the normal ElevenLabs path (Gemini still does the thinking).
 * Several custom voices can be kept, one can be removed (it is also deleted from the ElevenLabs account, which frees the slot),
 * and "default voices" switches back to the built-in Gemini voices at any time.
 * Only use a voice you have the permission of its owner for: the screen asks for this confirmation before anything is uploaded.
 */
object CustomVoices {
    class Item(val id: String, val name: String)

    private const val STORE = "custom_voices_json"
    private const val MAX_FILE = 10L * 1024 * 1024     // ElevenLabs accepts up to ~10 MB per sample file
    private const val MAX_FILES = 5

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    fun list(ctx: Context): List<Item> = try {
        val a = JSONArray(Prefs.get(ctx, STORE, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); if (id.isBlank()) null else Item(id, o.optString("name").ifBlank { "Custom voice" })
        }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, l: List<Item>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        Prefs.put(ctx, STORE, a.toString())
    }

    /** The custom voice that is speaking right now (ElevenLabs engine selected and its id is the chosen voice), else null. */
    fun active(ctx: Context): Item? {
        if (VoiceEngine.saved(ctx) != VoiceEngine.ELEVEN) return null
        val id = Prefs.get(ctx, Prefs.ELEVEN_VOICE, "").trim()
        return list(ctx).firstOrNull { it.id == id }
    }

    /** Speak with this custom voice. False when no ElevenLabs key is saved. */
    fun use(ctx: Context, v: Item): Boolean {
        if (!VoiceEngine.elevenReady(ctx)) return false
        Prefs.put(ctx, Prefs.ELEVEN_VOICE, v.id); Prefs.put(ctx, Prefs.ELEVEN_VOICE_NAME, v.name)
        return VoicePick.apply(ctx, VoiceEngine.ELEVEN)
    }

    /** Back to the built-in voices: the Gemini voice is used again and no custom voice stays selected. */
    fun useDefault(ctx: Context) {
        val cur = Prefs.get(ctx, Prefs.ELEVEN_VOICE, "").trim()
        if (list(ctx).any { it.id == cur }) { Prefs.put(ctx, Prefs.ELEVEN_VOICE, ""); Prefs.put(ctx, Prefs.ELEVEN_VOICE_NAME, "") }
        VoicePick.apply(ctx, VoiceEngine.GEMINI)
    }

    /** Uploads the sample(s) and creates the voice. [done] runs on the main thread: the new voice, or null and a message. */
    fun create(ctx: Context, name: String, uris: List<Uri>, done: (Item?, String) -> Unit) {
        val key = Prefs.get(ctx, Prefs.ELEVEN_KEY, "").trim()
        if (key.isEmpty()) { done(null, "Add your ElevenLabs API key on the API Key page first."); return }
        if (uris.isEmpty()) { done(null, "Choose a voice sample first."); return }
        val app = ctx.applicationContext
        thread(name = "nisu-voice-clone", isDaemon = true) {
            var item: Item? = null
            var msg = ""
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("name", name.ifBlank { "My voice" })
                    .addFormDataPart("remove_background_noise", "true")
                    .addFormDataPart("description", "Created in NISU AI")
                var n = 0
                for (u in uris.take(MAX_FILES)) {
                    var fname = "sample" + (n + 1)
                    var size = -1L
                    app.contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) c.getString(i)?.let { fname = it }
                            val s = c.getColumnIndex(OpenableColumns.SIZE); if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                        }
                    }
                    if (size > MAX_FILE) throw IllegalArgumentException("\"$fname\" is bigger than 10 MB. Use a shorter sample (1-2 minutes is enough).")
                    val bytes = app.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IllegalArgumentException("Could not read \"$fname\".")
                    if (bytes.size > MAX_FILE) throw IllegalArgumentException("\"$fname\" is bigger than 10 MB. Use a shorter sample (1-2 minutes is enough).")
                    val mime = app.contentResolver.getType(u) ?: "audio/mpeg"
                    body.addFormDataPart("files", fname, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                    n++
                }
                val req = Request.Builder().url("https://api.elevenlabs.io/v1/voices/add").header("xi-api-key", key).post(body.build()).build()
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) {
                        val id = JSONObject(text).optString("voice_id")
                        if (id.isBlank()) msg = "ElevenLabs did not return a voice id."
                        else { item = Item(id, name.ifBlank { "My voice" }); msg = "" }
                    } else msg = explain(r.code, text)
                }
            } catch (e: IllegalArgumentException) { msg = e.message ?: "Invalid sample" }
            catch (e: Exception) { msg = "Could not reach ElevenLabs: " + (e.message ?: e.javaClass.simpleName) }
            val made = item
            if (made != null) save(app, list(app) + made)
            main.post { done(made, msg) }
        }
    }

    /** Removes the voice from the list and from the ElevenLabs account. If it was speaking, the default voice is used again. */
    fun remove(ctx: Context, v: Item, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val key = Prefs.get(app, Prefs.ELEVEN_KEY, "").trim()
        thread(name = "nisu-voice-remove", isDaemon = true) {
            var note = ""
            if (key.isNotEmpty()) try {
                val req = Request.Builder().url("https://api.elevenlabs.io/v1/voices/" + java.net.URLEncoder.encode(v.id, "UTF-8"))
                    .header("xi-api-key", key).delete().build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful && r.code != 404) note = "Removed here, but ElevenLabs refused to delete it (" + explain(r.code, r.body?.string().orEmpty()) + "). Delete it on the ElevenLabs website to free the slot."
                }
            } catch (e: Exception) { note = "Removed here, but ElevenLabs could not be reached to delete it. Delete it on the ElevenLabs website to free the slot." }
            main.post {
                val wasActive = active(app)?.id == v.id
                save(app, list(app).filter { it.id != v.id })
                if (wasActive || Prefs.get(app, Prefs.ELEVEN_VOICE, "").trim() == v.id) useDefault(app)
                done(note)
            }
        }
    }

    private fun explain(code: Int, body: String): String {
        val detail = try {
            val j = JSONObject(body); val d = j.opt("detail")
            if (d is JSONObject) d.optString("message") else d?.toString().orEmpty()
        } catch (_: Exception) { body.take(160) }
        return when {
            code == 401 || detail.contains("missing_permissions") || detail.contains("voices_write") ->
                "This API key may not create voices. Turn on 'Voices: Write' for the key in ElevenLabs. ($detail)"
            code == 402 || detail.contains("can_not_use_instant_voice_cloning") || detail.contains("subscription") ->
                "Your ElevenLabs plan does not include voice cloning (or the voice limit is full). Upgrade the plan or remove an old voice. ($detail)"
            code == 422 || code == 400 -> "ElevenLabs could not use this sample: $detail. Use a clean recording of ONE person, 1-2 minutes, no music."
            else -> "ElevenLabs error HTTP $code " + detail.take(160)
        }
    }
}
