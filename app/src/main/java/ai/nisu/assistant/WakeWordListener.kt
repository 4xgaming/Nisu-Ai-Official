package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * v2.21: the ONLY thing that listens to the microphone while NISU AI sleeps.
 *
 * It uses Android's SpeechRecognizer (on-device where the phone offers it, otherwise the system's speech service).
 * Nothing is sent to Gemini. Recognised text is only compared with the strict phrase list in [WakeCommandParser];
 * it is never logged, stored or forwarded. Must be created / started / stopped on the main thread.
 *
 * The service guarantees that AudioIO (the Gemini microphone) is stopped before [start] and that [stop] has returned
 * before AudioIO starts again, so two microphone owners never exist at the same time.
 */
class WakeWordListener(
    private val ctx: Context,
    /** Called on the main thread with the recogniser alternatives of one FINAL result and their confidences (may be null). */
    private val onResult: (List<String>, FloatArray?) -> Unit,
    /** Called on the main thread with a short human-readable problem (permission, no recogniser). [fatal] = listening cannot continue. */
    private val onProblem: (String, Boolean) -> Unit
) : WakeSource {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    @Volatile private var active = false
    private var token = 0            // callbacks of a destroyed recogniser carry an old token and are ignored
    private var errorStreak = 0
    private var onDeviceOk = true     // false after the on-device recogniser said it has no model for the language
    private val restartTask = Runnable { if (active) begin() }

    val isRunning: Boolean get() = active

    override fun start() {
        if (active) return
        active = true
        errorStreak = 0
        begin()
    }

    override fun stop() {
        active = false
        token++
        main.removeCallbacks(restartTask)
        destroy()
    }

    private fun destroy() {
        val r = rec ?: return
        rec = null
        try { r.setRecognitionListener(null) } catch (_: Exception) {}
        try { r.cancel() } catch (_: Exception) {}
        try { r.destroy() } catch (_: Exception) {}
    }

    private fun create(): SpeechRecognizer? {
        if (onDeviceOk && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            try { return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) } catch (_: Exception) {}
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        return try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null }
    }

    private fun begin() {
        if (!active) return
        destroy()
        val r = create()
        if (r == null) {
            active = false
            onProblem("No speech recogniser on this phone. Install / enable Google speech services.", true)
            return
        }
        rec = r
        val my = ++token
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { if (my == token) errorStreak = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}   // finals only: a partial may still grow into something else
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                if (my != token || !active) return
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                // Re-arm first; if the result wakes the assistant, the service calls stop() which cancels this restart.
                schedule(150)
                if (list.isNotEmpty()) onResult(list, conf)
            }
            override fun onError(error: Int) {
                if (my != token || !active) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedule(150)   // silence: normal
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        active = false
                        onProblem("Microphone permission is missing.", true)
                    }
                    12, 13 -> { onDeviceOk = false; schedule(300) }   // language not supported / unavailable on-device: use the system recogniser instead
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> schedule(backoff(1000))
                    else -> schedule(backoff(1500))   // network / server / audio errors: retry slowly, never spin
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, WakeCommandParser.MAX_ALTERNATIVES)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            i.putStringArrayListExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayListOf("en-US"))   // string key: the Kotlin constant is not visible to every SDK stub
            i.putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList(WakeCommandParser.wakeBiasingStrings))
        }
        try { r.startListening(i) } catch (e: Exception) { schedule(backoff(1500)) }
    }

    private fun backoff(base: Long): Long {
        errorStreak++
        return minOf(base * minOf(errorStreak, 5), 8000L)
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(restartTask)
        if (active) main.postDelayed(restartTask, delayMs)
    }
}
