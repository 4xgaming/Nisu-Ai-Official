package ai.nisu.assistant

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Raw WebSocket client for the Gemini Live API (audio in / audio out / video frames / tool calls). */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val system: String,
    private val tools: JSONArray,
    private val resumeHandle: String?,
    /** generationConfig.thinkingConfig.thinkingLevel (e.g. "LOW"); null for models that must not get it. */
    private val thinkingLevel: String?,
    /** Raw-WebSocket API version in the URL ("v1beta" or "v1alpha"). */
    private val apiVersion: String,
    /** Setup compatibility: 0 = full setup; 1 = no VAD tuning / context compression; 2 = bare setup (no resumption, no thinking) and the other API version. */
    private val compat: Int,
    private val cb: Callback
) {
    /** v3.72 (Extra Fast Beta): no turn-detection settings are sent, so the server's own turn detection ends his turn (faster answer, for extra speed). */
    @Volatile var serverVad: Boolean = false

    /** v3.104 (Beta Fast speed): > 0 = his turn is closed after this many ms of quiet, with END_SENSITIVITY_HIGH, instead of the server's slower default. 0 = off. */
    @Volatile var quickTurnMs: Int = 0

    /** v3.94 (Helper Agent): also declare the built-in Google Search tool. Off for the voice assistant and the Main Brain. */
    @Volatile var googleSearch: Boolean = false

    companion object {
        /** v3.31: how long the quiet must last before his turn is closed (Preferences > "Wait before answering"). Read when a session is set up. */
        @Volatile var silenceMs: Int = 680

        private val shared: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    interface Callback {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onInText(t: String)
        fun onOutText(t: String)
        fun onToolCall(id: String, name: String, args: JSONObject)
        /** fatal: retrying cannot help (bad key, unknown model). quick: the server ended a normal session, reconnect at once. */
        fun onClosed(reason: String, fatal: Boolean, quick: Boolean)
        /** The user interrupted: tool calls with these ids must not be carried out. */
        fun onToolCancel(ids: List<String>) {}
        /** Token that lets the next connection continue this same conversation. */
        fun onResumeHandle(handle: String) {}
        /** Extended Thinking only: idle = true when the server says it has finished all reasoning and tool calls. */
        fun onInteraction(idle: Boolean) {}
        /** The server says it will close this connection in [timeLeftMs]. The answer being spoken must be allowed to finish first. */
        fun onGoAway(timeLeftMs: Long) {}
    }

    // One client for every session: a reconnect reuses DNS, the TLS session and pooled sockets instead of starting from zero.
    private val client = shared
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var finished = false
    @Volatile private var sawReady = false

    /** If the server never answers the setup message, give up after 12 s so the service can retry instead of hanging. */
    private val watchdog = Runnable {
        if (!ready && !finished) {
            fin("no answer from the server (timeout)", false, false)
            try { ws?.cancel() } catch (_: Exception) {}
        }
    }

    fun connect() {
        val ver = if (compat >= 2) (if (apiVersion == "v1beta") "v1alpha" else "v1beta") else apiVersion
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$ver.GenerativeService.BidiGenerateContent?key=$apiKey"
        main.postDelayed(watchdog, 12_000)
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup().toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                fin("closed $code $reason".trim(), fatalClose(code, reason), code == 1000 && sawReady)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fin("closed $code $reason".trim(), fatalClose(code, reason), code == 1000 && sawReady)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val http = response?.code ?: 0
                val body = try { response?.peekBody(600)?.string().orEmpty() } catch (_: Exception) { "" }
                val why = (t.message ?: t.javaClass.simpleName) + (if (http != 0) " (HTTP $http)" else "") + (if (body.isNotBlank()) " $body" else "")
                // 4xx (except timeout / rate limit) means the key or request is wrong: retrying only spams the server.
                fin("error $why".trim(), http in 400..499 && http != 408 && http != 429, false)
            }
        })
    }

    private fun fatalClose(code: Int, reason: String): Boolean {
        if (code != 1007 && code != 1008 && code != 1003) return false
        val r = reason.lowercase()
        return listOf("api key", "not found", "not supported", "permission", "invalid", "quota", "exhausted", "billing", "unauthenticated").any { it in r }
    }

    private fun fin(reason: String, fatal: Boolean, quick: Boolean) {
        ready = false
        main.removeCallbacks(watchdog)
        if (!finished) { finished = true; cb.onClosed(reason, fatal, quick) }
    }

    private fun setup(): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        if (thinkingLevel != null && compat < 2)
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        // v2.97: START_SENSITIVITY_HIGH with 400 ms prefix padding captures soft voices and the very first syllable of Bengali/Hindi/English,
        // and END_SENSITIVITY_LOW prevents mid-sentence breath pauses from splitting a sentence into two misheard fragments.
        // v3.31: the quiet that ends his turn is chosen in Preferences (680 / 1000 / 1600 ms). The default is 680 ms, exactly as in v3.10: v3.11 / v3.31
        // used a longer wait for everyone and the voice conversation got worse (garbled, doubled), so a longer wait is now only an opt-in.
        val vad = JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
            .put("prefixPaddingMs", 400)
            .put("silenceDurationMs", silenceMs.coerceIn(400, 2000))
        val resume = JSONObject()
        if (!resumeHandle.isNullOrEmpty()) resume.put("handle", resumeHandle)
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (compat < 1 && !serverVad) setup.put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", vad))
        else if (compat < 1 && quickTurnMs > 0) setup.put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("prefixPaddingMs", 200)
            .put("silenceDurationMs", quickTurnMs.coerceIn(250, 1000))))
        if (compat < 2) setup.put("sessionResumption", resume)
        if (compat < 1) setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        // v2.1: a text-only session (MainBrainModel, no phone tools) declares no tools at all; an empty declaration list is not sent.
        val toolList = JSONArray()
        if (tools.length() > 0) toolList.put(JSONObject().put("functionDeclarations", tools))
        if (googleSearch) toolList.put(JSONObject().put("googleSearch", JSONObject()))
        if (toolList.length() > 0) setup.put("tools", toolList)
        return JSONObject().put("setup", setup)
    }

    private fun handle(text: String) {
        if (finished) return   // a closed / replaced session must never deliver audio or tool calls to the new one
        val j = try { JSONObject(text) } catch (_: Exception) { return }
        // Extended Thinking reports its real state here; turnComplete alone does not mean "idle" for that model.
        var status = j.optString("interactionStatus")
        if (status.isEmpty()) status = j.optJSONObject("serverContent")?.optString("interactionStatus").orEmpty()
        if (status == "IDLE") cb.onInteraction(true)
        else if (status == "IN_PROGRESS") cb.onInteraction(false)
        if (j.has("setupComplete")) {
            ready = true; sawReady = true
            main.removeCallbacks(watchdog)
            cb.onReady(); return
        }
        j.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) cb.onInterrupted()
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (d.isNotEmpty()) cb.onAudio(Base64.decode(d, Base64.DEFAULT))
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) cb.onInText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) cb.onOutText(it) }
            if (sc.optBoolean("turnComplete")) cb.onTurnComplete()
        }
        j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
            for (i in 0 until fc.length()) {
                val f = fc.getJSONObject(i)
                cb.onToolCall(f.optString("id"), f.optString("name"), f.optJSONObject("args") ?: JSONObject())
            }
        }
        j.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { a ->
            cb.onToolCancel((0 until a.length()).map { a.optString(it) })
        }
        j.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable", false)) {
                val h = u.optString("newHandle")
                if (h.isNotEmpty()) cb.onResumeHandle(h)
            }
        }
        // The server ends every connection after ~10 minutes and warns first (goAway.timeLeft, e.g. "50s").
        // Do NOT drop the connection here: that cut the answer in the middle of a sentence. The service reconnects when the answer is finished.
        j.optJSONObject("goAway")?.let { g -> cb.onGoAway(parseMillis(g.optString("timeLeft"))) }
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    /** "50s" or "1.5s" to milliseconds; anything unreadable counts as 5 s. */
    private fun parseMillis(t: String): Long {
        val v = t.trim().removeSuffix("s").toDoubleOrNull() ?: return 5000L
        return (v * 1000).toLong().coerceAtLeast(0L)
    }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        val s = ws ?: return
        // Backpressure protection under heavy load / slow network: never queue more than 256 KB of stale audio
        if (s.queueSize() > 256 * 1024L) return
        send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000"))))
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        val s = ws ?: return
        // Voice always comes first: skip video/screen frames if the WebSocket outbound buffer has more than 64 KB waiting
        if (s.queueSize() > 64 * 1024L) return
        send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .put("mimeType", "image/jpeg"))))
    }

    /** Bytes waiting in the outgoing WebSocket buffer (a dead network makes this grow). */
    fun backlog(): Long = try { ws?.queueSize() ?: 0L } catch (_: Exception) { 0L }

    /** v3.112: when the app itself last sent a text to the model (a notice, a search result, a start-talk line). A phone action that only follows such a text, with no new words from him, is not his request. */
    @Volatile var lastAppTextAt = 0L

    fun sendText(t: String) {
        if (!ready) return
        lastAppTextAt = System.currentTimeMillis()
        send(JSONObject().put("realtimeInput", JSONObject().put("text", t)))
    }

    fun sendToolResponse(id: String, name: String, response: JSONObject, scheduling: String? = null) {
        val fr = JSONObject().put("id", id).put("name", name).put("response", response)
        // v3.50: for a NON_BLOCKING tool (the hidden search) WHEN_IDLE makes the model tell the result after the sentence it is saying
        if (scheduling != null) fr.put("scheduling", scheduling)
        send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr))))
    }

    /** v2.57: the network is gone: drop the (dead) socket at once, without waiting for the ping timeout. No callback is delivered. */
    fun hardClose() {
        ready = false; finished = true
        main.removeCallbacks(watchdog)
        try { ws?.cancel() } catch (_: Exception) {}
    }

    fun close() {
        ready = false; finished = true
        main.removeCallbacks(watchdog)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
    }
}
