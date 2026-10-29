package ai.nisu.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Privacy Protection and Conversation Management (step 11).
 *
 * Nothing here is a new system. It adds two things to the pipeline that steps 3-10 built, and uses their database and queue:
 *
 * 1. CONVERSATION LIFECYCLE ([ConversationLifecycle])
 *    One state per person on one app, under the same hash the reply counter uses (AutoReply.counterKeyOf: app package + chat name,
 *    no name stored). It lives in the task database (table conversation_lifecycle, TaskStore). No row = ACTIVE.
 *      ACTIVE     automation may run.
 *      PAUSED     Raj answered by hand while NISU AI was handling the conversation (set by OwnerWatch). Stays until Raj resumes it.
 *      COMPLETED  the reply limit was reached and the one closing message was handled (set in the same transaction that counts the send).
 *      BLOCKED    Raj blocked the person (Settings). Also deletes the stored messages of that chat.
 *    Automatic changes only go up (ACTIVE < PAUSED < COMPLETED < BLOCKED); only Raj lowers a state. It is checked at five places:
 *    MainBrain.decide (no task at all), TaskManager.pickAndClaim (before any task starts), AutoReply.consider / preflight (before a
 *    send is queued), NotificationReplySender.prepare (before a send) and once more right before the reply action is fired.
 *    A state that cannot be read from the database is treated as "do not send", never as ACTIVE.
 *
 * 2. PRIVACY CHECKS ON WHAT IS SENT ([PrivacyChecks])
 *    The AI is told the privacy rule, but a prompt is not a guarantee, so every AI reply is also checked by code, twice: when the
 *    shared generator returns it (MainBrainReplyWriter) and again right before the send (NotificationReplySender.prepare). A reply
 *    that contains an API key, a long piece of the AI's own instructions, the name of another chat or contact, or a longer piece of
 *    another conversation's messages is not used. The check compares plain text locally; nothing is sent anywhere and nothing is logged
 *    except a short code.
 *
 *    What this does NOT do: it cannot recognise a leak that is paraphrased, translated or hinted at. The real protection is isolation:
 *    the AI only ever receives the one conversation it answers (MainBrainReplyWriter drops every other line, names become labels), so
 *    it has nothing of another chat to reveal. The check is the backstop for what could still slip through (an echoed name, a
 *    hallucinated or injected quote, a copied instruction).
 *
 * Message text, sender names and chat names are never logged here. Logs contain short codes only.
 */

/** What the UI may show about the privacy checks: counters since the background service started. No text, no names. */
data class PrivacyStats(
    /** AI replies that were not used because a privacy check stopped them. */
    val repliesBlocked: Int = 0,
    /** Messages that asked about other people's messages and got the fixed refusal. */
    val refusals: Int = 0,
    /** Short code of the last blocked reply, or null. */
    val lastCode: String? = null
)

object PrivacyState {
    val stats = MutableStateFlow(PrivacyStats())

    internal fun blocked(code: String) { stats.update { it.copy(repliesBlocked = it.repliesBlocked + 1, lastCode = code) } }
    internal fun refusal() { stats.update { it.copy(refusals = it.refusals + 1) } }
    internal fun reset() { stats.value = PrivacyStats() }
}

object ConversationLifecycle {
    private const val TAG = "NisuLifecycle"

    /** Code of a refusal caused by an unreadable state (as opposed to a real PAUSED / COMPLETED / BLOCKED). */
    internal const val UNAVAILABLE = "lifecycle_unavailable"

    /** How many conversations are held back right now. For the Settings screen; refreshed after every change. */
    val counts = MutableStateFlow(LifecycleCounts())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The key of one person on one app. Same as the reply counter's key. */
    fun keyFor(packageName: String, chatTitle: String): String = AutoReply.counterKeyOf(packageName, chatTitle)

    /**
     * null = automation may go on (state ACTIVE). Otherwise a short code: "conversation_paused", "conversation_completed",
     * "conversation_blocked", or [UNAVAILABLE] when the state could not be read. Blocking call: use it off the main thread.
     */
    internal fun refusal(store: TaskStore, key: String): String? = try {
        // v2.21: a person whose limit was reached at least an hour ago starts again (checked whenever their state is asked for)
        store.refreshLimitDue(System.currentTimeMillis(), AutoReplyLimits.max(), AutoReplyLimits.REFRESH_MS, key)
        val s = store.lifecycleGet(key)
        when {
            s == ConvState.ACTIVE -> null
            // v2.31: an old "owner replied" pause (set automatically) does not hold anybody back any more
            s == ConvState.PAUSED && ReplyPolicy.OPEN && store.lifecycleReasonOf(key) == "owner_replied" -> null
            // v2.21: "Unlimited" is on: a closure caused by the reply limit does not apply
            s == ConvState.COMPLETED && ReplySettings.limit().unlimited && store.lifecycleReasonOf(key) == "reply_limit_reached" -> null
            else -> "conversation_" + s.name.lowercase(Locale.ROOT)
        }
    } catch (e: Exception) {
        Log.w(TAG, "state unreadable: " + e.javaClass.simpleName)
        UNAVAILABLE
    }

    internal fun publish(store: TaskStore) {
        try { counts.value = store.lifecycleCounts() } catch (_: Exception) {}
    }

    /**
     * Raj answered by hand while NISU AI was handling the conversation: pause it. A group is never answered, so nothing is paused for one.
     * A database error is logged and ignored: the owner check has already stopped the send that was in progress.
     */
    internal fun pauseForOwner(store: TaskStore, packageName: String, chat: String, kind: ConversationKind, now: Long) {
        if (kind == ConversationKind.GROUP || ReplyPolicy.OPEN) return   // v2.31: a manual reply no longer pauses a person
        val source = MessageSource.fromPackage(packageName) ?: return
        try {
            if (store.lifecycleRaise(keyFor(packageName, chat), source, ConvState.PAUSED, "owner_replied", now)) {
                Log.i(TAG, "conversation paused: owner replied")
                publish(store)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pause failed: " + e.javaClass.simpleName)
        }
    }

    /** The reply limit was reached and the closing message was already handled: the conversation is completed. */
    internal fun markCompleted(store: TaskStore, key: String, source: MessageSource, now: Long) {
        try {
            if (store.lifecycleRaise(key, source, ConvState.COMPLETED, "reply_limit_reached", now)) publish(store)
        } catch (e: Exception) {
            Log.w(TAG, "complete failed: " + e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ the owner's own decisions (Settings)

    private fun packagesOf(source: MessageSource): List<String> = when (source) {
        MessageSource.WHATSAPP -> Apps.WHATSAPP
        MessageSource.MESSENGER -> Apps.MESSENGER
    }

    suspend fun refreshCounts(ctx: Context) {
        withContext(Dispatchers.IO) { TaskManager.openStore(ctx)?.let { publish(it) } }
    }

    /** Resumes every PAUSED conversation. COMPLETED and BLOCKED ones stay as they are. Returns how many were resumed. */
    suspend fun resumePaused(ctx: Context): Int = withContext(Dispatchers.IO) {
        val store = TaskManager.openStore(ctx) ?: return@withContext 0
        try {
            val n = store.lifecycleResumePaused()
            publish(store)
            TaskManager.lifecycleChanged()
            n
        } catch (e: Exception) {
            Log.w(TAG, "resume failed: " + e.javaClass.simpleName)
            0
        }
    }

    /**
     * Blocks one person on one app (all packages of that app, e.g. WhatsApp and WhatsApp Business) and deletes the stored messages of
     * that chat. Automation never touches the conversation again. [chatTitle] is the name exactly as the app shows it.
     * Returns false when nothing could be done.
     */
    suspend fun block(ctx: Context, source: MessageSource, chatTitle: String): Boolean = setByOwner(ctx, source, chatTitle, ConvState.BLOCKED)

    /** Lifts any hold (paused, completed or blocked) from one person on one app. A person who reached the reply limit still cannot get more replies: the counter is separate. */
    suspend fun unblock(ctx: Context, source: MessageSource, chatTitle: String): Boolean = setByOwner(ctx, source, chatTitle, ConvState.ACTIVE)

    private suspend fun setByOwner(ctx: Context, source: MessageSource, chatTitle: String, state: ConvState): Boolean = withContext(Dispatchers.IO) {
        if (chatTitle.isBlank()) return@withContext false
        val store = TaskManager.openStore(ctx) ?: return@withContext false
        val now = System.currentTimeMillis()
        try {
            for (pkg in packagesOf(source)) {
                store.lifecycleSet(keyFor(pkg, chatTitle), source, state, if (state == ConvState.ACTIVE) null else "blocked_by_owner", now)
            }
            if (state == ConvState.BLOCKED) {
                val ms = MessageProcessor.store(ctx)
                if (ms != null) for (pkg in packagesOf(source)) ms.deleteChat(pkg, MessageStore.chatKey(chatTitle))
            }
            publish(store)
            TaskManager.lifecycleChanged()
            true
        } catch (e: Exception) {
            Log.w(TAG, "owner action failed: " + e.javaClass.simpleName)
            false
        }
    }

    /** Fire-and-forget versions for the Settings screen (no coroutine scope needed there). */
    fun resumePausedAsync(ctx: Context) { val app = ctx.applicationContext; scope.launch { resumePaused(app) } }

    fun setBlockedAsync(ctx: Context, source: MessageSource, chatTitle: String, blocked: Boolean) {
        val app = ctx.applicationContext
        scope.launch { if (blocked) block(app, source, chatTitle) else unblock(app, source, chatTitle) }
    }
}

/**
 * Looks at an AI reply before it is used. Returns a short code when the reply must not be used, null when nothing was found.
 * Implemented by [PrivacyOutputGuard]; the shared generator only knows this interface.
 */
interface OutputGuard {
    suspend fun violation(conversationId: String, reply: String, extraSystemText: String): String?
}

internal class PrivacyOutputGuard(private val app: Context) : OutputGuard {
    override suspend fun violation(conversationId: String, reply: String, extraSystemText: String): String? =
        withContext(Dispatchers.IO) { PrivacyChecks.violation(app, conversationId, reply, extraSystemText) }
}

internal object PrivacyChecks {
    private const val TAG = "NisuPrivacy"

    /** The other conversations could not be read, so the reply cannot be checked: it is not used. May succeed on a retry. */
    const val UNAVAILABLE = "context_unavailable"

    /** Number of consecutive words that count as "the same text". */
    private const val SHINGLE = 6
    /** Newest messages of OTHER conversations compared with a reply. Bounded so the check stays fast. */
    private const val MAX_OTHER_MESSAGES = 1500
    /** A short message of another chat (fewer than [SHINGLE] words) only counts when the reply contains all of it and it is this long. */
    private const val MIN_WHOLE_MESSAGE_CHARS = 15
    /** Names of the owner: replies talk about him, so a contact with that name must not block every reply. */
    private val OWNER_NAMES = setOf("raj")

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")   // letters, marks (Bengali vowel signs are marks) and digits stay
    private val API_KEY_SHAPE = Regex("AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{20,}")

    private val RULE_SHINGLES: Set<String> by lazy {
        // The fixed refusal sentence is part of the rules and is the one thing the AI is supposed to say, so it is not a leak.
        shingles(words(MainBrainReplyWriter.CORE_RULES.replace(AutoReplyLimits.PRIVACY_TEXT, " "))) +
            shingles(words(MainBrainReplyWriter.FRIEND_RULES.replace(AutoReplyLimits.PRIVACY_TEXT, " ")))
    }
    private val ALLOWED_EXACT: Set<String> by lazy { setOf(norm(AutoReplyLimits.PRIVACY_TEXT), norm(AutoReplyLimits.CLOSING_TEXT)) }

    fun norm(s: String): String = NON_WORD.replace(s.lowercase(Locale.ROOT), " ").trim()

    private fun words(s: String): List<String> {
        val n = norm(s)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }

    private fun shingles(w: List<String>): Set<String> {
        if (w.size < SHINGLE) return emptySet()
        val out = HashSet<String>()
        for (i in 0..w.size - SHINGLE) out.add(w.subList(i, i + SHINGLE).joinToString(" "))
        return out
    }

    /**
     * Blocking: call it off the main thread. [conversationId] is the conversation the reply is for; everything else in the message
     * store is "another conversation". [extraSystemText] is the app-specific part of the AI instructions (may be empty).
     */
    fun violation(app: Context, conversationId: String, reply: String, extraSystemText: String): String? {
        val code = find(app, conversationId, reply, extraSystemText)
        if (code != null) {
            PrivacyState.blocked(code)
            Log.i(TAG, "reply blocked: $code")
        }
        return code
    }

    private fun find(app: Context, conversationId: String, reply: String, extraSystemText: String): String? {
        val replyNorm = norm(reply)
        if (replyNorm.isEmpty()) return null
        if (replyNorm in ALLOWED_EXACT) return null

        // 1. secrets: the saved API key, or anything shaped like one
        val key = Prefs.get(app, Prefs.KEY, "").trim()
        if (key.length >= 8 && reply.contains(key)) return "secret"
        if (API_KEY_SHAPE.containsMatchIn(reply)) return "secret"
        // v2.31: nothing else is checked. Names, other chats' words and rule-like words never stop a reply (they blocked normal answers).
        if (ReplyPolicy.OPEN) return null

        // 2. the AI's own instructions: the fences of the prompt, or a run of words copied from the rules
        if (reply.contains("<<<") || reply.contains(">>>")) return "instructions"
        val replyShingles = shingles(replyNorm.split(' '))
        if (replyShingles.isNotEmpty()) {
            if (replyShingles.any { it in RULE_SHINGLES }) return "instructions"
            if (extraSystemText.isNotBlank()) {
                val extra = shingles(words(extraSystemText))
                if (replyShingles.any { it in extra }) return "instructions"
            }
        }

        // 3. other conversations: their names and their messages
        val ms = MessageProcessor.store(app) ?: return UNAVAILABLE
        val ctx = try {
            ms.privacyContext(conversationId, MAX_OTHER_MESSAGES)
        } catch (e: Exception) {
            Log.w(TAG, "context unreadable: " + e.javaClass.simpleName)
            return UNAVAILABLE
        }
        val padded = " $replyNorm "

        val ownIdent = HashSet<String>()
        for (n in ctx.ownIdentities) {
            val x = norm(n)
            if (x.isNotEmpty()) ownIdent.add(x)
        }
        val ownerNames = HashSet<String>(OWNER_NAMES)
        val savedName = norm(Prefs.get(app, Prefs.NAME, ""))
        if (savedName.isNotEmpty()) ownerNames.add(savedName)
        val ownTokens = HashSet<String>()
        for (id in ownIdent) ownTokens.addAll(id.split(' '))
        for (o in ownerNames) ownTokens.addAll(o.split(' '))
        for (t in ctx.ownTexts) ownTokens.addAll(words(t))

        for (raw in ctx.otherIdentities) {
            val id = norm(raw)
            if (id.length < 3 || id == "no name" || id in ownIdent || id in ownerNames) continue   // "no name" is the placeholder for a missing name
            // v2.31: a single SHORT word (up to 4 letters: "bhai", "mom", "ok", "tom") is an everyday word as much as a name. A reply that
            // uses it is normal, and the AI never receives any other chat, so it cannot be quoting that contact. Blocking it left many
            // people without any answer (log PRIV_other_identity_full_len_3). Longer names and full names are still checked.
            if (' ' !in id && id.length < 5) continue
            // v2.21: a single word that this very conversation already uses (a nickname such as "bhai", a common first name) is not a leak
            // when the reply repeats it; it only counts as another person's identity when this chat never uses it.
            if (padded.contains(" $id ") && !(' ' !in id && id in ownTokens)) {
                AutoReplyLog.add("privacy", "PRIV_other_identity_full_len_" + id.length, true)   // length only: never the name
                return "other_identity"
            }
            val first = id.substringBefore(' ')
            if (first != id && first.length >= 5 && first !in ownTokens && padded.contains(" $first ")) {
                AutoReplyLog.add("privacy", "PRIV_other_identity_first_len_" + first.length, true)
                return "other_identity"
            }
        }

        val ownShingles = HashSet<String>()
        for (t in ctx.ownTexts) ownShingles.addAll(shingles(words(t)))
        val ownJoined = " " + ctx.ownTexts.joinToString(" ") { norm(it) } + " "
        for (raw in ctx.otherTexts) {
            val w = words(raw)
            if (w.size >= SHINGLE) {
                if (replyShingles.isNotEmpty() && shingles(w).any { it in replyShingles && it !in ownShingles }) return "other_content"
            } else if (w.isNotEmpty()) {
                val n = w.joinToString(" ")
                if (n.length >= MIN_WHOLE_MESSAGE_CHARS && padded.contains(" $n ") && !ownJoined.contains(" $n ")) return "other_content"
            }
        }
        return null
    }
}
