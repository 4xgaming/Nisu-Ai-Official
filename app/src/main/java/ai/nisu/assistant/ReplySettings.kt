package ai.nisu.assistant

import android.content.Context

/**
 * The ONE place that knows the user's choice for "Reply Delay" and "Reply Limit".
 *
 * - Stored in the existing app preferences (Prefs), so the choice survives an app / process restart and a reboot.
 * - [init] is called from [NishuApp.onCreate] in every process start (also when Android starts a service at boot), so the
 *   in-memory copy used by the engine is loaded before the first message can arrive.
 * - A missing, unknown or damaged stored value always falls back to the default (Normal 30 s / 20 replies).
 * - The engine reads [delayMs] / [maxReplies] each time it needs them (never a copy of its own), so a change in Settings
 *   applies to the next message / the next send without a restart. A wait that is already running keeps its old deadline.
 */
/**
 * v2.31: the reply policy asked for by the owner. The only thing that is never answered is a GROUP or a CHANNEL (WhatsApp and Messenger).
 * Every private / direct chat, saved name or plain number, gets an answer to every message: no 30 s wait, no "owner replied / uncertain"
 * hold, no privacy-word block, no reply limit. People who write at the same time are answered one after the other, in the order the
 * messages came. The switches the owner uses himself (master switch, Pause, Stop, Block a person) still win.
 */
object ReplyPolicy {
    const val OPEN = true
}

object ReplySettings {
    const val KEY_DELAY = "reply_delay_mode"
    const val KEY_LIMIT = "reply_limit_mode"
    const val KEY_MODE = "reply_style_mode"

    /**
     * v2.74: HOW automatic replies on WhatsApp and Messenger talk.
     *  OWNER = the original behaviour: NISU AI answers as Raju's busy-time assistant (fixed introduction, short, careful replies).
     *  NISU_AI = "Default NISU AI": NISU AI talks to everybody the way people talk to ChatGPT (open discussion, ideas, advice, sharing
     *  thoughts, help with making things), without the "Raju is unavailable" introduction, with fuller answers.
     */
    enum class Mode(val title: String, val desc: String) {
        OWNER("Raju's assistant", "Replies on Raju's behalf: short, careful, with the first-message introduction. (Default)"),
        NISU_AI("Default NISU AI", "Talks with everyone like an AI chat assistant: open discussion, ideas, advice, sharing, help with making things. No \"Raju is unavailable\" introduction."),
        /** v2.84: the first message says NISU AI talks on Raju's behalf because he is not here; after that it talks like a friend (any topic, stories, songs, ideas), in the sender's language. */
        FRIEND("Friend conversation", "First message: \"I am talking on Raju's behalf, he is not here right now\". After that NISU AI talks like a friend: casual chat, discussion, stories, songs, ideas. Replies in the language the person writes (Bengali, Hindi, English, any language).")
    }
    val DEFAULT_MODE = Mode.OWNER
    @Volatile private var mode: Mode = DEFAULT_MODE

    enum class Delay(val seconds: Int, val title: String, val label: String) {
        INSTANT(0, "Instant", "Instant (0s)"),
        FAST(15, "Fast", "15 Seconds"),
        NORMAL(30, "Normal", "30 Seconds");

        val ms: Long get() = seconds * 1000L
    }

    enum class Limit(val max: Int, val title: String, val label: String) {
        UNLIMITED(Int.MAX_VALUE, "Unlimited", "Unlimited"),
        R20(20, "20 Replies", "20 Replies"),
        R50(50, "50 Replies", "50 Replies");

        val unlimited: Boolean get() = max == Int.MAX_VALUE
    }

    val DEFAULT_DELAY = Delay.INSTANT
    val DEFAULT_LIMIT = Limit.UNLIMITED

    @Volatile private var delay: Delay = DEFAULT_DELAY
    @Volatile private var limit: Limit = DEFAULT_LIMIT

    /** Loads the stored choice. Safe to call again. */
    fun init(c: Context) {
        // v2.31: one-time move to "Instant" + "Unlimited" (an older stored Normal 30 s / 20 replies would still hold replies back)
        if (!Prefs.getBool(c, "reply_policy_v231", false)) {
            Prefs.put(c, KEY_DELAY, DEFAULT_DELAY.name); Prefs.put(c, KEY_LIMIT, DEFAULT_LIMIT.name); Prefs.putBool(c, "reply_policy_v231", true)
            val app = c.applicationContext
            Thread { try { TaskManager.openStore(app)?.let { it.lifecycleClearLimitClosures(); ConversationLifecycle.publish(it); TaskManager.lifecycleChanged() } } catch (_: Throwable) {} }.start()
        }
        delay = readDelay(c)
        limit = readLimit(c)
        mode = Mode.values().firstOrNull { it.name == Prefs.get(c, KEY_MODE, DEFAULT_MODE.name) } ?: DEFAULT_MODE
    }

    private fun readDelay(c: Context): Delay {
        val s = Prefs.get(c, KEY_DELAY, DEFAULT_DELAY.name)
        return Delay.values().firstOrNull { it.name == s } ?: DEFAULT_DELAY
    }

    private fun readLimit(c: Context): Limit {
        val s = Prefs.get(c, KEY_LIMIT, DEFAULT_LIMIT.name)
        return Limit.values().firstOrNull { it.name == s } ?: DEFAULT_LIMIT
    }

    fun mode(): Mode = mode
    /** true = the "Default NISU AI" conversation style is selected. Read by the reply writer for every reply. */
    fun chatMode(): Boolean = mode == Mode.NISU_AI
    /** v2.84: true = the "Friend conversation" style is selected. */
    fun friendMode(): Boolean = mode == Mode.FRIEND
    /** v2.84: true in both open-talk styles (Default NISU AI and Friend conversation): longer replies, longer chat memory, no fixed introduction text. */
    fun openTalk(): Boolean = mode == Mode.NISU_AI || mode == Mode.FRIEND
    fun setMode(c: Context, m: Mode) { Prefs.put(c, KEY_MODE, m.name); mode = m }

    fun delay(): Delay = delay
    fun limit(): Limit = limit

    /** How long the owner-check wait lasts before an automatic reply may be prepared and sent. 0 = no wait. */
    fun delayMs(): Long = delay.ms

    /** Automatic replies allowed per person per app. [Int.MAX_VALUE] = unlimited (the "used >= max" checks can never be true). */
    fun maxReplies(): Int = limit.max

    fun setDelay(c: Context, d: Delay) {
        Prefs.put(c, KEY_DELAY, d.name)
        delay = d
    }

    fun setLimit(c: Context, l: Limit) {
        Prefs.put(c, KEY_LIMIT, l.name)
        limit = l
        // v2.21: Unlimited opens again every conversation that was closed by the reply limit. The counters are kept, so choosing a limit
        // again limits those people again on their next message.
        val app = c.applicationContext
        Thread {
            try {
                val s = TaskManager.openStore(app)
                if (s != null) {
                    if (l.unlimited) s.lifecycleClearLimitClosures() else s.lifecycleClearLimitClosuresBelow(l.max)   // a higher limit opens people who are below it
                    ConversationLifecycle.publish(s); TaskManager.lifecycleChanged()
                }
            } catch (_: Throwable) {}
        }.start()
    }
}
