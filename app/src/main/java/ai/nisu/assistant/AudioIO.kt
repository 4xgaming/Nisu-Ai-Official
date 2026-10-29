package ai.nisu.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

/** Mic capture (16 kHz PCM) + speaker playback (24 kHz PCM), tuned for low latency and echo cancellation. */
class AudioIO(private val ctx: Context, private val onMic: (ByteArray) -> Unit) {
    @Volatile private var run = false
    private var rec: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var fastTrack = false   // track was built on the low-latency path
    private var am: AudioManager? = null
    // Each chunk is tagged with the "epoch" it belongs to, so speech that was interrupted can never leak out after a flush.
    private val q = LinkedBlockingQueue<Pair<Int, ByteArray>>()
    private val epoch = AtomicInteger(0)
    @Volatile private var written = 0L   // 16-bit samples handed to the speaker so far (lets us know if sound is still coming out)

    @Volatile private var held = 0   // bytes waiting in the jitter buffer
    @Volatile private var pitchApplied = false
    @Volatile var micLevel = 0f      // real microphone RMS (never overwritten by the speaker)
        private set
    @Volatile private var lastSpokeAt = 0L
    @Volatile private var lastReadAt = 0L
    @Volatile private var micEnded = false
    /** v3.58: called (once, on the mic thread) when the recorder stopped by itself, so the service can open it again at once instead of waiting for the slow stall check. */
    @Volatile var onDead: (() -> Unit)? = null

    /** v3.58: true while the microphone is really delivering sound. The service keeps a living AudioIO across a reconnect instead of closing and re-opening the microphone (that was the "mic keeps switching off and on" gap). */
    fun alive(): Boolean = run && !micEnded && rec != null && android.os.SystemClock.uptimeMillis() - lastReadAt < 2500L

    /** v3.58: pick the speaker / headset again (a headset may have been plugged in since the microphone was opened). */
    fun reroute() { try { route() } catch (_: Exception) {} }

    companion object {
        /** 120 ms of 24 kHz 16-bit mono: fast start without cutting the first syllable. v3.72: Extra Fast Beta uses 60 ms (2880) so the voice starts sooner. */
        @Volatile var preRoll = 5760
        private const val HEADROOM = 0.80   // v3.80: about -2 dB of digital headroom for the phone speaker
        private const val KNEE = 0.60       // v3.80: the limiter starts rounding peaks above this level
        private const val RE_ROLL = 3840    // 80 ms re-buffer after a network underrun so speech never stutters mid-sentence
        @Volatile private var current: AudioIO? = null
        /** Called by the pitch slider: re-applies the saved pitch to the running speaker. */
        fun pitchChanged() { current?.let { it.applyPitch(it.track) } }
        /** v3.73: Preferences > Audio output changed: the running conversation moves to the new output at once. */
        fun outputChanged() { current?.reroute() }
    }

    /** v3.73: true while her voice goes to an earphone / headset (wired, USB or Bluetooth). Then there is no speaker echo, so the microphone is never muted for it. */
    @Volatile var onHeadset: Boolean = false
    private var devCb: android.media.AudioDeviceCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        am = ctx.getSystemService(AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        // v3.73: an earphone plugged in or out (or a Bluetooth headset switching on / off) while she talks: the output follows at once
        try {
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { reroute() }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { reroute() }
            }
            am?.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            devCb = cb
        } catch (_: Exception) {}
        val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 2
        )
        MicOwn.add(r.audioSessionId)   // v3.70: this recorder is ours, not another app's
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.setEnabled(true)
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.setEnabled(true)
        // v2.14: automatic gain keeps a quiet or far voice at a level the speech recogniser understands
        if (AutomaticGainControl.isAvailable()) try { AutomaticGainControl.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
        rec = r

        val t = buildTrack()
        track = t
        current = this
        applyPitch(t)
        run = true
        micEnded = false
        lastReadAt = android.os.SystemClock.uptimeMillis()
        r.startRecording()
        t.play()

        thread(name = "nisu-mic", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            val buf = ByteArray(1280) // 40 ms chunks = low latency with half the WebSocket framing overhead under load
            var hpPrevIn = 0.0
            var hpPrevOut = 0.0
            var agcGain = 1.35
            while (run) {
                val n = r.read(buf, 0, buf.size)
                lastReadAt = android.os.SystemClock.uptimeMillis()
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    val st = cleanMicVoice(chunk, n, hpPrevIn, hpPrevOut, agcGain)
                    hpPrevIn = st[0]; hpPrevOut = st[1]; agcGain = st[2]
                    val lv = rms(chunk, n)
                    micLevel = lv
                    if (!isPlaying()) AppState.level.value = lv
                    onMic(chunk)
                } else if (n < 0) break
            }
            if (run) { micEnded = true; try { onDead?.invoke() } catch (_: Exception) {} }   // the loop ended by itself (not by stop())
        }
        thread(name = "nisu-speaker", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            var carry = -1 // leftover odd byte: keeps 16-bit samples aligned across network chunks
            var seen = epoch.get()
            // Jitter buffer: Gemini audio arrives in bursts over the mobile network. If the speaker buffer runs dry in the middle of a
            // word (underrun) many phones play crackle or stutter. Every answer collects preRoll first, and if a network hiccup ever
            // causes an underrun mid-sentence, it briefly re-primes (RE_ROLL) so the voice stays smooth instead of stuttering.
            val pre = ArrayList<ByteArray>()
            var preBytes = 0
            var targetRoll = preRoll
            var primed = false
            var lastUnderrun = 0
            var noiseMuted = 0
            var warmPrev = 0.0
            var burstG = 1.0   // v3.80: smooth gain (1.0 = normal, 0.125 = softened glitch), carried across chunks so there is never a click
            val shapeOut = DoubleArray(2)

            fun emit(b: ByteArray, e: Int) {
                val lv = rms(b, b.size)
                val now = android.os.SystemClock.uptimeMillis()
                AppState.level.value = lv
                AppState.outLevel.value = lv
                AppState.outAt = now
                lastSpokeAt = now
                var off = 0
                while (off < b.size && run && e == epoch.get()) {
                    val w = track?.write(b, off, b.size - off) ?: break
                    if (w < 0) { recoverTrack(w); break }
                    off += w
                    written += w / 2
                }
                if (Build.VERSION.SDK_INT >= 24) try {
                    val u = track?.underrunCount ?: 0
                    if (u > lastUnderrun) {
                        lastUnderrun = u
                        // Re-prime 80 ms on the next chunks so a momentary network dip does not stutter through the rest of the sentence
                        primed = false
                        targetRoll = RE_ROLL
                    }
                } catch (_: Exception) {}
            }
            fun release(e: Int) {
                val items = ArrayList(pre); pre.clear(); preBytes = 0; held = 0; primed = true
                for (c in items) emit(c, e)
            }

            while (run) {
                val item = q.poll(if (!primed && preBytes > 0) 45L else 180L, TimeUnit.MILLISECONDS)
                if (item == null) { if (!primed && preBytes > 0) release(epoch.get()); continue }
                val (e, raw) = item
                val cur = epoch.get()
                // After an interruption the queue is emptied, so a carried odd byte of the OLD speech must be dropped too,
                // otherwise the next answer starts one byte off and every sample is garbled (sounds like another voice).
                if (cur != seen) { seen = cur; carry = -1; warmPrev = 0.0; burstG = 1.0; pre.clear(); preBytes = 0; held = 0; primed = false; targetRoll = preRoll }
                if (e != cur) { carry = -1; continue } // belonged to interrupted speech
                if (raw.isEmpty()) {   // end-of-turn marker: start the next answer sample-aligned
                    if (!primed && preBytes > 0) release(e)
                    carry = -1; warmPrev = 0.0; burstG = 1.0; primed = false; targetRoll = preRoll
                    emit(ByteArray(960), e)   // 20 ms of silence so the end of the buffer is never a loud sample that can repeat
                    continue
                }
                var b = raw
                if (carry >= 0) {
                    val m = ByteArray(b.size + 1)
                    m[0] = carry.toByte()
                    System.arraycopy(b, 0, m, 1, b.size)
                    b = m; carry = -1
                }
                if (b.size % 2 == 1) { carry = b[b.size - 1].toInt() and 0xFF; b = b.copyOf(b.size - 1) }
                if (b.isEmpty()) continue
                val burst = isNoiseBurst(b)
                if (burst && ++noiseMuted % 5 == 1) AppState.add("Audio: a loud noise burst from the voice model was softened (#$noiseMuted)")
                shapeVoice(b, warmPrev, burst, burstG, shapeOut)
                warmPrev = shapeOut[0]; burstG = shapeOut[1]
                if (primed) emit(b, e)
                else {
                    pre.add(b); preBytes += b.size; held = preBytes
                    if (preBytes >= targetRoll) release(e)
                }
            }
        }
    }

    /** Output device died or stopped (route change, Bluetooth switch): rebuild instead of going silent or garbled. */
    private fun recoverTrack(err: Int) {
        try {
            if (err == AudioTrack.ERROR_DEAD_OBJECT) {
                try { track?.release() } catch (_: Exception) {}
                val nt = buildTrack(); track = nt; applyPitch(nt); nt.play(); written = 0L
            } else track?.play()
        } catch (_: Exception) {}
    }

    private fun buildTrack(): AudioTrack {
        val minOut = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fastTrack = false
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minOut * 6, 57600))   // ~1.2 s capacity: absorbs mobile network jitter without underruns; adds no delay
            .setTransferMode(AudioTrack.MODE_STREAM)
            // v2.17: the low-latency "fast" path cannot change pitch, so it is used only while the pitch is normal
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)   // the low-latency fast path underruns easily and crackles on many phones; speech from the network does not need it
            .build()
    }

    /**
     * Pitch slider. First choice: change the pitch alone (speed stays the same). If this phone refuses that,
     * the playback rate is changed instead, which moves pitch and speed together (small slider steps still sound natural).
     */
    private fun applyPitch(t: AudioTrack?) {
        val tr = t ?: return
        val pct = VoicePitch.saved(ctx)
        // Normal pitch (100%): do not attach AudioFlinger's time-stretch DSP unless the user had changed pitch earlier,
        // because forcing PlaybackParams(1f, 1f) onto a streaming AudioTrack causes micro-stutters on several devices.
        if (pct == VoicePitch.DEFAULT && !pitchApplied) return
        pitchApplied = pct != VoicePitch.DEFAULT
        val p = pct / 100f
        try {
            tr.playbackParams = PlaybackParams().setPitch(p).setSpeed(1f)
        } catch (_: Exception) {
            try { tr.playbackRate = (24000 * p).toInt() } catch (_: Exception) {}
        }
    }

    fun play(pcm: ByteArray) { q.offer(epoch.get() to pcm) }

    /** Marks the end of one answer so the next one always starts on a clean 16-bit sample boundary. */
    fun endTurn() { q.offer(epoch.get() to ByteArray(0)) }

    /** Called when the user interrupts (barge-in): drop queued speech instantly. */
    fun flush() {
        q.clear()
        epoch.incrementAndGet()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
        written = head()
    }

    private fun head(): Long = try { (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL } catch (_: Exception) { 0L }

    /** True while queued or buffered speech is still coming out of the speaker (the voice check must not score NISU AI's own voice). */
    fun isPlaying(): Boolean = !q.isEmpty() || held > 0 || written - head() > 240

    /** True while the speaker is playing OR during the 280 ms room-echo decay right after the speaker stops. */
    fun isEchoWindow(): Boolean = isPlaying() || (android.os.SystemClock.uptimeMillis() - lastSpokeAt < 280L)

    fun stop() {
        run = false
        if (current === this) current = null
        try { devCb?.let { am?.unregisterAudioDeviceCallback(it) } } catch (_: Exception) {}
        devCb = null; onHeadset = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        rec = null; track = null
        val a = am ?: return
        a.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else {
            @Suppress("DEPRECATION") a.isSpeakerphoneOn = false
            try { @Suppress("DEPRECATION") a.stopBluetoothSco(); @Suppress("DEPRECATION") a.isBluetoothScoOn = false } catch (_: Exception) {}
        }
    }

    /**
     * v3.73: where her voice plays. Preferences > Audio output:
     *  AUTO (default)  an earphone / headset when one is connected (wired, USB or Bluetooth), otherwise the loudspeaker;
     *  SPEAKER         always the loudspeaker, even with an earphone in;
     *  EARPHONE        always the earphone when one is connected, and the phone's ear speaker (like a call) when none is.
     * Before, Android 11 phones always got the loudspeaker (the old code only looked for a headset on Android 12+).
     */
    private fun route() {
        val a = am ?: return
        val pref = Prefs.get(ctx, Prefs.AUDIO_OUT, "auto")
        val wiredTypes = intArrayOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = a.availableCommunicationDevices
            val headset = devs.firstOrNull { it.type in wiredTypes }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 }
            val speaker = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpiece = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val pick = when (pref) {
                "speaker" -> speaker ?: earpiece
                "earphone" -> headset ?: earpiece ?: speaker
                else -> headset ?: speaker
            }
            onHeadset = pick != null && pick == headset
            if (pick != null) a.setCommunicationDevice(pick)
        } else {
            val outs = try { a.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { emptyArray<AudioDeviceInfo>() }
            val wired = outs.any { it.type in wiredTypes }
            val bt = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val useHeadset = pref != "speaker" && (wired || bt)
            onHeadset = useHeadset
            @Suppress("DEPRECATION")
            try {
                if (useHeadset) {
                    a.isSpeakerphoneOn = false
                    if (!wired && bt && !a.isBluetoothScoOn) { a.startBluetoothSco(); a.isBluetoothScoOn = true }   // Bluetooth earphones: the voice channel needs SCO
                } else {
                    if (a.isBluetoothScoOn) { a.stopBluetoothSco(); a.isBluetoothScoOn = false }
                    a.isSpeakerphoneOn = pref != "earphone"                                  // earphone mode without an earphone = the ear speaker
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Speech is smooth (neighbouring samples are alike); a white-noise glitch is not. Thresholds are kept high (rms > 0.30 and d/e > 1.82)
     * so bright female sibilants ("স", "শ", "ছ", "তি") are never mistaken for noise and cut in the middle of a word.
     */
    private fun isNoiseBurst(b: ByteArray): Boolean {
        val n = b.size / 2
        if (n < 240) return false
        var e = 0.0; var d = 0.0; var prev = 0.0
        for (k in 0 until n) {
            val v = (((b[2 * k + 1].toInt() shl 8) or (b[2 * k].toInt() and 0xFF)).toShort().toInt()) / 32768.0
            e += v * v
            if (k > 0) { val x = v - prev; d += x * x }
            prev = v
        }
        val rms = sqrt(e / n)
        return rms > 0.30 && d / (e + 1e-9) > 1.82
    }

    /**
     * v3.80: one pass over the speaker audio (24 kHz 16-bit mono).
     *  1) light warmth: rounds off harsh digital high-frequency edges (same filter as before);
     *  2) HEADROOM: the voice model sends very hot audio (peaks near full scale). A phone loudspeaker at a high volume, and the
     *     voice-call processing on top of it, then clip and the voice sounds torn and crushed. The signal is lowered by about 2 dB;
     *  3) a smooth limiter (tanh knee from 0.60) replaces the old hard knee, so a loud peak is rounded and can never reach full scale;
     *  4) a noise glitch is lowered by a gain that MOVES smoothly (about 4 ms) instead of dividing the whole chunk by 8 at once (that jump was an audible click).
     * out[0] = filter memory, out[1] = glitch gain, both carried to the next chunk.
     */
    private fun shapeVoice(b: ByteArray, prevIn: Double, burst: Boolean, g0: Double, out: DoubleArray) {
        val n = b.size / 2
        var prev = prevIn
        var g = g0
        val target = if (burst) 0.125 else 1.0
        for (k in 0 until n) {
            val raw = (((b[2 * k + 1].toInt() shl 8) or (b[2 * k].toInt() and 0xFF)).toShort().toInt()) / 32768.0
            val warm = raw * 0.86 + prev * 0.14
            prev = warm
            g += (target - g) * 0.01
            val x = warm * HEADROOM * g
            val ax = Math.abs(x)
            val y = if (ax <= KNEE) x else Math.signum(x) * (KNEE + (1.0 - KNEE) * Math.tanh((ax - KNEE) / (1.0 - KNEE)))
            val o = (y * 32767.0).toInt().coerceIn(-32768, 32767)
            b[2 * k] = (o and 0xFF).toByte()
            b[2 * k + 1] = ((o shr 8) and 0xFF).toByte()
        }
        out[0] = prev; out[1] = g
    }

    /**
     * 16 kHz microphone speech clarity & adaptive gain filter:
     * 1) Removes <85 Hz low-frequency rumble / wind / table vibration so Bengali, Hindi and English consonants stay crisp.
     * 2) Gently normalizes quiet or arm's-length voice toward clear speech level without amplifying background floor hiss.
     */
    private fun cleanMicVoice(b: ByteArray, n: Int, prevIn0: Double, prevOut0: Double, gain0: Double): DoubleArray {
        val cnt = n / 2
        if (cnt == 0) return doubleArrayOf(prevIn0, prevOut0, gain0)
        var pIn = prevIn0
        var pOut = prevOut0
        var energy = 0.0
        val filtered = DoubleArray(cnt)
        for (k in 0 until cnt) {
            val raw = (((b[2 * k + 1].toInt() shl 8) or (b[2 * k].toInt() and 0xFF)).toShort().toInt()) / 32768.0
            val hp = 0.968 * (pOut + raw - pIn)
            pIn = raw; pOut = hp
            filtered[k] = hp
            energy += hp * hp
        }
        val chunkRms = sqrt(energy / cnt)
        // Update gain only on real voice energy (> 0.012); decay gently toward 1.25 on silence so floor noise is never boosted
        val targetGain = when {
            chunkRms > 0.012 -> (0.16 / chunkRms).coerceIn(1.0, 2.35)
            chunkRms < 0.005 -> 1.15
            else -> gain0
        }
        val g = gain0 * 0.78 + targetGain * 0.22
        for (k in 0 until cnt) {
            val v = filtered[k] * g
            val shaped = if (v > 0.85) 0.85 + (v - 0.85) * 0.25 else if (v < -0.85) -0.85 + (v + 0.85) * 0.25 else v
            val out = (shaped * 32767.0).toInt().coerceIn(-32768, 32767)
            b[2 * k] = (out and 0xFF).toByte()
            b[2 * k + 1] = ((out shr 8) and 0xFF).toByte()
        }
        return doubleArrayOf(pIn, pOut, g)
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var s = 0.0
        var i = 0
        val cnt = n / 2
        if (cnt == 0) return 0f
        while (i + 1 < n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            s += v * v
            i += 2
        }
        return min(1f, (sqrt(s / cnt) * 4).toFloat())
    }
}
