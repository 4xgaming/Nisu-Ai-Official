package ai.nisu.assistant

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/*
 * Owner Availability Check and the 30-second waiting system (step 9).
 *
 *   new message --MainBrain.onMessage--> OwnerWatch.admit()      opens (or joins) the conversation's wait window, saved in SQLite
 *                                        TaskManager.submit()    the message's task is created with notBefore = deadline
 *   Raj writes  --NisuNotificationService--> OwnerReplyDetector --> MessageProcessor (same worker, same order as incoming messages)
 *                                        --> OwnerWatch.handleSignal()   reply seen / unclear line / chat opened or dismissed
 *   deadline    --TaskManager.pickAndClaim--> OwnerWatch.gate()   re-checks the conversation, then: Proceed / Wait / Cancel
 *
 * There is NO timer thread and no coroutine that sleeps per conversation. A timer is two persisted facts: the window row
 * ([OwnerRecord.deadline], table owner_state in the task database) and the waiting task whose not_before equals that deadline.
 * The Task Manager's dispatcher already sleeps until the earliest not_before, so a restart loses nothing: both facts are on disk.
 * One window per conversation (primary key), one task per message (database key), one running task at a time: no duplicate
 * timers, no duplicate tasks, no concurrent tasks.
 *
 * What the check can and cannot know (Android gives no "owner replied" event):
 * - SEEN: a MessagingStyle notification of the chat contains a line written by the device owner, with its own time. Happens when
 *   Raj answers through the notification's Reply button, or the app re-posts the chat with his line in it. This is the only
 *   positive proof of a manual reply, and it cancels the conversation's pending automation at once.
 * - NOT SEEN, but there are signs that Raj engaged: the chat's notification was opened, dismissed or removed by the app, or it is
 *   gone when the timer ends. Replying inside WhatsApp / Messenger itself looks exactly like this, so it is UNCERTAIN:
 *   automation is held (cancelled as "owner_uncertain"), NOT treated as "replied" and NOT treated as safe.
 * - NOT SEEN and nothing unusual: the notification is still there, the listener was connected the whole time, the app gave us
 *   exact message times. Only then is the conversation ELIGIBLE. That means "no sign of a manual reply was observed", not "proof".
 *   A reply from another device that leaves the notification untouched cannot be seen by any notification-based check.
 * - Detection unavailable (plain notification without MessagingStyle, listener off or reconnected during the wait, message not
 *   fresh, no notification key): UNCERTAIN, never ELIGIBLE.
 *
 * Step 10 (automatic replies): outgoing lines written by NISU AI itself look exactly like Raj's lines. Each own line now carries a
 * hash of its text ([OwnLine]); [OwnSends] (AutoReply.kt) remembers the hash and start time of every send in progress, and
 * [handleSignal] drops own lines that match it, so a line NISU AI wrote never counts as a manual reply. Any own line that does not
 * match (Raj's) still counts, also when it sits in the same notification. While a send is in progress, a chat notification that
 * the APP itself removes (it re-posts or clears it after a reply) is not read as "Raj opened the chat"; a click or a dismissal by
 * the user still is. The sender calls [eligibility] and re-reads the notification right before it sends.
 *
 * Step 11 (conversation lifecycle, Privacy.kt): when a manual reply is seen while NISU AI is handling a conversation (a wait is open, or the
 * reply came right after the message that opened it), the conversation is also PAUSED for good, not only for this wait: later messages of
 * that person get no task and no send until Raj resumes the conversation in Settings. "May have replied" (unclear signs) is NOT a pause.
 *
 * Privacy: records hold ids, times, a hash of the notification key and short codes, never message text, sender or chat names.
 * Chat names pass through [OwnerSignal] only to find the conversation id and are never stored or logged. Logs show codes only.
 */

/** Fixed limits of the owner check. */
object OwnerLimits {
    /** Default wait (Normal mode). The ACTIVE wait is the user's choice (Instant 0 s / Fast 15 s / Normal 30 s): use [waitMs]. */
    const val WAIT_MS = 30_000L
    /** How long Raj gets to answer by hand before automation may continue, read from Settings each time a window opens or grows. */
    fun waitMs(): Long = ReplySettings.delayMs()
    /** Newer messages join the running wait and may push the deadline, but never beyond this after the first message. */
    const val MAX_WINDOW_MS = 120_000L
    /** Own lines written this long before the message arrived still count as "after" it (small clock differences). */
    const val SKEW_MS = 10_000L
    /** A message seen later than this after it arrived (or one found in the shade at connect) is not waited for: held as uncertain. */
    const val FRESH_MS = 5L * 60 * 1000
    /** An ELIGIBLE answer is only good for this long; a task that starts later is cancelled as stale. */
    const val ELIGIBLE_TTL_MS = 10L * 60 * 1000
    /** Finished owner records are deleted after this long. */
    const val KEEP_MS = 3L * 24 * 60 * 60 * 1000
}

enum class OwnerPhase {
    /** No wait is running. The record only remembers when the owner last wrote. */
    IDLE,
    /** The 30 seconds are running (or ran out and the check has not been made yet). */
    WAITING,
    /** The wait is over and no sign of a manual reply was observed. The next automation stage may continue. */
    ELIGIBLE,
    /** A manual reply was seen. Pending automation of the conversation is cancelled. */
    OWNER_REPLIED,
    /** Raj may or may not have taken over, or the check could not observe it. Automation is held. Never assumed safe. */
    UNCERTAIN
}

/** Answer of [OwnerWatch.eligibility] for the later automation steps. */
enum class OwnerEligibility { ELIGIBLE, WAITING, OWNER_REPLIED, UNCERTAIN, UNKNOWN }

/** What the UI may show: how many conversations are in each phase right now. */
data class OwnerCounts(val waiting: Int = 0, val eligible: Int = 0, val ownerReplied: Int = 0, val uncertain: Int = 0)

/** The saved state of ONE conversation. No text, no names. */
internal data class OwnerRecord(
    val conversationId: String,
    val source: MessageSource,
    val packageName: String,
    val kind: ConversationKind,
    val phase: OwnerPhase,
    /** Short code: why the phase is what it is (letters, digits, _ only). */
    val reason: String?,
    /** When the first message of this window reached NISU AI (local clock). */
    val windowStart: Long,
    /** When the 30 seconds end. Later messages may move it, within [OwnerLimits.MAX_WINDOW_MS]. */
    val deadline: Long,
    /** An own line written at or after this time counts as a reply to this window. */
    val refAt: Long,
    val newestMessageId: Long,
    /** SHA-256 (cut) of the newest message's notification key: lets the check ask "is that notification still there?". */
    val notifHash: String?,
    /** false when a message of the window came without exact times (plain notification): own lines cannot be ordered. */
    val detectionOk: Boolean,
    /** Newest own line seen for this conversation (0 = none). */
    val lastOwnerAt: Long,
    /** Last time something unclear happened (0 = never). */
    val uncertainAt: Long,
    val decidedAt: Long,
    val updatedAt: Long
)

/** One line written by the device owner (or by NISU AI in the owner's name): its own time and a hash of its text. No text. */
internal class OwnLine(val at: Long, val hash: String)

/** What the notification listener noticed about Raj in ONE conversation. Not a data class: nothing prints the chat name. */
internal sealed class OwnerSignal {
    abstract val packageName: String
    /** The chat as Android named it. Only used to look up the conversation id. Never stored, never logged. */
    abstract val chat: String
    abstract val kind: ConversationKind

    /** A line written by the owner, with its own time. */
    class Replied(
        override val packageName: String, override val chat: String, override val kind: ConversationKind, val at: Long,
        /** Step 10: every own line of the notification with its time and a hash of its text (never the text), so NISU AI's own lines can be told apart. */
        val lines: List<OwnLine> = emptyList()
    ) : OwnerSignal()
    /**
     * A line that may be the owner's but cannot be attributed (same name as the owner in a group, or an own line without a time).
     * [at] is the line's own time, or 0 when it has none (then it can never be ruled out as old). Notifications repeat their history
     * on every update, so a line older than the wait is ignored exactly like an old reply.
     */
    class Unsure(override val packageName: String, override val chat: String, override val kind: ConversationKind, val at: Long, val code: String) : OwnerSignal()
    /** The chat's notification went away (opened, dismissed, or removed by the app). */
    class Engaged(override val packageName: String, override val chat: String, override val kind: ConversationKind, val code: String) : OwnerSignal()
}

/** Reads WhatsApp / Messenger notifications for lines written by the owner. [MessageParser] drops those lines on purpose. */
@Suppress("DEPRECATION")
internal object OwnerReplyDetector {
    private enum class Who { OWNER, OTHER, UNSURE }

    /** Removal reasons that mean "somebody dealt with the notification". Timeouts, package changes and our own cancels are not in it. */
    private val ENGAGED_REASONS = setOf(
        NotificationListenerService.REASON_CLICK, NotificationListenerService.REASON_CANCEL, NotificationListenerService.REASON_CANCEL_ALL,
        NotificationListenerService.REASON_APP_CANCEL, NotificationListenerService.REASON_APP_CANCEL_ALL,
        NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED
    )

    private fun usable(sbn: StatusBarNotification): Notification? {
        if (MessageSource.fromPackage(sbn.packageName) == null) return null
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_TRANSPORT) return null
        return n
    }

    /** The chat name exactly the way [MessageParser] derives it, so both find the same conversation. */
    private fun chatOf(e: android.os.Bundle): String? {
        val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return (convTitle ?: title)?.takeIf { it.lowercase() !in MessageParser.GENERIC_TITLES }
    }

    private fun kindOf(e: android.os.Bundle, hasMessages: Boolean): ConversationKind {
        val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            e.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION) ->
                if (e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) ConversationKind.GROUP else ConversationKind.PRIVATE
            hasMessages -> if (convTitle != null) ConversationKind.GROUP else ConversationKind.PRIVATE   // same rule as MessageParser
            else -> ConversationKind.UNKNOWN
        }
    }

    private fun selfPerson(e: android.os.Bundle): android.app.Person? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            if (Build.VERSION.SDK_INT >= 33) e.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)
            else e.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
        } catch (_: Exception) { null }
    }

    /**
     * Who wrote a line. Keys decide when both sides have one. Otherwise: no sender, "You" and "Me" are the device owner (the
     * convention [MessageParser] uses too). A sender with the owner's display name is only the owner in a private chat with
     * somebody else; in a group, or in a chat named like the owner, another person could carry that name, so it is UNSURE.
     */
    private fun classify(senderName: String?, senderKey: String?, selfName: String?, selfKey: String?, group: Boolean, chat: String): Who {
        if (senderKey != null && selfKey != null) return if (senderKey == selfKey) Who.OWNER else Who.OTHER
        val name = senderName?.trim().orEmpty()
        if (name.isEmpty()) return Who.OWNER
        if (name.equals("You", true) || name.equals("Me", true)) return Who.OWNER
        if (selfName != null && name == selfName) return if (group || chat == selfName) Who.UNSURE else Who.OWNER
        return Who.OTHER
    }

    /** Signals for lines the owner wrote in this notification (at most one Replied and one Unsure). Never reads message text. */
    fun scan(sbn: StatusBarNotification): List<OwnerSignal> {
        val n = usable(sbn) ?: return emptyList()
        val e = n.extras ?: return emptyList()
        val chat = chatOf(e) ?: return emptyList()
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr == null || arr.isEmpty()) return emptyList()   // plain notification: no own lines can be seen
        val kind = kindOf(e, true)
        val group = kind == ConversationKind.GROUP
        val self = selfPerson(e)
        val selfName = self?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val selfKey = self?.key
        var newestOwn = 0L
        val ownLines = ArrayList<OwnLine>()
        var unsureAt = 0L          // newest time of a line whose author is unclear
        var unsureNoTime = false   // an own-looking line without any time
        var unsureCode: String? = null
        for (m in Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)) {
            val senderName: String?
            val senderKey: String?
            if (Build.VERSION.SDK_INT >= 28) {
                val p = m.senderPerson
                senderName = p?.name?.toString()?.trim()
                senderKey = p?.key
            } else {
                senderName = m.sender?.toString()?.trim()
                senderKey = null
            }
            when (classify(senderName, senderKey, selfName, selfKey, group, chat)) {
                Who.OTHER -> {}
                Who.UNSURE -> {
                    unsureCode = "own_name_unclear"
                    if (m.timestamp > 0) unsureAt = maxOf(unsureAt, m.timestamp) else unsureNoTime = true
                }
                Who.OWNER ->
                    if (m.timestamp > 0) {
                        newestOwn = maxOf(newestOwn, m.timestamp)
                        ownLines.add(OwnLine(m.timestamp, OwnText.hash(m.text?.toString().orEmpty())))
                    } else { unsureCode = "own_line_no_time"; unsureNoTime = true }
            }
        }
        val out = ArrayList<OwnerSignal>(2)
        if (newestOwn > 0) out.add(OwnerSignal.Replied(sbn.packageName, chat, kind, newestOwn, ownLines))
        val code = unsureCode
        if (code != null) out.add(OwnerSignal.Unsure(sbn.packageName, chat, kind, if (unsureNoTime) 0L else unsureAt, code))
        return out
    }

    /** What a notification is: which chat (as Android names it), which kind, and whether it lists messages (needed to see owner lines). Null for anything else. */
    class Identity(val chat: String, val kind: ConversationKind, val hasMessages: Boolean)

    /** Step 10: lets the sender check that a notification really belongs to the conversation it is about to answer. */
    fun identify(sbn: StatusBarNotification): Identity? {
        val n = usable(sbn) ?: return null
        val e = n.extras ?: return null
        val chat = chatOf(e) ?: return null
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        val has = arr != null && arr.isNotEmpty()
        return Identity(chat, kindOf(e, has), has)
    }

    /** A chat's notification was removed: somebody (probably Raj) dealt with it. Null for removals that say nothing about Raj. */
    fun removal(sbn: StatusBarNotification, reason: Int): OwnerSignal? {
        if (reason !in ENGAGED_REASONS) return null
        val n = usable(sbn) ?: return null
        val e = n.extras ?: return null
        val chat = chatOf(e) ?: return null
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        return OwnerSignal.Engaged(sbn.packageName, chat, kindOf(e, arr != null && arr.isNotEmpty()), "removed_$reason")
    }
}

object OwnerWatch {
    private const val TAG = "NisuOwner"
    const val UNCERTAIN_CODE = "owner_uncertain"
    private const val STALE_CODE = "owner_check_stale"
    private const val MISSING_CODE = "owner_check_missing"

    /** Serialises every read-modify-write of owner records (message worker thread and dispatcher both write). Short sections only. */
    private val lock = Any()

    /** Answer to "may a task of this conversation start now?". */
    internal sealed class Gate {
        object Proceed : Gate()
        class Wait(val until: Long) : Gate()
        class Cancel(val reason: String) : Gate()
    }

    /** Answer of [admit]: the message gets a waiting task due at [Wait.deadline], or none. */
    internal sealed class Admission {
        class Wait(val deadline: Long) : Admission()
        class Skip(val reason: String) : Admission()
    }

    // ------------------------------------------------------------------ 1. a new message arrives (message worker thread)

    /**
     * Opens the conversation's wait window, or joins the running one. Idempotent for the same message (a repeated hand-over after
     * a crash changes nothing). Later messages share the one window: no second timer, the deadline moves to the newest message's
     * 30 seconds but never beyond [OwnerLimits.MAX_WINDOW_MS] after the first. Older waiting tasks of the conversation are moved
     * to the same deadline, so the order of messages is kept.
     */
    internal fun admit(store: TaskStore, m: ProcessedMessage, now: Long): Admission {
        if (ReplyPolicy.OPEN) return Admission.Wait(now)   // v2.31: no waiting window: the task is due at once
        val result = synchronized(lock) {
            val old = store.ownerGet(m.conversationId)
            if (old != null && old.phase != OwnerPhase.IDLE && old.newestMessageId == m.messageId) return@synchronized disposition(old)

            val base = minOf(m.receivedAt, now)   // when NISU AI saw it: the same clock as the owner's own line times
            val hash = keyHash(m.notificationKey)
            val joins = old != null && old.phase == OwnerPhase.WAITING && now < old.windowStart + OwnerLimits.MAX_WINDOW_MS
            val rec: OwnerRecord
            if (joins && old != null) {
                val cap = old.windowStart + OwnerLimits.MAX_WINDOW_MS
                rec = old.copy(
                    deadline = minOf(maxOf(old.deadline, base + OwnerLimits.waitMs()), cap),
                    newestMessageId = m.messageId, notifHash = hash ?: old.notifHash,
                    detectionOk = old.detectionOk && m.timestampExact, updatedAt = now
                )
            } else {
                val fresh = !m.isBacklog && now - base <= OwnerLimits.FRESH_MS
                val refAt = base - OwnerLimits.SKEW_MS
                val lastOwner = old?.lastOwnerAt ?: 0L
                val unclear = old?.uncertainAt ?: 0L
                var phase = OwnerPhase.WAITING
                var reason: String? = null
                if (!fresh) { phase = OwnerPhase.UNCERTAIN; reason = "not_fresh" }
                else if (lastOwner >= refAt) { phase = OwnerPhase.OWNER_REPLIED; reason = "reply_seen" }
                else if (unclear > 0L && unclear >= refAt) { phase = OwnerPhase.UNCERTAIN; reason = "unclear_signal" }
                rec = OwnerRecord(
                    conversationId = m.conversationId, source = m.source, packageName = m.packageName, kind = m.kind,
                    phase = phase, reason = reason, windowStart = base, deadline = base + OwnerLimits.waitMs(), refAt = refAt,
                    newestMessageId = m.messageId, notifHash = hash, detectionOk = m.timestampExact,
                    lastOwnerAt = lastOwner, uncertainAt = unclear, decidedAt = if (phase == OwnerPhase.WAITING) 0L else now, updatedAt = now
                )
            }
            store.ownerPut(rec)
            // Step 11: Raj had already answered this very message before the wait opened: the conversation is his now.
            if (rec.phase == OwnerPhase.OWNER_REPLIED) ConversationLifecycle.pauseForOwner(store, m.packageName, m.chatTitle, m.kind, now)
            disposition(rec)
        }
        if (result is Admission.Wait) store.delayPendingOfConversation(m.conversationId, result.deadline)
        return result
    }

    private fun disposition(r: OwnerRecord): Admission = when (r.phase) {
        OwnerPhase.WAITING, OwnerPhase.ELIGIBLE -> Admission.Wait(r.deadline)
        OwnerPhase.OWNER_REPLIED -> Admission.Skip("owner_replied")
        OwnerPhase.UNCERTAIN, OwnerPhase.IDLE -> Admission.Skip(UNCERTAIN_CODE)
    }

    // ------------------------------------------------------------------ 2. what the listener saw (message worker thread)

    /**
     * Applies one signal to its conversation. Runs on the message worker right behind the ingest jobs, so a line and the message
     * before it are seen in the order Android delivered them. A reply (or an unclear sign) while a wait or an eligibility is open
     * cancels the conversation's waiting AND running tasks at once; it never touches another conversation.
     */
    internal fun handleSignal(app: Context, ms: MessageStore, sig: OwnerSignal) {
        if (ReplyPolicy.OPEN) return   // v2.31: what the owner does by hand never cancels or pauses an automatic reply
        val source = MessageSource.fromPackage(sig.packageName) ?: return
        val conv = ms.findConversation(sig.packageName, sig.kind, sig.chat) ?: return   // unknown chat: nothing waits for it
        val store = TaskManager.openStore(app) ?: return
        val now = System.currentTimeMillis()
        var cancel: String? = null
        synchronized(lock) {
            val existing = store.ownerGet(conv)
            val open = existing != null && (existing.phase == OwnerPhase.WAITING || existing.phase == OwnerPhase.ELIGIBLE)
            when (sig) {
                is OwnerSignal.Replied -> {
                    // Step 10: lines NISU AI wrote itself are not Raj's. Only the remaining lines can be a manual reply.
                    var at = sig.at
                    if (sig.lines.isNotEmpty()) {
                        val mine = sig.lines.filter { !OwnSends.isMine(conv, it, now) }
                        if (mine.isEmpty()) return@synchronized Unit   // nothing but NISU AI's own lines
                        at = mine.maxOf { it.at }
                    }
                    var r = existing ?: idle(conv, source, sig.packageName, sig.kind, now)
                    r = r.copy(lastOwnerAt = maxOf(r.lastOwnerAt, at), updatedAt = now)   // remembered even when no wait is open
                    if (open && at >= r.refAt) {
                        r = r.copy(phase = OwnerPhase.OWNER_REPLIED, reason = "reply_seen", decidedAt = now)
                        cancel = TaskManager.OWNER_REPLIED
                        // Step 11: Raj answered by hand while NISU AI was handling this conversation: pause it (kept until he resumes it).
                        ConversationLifecycle.pauseForOwner(store, sig.packageName, sig.chat, sig.kind, now)
                    }
                    store.ownerPut(r)
                }
                is OwnerSignal.Unsure -> {
                    // A line older than the window (old history repeated by the notification) says nothing about this wait.
                    val stale = sig.at > 0L && existing != null && existing.refAt > 0L && sig.at < existing.refAt
                    if (!stale) {
                        var r = (existing ?: idle(conv, source, sig.packageName, sig.kind, now)).copy(uncertainAt = if (sig.at > 0L) sig.at else now, updatedAt = now)
                        if (open) { r = r.copy(phase = OwnerPhase.UNCERTAIN, reason = "unclear_signal", decidedAt = now); cancel = UNCERTAIN_CODE }
                        store.ownerPut(r)
                    }
                }
                is OwnerSignal.Engaged -> {
                    // Step 10: while NISU AI's own send is running, the app clearing or re-posting the notification is its normal reaction.
                    val byApp = sig.code == "removed_" + NotificationListenerService.REASON_APP_CANCEL ||
                        sig.code == "removed_" + NotificationListenerService.REASON_APP_CANCEL_ALL
                    if (byApp && OwnSends.inProgress(conv, now)) return@synchronized Unit
                    if (existing != null && open) {
                        store.ownerPut(existing.copy(phase = OwnerPhase.UNCERTAIN, reason = "chat_opened_or_dismissed", uncertainAt = now, decidedAt = now, updatedAt = now))
                        cancel = UNCERTAIN_CODE
                    }
                }
            }
            Unit
        }
        val why = cancel
        if (why != null) {
            val n = TaskManager.cancelForOwner(app, conv, why)
            Log.i(TAG, "owner check: " + why + ", tasks affected " + n)
        }
    }

    private fun idle(conv: String, source: MessageSource, pkg: String, kind: ConversationKind, now: Long) = OwnerRecord(
        conversationId = conv, source = source, packageName = pkg, kind = kind, phase = OwnerPhase.IDLE, reason = null,
        windowStart = 0L, deadline = 0L, refAt = 0L, newestMessageId = 0L, notifHash = null, detectionOk = true,
        lastOwnerAt = 0L, uncertainAt = 0L, decidedAt = 0L, updatedAt = now
    )

    // ------------------------------------------------------------------ 3. the timer ends (Task Manager dispatcher thread)

    /**
     * Asked by the Task Manager before it starts any task of a conversation. WAITING before the deadline: wait. At the deadline the
     * conversation is checked once and the answer is saved (so it cannot be decided twice). Only ELIGIBLE lets a task run.
     * OWNER_REPLIED and UNCERTAIN cancel the conversation's waiting tasks; an unknown conversation is never assumed safe.
     */
    internal fun gate(store: TaskStore, t: AgentTask, now: Long): Gate = synchronized(lock) {
        if (ReplyPolicy.OPEN) return@synchronized Gate.Proceed   // v2.31
        var rec = store.ownerGet(t.conversationId) ?: return@synchronized Gate.Cancel(MISSING_CODE)
        if (rec.phase == OwnerPhase.WAITING) {
            if (now < rec.deadline) return@synchronized Gate.Wait(rec.deadline)
            rec = settle(store, rec, now)
        }
        when (rec.phase) {
            OwnerPhase.ELIGIBLE -> if (now - rec.decidedAt <= OwnerLimits.ELIGIBLE_TTL_MS) Gate.Proceed else Gate.Cancel(STALE_CODE)
            OwnerPhase.OWNER_REPLIED -> Gate.Cancel(TaskManager.OWNER_REPLIED)
            OwnerPhase.UNCERTAIN -> Gate.Cancel(UNCERTAIN_CODE)
            OwnerPhase.WAITING -> Gate.Wait(rec.deadline)   // not reachable: settle() always leaves WAITING
            OwnerPhase.IDLE -> Gate.Cancel(MISSING_CODE)
        }
    }

    /** The one check at the end of the wait. Call with [lock] held. Saves and returns the decided record. */
    private fun settle(store: TaskStore, rec: OwnerRecord, now: Long): OwnerRecord {
        val (phase, reason) = evaluate(rec)
        val out = rec.copy(phase = phase, reason = reason, decidedAt = now, updatedAt = now)
        store.ownerPut(out)
        Log.i(TAG, "owner check settled: " + phase.name + " / " + reason)
        return out
    }

    private fun evaluate(rec: OwnerRecord): Pair<OwnerPhase, String> {
        if (rec.lastOwnerAt >= rec.refAt) return OwnerPhase.OWNER_REPLIED to "reply_seen"
        if (!rec.detectionOk) return OwnerPhase.UNCERTAIN to "detection_unavailable"
        if (rec.uncertainAt > 0L && rec.uncertainAt >= rec.refAt) return OwnerPhase.UNCERTAIN to "unclear_signal"
        // From here only the listener can tell us more, and only if it watched the whole wait.
        val svc = NisuNotificationService.inst ?: return OwnerPhase.UNCERTAIN to "listener_off"
        if (NisuNotificationService.connectedSince > rec.windowStart) return OwnerPhase.UNCERTAIN to "listener_gap"
        val hash = rec.notifHash ?: return OwnerPhase.UNCERTAIN to "no_notification_key"
        val present = try {
            svc.activeNotifications?.any { keyHash(it.key) == hash } == true
        } catch (_: Exception) {
            return OwnerPhase.UNCERTAIN to "notifications_unreadable"
        }
        if (!present) return OwnerPhase.UNCERTAIN to "notification_gone"   // opened, dismissed or replied inside the app: cannot tell which
        return OwnerPhase.ELIGIBLE to "no_reply_seen"
    }

    // ------------------------------------------------------------------ 4. housekeeping, status, API for later steps

    /** Decides windows that ran out without a waiting task (so counts are true after a restart) and deletes old records. */
    internal fun housekeeping(store: TaskStore, now: Long) {
        try {
            for (r in store.ownerExpiredWaiting(now)) synchronized(lock) {
                val cur = store.ownerGet(r.conversationId)
                if (cur != null && cur.phase == OwnerPhase.WAITING && now >= cur.deadline) settle(store, cur, now)
            }
            store.ownerPrune(now)
        } catch (e: Exception) {
            Log.w(TAG, "owner housekeeping problem: " + e.javaClass.simpleName)
        }
    }

    /**
     * For the later automation steps: where does this conversation stand? ELIGIBLE only while the answer is fresh. It is a
     * snapshot: Raj can still reply a second later, so a sender must also react to [TaskManager.OWNER_REPLIED] cancellations.
     */
    suspend fun eligibility(ctx: Context, conversationId: String): OwnerEligibility = withContext(Dispatchers.IO) {
        if (ReplyPolicy.OPEN) return@withContext OwnerEligibility.ELIGIBLE   // v2.31
        if (!TaskStore.validConversationId(conversationId)) return@withContext OwnerEligibility.UNKNOWN
        val store = TaskManager.openStore(ctx) ?: return@withContext OwnerEligibility.UNKNOWN
        val r = try { store.ownerGet(conversationId) } catch (_: Exception) { null } ?: return@withContext OwnerEligibility.UNKNOWN
        when (r.phase) {
            OwnerPhase.WAITING -> OwnerEligibility.WAITING
            OwnerPhase.ELIGIBLE -> if (System.currentTimeMillis() - r.decidedAt <= OwnerLimits.ELIGIBLE_TTL_MS) OwnerEligibility.ELIGIBLE else OwnerEligibility.UNCERTAIN
            OwnerPhase.OWNER_REPLIED -> OwnerEligibility.OWNER_REPLIED
            OwnerPhase.UNCERTAIN -> OwnerEligibility.UNCERTAIN
            OwnerPhase.IDLE -> OwnerEligibility.UNKNOWN
        }
    }

    /** Hash of a notification key. Lets the record answer "is it still there?" without keeping the key (it can contain a chat id). */
    internal fun keyHash(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return try {
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { null }
    }
}
