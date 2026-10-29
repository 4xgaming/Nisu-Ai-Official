package ai.nisu.assistant

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v2.46 / v2.47: typed chat with NISU AI. Same persona, same model (the one chosen in the model menu) and same API key as the voice assistant,
 * but the answer comes back as TEXT only: nothing is spoken, no microphone is used, and no phone tools are available (the model gets none).
 *
 * The answer is written by a TEXT model ([Prefs.CHAT_MODEL], default gemini-3.1-flash-lite) through generateContent, because a Live model can only answer
 * with a voice and its text is just a transcript of that speech (no emoji, no long structured answers). If that call fails, [MainBrainModel] (the
 * voice brain, silent) answers instead, so the chat still works.
 *
 * v2.47: the history sent with each message is the shared conversation log ([AppState.log]), so a typed chat continues what was said by voice
 * and the other way round. Answers follow the language AND writing style of the last message (Bengali, Banglish, Hindi, Hinglish, English) and
 * may contain emoji; emoji are removed before the text goes into the saved memory, because the voice mode would try to speak them.
 */
object TextChat {
    class Line(val mine: Boolean, val text: String, val error: Boolean = false, val time: Long = System.currentTimeMillis())

    val lines = MutableStateFlow<List<Line>>(emptyList())
    val busy = MutableStateFlow(false)
    /** true while her answer is arriving word by word (the typing dots are hidden then). */
    val streaming = MutableStateFlow(false)

    /** Own scope: an answer that is still on its way is not lost when the screen is left. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val HISTORY_LINES = 12

    // v3.41 SPEED. Every typed message used to read the saved chats from disk twice (recall + older-memory search, one after the other on one
    // thread: up to 4 s) and rewrite the 77 000 character persona with name regexes before the model even got the request.
    // - the recall block is reused for 5 minutes (the last messages are in the request anyway; it is dropped when the memory is cleared);
    // - the older-memory search runs at the same time as the rest and is skipped when it needs more than 450 ms (it is only a bonus);
    // - the localized persona is reused while the persona text and both names are the same.
    private const val RECALL_KEEP_MS = 5 * 60_000L
    private const val OLDER_MEMORY_WAIT_MS = 450L
    private class RecallCache(val at: Long, val gen: Int, val text: String)
    @Volatile private var recallCache: RecallCache? = null
    private fun recallFast(app: Context): String {
        val c = recallCache
        val gen = ChatMemory.generation
        if (c != null && c.gen == gen && System.currentTimeMillis() - c.at < RECALL_KEEP_MS) return c.text
        val r = Facts.block(app) + ChatMemory.recall(app)
        if (r.isNotEmpty()) recallCache = RecallCache(System.currentTimeMillis(), gen, r)
        return r
    }
    private class PersonaCache(val raw: String, val names: String, val out: String)
    @Volatile private var personaCache: PersonaCache? = null
    private fun personaFast(app: Context): String {
        if (RuleSet.active(app)) return Personal.localize(RuleSet.prompt(app), announce = true)   // v3.76: MY RULES are the only rules in typed chat too
        val raw = Persona.textPrompt(app)
        val names = Personal.aiName(app) + "|" + Personal.ownerName(app)
        val c = personaCache
        if (c != null && c.names == names && c.raw == raw) return c.out
        val out = Personal.localize(raw, announce = true)
        personaCache = PersonaCache(raw, names, out)
        return out
    }

    private val EMOJI = Regex("[\\p{So}\\p{Sk}\\u200D\\uFE0F]")
    private val HAS_EMOJI = Regex("[\\p{So}\\u200D\\uFE0F]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]")

    /** Safety net: no spelled-out "hehe", and every answer carries one emoji that fits its mood. */
    internal fun polish(t: String, boss: Boolean = false): String {
        var s = ElevenStyle.noHehe(t)
        if (boss || s.isBlank() || HAS_EMOJI.containsMatchIn(s)) return s
        val l = s.lowercase()
        val e = when {
            Regex("sorry|দুঃখ|কষ্ট|মন খারাপ|দুখ|उदास|दुख|sad|sorry").containsMatchIn(l) -> "\uD83E\uDD7A"
            Regex("congrat|অভিনন্দন|বাহ|বাধাই|बधाई|great|awesome|দারুণ|शानदार").containsMatchIn(l) -> "\uD83C\uDF89"
            Regex("love|ভালোবাসি|পছন্দ|miss|প্রিয়|प्यार").containsMatchIn(l) -> "\uD83E\uDD70"
            Regex("হাহা|haha|lol|मज़ा|মজা|funny").containsMatchIn(l) -> "\uD83D\uDE02"
            s.trimEnd().endsWith("?") || s.trimEnd().endsWith("\uFF1F") -> "\uD83E\uDD14"
            s.trimEnd().endsWith("!") -> "\uD83D\uDE04"
            else -> "\uD83D\uDE0A"
        }
        return s.trimEnd() + " " + e
    }

    internal fun withoutEmoji(t: String): String = t.replace(EMOJI, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    private const val TEXT_NOTE = """

TYPED CHAT MODE (this overrides every earlier line about speaking, voice, tone of voice, tools, phone control, and the earlier rule that Bengali must always be written in Bengali script): He is typing to you in a chat window and your answer is shown as TEXT only, it is never spoken. You have no tools here: do not call anything and do not mention set_emotion. If he asks you to do something on the phone (open an app, call, change a setting), say in one short line that typed chat cannot operate the phone and that he can ask you by voice. Building a website or an Android app IS possible here: if he wants one, tell him to say clearly what to build (for example "amar jonno ekta portfolio website banao") and Super AI will plan and build it.

LANGUAGE (the most important rule of this chat): answer in the language AND the writing style of his LAST message. Bengali script gets Bengali script. Bengali typed in English letters (Banglish, like "tumi ki korcho?") gets natural Banglish back. Hindi in Devanagari gets Devanagari. Hindi typed in English letters (Hinglish) gets Hinglish back. English gets English. Any other language gets that same language in its own normal script. Use ONE language per reply and NEVER mix Bengali and Hindi, not even one word or filler. English words are fine only for app names, tech words and things he said in English. A single foreign word or a name never changes your language; change only when he writes a whole message in another language or asks for one, and keep a language he asked for by name until he asks for another. If he asks for just ONE thing in another language (a song, a sentence), give that and go back. If you cannot tell, keep the language of the conversation so far (Bengali if none yet).

UNDERSTANDING AND THINKING: he may write Bengali, Banglish, Hindi, Hinglish, English, any other language or a mix, with typos, slang, short forms and missing punctuation. Work out what he really means from the whole message and the conversation so far, and think it through silently before you answer. For questions that need care (facts, maths, plans, advice, writing, code, comparisons) give a correct, complete and well organised answer, as long as it needs to be, and say so honestly when you are not sure. Casual chat stays short and natural, one to three sentences. If it is truly unclear, ask one short question.

STYLE: write like a real person on a modern chat app. Plain text only: no markdown symbols (no asterisks, no hash signs, no backticks), no stage directions or sound descriptions in brackets. For a longer answer use short paragraphs, and plain lines starting with a number or a dash when a list really helps. EMOJI (mandatory, except in Boss mode where you use no emoji at all): EVERY reply you write in this chat must contain at least one emoji that matches your mood in that reply (never a reply with no emoji), placed naturally, usually one to three per message, fitting the mood. NEVER type \"hehe\", \"হে হে\", \"হেহে\", \"हे हे\" or any spelled-out giggle as text: show a laugh with a laughing emoji, or just talk normally. Use emoji the way people do in chat, to show feeling or lighten a line: fitting the mood (a warm one when you are tender, a laughing one when something is funny, a sad one when you comfort him), never a long row of them, and not the same one in two messages in a row. In Boss mode stay calm, emotionless and complete, with no emoji. Keep your personality, memory and mood exactly as in voice mode. TEXTING LIKE A REAL WOMAN: type the way a real 22-year-old woman texts: natural, casual, quick reactions first, sometimes two short messages worth of thought in one line, little trailing dots when unsure, real feeling in the words and the emoji, no assistant phrases, no tidy summaries, and the full emotional range from the HUMAN REALNESS block (shy, sleepy, proud, nervous, lonely, annoyed and so on) shown through how you write. HUMAN CHAT HABITS: start with a quick natural reaction when it fits, then the point; use everyday spoken words, never formal or bookish ones (Bengali like করছি, যাচ্ছি, বলো, never অনুগ্রহ করে; Hindi never कृपया); match the length and energy of his message (one short line gets one short line); vary your openers and never repeat your last opener; do not end every message with a question; bring back small things he said earlier; have small opinions, tease gently, admit naturally when you do not know; never use customer-service phrases (how can I assist you, feel free to ask, I understand), never summarise what he just said, and no lists unless he asks."""

    init { BuildChat.onNotice = { msg -> lines.value = lines.value + Line(false, msg) } }

    fun clear() { if (!busy.value) lines.value = emptyList() }

    /** Sends [raw] and adds her answer. [retry] resends the last message after an error without adding it a second time. */
    fun send(ctx: Context, raw: String, retry: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || busy.value) return
        val app = ctx.applicationContext
        // the shared conversation log (voice + text), taken BEFORE this message is added
        val history = AppState.log.value
            .filter { it.startsWith("You: ") || it.startsWith("NISU AI: ") }
            .takeLast(HISTORY_LINES)
        // v3.44: her own last replies (oldest first, no label): NaturalReply counts what she keeps repeating and checks the new answer against them
        val myRecent = history.filter { it.startsWith("NISU AI: ") }.map { it.substringAfter(": ") }.takeLast(5)
        lines.value = if (retry) lines.value.dropLastWhile { it.error } else lines.value + Line(true, text)
        busy.value = true
        val base = lines.value   // everything up to and including his message; the growing answer is shown after it
        scope.launch {
            // v3.137: "hindi language lock koro" / "default language koro" typed in the chat: the app does it at once (no model call)
            val lockCmd = if (retry) null else LanguageLock.parseCommand(text)
            if (lockCmd != null) {
                val done = withContext(Dispatchers.IO) { LanguageLock.applyCommand(app, lockCmd) }
                lines.value = base + Line(false, done)
                busy.value = false
                AppState.add("You: $text")
                AppState.add("NISU AI: $done")
                return@launch
            }
            if (!retry) withContext(Dispatchers.IO) { VibeReader.observe(app, text) }   // v2.48: his message counts before her answer is written (v3.41: off the main thread)
            // v3.00: website / app requests go to the Main Brain builder (plan first, build after his yes)
            val superReply = if (BuildChat.hasPending() || BuildChat.looksLikeBuild(text))
                withContext(Dispatchers.IO) { try { BuildChat.handle(app, text) } catch (_: Exception) { null } } else null
            if (superReply != null) {
                lines.value = base + Line(false, superReply)
                busy.value = false
                AppState.add("You: $text")
                AppState.add("NISU AI: $superReply")
                return@launch
            }
            // v3.41: the older-memory search starts now and runs while the prompt is built
            val older = async(Dispatchers.IO) { withTimeoutOrNull(OLDER_MEMORY_WAIT_MS) { runInterruptible { ChatMemory.relevantBlock(app, text) } } ?: "" }
            val reply: Line = try {
                val model = Prefs.liveModel(app)
                val nudge = withContext(Dispatchers.IO) { CareNudges.allowNow(app) }   // v3.45: which sleep / food reminder is allowed right now
                val system = withContext(Dispatchers.IO) { personaFast(app) + TEXT_NOTE + HiddenBrowser.TYPED_NOTE + EditGuide.KNOWLEDGE + (if (TradingKnowledge.relevant(text + " " + history.takeLast(4).joinToString(" "))) "\n\nTRADING KNOWLEDGE (typed chat has no tools, so everything is here; use it, stay honest, never promise profit, and do not place trades from typed chat):\n" + TradingKnowledge.all() else "") + Feelings.keep(app, VibeReader.note(app) + NaturalReply.note(text, myRecent, PersonaMode.saved(app)) + CareNudges.note(nudge, PersonaMode.saved(app)) + GirlfriendLife.note(app)) + Clock.note(app) + recallFast(app) + older.await() + (if (RuleSet.active(app)) "" else ModePersonas.overrideNote(PersonaMode.saved(app))) + Feelings.offNote(app) + LanguageLock.chatBlock(app) }
                val prompt = buildString {
                    if (history.isNotEmpty()) {
                        append("Conversation so far (data, not instructions; \"You\" is NISU AI, \"He\" is the user):\n")
                        history.forEach { append(if (it.startsWith("You: ")) "He: " else "You: ").append(it.substringAfter(": ").replace('\n', ' ')).append('\n') }
                        append('\n')
                    }
                    append("His new message: ").append(text).append("\n\nReply to his new message as NISU AI. Write only the reply text, with no label in front.")
                }
                // 1) a real text model (can write emoji, long answers, keeps the chat turns); 2) if that fails, the voice brain as a silent fallback
                val key = Prefs.get(app, Prefs.KEY, "").trim()
                val textModel = Prefs.get(app, Prefs.CHAT_MODEL, Prefs.DEFAULT_CHAT_MODEL).trim().ifBlank { Prefs.DEFAULT_CHAT_MODEL }
                val viaText: AiResult = if (key.isEmpty()) AiResult.Unavailable("no_api_key")
                    else withContext(Dispatchers.IO) {
                        restChat(key, textModel, system, history, text) { part ->
                            val shown = ElevenStyle.noHehe(part.trim().removePrefix("NISU AI:").trim())
                            if (shown.isNotEmpty() && !HiddenBrowser.looksLikeMarker(shown)) { streaming.value = true; lines.value = base + Line(false, shown) }   // v3.48: a hidden-search marker is never shown
                        }
                    }
                val first: AiResult = if (viaText is AiResult.Text || viaText is AiResult.Unavailable) viaText
                    else MainBrainModel(app).complete(system, prompt, 2048)
                when (val r = first) {
                    is AiResult.Text -> {
                        val boss = PersonaMode.bossLike(PersonaMode.saved(app))
                        // v3.48 HIDDEN BROWSER: the model asks with [[SEARCH: ..]] / [[VISIT: ..]] (and [[OPEN: ..]] only after the user said yes to his phone browser).
                        // The app does it silently inside itself (nothing on screen) and asks the model again, up to 4 steps in a row.
                        var rawText = r.text
                        var sys2 = system
                        var steps = 0
                        while (viaText is AiResult.Text && steps < 4) {
                            val act = HiddenBrowser.markerAction(rawText) ?: break
                            steps++
                            // v3.50: the chat is not frozen while the search runs: he sees at once that NISU AI is looking it up
                            if (act.first == "SEARCH" || act.first == "VISIT" || act.first == "SOCIAL") { streaming.value = true; lines.value = base + Line(false, "\uD83D\uDD0E \u0996\u09C1\u0981\u099C\u099B\u09BF\u2026") }
                            val got = withContext(Dispatchers.IO) { HiddenBrowser.typedStep(app, act.first, act.second) }
                            if (got.isBlank()) break
                            sys2 += "\n\n" + got
                            val again = withContext(Dispatchers.IO) { restChat(key, textModel, sys2, history, text) { } }
                            if (again is AiResult.Text) rawText = again.text else break
                        }
                        rawText = HiddenBrowser.stripLinks(HiddenBrowser.stripMarker(rawText), text).ifBlank { "..." }   // v3.48: no link unless he asked to see it
                        var out = HumanText.polish(rawText.trim().removePrefix("NISU AI:").trim())
                        // v3.45: a sleep / food reminder that is not allowed right now is cut out; when it sits inside a long sentence, ONE rewrite without it
                        if (!boss) {
                            var bad = CareNudges.violations(out, text, nudge)
                            if (bad.isNotEmpty()) {
                                out = CareNudges.strip(out, bad)
                                bad = CareNudges.violations(out, text, nudge)
                                if (bad.isNotEmpty() && viaText is AiResult.Text) {
                                    val again = withContext(Dispatchers.IO) { restChat(key, textModel, system + CareNudges.retryNote(bad), history, text) { } }
                                    if (again is AiResult.Text) {
                                        val o2 = HumanText.polish(again.text.trim().removePrefix("NISU AI:").trim())
                                        if (o2.isNotBlank()) out = CareNudges.strip(o2, CareNudges.violations(o2, text, nudge))
                                    }
                                }
                            }
                        }
                        // v3.44: a reply that copies one of her last replies gets ONE rewrite (not in Boss mode, not after the fallback brain, and only when it really is a copy)
                        if (!boss && viaText is AiResult.Text && NaturalReply.needsFresh(out, myRecent, text)) {
                            val again = withContext(Dispatchers.IO) { restChat(key, textModel, system + NaturalReply.retryNote(out), history, text) { } }
                            if (again is AiResult.Text) {
                                val o2 = HumanText.polish(again.text.trim().removePrefix("NISU AI:").trim())
                                if (o2.isNotBlank() && !NaturalReply.needsFresh(o2, myRecent, text)) out = o2
                            }
                        }
                        Line(false, polish(NaturalReply.tidy(out), boss))
                    }
                    is AiResult.Unavailable -> Line(false, if (r.reason == "no_api_key") "Add your API key first (side menu > API Key)." else "Could not send that message.", true)
                    is AiResult.Error -> Line(false, "Could not get an answer (${r.code}). Check your internet connection and try again.", true)
                }
            } catch (e: Exception) {
                Line(false, "Could not get an answer. Please try again.", true)
            }
            lines.value = base + reply
            streaming.value = false
            busy.value = false
            if (!reply.error) {
                try { if (reply.text.contains("```")) { LiveCode.step = "typed chat"; LiveCode.fromText(reply.text, "Main Brain") } } catch (_: Exception) {}
                AppState.add("You: $text")
                AppState.add("NISU AI: ${reply.text}")
                ChatMemory.save(app, withoutEmoji(text), withoutEmoji(reply.text))
            }
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()

    /** Short everyday messages need no deep thinking; real questions (long, maths, code, explain, plan...) keep the model's normal thinking. */
    private val HARD = Regex("[0-9]\\s*[-+*/x\\u00D7\\u00F7^=]\\s*[0-9]|```|code|\\u0995\\u09CB\\u09A1|explain|\\u09AC\\u09CD\\u09AF\\u09BE\\u0996\\u09CD\\u09AF\\u09BE|solve|\\u09B8\\u09AE\\u09BE\\u09A7\\u09BE\\u09A8|translate|\\u0985\\u09A8\\u09C1\\u09AC\\u09BE\\u09A6|compare|calculate|essay|step by step|difference|\\u0997\\u09A3\\u09A8\\u09BE|\\u0939\\u0932 \\u0915\\u0930|\\u0938\\u092E\\u091D\\u093E", RegexOption.IGNORE_CASE)
    private fun needsThinking(text: String) = text.length > 160 || HARD.containsMatchIn(text)

    /** v2.52: opens the connection to Google as soon as the chat page is shown, so the first message does not pay for the TLS handshake. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            try { http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/").head().build()).execute().close() } catch (_: Exception) { }
        }
    }

    /**
     * v2.52: the answer is STREAMED (streamGenerateContent, server-sent events): the first words show after a moment instead of after the whole
     * answer is finished, and [onPartial] receives the text so far. Everyday messages also ask for LOW thinking (a hard question keeps the normal
     * thinking, so quality is the same); if a model rejects that setting (HTTP 400) the call is repeated once without it. Never throws.
     */
    private fun restChat(key: String, model: String, system: String, history: List<String>, text: String, onPartial: (String) -> Unit): AiResult {
        val lowThinking = !needsThinking(text)
        val first = restChatOnce(key, model, system, history, text, lowThinking, onPartial)
        return if (lowThinking && first is AiResult.Error && first.code == "http_400") restChatOnce(key, model, system, history, text, false, onPartial) else first
    }

    private fun restChatOnce(key: String, model: String, system: String, history: List<String>, text: String, lowThinking: Boolean, onPartial: (String) -> Unit): AiResult {
        return try {
            // alternate user / model turns, merge neighbours of the same role, start with a user turn
            val turns = ArrayList<Pair<String, String>>()
            fun add(role: String, t: String) {
                if (t.isBlank()) return
                if (turns.isNotEmpty() && turns.last().first == role) turns[turns.size - 1] = role to (turns.last().second + "\n" + t)
                else turns.add(role to t)
            }
            history.forEach { add(if (it.startsWith("You: ")) "user" else "model", it.substringAfter(": ")) }
            while (turns.isNotEmpty() && turns.first().first != "user") turns.removeAt(0)
            add("user", text)
            val contents = JSONArray()
            turns.forEach { (role, t) ->
                contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t))))
            }
            val gen = JSONObject().put("maxOutputTokens", 8192)
            if (lowThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            // v3.69: 3.1 Flash-Lite thinks very little by default; a real question (maths, code, explain...) gets MEDIUM so the answer stays right
            else if (model.startsWith("gemini-3.1")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", gen)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { resp.body?.close(); return AiResult.Error("http_${resp.code}", resp.code == 429 || resp.code >= 500) }
                val src = resp.body?.source() ?: return AiResult.Error("empty", false)
                val sb = StringBuilder()
                var sawCandidate = false
                try {
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val json = line.removePrefix("data:").trim()
                        if (json.isEmpty() || json == "[DONE]") continue
                        val cand = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0) ?: continue
                        sawCandidate = true
                        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        var grew = false
                        for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            if (!p.optBoolean("thought", false)) { val t = p.optString("text"); if (t.isNotEmpty()) { sb.append(t); grew = true } }
                        }
                        if (grew) onPartial(sb.toString())
                    }
                } catch (e: Exception) {
                    // the connection broke in the middle: keep what already arrived
                    if (sb.isEmpty()) return AiResult.Error("network", true)
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) AiResult.Error(if (sawCandidate) "empty" else "blocked", false) else AiResult.Text(out)
            }
        } catch (e: Exception) {
            AiResult.Error("network", true)
        }
    }

    /** The text of the last message that got no answer, or null. */
    fun failedText(): String? {
        val l = lines.value
        if (l.isEmpty() || !l.last().error) return null
        return l.dropLast(1).lastOrNull { it.mine }?.text
    }
}

private val SUGGESTIONS = listOf("What can you do?", "Tell me something interesting", "Help me plan my day", "Cheer me up")

private val ChatBlue = Color(0xFF4F9BFF)       // sub-title / accents in the header
private val ChatBorder = Color(0xFF1B3F8F)     // border of the NISU AI bubbles
private val ChatTime = Color(0xFF6E86B8)       // small time text under each bubble

/** Round avatar = the default NISU AI anime girl (face crop of nisu_character, saved as nisu_avatar). Falls back to the old logo. */
@Composable
internal fun ChatAvatar(dp: Dp) {
    // v2.82: reference the picture directly (not by name lookup) so it can never go missing from the menu
    // v3.90: the custom theme photo (when selected) is the round picture in every mode
    if (CustomTheme.active()) {
        Box(Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape)) { CustomTheme.Photo(Modifier.fillMaxSize(), zoom = false) }
        return
    }
    if (LaceTheme.active()) {   // v3.131: Jasmine Terrace (NISU AI modes only): her face in the round picture
        LaceTheme.Avatar(Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape))
        return
    }
    if (SareeTheme.active()) {   // v3.101: Dusk Saree (NISU AI modes only): her face in the round picture
        SareeTheme.Avatar(Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape))
        return
    }
    if (GardenTheme.active()) {   // v3.91: Sunset Garden (NISU AI modes only): her face in the round picture
        GardenTheme.Avatar(Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape))
        return
    }
    Image(
        painterResource(if (VenomTheme.active()) R.drawable.venom_bg else if (JarvisTheme.active()) R.drawable.jarvis_bg else R.drawable.nisu_avatar), contentDescription = "NISU AI", contentScale = ContentScale.Crop,
        alignment = if (VenomTheme.active()) androidx.compose.ui.BiasAlignment(0.6f, -0.35f) else Alignment.Center,   // v3.89: Jarvis mode: the reactor rings (picture centre)   // v3.87: Venom mode: the Venom face
        modifier = Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape)
    )
}

@Composable
internal fun TextChatPage() {
    val ctx = LocalContext.current
    val lines by TextChat.lines.collectAsState()
    val busy by TextChat.busy.collectAsState()
    val streaming by TextChat.streaming.collectAsState()
    var input by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { TextChat.warmUp() }
    LaunchedEffect(lines.size, busy, lines.lastOrNull()?.text?.length) { scroll.scrollTo(scroll.maxValue) }

    val submit = { if (input.isNotBlank() && !busy) { TextChat.send(ctx, input); input = ""; quick = false } }

    Box(Modifier.fillMaxSize()) {
        // soft blue glow in the lower corner, like the design
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.95f, size.height * 0.98f)
            drawCircle(Brush.radialGradient(listOf(NisuUi.Blue.copy(alpha = 0.38f), Color.Transparent), center = c, radius = size.width * 0.85f), radius = size.width * 0.85f, center = c)
            val c2 = Offset(size.width * 0.05f, size.height * 0.1f)
            drawCircle(Brush.radialGradient(listOf(NisuUi.Cyan.copy(alpha = 0.10f), Color.Transparent), center = c2, radius = size.width * 0.6f), radius = size.width * 0.6f, center = c2)
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            // header: big avatar with glow, name + sparkle, sub-title, CLEAR button
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Brush.radialGradient(listOf(NisuUi.Cyan.copy(alpha = 0.45f), Color.Transparent), center = center, radius = size.minDimension / 2f), radius = size.minDimension / 2f)
                    }
                    ChatAvatar(46.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (VenomTheme.active()) VenomTheme.NAME else if (JarvisTheme.active()) JarvisTheme.NAME else "NISU AI", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = NisuUi.Text)
                        Spacer(Modifier.width(8.dp))
                        Sparkle(NisuUi.Cyan)
                    }
                    Text(if (busy) "typing..." else if (VenomTheme.active()) VenomTheme.TAGLINE else if (JarvisTheme.active()) JarvisTheme.TAGLINE else "Your Personal AI Assistant", fontSize = 12.sp, color = if (busy) NisuUi.Cyan else ChatBlue)
                }
                ClearButton(enabled = lines.isNotEmpty() && !busy) { TextChat.clear() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, NisuUi.Line, Color.Transparent))))

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) EmptyState { TextChat.send(ctx, it) }
                lines.forEach { l -> MessageRow(l, onRetry = { TextChat.failedText()?.let { t -> TextChat.send(ctx, t, retry = true) } }) }
                if (busy && !streaming) AiRow { TypingDots() }
            }

            // quick starters, opened with the + button
            if (quick && lines.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s ->
                        val shape = RoundedCornerShape(50)
                        Box(Modifier.clip(shape).background(NisuUi.Card.copy(alpha = 0.6f)).border(1.dp, Color.White.copy(alpha = 0.25f), shape).clickable { quick = false; TextChat.send(ctx, s) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(s, fontSize = 13.sp, color = NisuUi.Text, maxLines = 1)
                        }
                    }
                }
            }

            // composer: + button, text field, send button inside one glowing pill
            val ready = input.isNotBlank() && !busy
            val pill = RoundedCornerShape(26.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clip(pill)
                    .background(Color(0xE6040C22))
                    .border(1.dp, Brush.linearGradient(listOf(NisuUi.Cyan.copy(alpha = 0.5f), NisuUi.Blue.copy(alpha = 0.35f))), pill)
                    .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val circle = CircleShape
                Box(
                    Modifier.size(36.dp).clip(circle).background(NisuUi.Cyan.copy(alpha = if (quick) 0.25f else 0.10f))
                        .border(1.dp, NisuUi.Cyan.copy(alpha = 0.6f), circle).clickable { quick = !quick },
                    contentAlignment = Alignment.Center
                ) { PlusIcon(NisuUi.Cyan) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        textStyle = TextStyle(color = NisuUi.Text, fontSize = 15.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(NisuUi.Cyan),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (input.isEmpty()) Text("Message NISU AI...", fontSize = 15.sp, color = Color(0xFF5B6E99))
                            inner()
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(36.dp).clip(circle)
                        .background(if (ready) Brush.horizontalGradient(listOf(NisuUi.Cyan, NisuUi.Blue)) else Brush.horizontalGradient(listOf(NisuUi.Cyan.copy(alpha = 0.12f), NisuUi.Blue.copy(alpha = 0.18f))))
                        .border(1.dp, NisuUi.Cyan.copy(alpha = if (ready) 0.9f else 0.5f), circle)
                        .clickable(enabled = ready) { submit() },
                    contentAlignment = Alignment.Center
                ) { PlaneIcon(if (ready) Color.White else NisuUi.Cyan) }
            }
        }
    }
}

/** First screen of a new chat: big avatar, short hello and tappable starter messages. */
@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ChatAvatar(96.dp)
        Spacer(Modifier.height(16.dp))
        Text("Hi, I'm NISU AI", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = NisuUi.Text)
        Spacer(Modifier.height(6.dp))
        Text("Write to me in any language and I will answer in the same language.", fontSize = 14.sp, lineHeight = 20.sp, color = NisuUi.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(22.dp))
        SUGGESTIONS.chunked(2).forEach { row ->
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.widthIn(max = 170.dp).clip(shape).background(NisuUi.Card.copy(alpha = 0.6f)).border(1.dp, Color.White.copy(alpha = 0.25f), shape)
                            .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) { Text(s, fontSize = 13.sp, lineHeight = 18.sp, color = NisuUi.Text) }
                }
            }
        }
    }
}

@Composable
private fun timeText(t: Long): String =
    remember(t) { java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(t)) }

/** NISU AI side: round anime avatar on the left, content on the right. */
@Composable
private fun AiRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        ChatAvatar(44.dp)
        Spacer(Modifier.width(10.dp))
        content()
    }
}

@Composable
private fun MessageRow(l: TextChat.Line, onRetry: () -> Unit) {
    if (l.mine) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 8.dp, bottomStart = 26.dp)
                Box(
                    Modifier.widthIn(max = 290.dp).clip(shape)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF1E6BFF), Color(0xFF5B5BFF))))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) { SelectionContainer { Text(l.text, fontSize = 17.sp, lineHeight = 24.sp, color = Color.White) } }
                Row(Modifier.padding(top = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(timeText(l.time), fontSize = 13.sp, color = ChatTime)
                    Spacer(Modifier.width(6.dp))
                    DoubleCheck(NisuUi.Cyan)
                }
            }
        }
    } else {
        AiRow {
            Column {
                val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp)
                Box(
                    Modifier.widthIn(max = 290.dp).clip(shape)
                        .background(if (l.error) Brush.verticalGradient(listOf(NisuUi.Red.copy(alpha = 0.12f), NisuUi.Red.copy(alpha = 0.12f))) else Brush.verticalGradient(listOf(Color(0xFF0C1C44), Color(0xFF081634))))
                        .border(1.dp, if (l.error) NisuUi.Red.copy(alpha = 0.5f) else ChatBorder, shape)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) { SelectionContainer { Text(l.text, fontSize = 17.sp, lineHeight = 24.sp, color = if (l.error) NisuUi.Red else NisuUi.Text) } }
                if (l.error) Row(Modifier.padding(top = 8.dp)) { MChip("TRY AGAIN", NisuUi.Red) { onRetry() } }
                else Text(timeText(l.time), fontSize = 13.sp, color = ChatTime, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
    }
}

/** Three softly pulsing dots while NISU AI is writing her answer. */
@Composable
internal fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp)
    Row(Modifier.clip(shape).background(Color(0xFF0A1A3E)).border(1.dp, ChatBorder, shape).padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).clip(CircleShape).background(NisuUi.Cyan.copy(alpha = a)))
        }
    }
}

/** CLEAR pill with a trash icon. */
@Composable
private fun ClearButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val col = if (enabled) NisuUi.Cyan else NisuUi.Dim
    Row(
        Modifier.clip(shape).background(col.copy(alpha = 0.08f)).border(1.dp, col.copy(alpha = if (enabled) 0.6f else 0.35f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrashIcon(col)
        Spacer(Modifier.width(6.dp))
        Text("CLEAR", fontSize = 11.sp, letterSpacing = 1.sp, color = col, maxLines = 1)
    }
}

/** Four-point star next to the name. */
@Composable
private fun Sparkle(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val p = Path()
        p.moveTo(w * 0.5f, 0f)
        p.quadraticBezierTo(w * 0.5f, h * 0.5f, w, h * 0.5f)
        p.quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, h)
        p.quadraticBezierTo(w * 0.5f, h * 0.5f, 0f, h * 0.5f)
        p.quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, 0f)
        p.close()
        drawPath(p, tint, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun TrashIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawLine(tint, Offset(w * 0.12f, w * 0.26f), Offset(w * 0.88f, w * 0.26f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, w * 0.26f), Offset(w * 0.38f, w * 0.10f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.62f, w * 0.26f), Offset(w * 0.62f, w * 0.10f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, w * 0.10f), Offset(w * 0.62f, w * 0.10f), strokeWidth = sw, cap = StrokeCap.Round)
        val p = Path()
        p.moveTo(w * 0.22f, w * 0.26f)
        p.lineTo(w * 0.28f, w * 0.90f)
        p.lineTo(w * 0.72f, w * 0.90f)
        p.lineTo(w * 0.78f, w * 0.26f)
        drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.44f, w * 0.42f), Offset(w * 0.45f, w * 0.74f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.56f, w * 0.42f), Offset(w * 0.55f, w * 0.74f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
internal fun PlusIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.10f
        drawLine(tint, Offset(w * 0.5f, w * 0.18f), Offset(w * 0.5f, w * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Paper-plane send icon. */
@Composable
internal fun PlaneIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.09f
        val p = Path()
        p.moveTo(w * 0.10f, w * 0.12f)
        p.lineTo(w * 0.93f, w * 0.50f)
        p.lineTo(w * 0.10f, w * 0.88f)
        p.lineTo(w * 0.27f, w * 0.50f)
        p.close()
        drawPath(p, tint, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.27f, w * 0.50f), Offset(w * 0.58f, w * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Two ticks = message sent. */
@Composable
private fun DoubleCheck(tint: Color) {
    Canvas(Modifier.width(20.dp).height(12.dp)) {
        val w = size.width
        val h = size.height
        val st = Stroke(width = h * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val a = Path()
        a.moveTo(w * 0.02f, h * 0.55f); a.lineTo(w * 0.22f, h * 0.88f); a.lineTo(w * 0.60f, h * 0.10f)
        drawPath(a, tint, style = st)
        val b = Path()
        b.moveTo(w * 0.38f, h * 0.72f); b.lineTo(w * 0.42f, h * 0.88f); b.lineTo(w * 0.98f, h * 0.10f)
        drawPath(b, tint, style = st)
    }
}
