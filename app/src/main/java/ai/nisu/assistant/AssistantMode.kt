package ai.nisu.assistant

/** v2.21: the two modes of the assistant. */
enum class AssistantMode { SLEEP, ACTIVE }

/**
 * The ONE authoritative mode holder, shared by the service, the audio callbacks and the Gemini callbacks.
 * Pure Kotlin (no Android classes) so every transition is unit-tested on the JVM.
 *
 * [epoch] increases on every real transition. Anything asynchronous (a Gemini reply, a queued sound, a tool result, a
 * delayed callback) captures the epoch when it starts and must check [isCurrent] before it speaks, acts or wakes anything.
 */
class ModeController(initial: AssistantMode = AssistantMode.ACTIVE, private val onChange: ((AssistantMode, Int) -> Unit)? = null) {

    /** What the caller must do with a recognised utterance. */
    enum class Decision {
        /** SLEEP -> ACTIVE happened: start the assistant. */
        WAKE,
        /** ACTIVE -> SLEEP happened: stop everything, discard the utterance. */
        SLEEP,
        /** ACTIVE and not a sleep command: hand it to the assistant as usual. */
        FORWARD,
        /** SLEEP and not a wake phrase (or a sleep command while already asleep): drop it, no response. */
        DISCARD
    }

    @Volatile var mode: AssistantMode = initial
        private set
    @Volatile var epoch: Int = 0
        private set

    val isActive: Boolean get() = mode == AssistantMode.ACTIVE
    val isSleeping: Boolean get() = mode == AssistantMode.SLEEP

    fun isCurrent(e: Int): Boolean = e == epoch && isActive

    /** Decides what to do with one recognised utterance, and performs the transition when there is one. */
    @Synchronized
    fun onTranscript(text: String): Decision = when (mode) {
        AssistantMode.SLEEP ->
            if (WakeCommandParser.isWake(text)) { set(AssistantMode.ACTIVE); Decision.WAKE } else Decision.DISCARD
        AssistantMode.ACTIVE ->
            if (WakeCommandParser.isSleep(text)) { set(AssistantMode.SLEEP); Decision.SLEEP } else Decision.FORWARD
    }

    /** Same as [onTranscript] for a recogniser that returns several alternatives (wake path only; used while sleeping). */
    @Synchronized
    fun onWakeAlternatives(alternatives: List<String>, confidences: FloatArray? = null): Decision {
        if (mode != AssistantMode.SLEEP) return Decision.DISCARD
        if (WakeCommandParser.findWake(alternatives, confidences)) { set(AssistantMode.ACTIVE); return Decision.WAKE }
        return Decision.DISCARD
    }

    /** Non-speech transitions (the user taps start / stop, boot start). Returns true if the mode actually changed. */
    @Synchronized
    fun force(m: AssistantMode): Boolean {
        if (mode == m) return false
        set(m); return true
    }

    private fun set(m: AssistantMode) {
        mode = m
        epoch++
        onChange?.invoke(m, epoch)
    }
}
