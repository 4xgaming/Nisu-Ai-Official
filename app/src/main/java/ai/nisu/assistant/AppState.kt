package ai.nisu.assistant

import kotlinx.coroutines.flow.MutableStateFlow

object AppState {
    val status = MutableStateFlow("Ready")
    val emotion = MutableStateFlow("calm")
    val running = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    /** v2.32: loudness (0..1) of the AI's own voice being played (not the microphone). Used for the character's lip-sync. */
    val outLevel = MutableStateFlow(0f)
    /** uptime millis of the last played voice chunk; the AI counts as "speaking" while this is fresh. */
    @Volatile var outAt = 0L
    /** 0 while the AI is silent, else the loudness of her voice right now (0..1). */
    fun speechLevel(): Float = if (android.os.SystemClock.uptimeMillis() - outAt < 220L) outLevel.value else 0f
    /** True while the model is working on an answer (user finished talking, tool running, or Extended Thinking) and has not started speaking yet. */
    val thinking = MutableStateFlow(false)
    /** v2.58: real-time screen share is ON (frames of the screen go to Gemini). Drawer toggle + Preferences switch control it. */
    val screenShare = MutableStateFlow(false)
    /** v2.58: game mode = faster screen frames (about 3 per second) while NISU AI plays a game with multi-touch. */
    val gameMode = MutableStateFlow(false)
    /** v2.59: Game Auto Play is running (plays Free Fire match after match). */
    val gameAuto = MutableStateFlow(false)
    /** v2.62: NISU AI looks through the phone camera (frames go to Gemini like the screen share). */
    val cameraOn = MutableStateFlow(false)
    val cameraFacing = MutableStateFlow("back")
    val gameStage = MutableStateFlow("")
    val gameMatches = MutableStateFlow(0)
    val gameNote = MutableStateFlow("")
    val gameTouch = MutableStateFlow("")
    /** The switch was turned on while the voice assistant was not running yet: the service starts the game when it is up. */
    @Volatile var gameWanted = false
    val log = MutableStateFlow<List<String>>(emptyList())
    /** Id of the Live model the service is using (or switching to). Empty until a session has started. */
    val model = MutableStateFlow("")
    /** True only while a Live session is connected and ready (drives the ONLINE control). */
    val online = MutableStateFlow(false)
    /** True while the service is swapping to another model (the selector is locked so switches cannot overlap). */
    val switching = MutableStateFlow(false)
    /** Voice engine in use: "gemini" or "eleven" (see VoiceEngine). */
    val engine = MutableStateFlow("gemini")
    /** v2.21: mirror of the service's ModeController (SLEEP = only the wake-word listener runs; ACTIVE = normal assistant). */
    val mode = MutableStateFlow(AssistantMode.ACTIVE)
    fun add(line: String) { log.value = (log.value + line).takeLast(40) }
}
