package ai.nisu.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v3.127: JARVIS MODE SONG. The song "Headlights" is inside the app (res/raw/headlights.mp3), so it plays with no internet at all.
 *
 *  - Only in Jarvis mode and only when he clearly says "Jarvis, headlight play koro" (the model calls jarvis_song action play). No other trigger.
 *  - "off koro / stop / bondho koro" (jarvis_song action stop), or the small glass OFF button in the bottom right corner of the screen, stops it. The button
 *    is shown only while the song plays and disappears when it stops (also when the song ends by itself).
 *  - Leaving Jarvis mode (any other mode) stops the song within a second; stopping NISU AI completely stops it too.
 *  - Losing the internet does not stop it (the voice session may drop, the song and its OFF button stay).
 * For personal use inside his own app; the song is not shared anywhere.
 */
object JarvisSong {
    const val NAME = "jarvis_song"
    val playing = MutableStateFlow(false)

    @Volatile private var app: Context? = null
    @Volatile private var mp: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** No dollar signs: it is a const. Added to the prompt in Jarvis mode only. */
    const val NOTE = """
SONG (Jarvis mode only, jarvis_song): the phone has one built-in song, Headlights. ONLY when he clearly says to play it (for example "Jarvis, headlight play koro", "headlights chalao", "headlight baja") call jarvis_song with action play, say ONE very short line (for example "Playing Headlights, Sir") and then stay quiet. Never play it for any other reason and never offer another song. While it plays, the music and the singing you hear are background: do not answer them, do not comment on them and never repeat the words; react only to his clear command. When he says off, stop or bondho (the song) call jarvis_song with action stop and say ONE very short line. If he asks for a different song, say plainly that only Headlights is built in.
"""

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val l = CountDownLatch(1)
        main.post { try { block() } finally { l.countDown() } }
        l.await(3, TimeUnit.SECONDS)
    }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        app = ctx.applicationContext
        return when (a.optString("action", "").trim().lowercase()) {
            "play", "start" -> play(ctx)
            "stop", "off" -> stop()
            else -> err("Say action play or stop.")
        }
    }

    fun play(ctx: Context): JSONObject {
        if (PersonaMode.saved(ctx) != PersonaMode.JARVIS) return err("The song only plays in Jarvis mode. Tell him plainly.")
        app = ctx.applicationContext
        var ok = false
        var msg = ""
        onMain {
            synchronized(lock) {
                try {
                    if (mp != null && playing.value) { ok = true; return@synchronized }
                    release()
                    val p = MediaPlayer()
                    val afd = ctx.resources.openRawResourceFd(R.raw.headlights)
                    try { p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) } finally { afd.close() }
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    p.setVolume(0.8f, 0.8f)
                    p.setOnCompletionListener { stop() }
                    p.setOnErrorListener { _, _, _ -> stop(); true }
                    p.prepare()
                    p.start()
                    mp = p
                    playing.value = true
                    main.removeCallbacks(watch); main.postDelayed(watch, 700)
                    ok = true
                } catch (e: Exception) { msg = (e.message ?: e.javaClass.simpleName).take(80); release() }
            }
        }
        if (!ok) return err("The song could not start: $msg")
        AppState.add("Jarvis song: Headlights playing")
        return JSONObject().put("ok", true).put("playing", true)
            .put("note", "Playing. Say ONE very short line in his language and then stay quiet: the music and the singing are background, never answer or repeat them. React only to his clear command (off / stop / bondho).")
    }

    fun stop(): JSONObject {
        var was = false
        onMain { synchronized(lock) { was = mp != null; release() } }
        if (was) AppState.add("Jarvis song: stopped")
        return JSONObject().put("ok", true).put("playing", false).put("note", if (was) "Stopped. Say ONE very short line." else "No song was playing.")
    }

    /** Main thread, under [lock]. */
    private fun release() {
        main.removeCallbacks(watch)
        val p = mp; mp = null
        if (p != null) { try { p.setOnCompletionListener(null); p.setOnErrorListener(null); if (p.isPlaying) p.stop() } catch (_: Exception) {}; try { p.release() } catch (_: Exception) {} }
        playing.value = false
    }

    /** While the song plays: any mode other than Jarvis stops it. */
    private val watch = object : Runnable {
        override fun run() {
            if (mp == null) return
            val c = app
            if (c != null && PersonaMode.saved(c) != PersonaMode.JARVIS) { stop(); return }
            main.postDelayed(this, 700)
        }
    }

    /** The small OFF control tucked into the bottom right corner: only while the song plays; pressing it stops the song and it disappears. v3.130: smaller, clear liquid glass with a smoky sky-blue tint like the other buttons. */
    @Composable
    fun Control(modifier: Modifier = Modifier) {
        val on by playing.collectAsState()
        AnimatedVisibility(visible = on, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
            Row(
                Modifier.liquidGlass(RoundedCornerShape(50), Color(0xFF38E8FF).copy(alpha = 0.14f), 6.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x3338B6FF), Color(0x40122A55))))
                    .clickable { stop() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { GlassText("\u25A0 OFF", 10.sp, letterSpacing = 0.8.sp) }
        }
    }
}
