package ai.nisu.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/*
 * The Main Brain's own model, used silently for automatic replies (v2.1).
 *
 * "The Main Brain" of NISU AI is the model chosen in the model menu (Prefs.MODEL: 3.8 Live, 3.8 Thinking, 3.1 Flash Live, or a custom
 * Live model name). This class opens ONE short session with exactly that model, through the same [GeminiLive] client and the same API key
 * the voice assistant uses, sends one text turn and returns what the model said as text. Nothing is hardcoded: the model is read from
 * [Prefs.liveModel] on every call, so a model switch in the menu is used from the next reply on.
 *
 * Silent by construction:
 * - the audio the Live model produces is never handed to [AudioIO] or anything else (onAudio below drops it); the text comes from the
 *   model's output transcription of that same answer;
 * - it does not touch [AppState] (no status text, no orb, no "Speaking"), does not start [AssistantService] and does not need the
 *   microphone;
 * - no tools are declared, so the model cannot open apps, tap or run anything: its output is only ever text.
 * It is an ONLINE model: without internet or an API key it returns an error code and no text is invented.
 *
 * It replaces the former separate text client (GeminiTextAi, fixed to the code model; removed) for reply writing.
 * One session per reply, closed right after the turn (or after [TURN_TIMEOUT_MS]).
 */
class MainBrainModel(private val appContext: Context) : BrainAi {

    companion object {
        /** Whole turn (connect + answer). Below the writer's own 75 s limit. */
        const val TURN_TIMEOUT_MS = 60_000L
    }

    private fun key(): String = Prefs.get(appContext, Prefs.KEY, "").trim()

    override fun available(): Boolean = key().isNotEmpty()

    /** [maxOutputTokens] is not used by the Live session (it has no such field); the writer cuts the text to its own length limit. */
    override suspend fun complete(system: String, prompt: String, maxOutputTokens: Int): AiResult {
        if (prompt.isBlank()) return AiResult.Unavailable("empty_prompt")
        val apiKey = key()
        if (apiKey.isEmpty()) return AiResult.Unavailable("no_api_key")
        val model = Prefs.liveModel(appContext)   // the CURRENT Main Brain model, read now
        val rules = LiveModels.rules(model)
        return withTimeoutOrNull(TURN_TIMEOUT_MS) { oneTurn(apiKey, model, rules, system, prompt) } ?: AiResult.Error("timeout", true)
    }

    private suspend fun oneTurn(apiKey: String, model: String, rules: LiveModels.Model, system: String, prompt: String): AiResult =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val busy = AtomicBoolean(false)   // Extended Thinking: the server may still be reasoning after a turn ended
            val text = StringBuilder()
            var live: GeminiLive? = null

            fun result(): AiResult {
                val t = synchronized(text) { text.toString().trim() }
                return if (t.isEmpty()) AiResult.Error("blocked_or_empty", false) else AiResult.Text(t)
            }
            fun finish(r: AiResult) {
                if (!done.compareAndSet(false, true)) return
                try { live?.close() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(r)
            }

            val cb = object : GeminiLive.Callback {
                override fun onReady() {
                    try { live?.sendText(prompt) } catch (_: Throwable) { finish(AiResult.Error("live_send_failed", true)) }
                }
                override fun onAudio(pcm: ByteArray) { /* silent: the sound is dropped, never played */ }
                override fun onInterrupted() {}
                override fun onTurnComplete() { if (!busy.get()) finish(result()) }
                override fun onInText(t: String) {}
                override fun onOutText(t: String) { synchronized(text) { text.append(t) } }
                override fun onToolCall(id: String, name: String, args: JSONObject) { /* no tools were declared; anything else is ignored */ }
                override fun onClosed(reason: String, fatal: Boolean, quick: Boolean) {
                    // Only a short code leaves this class: the server's wording is not passed on.
                    // A server that closes after it already sent the answer must not lose that answer.
                    val got = synchronized(text) { text.toString().trim() }
                    if (!fatal && got.isNotEmpty()) { finish(AiResult.Text(got)); return }
                    // Diagnostic: the numeric close code only ("closed 1011 ..." -> 1011), never the server's wording.
                    val closeCode = Regex("^closed (\\d{3,4})").find(reason)?.groupValues?.get(1) ?: "none"
                    AutoReplyLog.add("ai", "live_closed_code_" + closeCode + (if (fatal) "_fatal" else "") + (if (quick) "_quick" else ""), true)
                    finish(AiResult.Error(if (fatal) "live_rejected" else "live_closed", !fatal))
                }
                override fun onInteraction(idle: Boolean) {
                    busy.set(!idle)
                    if (idle && synchronized(text) { text.isNotBlank() }) finish(result())
                }
            }

            live = GeminiLive(apiKey, model, Voice.saved(appContext), system, JSONArray(), null, rules.thinkingLevel, rules.apiVersion, 0, cb)
            cont.invokeOnCancellation { done.set(true); try { live?.close() } catch (_: Throwable) {} }
            try { live?.connect() } catch (_: Throwable) { finish(AiResult.Error("live_connect_failed", true)) }
        }
}
