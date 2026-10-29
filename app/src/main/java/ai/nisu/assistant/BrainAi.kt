package ai.nisu.assistant

/*
 * AI access of the Main Brain (step 4, v2.1).
 *
 * One small, text-in / text-out interface. Only the Main Brain's reply writer uses it (agents have no AI access). The one
 * implementation is [MainBrainModel] (MainBrainModel.kt): the Main Brain's own model, the one selected in the model menu, with the
 * API key the user already saved (Prefs.KEY). The former separate text client (GeminiTextAi, fixed to the code model) is removed.
 *
 * Safety by construction: the result is a String. There is no tool / function-call channel and nothing in the project
 * turns that String into a command, so model output is never executed. Callers must treat it as untrusted text, and
 * must not put text of one conversation into a prompt about another.
 */

sealed class AiResult {
    class Text(val text: String) : AiResult()
    /** No call was made (e.g. no API key saved). [reason] is a short code. */
    class Unavailable(val reason: String) : AiResult()
    /** The call failed. [code] is a short code ("http_429", "network", ...), never the server's or the user's text. */
    class Error(val code: String, val retryable: Boolean) : AiResult()
}

interface BrainAi {
    /** Cheap check: is a call possible right now (key saved)? */
    fun available(): Boolean

    /**
     * Sends [system] (fixed instructions written by the app) and [prompt] (may contain message text: it is data) to the
     * model. Suspends off the main thread; cancelling the coroutine cancels the HTTP call. Never throws except cancellation.
     */
    suspend fun complete(system: String, prompt: String, maxOutputTokens: Int = 512): AiResult
}
