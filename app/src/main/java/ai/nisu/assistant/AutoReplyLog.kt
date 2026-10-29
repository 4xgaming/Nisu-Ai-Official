package ai.nisu.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Auto Reply log (v2.0).
 *
 * WHY: until now every refusal in the automatic reply pipeline (owner check, lifecycle, limit, sender checks, AI failure) only went to
 * logcat, which nobody can read on the phone. The result was "nothing happens after 30 seconds" with no way to see at which step.
 * This is a tiny file log of the SAME short codes the pipeline already produced. It is not a new engine and it changes no decision.
 *
 * What it holds: time | stage | code. Stages are the steps of the pipeline: brain, owner, queue, agent, consider, send.
 * What it never holds: message text, sender names, chat names, reply text, API keys. Codes are cut down to letters, digits and _ . : -
 * The file is private app storage, capped at [MAX_BYTES] (older lines are dropped), and can be cleared from the Automation screen.
 */
object AutoReplyLog {
    private const val TAG = "NisuLog"
    private const val FILE = "auto_reply_log.txt"
    private const val MAX_BYTES = 40_000
    private val UNSAFE = Regex("[^A-Za-z0-9_.:-]")
    private val lock = Any()
    @Volatile private var appRef: Context? = null

    /** Remembers the application context so any part of the pipeline can write without passing it around. Cheap, repeatable. */
    fun init(ctx: Context) { appRef = ctx.applicationContext }

    /** One line. [error] = a step that stopped the reply or failed (shown in red on the screen). Never throws. */
    fun add(stage: String, code: String, error: Boolean = false) {
        try {
            val app = appRef ?: return
            val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " | " + (if (error) "ERR " else "ok  ") +
                UNSAFE.replace(stage, "_").take(16) + " | " + UNSAFE.replace(code, "_").take(80)
            Log.i(TAG, line)
            synchronized(lock) {
                val f = File(app.filesDir, FILE)
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) {
                    val keep = f.readLines().takeLast(250)
                    f.writeText(keep.joinToString("\n") + "\n")
                }
            }
        } catch (_: Throwable) {}
    }

    /** The newest [max] lines, newest first. */
    fun read(ctx: Context, max: Int = 40): List<String> = try {
        synchronized(lock) {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.exists()) emptyList() else f.readLines().takeLast(max).asReversed()
        }
    } catch (_: Throwable) { emptyList() }

    fun clear(ctx: Context) {
        try { synchronized(lock) { File(ctx.applicationContext.filesDir, FILE).delete() } } catch (_: Throwable) {}
    }
}
