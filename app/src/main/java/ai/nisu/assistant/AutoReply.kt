package ai.nisu.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

/*
 * Automatic Reply System (step 10).
 *
 *   MessageProcessor -> MainBrain.onMessage -> OwnerWatch.admit (30 s wait) -> TaskManager: INCOMING_MESSAGE task, due at the end of the wait
 *   deadline: OwnerWatch.gate (no sign of a manual reply?) -> MainBrain.runIncoming
 *       AutoReply.preflight   limit already reached?  -> no AI call; queue the ONE closing message instead (or nothing, if it was sent)
 *       Master Agent (WhatsApp / Messenger)  -> shared MainBrainReplyWriter -> draft kept in the agent's memory
 *       Agent's automation hook = WhatsAppAutoReply / MessengerAutoReply -> AutoReply.consider -> TaskManager.submitSend(SEND_REPLY)
 *   SEND_REPLY task (same queue, same single lane, same 3-5 s pause after a CONFIRMED send):
 *       OwnerWatch.gate again -> sender.precheck -> markAttempting -> sender.send -> Confirmed / NotSent / Uncertain
 *       The sender is NotificationReplySender (NotificationReply.kt): the notification's own Reply action. Nothing else.
 *
 * Rules that live in this file:
 * - Off by default. Master switch + one switch per app (Prefs.AUTO_REPLY, WA_AUTO, MSGR_AUTO). Switching off stops every send: the
 *   sender checks the switch again right before it sends, and waiting send tasks end as "auto_reply_off".
 * - Only a PRIVATE chat in which the other person already wrote, and only as an answer to that person's newest message. NISU AI never
 *   starts a conversation (a send needs an incoming message) and never answers in groups (a group has many people and a reply there
 *   is not "to a person"; the kind UNKNOWN is treated as not private).
 * - The limit: [AutoReplyLimits.MAX_REPLIES] per person per app. The count lives in the task database under a hash of (app package,
 *   chat name) and is changed in the same transaction that finishes the send task (see TaskStore.finish). It counts CONFIRMED sends
 *   (what the owner sees); a send whose delivery could not be verified may have reached the person, so it also counts against the
 *   limit (the limit can therefore never be exceeded; it can only be reached a little early). When the limit is reached, the next
 *   message of that person gets ONE closing message, and after it (sent or uncertain) the conversation is never answered again.
 *   The closing message is an answer to a new message, never a message of its own, and is not counted as one of the 20.
 * - Privacy: a message that asks who else wrote to Raj or what others sent gets the fixed refusal text, decided by [PrivacyGuard]
 *   (in addition to the rule the AI itself has). The AI never sees any other chat. Nothing here logs message text or names.
 *
 * Step 11 (Privacy.kt): the conversation lifecycle (paused / completed / blocked) is checked before a send is queued ([consider]), before the
 * AI is asked ([preflight]) and by the sender right before it fires; an AI reply is also checked for private data of other chats, API keys
 * and the AI's own instructions (PrivacyChecks) in the shared generator and again in the sender. The limit is never reset by a restart: it is
 * read from the task database every time.
 *
 * Message text, sender and chat names are never logged; only short codes.
 */

object AutoReplyLimits {
    /** Default number of automatic replies per person per app. The ACTIVE limit is the user's choice: use [max]. */
    const val MAX_REPLIES = 20
    /** v2.21: a person who reached the limit is refreshed (counter 0, answered again) this long after the last automatic reply. */
    const val REFRESH_MS = 60L * 60 * 1000
    /** The active limit (Settings: Unlimited / 20 / 50). Int.MAX_VALUE = unlimited, so every "used >= max" check is simply never true. */
    fun max(): Int = ReplySettings.maxReplies()
    /** The active limit as text for the screens ("20", "50" or "unlimited"). */
    fun maxText(): String = if (ReplySettings.limit().unlimited) "unlimited" else ReplySettings.maxReplies().toString()
    const val CLOSING_TEXT = "Sorry, my time to reply has ended. Please message again later when Raj is back."
    const val PRIVACY_TEXT = "Raj has not permitted me to share that information."
    /** A reply longer than this is never sent (the generator already limits it; this is a last guard). */
    const val MAX_REPLY_CHARS = 2000
    /** How long the sender looks for its own line in the chat notification before it gives up and calls the send uncertain. */
    const val VERIFY_MS = 15_000L
    const val VERIFY_POLL_MS = 500L
}

/**
 * The switches (step 10) and the owner's pause (step 13). Off by default: nothing is ever sent until the owner turns it on.
 * This is the ONE place that answers "may NISU AI send automatically right now?". Every check in the pipeline (agent hooks,
 * AutoReply.consider / preflight, the sender's precheck, send, and the last look before the reply action is fired) goes through it.
 * The Automation Control Panel (AutomationControl.kt) only changes the stored values and cancels waiting tasks; it never decides.
 */
object AutoReplySettings {
    /** Machine-readable reasons why automation does not run. Also the outcome codes of tasks cancelled by the control panel. */
    const val OFF = "auto_reply_off"
    const val PAUSED = "auto_reply_paused"
    const val APP_OFF = "auto_reply_app_off"

    fun masterOn(ctx: Context): Boolean = Prefs.getBool(ctx, Prefs.AUTO_REPLY, Prefs.AUTO_REPLY_DEFAULT)

    /** Step 13: the owner paused automation. The master switch and the app switches stay as they were. */
    fun paused(ctx: Context): Boolean = Prefs.getBool(ctx, Prefs.AUTO_PAUSED, false)

    fun appOn(ctx: Context, source: MessageSource): Boolean = when (source) {
        MessageSource.WHATSAPP -> Prefs.getBool(ctx, Prefs.WA_AUTO, true)
        MessageSource.MESSENGER -> Prefs.getBool(ctx, Prefs.MSGR_AUTO, true)
    }

    /** null = automation may run for this app; otherwise the short code of the first switch that says no. */
    fun blockReason(ctx: Context, source: MessageSource): String? = when {
        !PremiumGate.isUnlocked(ctx) -> OFF
        !masterOn(ctx) -> OFF
        paused(ctx) -> PAUSED
        !appOn(ctx, source) -> APP_OFF
        else -> null
    }

    /** true only when the master switch AND the switch of this app are on AND automation is not paused. */
    fun enabled(ctx: Context, source: MessageSource): Boolean = blockReason(ctx, source) == null
}

/** What the UI may show: counters since the app started. No text, no names. */
data class AutoReplyStats(
    /** Replies whose delivery was confirmed. */
    val confirmed: Int = 0,
    /** Closing messages whose delivery was confirmed. */
    val closing: Int = 0,
    /** Sends that may or may not have gone out. */
    val uncertain: Int = 0,
    /** Sends that were refused or failed with certainty (nothing went out). */
    val notSent: Int = 0,
    /** Short code of the last refusal or uncertain result, or null. */
    val lastCode: String? = null
)

object AutoReplyState {
    val stats = MutableStateFlow(AutoReplyStats())

    internal fun confirmed(closing: Boolean) { stats.update { if (closing) it.copy(closing = it.closing + 1) else it.copy(confirmed = it.confirmed + 1) } }
    internal fun uncertain(code: String) { stats.update { it.copy(uncertain = it.uncertain + 1, lastCode = code) } }
    internal fun notSent(code: String) { stats.update { it.copy(notSent = it.notSent + 1, lastCode = code) } }
    internal fun reset() { stats.value = AutoReplyStats() }
}

/** Text of a chat line reduced to a short hash, so NISU AI can recognise its OWN line in a notification without keeping any text. */
internal object OwnText {
    private val SPACES = Regex("\\s+")
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    fun normalize(raw: String): String = SPACES.replace(INVISIBLE.replace(raw, ""), " ").trim()

    fun hash(raw: String): String = sha(normalize(raw), 12)

    internal fun sha(s: String, bytes: Int): String = try {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(bytes).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }
}

/**
 * Remembers, in memory only, which lines NISU AI itself is writing right now (hash of the text + start time, per conversation).
 * OwnerWatch uses it so that a line NISU AI wrote is not mistaken for a manual reply by Raj. After a process restart the memory is
 * empty: then NISU AI's old line may look like Raj's, which only makes the next wait stricter (a held conversation), never looser.
 */
internal object OwnSends {
    private class Entry(val hash: String, val startedAt: Long)

    /** A line counts as NISU AI's own if it was written this long after the send started. */
    private const val MATCH_WINDOW_MS = 5L * 60 * 1000
    /** After a send ends, the app's reaction (re-posting or clearing the notification) is still expected for this long. */
    private const val GRACE_MS = 15_000L
    private const val KEEP_MS = 30L * 60 * 1000

    private val lock = Any()
    private val entries = HashMap<String, MutableList<Entry>>()
    private val activeUntil = HashMap<String, Long>()

    fun begin(conversationId: String, hash: String, now: Long) {
        synchronized(lock) {
            val list = entries.getOrPut(conversationId) { ArrayList() }
            list.removeAll { now - it.startedAt > KEEP_MS }
            list.add(Entry(hash, now))
            activeUntil[conversationId] = now + AutoReplyLimits.VERIFY_MS + GRACE_MS
            if (entries.size > 200) entries.entries.removeAll { e -> e.value.all { now - it.startedAt > KEEP_MS } }
        }
    }

    fun end(conversationId: String, now: Long) {
        synchronized(lock) { activeUntil[conversationId] = now + GRACE_MS }
    }

    fun inProgress(conversationId: String, now: Long): Boolean = synchronized(lock) { (activeUntil[conversationId] ?: 0L) > now }

    /** v2.0: true when NISU AI itself sent a text with this hash in any conversation recently (so the line is not a new incoming message, and is never read out loud). */
    fun wroteRecently(hash: String, now: Long): Boolean = synchronized(lock) {
        entries.values.any { l -> l.any { it.hash == hash && now - it.startedAt <= KEEP_MS } }
    }

    /** true when [line] is a line NISU AI wrote itself (same text hash, written after one of its sends started). */
    fun isMine(conversationId: String, line: OwnLine, @Suppress("UNUSED_PARAMETER") now: Long): Boolean = synchronized(lock) {
        entries[conversationId]?.any {
            it.hash == line.hash && line.at >= it.startedAt - OwnerLimits.SKEW_MS && line.at <= it.startedAt + MATCH_WINDOW_MS
        } == true
    }
}

/**
 * Backstop for the privacy rule. The AI has the same rule, but the refusal must not depend on the AI obeying it: a message that
 * asks who else messaged Raj, or what other people wrote, gets the fixed refusal text and nothing else. Plain phrase matching in
 * English, Bengali script and Bengali in English letters. It errs towards refusing: a harmless message that happens to match
 * gets the polite refusal, never a leak. The reply to such a message never contains anything from any chat.
 */
internal object PrivacyGuard {
    private val PHRASES = listOf(
        // English
        "who messaged", "who texted", "who else messaged", "who else texted", "who has messaged", "who has texted", "who has been messaging",
        "who is messaging", "who is texting", "who all messaged", "who called", "who contacted", "who wrote to", "who sent raj", "who messages raj",
        "anyone else messaged", "anyone else texted", "anyone else wrote", "what did others", "what others sent", "what others said", "what other people",
        "what did they say to raj", "other messages", "other chats", "other conversations", "messages from others", "raj's messages", "raj's chats",
        "rajs messages", "his messages", "his chats", "read his messages", "show me his", "forward me", "last message from",
        // Bengali script
        "কে মেসেজ", "কারা মেসেজ", "কে কে মেসেজ", "কে ম্যাসেজ", "কারা ম্যাসেজ", "কে ফোন", "কে লিখেছে", "কে কী পাঠিয়েছে", "কে কি পাঠিয়েছে",
        "অন্য কেউ", "অন্যরা কী", "অন্যরা কি", "অন্যদের মেসেজ", "রাজের মেসেজ", "রাজের চ্যাট", "কার কার মেসেজ", "মেসেজ দেখাও", "মেসেজ পড়ে শোনাও",
        // Bengali in English letters
        "ke message", "ke msg", "ke ke message", "ke ke msg", "kara message", "kara msg", "kar kar message", "kar kar msg", "onno ke", "onyo ke",
        "onnora ki", "onno der message", "raj er message", "raj er chat", "raj er msg", "ke ki pathiyeche", "ke likhse", "ke likhechhe", "ke text korse"
    )

    private val SPACES = Regex("\\s+")

    fun asksAboutOthers(raw: String): Boolean {
        val t = SPACES.replace(raw.lowercase(Locale.ROOT).replace('\u2019', '\''), " ")
        return PHRASES.any { t.contains(it) }
    }
}

object AutoReply {
    private const val TAG = "NisuAutoReply"

    /** Names ONE person on ONE app without any name: hash of (app package, normalised chat name). The reply counter is kept under it. */
    internal fun counterKey(m: ProcessedMessage): String = counterKeyOf(m.packageName, m.chatTitle)

    /** The same key from an app package and a chat title (the conversation lifecycle uses it too, so one person has one key everywhere). */
    internal fun counterKeyOf(packageName: String, chatTitle: String): String =
        "k_" + OwnText.sha(packageName + "|" + MessageStore.chatKey(chatTitle), 16)

    /** v2.0: true while nothing was ever sent to this person (the first reply introduces NISU AI as Raj's AI assistant). Unreadable = true: an intro twice is harmless. */
    internal fun isFirstReply(app: Context, m: ProcessedMessage): Boolean = try {
        (TaskManager.openStore(app)?.replyCounts(counterKey(m))?.used ?: 0) == 0
    } catch (_: Throwable) { true }

    /** v2.31: every chat of the right app except a GROUP (a chat whose kind Android did not reveal is a direct chat). */
    internal fun answerable(m: ProcessedMessage, source: MessageSource): Boolean =
        m.source == source && MessageSource.fromPackage(m.packageName) == source && m.kind != ConversationKind.GROUP && m.text.isNotBlank()

    /**
     * Called by an agent's automation hook after it prepared a draft. Decides whether to queue the send of that draft. The queued
     * task holds ids only; the sender reads the draft from the agent and checks everything again when the task runs.
     */
    internal suspend fun consider(app: Context, source: MessageSource, conversationId: String, messageId: Long): AutomationDecision =
        withContext(Dispatchers.IO) {
            try {
                if (!AutoReplySettings.enabled(app, source)) { AutoReplyLog.add("consider", "switch_" + (AutoReplySettings.blockReason(app, source) ?: "off"), true); return@withContext AutomationDecision.NotEnabled }
                val m = MessageGateway.message(app, conversationId, messageId) ?: return@withContext AutomationDecision.Skipped("message_gone").also { AutoReplyLog.add("consider", "message_gone", true) }
                if (!answerable(m, source)) return@withContext AutomationDecision.Skipped("not_private_chat").also { AutoReplyLog.add("consider", source.name.take(4) + "_not_private_chat_kind_" + m.kind.name, true) }
                val store = TaskManager.openStore(app) ?: return@withContext AutomationDecision.Skipped("store_unavailable")
                val key = counterKey(m)
                // Step 11: a paused, completed or blocked conversation (or one whose state cannot be read) is never answered.
                ConversationLifecycle.refusal(store, key)?.let { return@withContext AutomationDecision.Skipped(it).also { _ -> AutoReplyLog.add("consider", source.name.take(4) + "_conversation_" + it, true) } }
                if (store.replyCounts(key).used >= AutoReplyLimits.max()) return@withContext AutomationDecision.Skipped("limit_reached").also { AutoReplyLog.add("consider", source.name.take(4) + "_limit_reached", true) }
                val elig = OwnerWatch.eligibility(app, conversationId)
                if (elig != OwnerEligibility.ELIGIBLE) return@withContext AutomationDecision.Skipped("owner_not_eligible").also { AutoReplyLog.add("consider", source.name.take(4) + "_owner_not_eligible_" + elig.name, true) }
                // v3.42: a reply for an OLDER message that is still waiting is replaced by this newer draft (it answers the whole recent chat), so two replies never go out together
                try { store.cancelPendingReplySendsBefore(conversationId, messageId, "superseded_by_newer_draft", System.currentTimeMillis()).let { if (it > 0) AutoReplyLog.add("burst", source.name.take(4) + "_older_unsent_reply_replaced") } } catch (_: Throwable) { /* never block a reply because of this */ }
                when (val r = TaskManager.submitSend(SendRequest(source, m.packageName, conversationId, messageId, closing = false, counterKey = key))) {
                    is SubmitResult.Accepted -> { AutoReplyLog.add("consider", source.name.take(4) + "_reply_queued"); AutomationDecision.Queued(r.taskId) }
                    is SubmitResult.Duplicate -> AutomationDecision.Skipped("already_queued")
                    is SubmitResult.Rejected -> AutomationDecision.Skipped(r.reason)
                }.also { d -> if (d is AutomationDecision.Skipped) AutoReplyLog.add("consider", source.name.take(4) + "_not_queued_" + d.code, true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "consider failed: " + e.javaClass.simpleName)
                AutomationDecision.Skipped("error")
            }
        }

    /**
     * Called by the Main Brain before it hands an incoming task to a Master Agent. A person who already got the maximum number of
     * replies is not answered by the AI any more: the ONE closing message is queued (once), or nothing happens when it was already
     * sent. Returns the verdict that ends the task, or null = not a limit case, the agent runs as before.
     */
    internal suspend fun preflight(app: Context, task: AgentTask, m: ProcessedMessage): TaskVerdict? =
        withContext(Dispatchers.IO) {
            try {
                val source = task.source
                if (!AutoReplySettings.enabled(app, source) || !answerable(m, source)) return@withContext null
                val store = TaskManager.openStore(app) ?: return@withContext null
                val key = counterKey(m)
                // Step 11: not ACTIVE (or unreadable): no AI call and no closing message.
                ConversationLifecycle.refusal(store, key)?.let { return@withContext TaskVerdict.Done(it) }
                val c = store.replyCounts(key)
                if (c.used < AutoReplyLimits.max()) return@withContext null
                if (c.closingDone) {
                    // Limit reached and the closing message was handled earlier (also before this version existed): the conversation is completed.
                    ConversationLifecycle.markCompleted(store, key, source, System.currentTimeMillis())
                    return@withContext TaskVerdict.Done("reply_limit_reached")
                }
                if (store.hasOpenClosing(key)) return@withContext TaskVerdict.Done("reply_limit_reached")
                when (TaskManager.submitSend(SendRequest(source, m.packageName, m.conversationId, m.messageId, closing = true, counterKey = key))) {
                    is SubmitResult.Accepted -> TaskVerdict.Done("closing_queued")
                    is SubmitResult.Duplicate -> TaskVerdict.Done("closing_already_queued")
                    is SubmitResult.Rejected -> TaskVerdict.Done("closing_not_queued")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "preflight failed: " + e.javaClass.simpleName)
                null   // the agent runs as usual; consider() and the sender enforce the limit again
            }
        }
}

/** The automation hook of the WhatsApp Master Agent (the socket of step 5). */
class WhatsAppAutoReply(private val app: Context) : WhatsAppAutomation {
    override val enabled: Boolean get() = AutoReplySettings.enabled(app, MessageSource.WHATSAPP)
    override suspend fun onDraftPrepared(request: AutoReplyRequest): AutomationDecision =
        AutoReply.consider(app, MessageSource.WHATSAPP, request.conversationId, request.messageId)
}

/** The automation hook of the Messenger Master Agent (the socket of step 6). */
class MessengerAutoReply(private val app: Context) : MessengerAutomation {
    override val enabled: Boolean get() = AutoReplySettings.enabled(app, MessageSource.MESSENGER)
    override suspend fun onDraftPrepared(request: MessengerAutoReplyRequest): AutomationDecision =
        AutoReply.consider(app, MessageSource.MESSENGER, request.conversationId, request.messageId)
}
