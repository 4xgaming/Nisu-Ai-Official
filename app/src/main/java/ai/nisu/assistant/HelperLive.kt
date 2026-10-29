package ai.nisu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * v3.94 HELPER ENGINE = a Gemini LIVE model (default 3.8 Thinking), used silently and as text.
 *
 * Same idea as the Main Brain: a Live model on the same kind of API key, but silent. v3.129: its session stays open between jobs (see [Sess]); before that it was one short session per job, the question goes in as a text message, the sound the model makes is dropped,
 * and the answer is read from the model's output transcription. Nothing is played, nothing is shown, the microphone is not used, and
 * AppState (status, orb) is not touched. The Main Brain (3.8 Live / 3.1 / Beta Fast) only gets the finished result.
 *
 * One tool kind per session (v3.132: no Google Search any more, the app's own browser does the searching; or the three read-only file tools), because mixing them is not guaranteed on every Live model.
 * File tool calls are carried out one at a time on a worker thread through Tools.run (System Access and Rules still apply); only
 * HelperRules.FILE_TOOLS are allowed. The text of the turn before a tool call is thrown away: only what the model says AFTER its last tool result counts.
 * Never throws; the result carries a short error code (never the server's wording, never the key).
 */
internal object HelperLive {
    class Out(val text: String?, val error: String, val model: String, val toolCalls: Int, /** v3.119: the short, key-free reason the session ended (for the activity log and the Main Brain). */ val detail: String = "", /** v3.128: for error "quota": how long the helper rests, in ms. */ val restMs: Long = 0L)

    fun fileDeclarations(behavior: String?): JSONArray {
        fun d(name: String, desc: String, props: Map<String, String>, req: List<String>): JSONObject = HelperAgent.decl(name, desc, props, req, behavior)
        return JSONArray()
            .put(d("file_search", "Find files on the phone instantly from the file index. Use it first. Newest first.",
                mapOf("query" to "name words", "kind" to "photo | video | music | document | text | archive | apk | any", "folder" to "optional folder path", "limit" to "how many, default 8, max 30"), emptyList()))
            .put(d("file_list", "List what is inside a folder, for example /sdcard/Download.", mapOf("path" to "folder path"), listOf("path")))
            .put(d("file_read", "Read the text inside a text file (txt, md, json, log, srt ...).", mapOf("path" to "full file path", "max_chars" to "default 3000, max 12000"), listOf("path")))
    }

    // v3.128: a quota / rate / session-limit end is NEVER tried again straight away (it used to be: 3 more sessions on the empty key every time, so the quota never came back).
    private val RETRY = setOf("live_closed", "live_rejected", "connect_failed", "send_failed")

    /** v3.128: at most [HelperRules.MAX_PARALLEL] helper sessions at once (a search + two page reads used to open 3 Live sessions together, next to the voice session on the same key). */
    private val gate = java.util.concurrent.Semaphore(HelperRules.MAX_PARALLEL, true)

    /** v3.128: after a quota problem the helper rests (per key, the key itself is never stored: only its hash). While it rests no session is opened at all. */
    private val restUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun restLeftMs(apiKey: String): Long = ((restUntil[apiKey.hashCode()] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun resting(apiKey: String, model: String): Out? {
        val left = restLeftMs(apiKey)
        return if (left > 0L) Out(null, "quota", model, 0, "resting " + (left / 1000) + " s after a quota limit", left) else null
    }

    private fun redact(s: String, key: String): String = s.replace(key, "***").replace(Regex("key=[^&\\s\\\"]+"), "key=***").take(160)

    // ---- v3.129: the helper is a Live model like the Main Brain: ONE long-lived silent session per kind of job, not a new session for every job ----

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Sess>()
    @Volatile private var reaper: Thread? = null

    /** Closes a session that nobody used for [HelperRules.IDLE_MS] (it gives its place on the key back). The next job opens it again by itself. */
    private fun startReaper() {
        synchronized(this) {
            if (reaper != null) return
            reaper = Thread {
                while (true) {
                    try { Thread.sleep(30_000L) } catch (_: InterruptedException) { return@Thread }
                    val now = System.currentTimeMillis()
                    for (s in sessions.values) s.idleCheck(now)
                }
            }.also { it.isDaemon = true; it.start() }
        }
    }

    /**
     * One silent Live session that stays open between jobs. A job is sent as a text message, the sound is dropped, the answer is read from the output
     * transcription (exactly like [MainBrainModel], but the session is kept). One job at a time per session; the server's 10-minute goAway is followed:
     * the session is closed when it is free and opened again by the next job. A job that ended with the model still thinking, a timeout or an error drops
     * the session so the next job starts clean.
     */
    private class Sess(private val app: Context, private val apiKey: String, private val model: String, private val system: String, private val search: Boolean, private val fileTools: Boolean) {
        class JobState {
            val text = StringBuilder()
            val latch = CountDownLatch(1)
            val result = AtomicReference<Out?>(null)
            val done = AtomicBoolean(false)
            val busy = AtomicBoolean(false)
            val pending = AtomicInteger(0)
            val awaiting = AtomicBoolean(false)
            val calls = AtomicInteger(0)
        }

        val lock = java.util.concurrent.locks.ReentrantLock(true)
        private val rules = LiveModels.agentRules(model)
        private val worker = Executors.newSingleThreadExecutor(java.util.concurrent.ThreadFactory { r -> Thread(r).also { it.isDaemon = true } })
        @Volatile private var live: GeminiLive? = null
        @Volatile private var job: JobState? = null
        @Volatile private var alive = false
        @Volatile private var retire = false
        @Volatile private var connectLatch = CountDownLatch(1)
        @Volatile private var connectError: Out? = null
        @Volatile private var lastUsed = System.currentTimeMillis()
        private var jobs = 0   // v3.132: jobs done on this connection (guarded by [lock])

        private fun cur(j: JobState): String = synchronized(j.text) { j.text.toString().trim() }
        private fun finish(j: JobState, o: Out) { if (!j.done.compareAndSet(false, true)) return; j.result.set(o); j.latch.countDown() }
        private fun tryFinish(j: JobState) {
            if (j.pending.get() > 0 || j.awaiting.get() || j.busy.get()) return
            val t = cur(j)
            if (t.isNotEmpty()) finish(j, Out(t, "", model, j.calls.get()))
        }
        // Extended Thinking sometimes never sends IDLE after the answer: a turn that ended with text, no tool pending and no new words for GRACE_MS (v3.132: 0.6 s, was 3 s) is the answer.
        private fun grace(j: JobState) {
            val seen = cur(j).length
            val t = Thread {
                try { Thread.sleep(HelperRules.GRACE_MS) } catch (_: InterruptedException) { return@Thread }
                val now = cur(j)
                if (!j.done.get() && j.pending.get() == 0 && !j.awaiting.get() && now.length == seen && now.isNotEmpty()) finish(j, Out(now, "", model, j.calls.get()))
            }
            t.isDaemon = true
            t.start()
        }
        private fun closedOut(reason: String, fatal: Boolean, calls: Int): Out {
            val d = "$model: " + HelperLive.redact(reason, apiKey)
            return if (HelperRules.isQuota(reason)) Out(null, "quota", model, calls, d) else Out(null, if (fatal) "live_rejected" else "live_closed", model, calls, d)
        }
        private fun closeLive() {
            alive = false
            val l = live
            live = null
            try { l?.close() } catch (_: Throwable) {}
        }

        private val cb = object : GeminiLive.Callback {
            override fun onReady() { alive = true; connectLatch.countDown() }
            override fun onAudio(pcm: ByteArray) { /* silent: the sound is dropped, never played */ }
            override fun onInterrupted() {}
            override fun onTurnComplete() {
                val j = job ?: return
                if (j.pending.get() > 0 || j.awaiting.get()) return
                if (j.busy.get()) { if (cur(j).isNotEmpty()) grace(j); return }
                if (cur(j).isEmpty()) finish(j, Out(null, "empty", model, j.calls.get())) else tryFinish(j)
            }
            override fun onInText(t: String) {}
            override fun onOutText(t: String) { val j = job ?: return; synchronized(j.text) { j.text.append(t) }; j.awaiting.set(false) }
            override fun onToolCall(id: String, name: String, args: JSONObject) {
                val j = job ?: return
                j.pending.incrementAndGet()
                synchronized(j.text) { j.text.setLength(0) }   // what was said before a tool call is not the answer
                try {
                    worker.execute {
                        val out: JSONObject = try {
                            if (name !in HelperRules.FILE_TOOLS) JSONObject().put("error", "This tool is not available to the helper (read only).")
                            else if (j.calls.incrementAndGet() > HelperRules.MAX_ROUNDS * 2) JSONObject().put("error", "Too many steps. Answer now with what you have.")
                            else Tools.run(app, name, args)
                        } catch (e: Exception) { JSONObject().put("error", "the file tool failed") }
                        val shown = if (out.toString().length <= 14_000) out else JSONObject().put("result", HelperRules.clip(out.toString(), 14_000))
                        j.awaiting.set(true)
                        try { live?.sendToolResponse(id, name, shown, if (rules.tools == LiveModels.ToolMode.NON_BLOCKING) "WHEN_IDLE" else null) } catch (_: Throwable) {}
                        j.pending.decrementAndGet()
                    }
                } catch (_: Throwable) { j.pending.decrementAndGet() }
            }
            override fun onClosed(reason: String, fatal: Boolean, quick: Boolean) {
                alive = false
                live = null
                if (connectLatch.count > 0L) { connectError = closedOut(reason, fatal, 0); connectLatch.countDown() }
                val j = job ?: return
                val got = cur(j)
                if (!fatal && got.isNotEmpty() && j.pending.get() == 0) { finish(j, Out(got, "", model, j.calls.get())); return }
                finish(j, closedOut(reason, fatal, j.calls.get()))
            }
            override fun onInteraction(idle: Boolean) { val j = job ?: return; j.busy.set(!idle); if (idle) tryFinish(j) }
            override fun onGoAway(timeLeftMs: Long) { retire = true; if (job == null) closeLive() }
        }

        /** null = the session is open and ready, otherwise why it could not be opened. */
        private fun ensure(): Out? {
            val l = live
            if (alive && l != null && l.ready && !retire) return null
            closeLive()
            retire = false
            connectError = null
            val latch = CountDownLatch(1)
            connectLatch = latch
            val decls = if (fileTools) HelperLive.fileDeclarations(rules.tools.wire) else JSONArray()
            val g = GeminiLive(apiKey, model, Voice.saved(app), system, decls, null, rules.thinkingLevel, rules.apiVersion, 0, cb)
            g.googleSearch = search
            live = g
            try { g.connect() } catch (_: Throwable) { closeLive(); return Out(null, "connect_failed", model, 0) }
            try { latch.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            if (alive && g.ready) return null
            val e = connectError
            closeLive()
            return e ?: Out(null, "live_closed", model, 0, "$model: no answer from the server")
        }

        fun run(prompt: String, timeoutMs: Long): Out {
            val got = try { lock.tryLock(HelperRules.LOCK_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
            if (!got) return Out(null, "busy", model, 0)
            try {
                ensure()?.let { return it }
                val g = live ?: return Out(null, "live_closed", model, 0)
                val j = JobState()
                job = j
                lastUsed = System.currentTimeMillis()
                g.sendText("[New request: ignore all earlier requests.]\n" + prompt)
                val inTime = try { j.latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
                if (!inTime) {
                    val t = cur(j)
                    finish(j, if (t.isNotEmpty() && j.pending.get() == 0) Out(t, "", model, j.calls.get()) else Out(null, "timeout", model, j.calls.get()))
                }
                val out = j.result.get() ?: Out(null, "no_result", model, j.calls.get())
                job = null
                lastUsed = System.currentTimeMillis()
                if (out.text == null || j.busy.get() || retire || !alive) { closeLive(); jobs = 0 }   // not a clean end: the next job starts on a fresh session
                else if (++jobs >= HelperRules.SESSION_JOBS) { closeLive(); jobs = 0 }   // v3.132: every job stays in a session's memory, so it gets slower: a fresh connection every few jobs
                return out
            } finally {
                lock.unlock()
            }
        }

        /** v3.132: opens the connection now (so the next job finds it ready); does nothing when a job is running or it is already open. */
        fun warm() {
            if (!lock.tryLock()) return
            try { if (ensure() == null) lastUsed = System.currentTimeMillis() } finally { lock.unlock() }
        }

        fun idleCheck(now: Long) {
            if (live == null || now - lastUsed < HelperRules.IDLE_MS) return
            if (lock.tryLock()) { try { if (now - lastUsed >= HelperRules.IDLE_MS) closeLive() } finally { lock.unlock() } }
        }
    }

    private fun sessionFor(ctx: Context, apiKey: String, model: String, system: String, search: Boolean, fileTools: Boolean): Sess {
        val key = apiKey.hashCode().toString() + "|" + model + "|" + search + "|" + fileTools + "|" + system.hashCode()
        return sessions.getOrPut(key) { Sess(ctx.applicationContext, apiKey, model, system, search, fileTools) }
    }

    /**
     * v3.132: starts connecting the helper's session in the background and returns at once. Used by a search so the connection (1-2 s) is made WHILE the app's browser
     * is searching, not after it. Nothing happens while the helper rests after a quota problem.
     */
    fun warm(ctx: Context, apiKey: String, model: String, system: String, search: Boolean = false, fileTools: Boolean = false) {
        if (restLeftMs(apiKey) > 0L) return
        val sess = sessionFor(ctx, apiKey, model, system, search, fileTools)
        startReaper()
        Thread { try { sess.warm() } catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() }
    }

    private fun rested(apiKey: String, o: Out): Out {
        val rest = HelperRules.restMs(o.detail)
        restUntil[apiKey.hashCode()] = System.currentTimeMillis() + rest
        return Out(null, "quota", o.model, o.toolCalls, o.detail, rest)
    }

    /**
     * Blocking. Call from a worker thread. v3.129: the job goes to the helper's long-lived session (opened by the first job, kept open, opened again by itself
     * after a goAway, an error or a long idle time). Only when that session cannot be opened or breaks, the old way is tried: a fresh one-shot session with the bare
     * setup and the other API version, then the plain 3.8 Live model. v3.128: a quota problem stops everything at once and starts a rest; at most
     * [HelperRules.MAX_PARALLEL] jobs run at the same time.
     */
    fun turn(ctx: Context, apiKey: String, model: String, system: String, prompt: String, search: Boolean, fileTools: Boolean, timeoutMs: Long): Out {
        resting(apiKey, model)?.let { return it }
        val got = try { gate.tryAcquire(HelperRules.GATE_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!got) return Out(null, "busy", model, 0)
        try {
            resting(apiKey, model)?.let { return it }   // the job before this one may have hit the quota while this one waited
            val started = System.currentTimeMillis()
            val sess = sessionFor(ctx, apiKey, model, system, search, fileTools)
            startReaper()
            var last = sess.run(prompt, timeoutMs)
            if (last.text != null) return last
            if (last.error == "quota") return rested(apiKey, last)
            if (last.error !in RETRY) return last
            val plain = "gemini-3.8-live"
            val tries = listOf(model to 2) + (if (model != plain) listOf(plain to 0) else emptyList())
            for (t in tries) {
                val left = timeoutMs - (System.currentTimeMillis() - started)
                if (left < 15_000L) break
                last = attempt(ctx, apiKey, t.first, t.second, system, prompt, search, fileTools, left)
                if (last.text == null && last.error == "quota") return rested(apiKey, last)
                if (last.text != null || last.error !in RETRY) return last
            }
            return last
        } finally {
            gate.release()
        }
    }

    private fun attempt(ctx: Context, apiKey: String, model: String, compat: Int, system: String, prompt: String, search: Boolean, fileTools: Boolean, timeoutMs: Long): Out {
        val app = ctx.applicationContext
        val rules = LiveModels.agentRules(model)
        val decls = if (fileTools) fileDeclarations(rules.tools.wire) else JSONArray()
        val latch = CountDownLatch(1)
        val text = StringBuilder()
        val result = AtomicReference<Out?>(null)
        val done = AtomicBoolean(false)
        val busy = AtomicBoolean(false)          // Extended Thinking: the server may still be reasoning after a turn ended
        val pending = AtomicInteger(0)           // tool calls being carried out
        val awaiting = AtomicBoolean(false)      // a tool result was sent and the model has not answered yet
        val calls = AtomicInteger(0)
        val liveRef = AtomicReference<GeminiLive?>(null)
        val worker = Executors.newSingleThreadExecutor()

        fun current(): String = synchronized(text) { text.toString().trim() }
        fun finish(o: Out) {
            if (!done.compareAndSet(false, true)) return
            result.set(o)
            try { liveRef.get()?.close() } catch (_: Throwable) {}
            worker.shutdown()
            latch.countDown()
        }
        fun tryFinish() {
            if (pending.get() > 0 || awaiting.get() || busy.get()) return
            val t = current()
            if (t.isNotEmpty()) finish(Out(t, "", model, calls.get()))
        }

        // v3.104: Extended Thinking sometimes never sends IDLE after the answer, so the old code waited for the whole 75 s timeout before the Main Brain
        // got a finished answer. A turn that ended with text, with no tool pending and no new words for 3 s, is the answer.
        fun graceFinish() {
            val seen = current().length
            val t = Thread {
                try { Thread.sleep(HelperRules.GRACE_MS) } catch (_: InterruptedException) { return@Thread }
                val now = current()
                if (!done.get() && pending.get() == 0 && !awaiting.get() && now.length == seen && now.isNotEmpty()) finish(Out(now, "", model, calls.get()))
            }
            t.isDaemon = true
            t.start()
        }

        val cb = object : GeminiLive.Callback {
            override fun onReady() { try { liveRef.get()?.sendText(prompt) } catch (_: Throwable) { finish(Out(null, "send_failed", model, calls.get())) } }
            override fun onAudio(pcm: ByteArray) { /* silent: the sound is dropped, never played */ }
            override fun onInterrupted() {}
            override fun onTurnComplete() {
                if (pending.get() > 0 || awaiting.get()) return
                if (busy.get()) { if (current().isNotEmpty()) graceFinish(); return }
                if (current().isEmpty()) finish(Out(null, "empty", model, calls.get())) else tryFinish()
            }
            override fun onInText(t: String) {}
            override fun onOutText(t: String) { synchronized(text) { text.append(t) }; awaiting.set(false) }
            override fun onToolCall(id: String, name: String, args: JSONObject) {
                pending.incrementAndGet()
                synchronized(text) { text.setLength(0) }   // what was said before a tool call is not the answer
                try {
                    worker.execute {
                        val out: JSONObject = try {
                            if (name !in HelperRules.FILE_TOOLS) JSONObject().put("error", "This tool is not available to the helper (read only).")
                            else if (calls.incrementAndGet() > HelperRules.MAX_ROUNDS * 2) JSONObject().put("error", "Too many steps. Answer now with what you have.")
                            else Tools.run(app, name, args)
                        } catch (e: Exception) { JSONObject().put("error", "the file tool failed") }
                        val shown = if (out.toString().length <= 14_000) out else JSONObject().put("result", HelperRules.clip(out.toString(), 14_000))
                        awaiting.set(true)
                        try { liveRef.get()?.sendToolResponse(id, name, shown, if (rules.tools == LiveModels.ToolMode.NON_BLOCKING) "WHEN_IDLE" else null) } catch (_: Throwable) {}
                        pending.decrementAndGet()
                    }
                } catch (_: Throwable) { pending.decrementAndGet() }
            }
            override fun onClosed(reason: String, fatal: Boolean, quick: Boolean) {
                val got = current()
                if (!fatal && got.isNotEmpty() && pending.get() == 0) { finish(Out(got, "", model, calls.get())); return }
                // v3.128: quota / rate / session limit (Google often uses close code 1011 for it): its own error, never tried again at once
                if (HelperRules.isQuota(reason)) { finish(Out(null, "quota", model, calls.get(), "$model: " + redact(reason, apiKey))); return }
                finish(Out(null, if (fatal) "live_rejected" else "live_closed", model, calls.get(), "$model: " + redact(reason, apiKey)))
            }
            override fun onInteraction(idle: Boolean) { busy.set(!idle); if (idle) tryFinish() }
        }

        val live = GeminiLive(apiKey, model, Voice.saved(app), system, decls, null, rules.thinkingLevel, rules.apiVersion, compat, cb)
        live.googleSearch = search
        liveRef.set(live)
        try { live.connect() } catch (_: Throwable) { finish(Out(null, "connect_failed", model, 0)) }
        val inTime = try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!inTime) {
            val got = current()
            finish(if (got.isNotEmpty() && pending.get() == 0) Out(got, "", model, calls.get()) else Out(null, "timeout", model, calls.get()))
        }
        return result.get() ?: Out(null, "no_result", model, calls.get())
    }
}
