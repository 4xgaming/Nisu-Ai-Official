package ai.nisu.assistant

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/*
 * Task model and task storage (step 4).
 *
 * A task is a small record: WHAT kind of work, for WHICH conversation of WHICH app, caused by WHICH stored message, and how
 * far it got. It never contains message text, sender or chat names (the agent reads the message from the message store
 * at run time, through its own conversation only). It also has no free-form "command" field: the work type is a closed
 * enum, so nothing an AI or a message writes can become an instruction.
 *
 * Tasks live in their own SQLite file (private storage, not backed up) so they survive restarts of the app and the
 * service. [TaskStore] is the only code that touches that file; since step 8 only the Shared Task Manager (TaskManager.kt)
 * uses it. Step 8 added the task type SEND_REPLY and the column send_phase (database version 2).
 * Step 9 added the table owner_state (database version 3): one row per conversation with the 30-second owner wait
 * (OwnerWatch.kt). Ids, times and short codes only.
 * Step 10 added (database version 4) the columns send_kind / counter_key and the table reply_counter: the per-person, per-app
 * count of automatic replies (AutoReply.kt). The counter is changed in the SAME transaction that finishes the send task, so a
 * crash can never leave "sent" without the count, and the count survives restarts and the pruning of finished tasks.
 * Step 11 added (database version 5) the table conversation_lifecycle: the automation state (paused / completed / blocked) of one
 * person on one app, under the same hash as the reply counter (AutoReply.counterKeyOf). ACTIVE is the absence of a row. Keys,
 * states and short codes only; never names or text (see Privacy.kt).
 */

enum class TaskType {
    /** A new incoming message needs the attention of the app's agent. */
    INCOMING_MESSAGE,
    /**
     * Send the prepared draft that answers one incoming message (step 8: the queue side only). The task holds ids, never the
     * text: the sender reads the draft from the agent when the task runs. Nothing in this build creates such a task, because
     * no [MessageSender] exists yet; the queue, the persistence and the rules for it are in place for the later steps.
     */
    SEND_REPLY
}

/**
 * How far a SEND_REPLY got. This is what makes a restart safe: ATTEMPTING is written BEFORE the sender is called, so a task
 * found RUNNING in this phase after a crash is an uncertain send and is never repeated on its own.
 */
enum class SendPhase {
    /** Not a send, or the send was not started yet (nothing can have been sent). */
    NONE,
    /** The sender was (or is being) called. If the process dies now, nobody knows whether the message went out. */
    ATTEMPTING,
    /** The sender confirmed the delivery (or it was verified after a restart). The only phase that counts as sent. */
    CONFIRMED,
    /** The sender is certain that nothing was sent. */
    NOT_SENT,
    /** The message may or may not have been sent. Never retried automatically. */
    UNCERTAIN
}

/** Step 10: what a SEND_REPLY task carries. A normal answer, or the one polite closing message of a conversation that reached the limit. */
enum class SendKind { REPLY, CLOSING }

enum class TaskStatus {
    /** Waiting: for its turn, for an agent of its app, or for a retry delay. */
    PENDING,
    /** Claimed by exactly one runner and handed to an agent. */
    RUNNING,
    COMPLETED,
    FAILED,
    /** Cancelled by a user / code request, or dropped as expired / overflowing / no longer valid. [AgentTask.outcome] says which. */
    CANCELLED;

    val isFinal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class AgentTask(
    val id: String,
    val type: TaskType,
    val source: MessageSource,
    val packageName: String,
    /** The conversation this task belongs to. A task never spans conversations. */
    val conversationId: String,
    /** Store id of the message that caused the task ([ProcessedMessage.messageId]). */
    val messageId: Long,
    val status: TaskStatus,
    /** The agent that was given the task; null until it was first handed to one. */
    val agentId: String?,
    /** How many times the task was handed to an agent (1 while the first run is going on). */
    val attempts: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long,
    val finishedAt: Long,
    /** Do not start before this time (epoch ms). Used for retry delays. */
    val notBefore: Long,
    /** Short code describing how it ended or why it was dropped ("no_action", "timeout", "expired", ...). Never message text. */
    val outcome: String?,
    /** Only meaningful for SEND_REPLY tasks; [SendPhase.NONE] for everything else. */
    val sendPhase: SendPhase = SendPhase.NONE,
    /** Step 10, SEND_REPLY only: normal answer or closing message. */
    val sendKind: SendKind = SendKind.REPLY,
    /** Step 10, SEND_REPLY only: hash that names ONE person on ONE app (never a name). The reply counter is kept under it. */
    val counterKey: String? = null
)

/** Step 10: what has been sent automatically to one person on one app. No names, no text. */
data class ReplyCounts(
    /** Automatic replies whose delivery was CONFIRMED. The number the owner sees. */
    val confirmed: Int = 0,
    /** Sends whose delivery could not be verified. They may have gone out, so they also count against the limit. */
    val uncertain: Int = 0,
    /** The closing message was sent (or may have been sent: it is never tried twice). */
    val closingDone: Boolean = false
) {
    /** What counts against the limit: everything that may have reached the person. */
    val used: Int get() = confirmed + uncertain
}

/**
 * Step 11: automation state of one conversation (one person on one app). ACTIVE = nothing is held back.
 * Automatic changes only ever go UP the ranking (ACTIVE < PAUSED < COMPLETED < BLOCKED); only the owner brings a state back down.
 */
enum class ConvState(val rank: Int) {
    ACTIVE(0),
    /** Raj answered by hand: NISU AI stays out of this conversation until he resumes it. */
    PAUSED(1),
    /** The reply limit was reached and the closing message was handled: never answered again. */
    COMPLETED(2),
    /** Raj blocked this conversation: never answered. */
    BLOCKED(3);

    companion object {
        /** A state value that cannot be read counts as BLOCKED, never as ACTIVE. */
        fun fromName(raw: String?): ConvState = values().firstOrNull { it.name == raw } ?: BLOCKED
    }
}

/** Step 11: how many conversations are held back, by state. ACTIVE conversations have no row, so they are not counted. */
data class LifecycleCounts(val paused: Int = 0, val completed: Int = 0, val blocked: Int = 0)

/** Step 13: one held-back conversation as stored (hash key, never a name). [reason] is a short code or null. */
data class LifecycleRow(val key: String, val source: MessageSource, val state: ConvState, val reason: String?, val updatedAt: Long)

enum class ConversationPhase {
    /** Nothing waiting, nothing running. */
    IDLE,
    /** At least one task is waiting, none is running. */
    QUEUED,
    /** A task of this conversation is running right now. */
    BUSY
}

/** What the Main Brain knows about one conversation. No message text, no names. */
data class ConversationState(
    val conversationId: String,
    val source: MessageSource?,
    val phase: ConversationPhase,
    val activeTaskId: String?,
    val queuedTasks: Int,
    /** When the last task of this conversation reached a final state (0 = never). */
    val lastFinishedAt: Long,
    val lastOutcome: String?
)

data class TaskCounts(
    val pending: Int = 0, val running: Int = 0, val completed: Int = 0, val failed: Int = 0, val cancelled: Int = 0,
    /** Subset of [pending]: tasks whose app has no agent (or no sender) registered. */
    val waitingForAgent: Int = 0,
    /** Sends that may or may not have gone out (they ended FAILED, phase UNCERTAIN). They need a look by the owner and are never retried. */
    val uncertainSends: Int = 0
)

/** Fixed limits of the task system. */
object TaskLimits {
    /** An incoming message older than this is not worth acting on any more (also applies to tasks that waited this long). */
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    /** Waiting tasks kept at most. Beyond it the oldest are cancelled ("overflow"). */
    const val MAX_PENDING = 300
    /** Finished tasks are kept for a while for the status display, then deleted. */
    const val KEEP_FINISHED_MS = 3L * 24 * 60 * 60 * 1000
    const val MAX_FINISHED = 1000
    /** A task handed to an agent at most this many times (only retryable failures repeat). */
    const val MAX_ATTEMPTS = 3
    const val RETRY_BASE_MS = 5_000L
    /** Pause between two CONFIRMED sends (any app). The user picks a value in this range; see [Prefs.SEND_DELAY_MS]. */
    const val SEND_DELAY_MIN_MS = 2_000L
    const val SEND_DELAY_MAX_MS = 5_000L
    const val SEND_DELAY_DEFAULT_MS = 2_000L
    /** A send that has not answered after this long is an uncertain send, not a failed one. */
    const val SEND_TIMEOUT_MS = 120_000L
}

/** SQLite storage for tasks. Thread-safe; every status change is a conditional UPDATE so a task can only be claimed once. */
internal class TaskStore(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 5) {
    companion object {
        const val NAME = "nisu_tasks.db"
        private val ID_FORMAT = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val CONVERSATION_ID = Regex("^c_[0-9a-f]{32}$")
        private val OUTCOME_UNSAFE = Regex("[^A-Za-z0-9_.:-]")
        private val COUNTER_KEY = Regex("^k_[0-9a-f]{32}$")
        /** A person not answered for this long starts again at zero (about a year: far longer than any chat the limit is meant for). */
        const val COUNTER_KEEP_MS = 400L * 24 * 60 * 60 * 1000

        fun newId(): String = "t_" + UUID.randomUUID().toString().replace("-", "")
        fun validId(id: String): Boolean = ID_FORMAT.matches(id)
        fun validConversationId(id: String): Boolean = CONVERSATION_ID.matches(id)
        fun validCounterKey(k: String): Boolean = COUNTER_KEY.matches(k)
        fun cleanOutcome(raw: String?): String? = raw?.let { OUTCOME_UNSAFE.replace(it, "_").take(60) }?.ifEmpty { null }
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, source TEXT NOT NULL, package TEXT NOT NULL, " +
                "conversation_id TEXT NOT NULL, message_id INTEGER NOT NULL, " +
                "dedupe_key TEXT NOT NULL UNIQUE, status TEXT NOT NULL, agent_id TEXT, attempts INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, started_at INTEGER NOT NULL DEFAULT 0, " +
                "finished_at INTEGER NOT NULL DEFAULT 0, not_before INTEGER NOT NULL DEFAULT 0, outcome TEXT, " +
                "send_phase TEXT NOT NULL DEFAULT 'NONE', send_kind TEXT NOT NULL DEFAULT 'REPLY', counter_key TEXT)"
        )
        db.execSQL("CREATE INDEX ix_task_status ON tasks(status, seq)")
        db.execSQL("CREATE INDEX ix_task_conversation ON tasks(conversation_id, status)")
        createOwnerTable(db)
        createCounterTable(db)
        createLifecycleTable(db)
    }

    /** Step 11: automation state per person and app. Created fresh or added to an existing database; nothing is deleted. */
    private fun createLifecycleTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_lifecycle (" +
                "counter_key TEXT PRIMARY KEY, source TEXT NOT NULL, state TEXT NOT NULL, reason TEXT, updated_at INTEGER NOT NULL)"
        )
    }

    /** Step 10: automatic replies sent per person and app. Created fresh or added to an existing database; nothing is deleted. */
    private fun createCounterTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reply_counter (" +
                "counter_key TEXT PRIMARY KEY, replies INTEGER NOT NULL DEFAULT 0, uncertain INTEGER NOT NULL DEFAULT 0, " +
                "closing INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)"
        )
    }

    /** Step 9: the owner wait of each conversation. Created fresh or added to an existing database; nothing is deleted. */
    private fun createOwnerTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS owner_state (" +
                "conversation_id TEXT PRIMARY KEY, source TEXT NOT NULL, package TEXT NOT NULL, kind TEXT NOT NULL, " +
                "phase TEXT NOT NULL, reason TEXT, window_start INTEGER NOT NULL, deadline INTEGER NOT NULL, ref_at INTEGER NOT NULL, " +
                "newest_message_id INTEGER NOT NULL, notif_hash TEXT, detection_ok INTEGER NOT NULL, " +
                "last_owner_at INTEGER NOT NULL, uncertain_at INTEGER NOT NULL, decided_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_owner_phase ON owner_state(phase, deadline)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 1 -> 2 (step 8): the send phase. Existing tasks are INCOMING_MESSAGE tasks, for which NONE is correct. Nothing is deleted.
        if (oldVersion < 2) db.execSQL("ALTER TABLE tasks ADD COLUMN send_phase TEXT NOT NULL DEFAULT 'NONE'")
        // 2 -> 3 (step 9): the owner wait table. Existing tasks keep working; waiting tasks without an owner record are cancelled by the gate, never assumed safe.
        if (oldVersion < 3) createOwnerTable(db)
        // 3 -> 4 (step 10): send kind, counter key, reply counters. Existing tasks keep working (kind REPLY, no key).
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN send_kind TEXT NOT NULL DEFAULT 'REPLY'")
            db.execSQL("ALTER TABLE tasks ADD COLUMN counter_key TEXT")
            createCounterTable(db)
        }
        // 4 -> 5 (step 11): lifecycle states. No row = ACTIVE, so existing conversations behave exactly as before.
        if (oldVersion < 5) createLifecycleTable(db)
    }

    // ------------------------------------------------------------------ creating

    /** One task per (conversation, message). Returns false when it already exists (duplicate hand-over after a crash or retry). */
    fun insertIfNew(t: AgentTask): Boolean {
        val v = ContentValues().apply {
            put("id", t.id); put("type", t.type.name); put("source", t.source.name); put("package", t.packageName)
            put("conversation_id", t.conversationId); put("message_id", t.messageId)
            put("dedupe_key", dedupeKey(t.type, t.conversationId, t.messageId))
            put("status", t.status.name); put("attempts", t.attempts)
            put("created_at", t.createdAt); put("updated_at", t.updatedAt); put("not_before", t.notBefore)
            put("send_kind", t.sendKind.name); put("counter_key", t.counterKey)
        }
        return writableDatabase.insertWithOnConflict("tasks", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun dedupeKey(type: TaskType, conversationId: String, messageId: Long) = type.name + "|" + conversationId + "|" + messageId

    // ------------------------------------------------------------------ transitions (all conditional)

    /** PENDING -> RUNNING for exactly one caller. Returns the task as claimed, or null when somebody else got it / it is no longer pending. */
    fun claim(id: String, agentId: String, now: Long): AgentTask? {
        val v = ContentValues().apply { put("status", TaskStatus.RUNNING.name); put("agent_id", agentId); put("started_at", now); put("updated_at", now) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.update("tasks", v, "id = ? AND status = ?", arrayOf(id, TaskStatus.PENDING.name))
            if (n != 1) return null
            db.execSQL("UPDATE tasks SET attempts = attempts + 1 WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return get(id)
    }

    /**
     * RUNNING -> final state. False when the task was not running any more (e.g. it was cancelled meanwhile).
     * Step 10: a send that ends CONFIRMED or UNCERTAIN is counted in the reply counter in the SAME transaction, so every path
     * that ends a send (normal end, cancel, timeout, recovery after a restart) counts it exactly once.
     */
    fun finish(id: String, status: TaskStatus, outcome: String?, now: Long, phase: SendPhase? = null): Boolean {
        require(status.isFinal)
        val v = ContentValues().apply {
            put("status", status.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now)
            if (phase != null) put("send_phase", phase.name)
        }
        val db = writableDatabase
        if (phase == null || (phase != SendPhase.CONFIRMED && phase != SendPhase.UNCERTAIN))
            return db.update("tasks", v, "id = ? AND status = ?", arrayOf(id, TaskStatus.RUNNING.name)) == 1
        db.beginTransaction()
        try {
            val ok = db.update("tasks", v, "id = ? AND status = ?", arrayOf(id, TaskStatus.RUNNING.name)) == 1
            if (ok) countSend(db, id, phase, now)
            db.setTransactionSuccessful()
            return ok
        } finally { db.endTransaction() }
    }

    /** Inside the finish transaction: adds one finished send to the counter of its person (REPLY) or marks the closing message as done. */
    private fun countSend(db: SQLiteDatabase, id: String, phase: SendPhase, now: Long) {
        var type: String? = null; var kind: String? = null; var key: String? = null; var src: String? = null
        db.rawQuery("SELECT type, send_kind, counter_key, source FROM tasks WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) { type = it.getString(0); kind = it.getString(1); key = if (it.isNull(2)) null else it.getString(2); src = it.getString(3) }
        }
        val k = key ?: return
        if (type != TaskType.SEND_REPLY.name) return
        db.execSQL("INSERT OR IGNORE INTO reply_counter(counter_key, replies, uncertain, closing, updated_at) VALUES (?, 0, 0, 0, ?)", arrayOf<Any>(k, now))
        val sql = when {
            kind == SendKind.CLOSING.name -> "UPDATE reply_counter SET closing = 1, updated_at = ? WHERE counter_key = ?"
            phase == SendPhase.CONFIRMED -> "UPDATE reply_counter SET replies = replies + 1, updated_at = ? WHERE counter_key = ?"
            else -> "UPDATE reply_counter SET uncertain = uncertain + 1, updated_at = ? WHERE counter_key = ?"
        }
        db.execSQL(sql, arrayOf<Any>(now, k))
        // Step 11: the closing message was sent (or may have been): this conversation is completed, in the SAME transaction.
        if (kind == SendKind.CLOSING.name) raiseIn(db, k, src ?: MessageSource.WHATSAPP.name, ConvState.COMPLETED, "reply_limit_reached", now)
    }

    /** Step 10: what was sent to this person on this app so far. Zero for a person never answered. Read from disk, so it is true after a restart. */
    fun replyCounts(counterKey: String): ReplyCounts =
        writableDatabase.rawQuery("SELECT replies, uncertain, closing FROM reply_counter WHERE counter_key = ?", arrayOf(counterKey)).use {
            if (it.moveToFirst()) ReplyCounts(it.getInt(0), it.getInt(1), it.getInt(2) == 1) else ReplyCounts()
        }

    /**
     * v2.39 (one reply per burst): the moment up to which this conversation has already been answered. It is the newest finish time of a
     * REPLY send that went out (CONFIRMED / UNCERTAIN) and, when [includeInFlight], "now" while a REPLY send of the conversation is still
     * waiting or running (the answer is on its way, so every message that arrived before this moment is covered by it).
     * [excludeId] leaves one task out (a send must not count itself). 0 = never answered.
     */
    fun replyBarrier(conversationId: String, excludeId: String?, includeInFlight: Boolean, now: Long): Long {
        var best = 0L
        readableDatabase.rawQuery(
            "SELECT id, status, finished_at FROM tasks WHERE type = ? AND send_kind = ? AND conversation_id = ? " +
                "AND (status IN ('PENDING','RUNNING') OR send_phase IN ('CONFIRMED','UNCERTAIN')) ORDER BY seq DESC LIMIT 12",
            arrayOf(TaskType.SEND_REPLY.name, SendKind.REPLY.name, conversationId)
        ).use { c ->
            while (c.moveToNext()) {
                if (excludeId != null && c.getString(0) == excludeId) continue
                val st = c.getString(1)
                val inFlight = st == TaskStatus.PENDING.name || st == TaskStatus.RUNNING.name
                val t = if (inFlight) (if (includeInFlight) now else 0L) else c.getLong(2)
                if (t > best) best = t
            }
        }
        return best
    }

    /**
     * v3.42: which messages a reply already covers, by MESSAGE ID (not by time). It is the highest id of the message that a REPLY send of this
     * conversation answered, for a send that went out (CONFIRMED / UNCERTAIN) and, when [includeInFlight], for a send still waiting or running.
     * Every message with an id up to this one was in the chat when that reply was written. A message that arrived AFTER the reply was drafted
     * has a higher id and is NOT covered (the old time rule marked it as answered, so it never got a reply). [excludeId] leaves one task out. 0 = none.
     */
    fun replyCoveredId(conversationId: String, excludeId: String?, includeInFlight: Boolean): Long {
        var best = 0L
        readableDatabase.rawQuery(
            "SELECT id, status, message_id FROM tasks WHERE type = ? AND send_kind = ? AND conversation_id = ? " +
                "AND (status IN ('PENDING','RUNNING') OR send_phase IN ('CONFIRMED','UNCERTAIN')) ORDER BY seq DESC LIMIT 12",
            arrayOf(TaskType.SEND_REPLY.name, SendKind.REPLY.name, conversationId)
        ).use { c ->
            while (c.moveToNext()) {
                if (excludeId != null && c.getString(0) == excludeId) continue
                val st = c.getString(1)
                val inFlight = st == TaskStatus.PENDING.name || st == TaskStatus.RUNNING.name
                if (inFlight && !includeInFlight) continue
                val id = c.getLong(2)
                if (id > best) best = id
            }
        }
        return best
    }

    /**
     * v3.42: a newer draft replaces an older reply that is still WAITING (not started). The newer draft was written from the whole recent
     * chat, so it answers everything the older one did; sending both would put two messages in the chat at once. Only PENDING reply sends
     * for OLDER messages of this conversation are cancelled (a send that already started is never touched). Returns how many.
     */
    fun cancelPendingReplySendsBefore(conversationId: String, messageId: Long, outcome: String, now: Long): Int {
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now) }
        return writableDatabase.update(
            "tasks", v, "type = ? AND send_kind = ? AND conversation_id = ? AND status = ? AND message_id < ?",
            arrayOf(TaskType.SEND_REPLY.name, SendKind.REPLY.name, conversationId, TaskStatus.PENDING.name, messageId.toString())
        )
    }

    /** Step 10: is a closing message for this person already waiting or running? (Never queue a second one.) */
    fun hasOpenClosing(counterKey: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM tasks WHERE type = ? AND send_kind = ? AND counter_key = ? AND status IN ('PENDING','RUNNING') LIMIT 1",
            arrayOf(TaskType.SEND_REPLY.name, SendKind.CLOSING.name, counterKey)
        ).use { it.moveToFirst() }

    /** Step 10: reply counters of people who were not answered for a long time are dropped, so the table cannot grow forever. */
    fun pruneCounters(now: Long) {
        writableDatabase.execSQL("DELETE FROM reply_counter WHERE updated_at < ?", arrayOf<Any>(now - COUNTER_KEEP_MS))
    }

    /** RUNNING -> PENDING again, not before [notBefore] (retry). */
    fun requeue(id: String, notBefore: Long, outcome: String?, now: Long, phase: SendPhase? = null): Boolean {
        val v = ContentValues().apply {
            put("status", TaskStatus.PENDING.name); put("not_before", notBefore); put("outcome", cleanOutcome(outcome)); put("updated_at", now)
            if (phase != null) put("send_phase", phase.name)
        }
        return writableDatabase.update("tasks", v, "id = ? AND status = ?", arrayOf(id, TaskStatus.RUNNING.name)) == 1
    }

    /** PENDING -> CANCELLED. False when the task is not waiting (running, finished or unknown). */
    fun cancelPending(id: String, outcome: String, now: Long): Boolean {
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now) }
        return writableDatabase.update("tasks", v, "id = ? AND status = ?", arrayOf(id, TaskStatus.PENDING.name)) == 1
    }

    /** Cancels every waiting task of one conversation. Returns how many. */
    fun cancelPendingOfConversation(conversationId: String, outcome: String, now: Long): Int {
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now) }
        return writableDatabase.update("tasks", v, "conversation_id = ? AND status = ?", arrayOf(conversationId, TaskStatus.PENDING.name))
    }

    /** Step 13: cancels every waiting task (reply drafting AND sending) of the given apps. Tasks that are running are not touched here. */
    fun cancelPendingOfSources(sources: Set<MessageSource>, outcome: String, now: Long): Int {
        if (sources.isEmpty()) return 0
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now) }
        val marks = sources.joinToString(",") { "?" }
        return writableDatabase.update("tasks", v, "status = ? AND source IN ($marks)", arrayOf(TaskStatus.PENDING.name) + sources.map { it.name }.toTypedArray())
    }

    fun cancelAllPending(outcome: String, now: Long): Int {
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", cleanOutcome(outcome)); put("finished_at", now); put("updated_at", now) }
        return writableDatabase.update("tasks", v, "status = ?", arrayOf(TaskStatus.PENDING.name))
    }

    /**
     * RUNNING send, phase NONE -> ATTEMPTING. Must succeed BEFORE the sender is called. False = do not send (not running any more,
     * not a send, or already attempted).
     */
    fun markAttempting(id: String, now: Long): Boolean {
        val v = ContentValues().apply { put("send_phase", SendPhase.ATTEMPTING.name); put("updated_at", now) }
        return writableDatabase.update(
            "tasks", v, "id = ? AND status = ? AND type = ? AND send_phase = ?",
            arrayOf(id, TaskStatus.RUNNING.name, TaskType.SEND_REPLY.name, SendPhase.NONE.name)
        ) == 1
    }

    // ------------------------------------------------------------------ conversation lifecycle (step 11)

    /** The state of one conversation. ACTIVE when there is no row. A stored value that cannot be read counts as BLOCKED. Throws on a database error: callers must treat that as "do not send". */
    fun lifecycleGet(key: String): ConvState =
        writableDatabase.rawQuery("SELECT state FROM conversation_lifecycle WHERE counter_key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) ConvState.fromName(it.getString(0)) else ConvState.ACTIVE
        }

    /** Automatic transition: moves a conversation UP to [state] (never down, never sideways). Returns true when the state changed. */
    fun lifecycleRaise(key: String, source: MessageSource, state: ConvState, reason: String, now: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = raiseIn(db, key, source.name, state, reason, now)
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    /** Inside a transaction: the raise of [lifecycleRaise]. */
    private fun raiseIn(db: SQLiteDatabase, key: String, source: String, state: ConvState, reason: String?, now: Long): Boolean {
        var current = ConvState.ACTIVE
        db.rawQuery("SELECT state FROM conversation_lifecycle WHERE counter_key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) current = ConvState.fromName(it.getString(0))
        }
        if (current.rank >= state.rank) return false
        putLifecycle(db, key, source, state, reason, now)
        return true
    }

    private fun putLifecycle(db: SQLiteDatabase, key: String, source: String, state: ConvState, reason: String?, now: Long) {
        val v = ContentValues().apply {
            put("counter_key", key); put("source", source); put("state", state.name); put("reason", cleanOutcome(reason)); put("updated_at", now)
        }
        db.insertWithOnConflict("conversation_lifecycle", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The owner's own decision (block, unblock): sets the state exactly. ACTIVE removes the row, so nothing is kept for a normal conversation. */
    fun lifecycleSet(key: String, source: MessageSource, state: ConvState, reason: String?, now: Long) {
        val db = writableDatabase
        if (state == ConvState.ACTIVE) db.execSQL("DELETE FROM conversation_lifecycle WHERE counter_key = ?", arrayOf<Any>(key))
        else putLifecycle(db, key, source.name, state, reason, now)
    }

    /** v2.21: the stored reason of a conversation's state (null = no row or no reason). */
    fun lifecycleReasonOf(key: String): String? =
        writableDatabase.rawQuery("SELECT reason FROM conversation_lifecycle WHERE counter_key = ?", arrayOf(key)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    /**
     * v2.21: "Unlimited" was chosen: every conversation that was closed because it reached the reply limit is opened again.
     * The reply counters are NOT touched, so when a limit is chosen again those people are limited again on their next message.
     * Closures made by the owner (Stop) and blocks stay. Returns how many conversations were opened.
     */
    fun lifecycleClearLimitClosures(): Int =
        writableDatabase.delete("conversation_lifecycle", "state = ? AND reason = ?", arrayOf(ConvState.COMPLETED.name, "reply_limit_reached"))

    /** v2.21: a higher limit (for example 50) was chosen: limit closures of people whose counter is now below it are removed. */
    fun lifecycleClearLimitClosuresBelow(max: Int): Int =
        writableDatabase.delete(
            "conversation_lifecycle",
            "state = ? AND reason = ? AND counter_key IN (SELECT counter_key FROM reply_counter WHERE replies + uncertain < ?)",
            arrayOf(ConvState.COMPLETED.name, "reply_limit_reached", max.toString())
        )

    /**
     * v2.21: hourly refresh. A person whose limit was reached (counter >= [max]) and who was last answered at least [ageMs] ago starts again:
     * counter back to 0, closing message allowed again, and the limit closure removed. [onlyKey] = just that person (used lazily when a
     * message arrives); null = everybody (housekeeping). Nothing happens for "unlimited". Returns how many people were refreshed.
     */
    fun refreshLimitDue(now: Long, max: Int, ageMs: Long, onlyKey: String? = null): Int {
        if (max == Int.MAX_VALUE) return 0
        val db = writableDatabase
        val keys = ArrayList<String>()
        val args = ArrayList<String>().apply { add((now - ageMs).toString()); add(max.toString()); if (onlyKey != null) add(onlyKey) }
        db.rawQuery(
            "SELECT counter_key FROM reply_counter WHERE updated_at <= ? AND (replies + uncertain) >= ?" + (if (onlyKey != null) " AND counter_key = ?" else ""),
            args.toTypedArray()
        ).use { while (it.moveToNext()) keys.add(it.getString(0)) }
        if (keys.isEmpty()) return 0
        db.beginTransaction()
        try {
            for (k in keys) {
                db.execSQL("UPDATE reply_counter SET replies = 0, uncertain = 0, closing = 0, updated_at = ? WHERE counter_key = ?", arrayOf<Any>(now, k))
                db.execSQL("DELETE FROM conversation_lifecycle WHERE counter_key = ? AND state = ? AND reason = ?", arrayOf<Any>(k, ConvState.COMPLETED.name, "reply_limit_reached"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return keys.size
    }

    /** The owner resumes every PAUSED conversation. COMPLETED and BLOCKED stay as they are. Returns how many were resumed. */
    fun lifecycleResumePaused(): Int =
        writableDatabase.delete("conversation_lifecycle", "state = ?", arrayOf(ConvState.PAUSED.name))

    fun lifecycleCounts(): LifecycleCounts {
        var p = 0; var c = 0; var b = 0
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM conversation_lifecycle GROUP BY state", null).use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getInt(1)
                when (ConvState.fromName(cur.getString(0))) {
                    ConvState.PAUSED -> p += n
                    ConvState.COMPLETED -> c += n
                    ConvState.BLOCKED -> b += n
                    ConvState.ACTIVE -> {}
                }
            }
        }
        return LifecycleCounts(p, c, b)
    }

    /** Step 13: every held-back conversation (PAUSED, COMPLETED, BLOCKED) as stored. Keys only. Throws on a database error. */
    fun lifecycleRows(): List<LifecycleRow> {
        val out = ArrayList<LifecycleRow>()
        readableDatabase.rawQuery("SELECT counter_key, source, state, reason, updated_at FROM conversation_lifecycle", null).use { c ->
            while (c.moveToNext()) {
                val src = try { MessageSource.valueOf(c.getString(1)) } catch (_: Exception) { continue }
                out.add(LifecycleRow(c.getString(0), src, ConvState.fromName(c.getString(2)), if (c.isNull(3)) null else c.getString(3), c.getLong(4)))
            }
        }
        return out
    }

    /** Step 13: the owner resumes ONE paused conversation. COMPLETED and BLOCKED stay as they are. Returns true when it was paused. */
    fun lifecycleResumeOne(key: String): Boolean =
        writableDatabase.delete("conversation_lifecycle", "counter_key = ? AND state = ?", arrayOf(key, ConvState.PAUSED.name)) == 1

    /** PAUSED and COMPLETED rows of people not heard of for as long as the reply counters are kept are dropped (together with the counter). BLOCKED rows stay: the owner decided that. */
    fun lifecyclePrune(now: Long) {
        writableDatabase.execSQL(
            "DELETE FROM conversation_lifecycle WHERE state IN ('PAUSED','COMPLETED') AND updated_at < ?", arrayOf<Any>(now - COUNTER_KEEP_MS)
        )
    }

    // ------------------------------------------------------------------ housekeeping

    /** Waiting tasks that are too old to act on. */
    fun expire(now: Long): Int {
        val v = ContentValues().apply { put("status", TaskStatus.CANCELLED.name); put("outcome", "expired"); put("finished_at", now); put("updated_at", now) }
        return writableDatabase.update("tasks", v, "status = ? AND created_at < ?", arrayOf(TaskStatus.PENDING.name, (now - TaskLimits.MAX_AGE_MS).toString()))
    }

    /** More than [TaskLimits.MAX_PENDING] waiting tasks: the oldest surplus is cancelled. */
    fun trimPending(now: Long): Int {
        val db = writableDatabase
        val pending = count(db, "SELECT COUNT(*) FROM tasks WHERE status = 'PENDING'")
        val extra = pending - TaskLimits.MAX_PENDING
        if (extra <= 0) return 0
        db.execSQL(
            "UPDATE tasks SET status = 'CANCELLED', outcome = 'overflow', finished_at = ?, updated_at = ? " +
                "WHERE seq IN (SELECT seq FROM tasks WHERE status = 'PENDING' ORDER BY seq ASC LIMIT ?)",
            arrayOf<Any>(now, now, extra)
        )
        return extra
    }

    fun pruneFinished(now: Long) {
        val db = writableDatabase
        db.execSQL("DELETE FROM tasks WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND finished_at < ?", arrayOf<Any>(now - TaskLimits.KEEP_FINISHED_MS))
        val finished = count(db, "SELECT COUNT(*) FROM tasks WHERE status IN ('COMPLETED','FAILED','CANCELLED')")
        if (finished > TaskLimits.MAX_FINISHED) db.execSQL(
            "DELETE FROM tasks WHERE seq IN (SELECT seq FROM tasks WHERE status IN ('COMPLETED','FAILED','CANCELLED') ORDER BY finished_at ASC, seq ASC LIMIT ?)",
            arrayOf<Any>(finished - TaskLimits.MAX_FINISHED)
        )
    }

    // ------------------------------------------------------------------ reading

    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private val COLUMNS = "seq, id, type, source, package, conversation_id, message_id, status, agent_id, attempts, created_at, updated_at, started_at, finished_at, not_before, outcome, send_phase, send_kind, counter_key"

    private fun read(c: Cursor): AgentTask? {
        val type = try { TaskType.valueOf(c.getString(2)) } catch (_: Exception) { return null }
        val source = try { MessageSource.valueOf(c.getString(3)) } catch (_: Exception) { return null }
        val status = try { TaskStatus.valueOf(c.getString(7)) } catch (_: Exception) { return null }
        val phase = try { SendPhase.valueOf(c.getString(16)) } catch (_: Exception) { SendPhase.NONE }
        val kind = try { SendKind.valueOf(c.getString(17)) } catch (_: Exception) { SendKind.REPLY }
        return AgentTask(
            id = c.getString(1), type = type, source = source, packageName = c.getString(4), conversationId = c.getString(5),
            messageId = c.getLong(6), status = status, agentId = if (c.isNull(8)) null else c.getString(8), attempts = c.getInt(9),
            createdAt = c.getLong(10), updatedAt = c.getLong(11), startedAt = c.getLong(12), finishedAt = c.getLong(13),
            notBefore = c.getLong(14), outcome = if (c.isNull(15)) null else c.getString(15), sendPhase = phase,
            sendKind = kind, counterKey = if (c.isNull(18)) null else c.getString(18)
        )
    }

    fun get(id: String): AgentTask? =
        readableDatabase.rawQuery("SELECT $COLUMNS FROM tasks WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }

    /** Waiting tasks of the given apps, oldest first (this order is the order of work within a conversation). */
    fun pending(sources: Set<MessageSource>, limit: Int): List<AgentTask> {
        if (sources.isEmpty()) return emptyList()
        val marks = sources.joinToString(",") { "?" }
        val args = sources.map { it.name }.toTypedArray() + limit.toString()
        val out = ArrayList<AgentTask>()
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM tasks WHERE status = 'PENDING' AND source IN ($marks) ORDER BY seq ASC LIMIT CAST(? AS INTEGER)", args
        ).use { c -> while (c.moveToNext()) read(c)?.let { out.add(it) } }
        return out
    }

    fun running(): List<AgentTask> {
        val out = ArrayList<AgentTask>()
        readableDatabase.rawQuery("SELECT $COLUMNS FROM tasks WHERE status = 'RUNNING' ORDER BY seq ASC", null)
            .use { c -> while (c.moveToNext()) read(c)?.let { out.add(it) } }
        return out
    }

    /** When the last CONFIRMED send (any app) finished; 0 = none kept. This is what the pause between sends counts from. */
    fun lastConfirmedSendAt(): Long =
        readableDatabase.rawQuery(
            "SELECT MAX(finished_at) FROM tasks WHERE type = ? AND status = ? AND send_phase = ?",
            arrayOf(TaskType.SEND_REPLY.name, TaskStatus.COMPLETED.name, SendPhase.CONFIRMED.name)
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    /** The earliest retry time in the future, or null when no waiting task is delayed. */
    fun nextNotBefore(now: Long): Long? =
        readableDatabase.rawQuery("SELECT MIN(not_before) FROM tasks WHERE status = 'PENDING' AND not_before > CAST(? AS INTEGER)", arrayOf(now.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** [servedSources] = apps that currently have an agent; waiting tasks of any other app are counted as waiting for an agent. */
    fun counts(servedSources: Set<MessageSource>): TaskCounts {
        var p = 0; var r = 0; var ok = 0; var f = 0; var x = 0
        val db = readableDatabase
        db.rawQuery("SELECT status, COUNT(*) FROM tasks GROUP BY status", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                when (c.getString(0)) {
                    "PENDING" -> p = n
                    "RUNNING" -> r = n
                    "COMPLETED" -> ok = n
                    "FAILED" -> f = n
                    "CANCELLED" -> x = n
                }
            }
        }
        var waiting = 0
        if (p > 0) {
            val sql = "SELECT COUNT(*) FROM tasks WHERE status = 'PENDING'" +
                (if (servedSources.isNotEmpty()) " AND source NOT IN (" + servedSources.joinToString(",") { "?" } + ")" else "")
            waiting = db.rawQuery(sql, servedSources.map { it.name }.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
        val uncertain = db.rawQuery("SELECT COUNT(*) FROM tasks WHERE send_phase = ?", arrayOf(SendPhase.UNCERTAIN.name)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return TaskCounts(p, r, ok, f, x, waiting, uncertain)
    }

    /** Step 13: waiting tasks per app (reply drafting and sending). */
    fun pendingBySource(): Map<MessageSource, Int> {
        val out = HashMap<MessageSource, Int>()
        readableDatabase.rawQuery("SELECT source, COUNT(*) FROM tasks WHERE status = 'PENDING' GROUP BY source", null).use { c ->
            while (c.moveToNext()) {
                val s = try { MessageSource.valueOf(c.getString(0)) } catch (_: Exception) { continue }
                out[s] = c.getInt(1)
            }
        }
        return out
    }

    fun conversationState(conversationId: String): ConversationState {
        val db = readableDatabase
        var running: String? = null
        var queued = 0
        var source: MessageSource? = null
        db.rawQuery("SELECT id, status, source FROM tasks WHERE conversation_id = ? AND status IN ('PENDING','RUNNING')", arrayOf(conversationId)).use { c ->
            while (c.moveToNext()) {
                if (source == null) source = try { MessageSource.valueOf(c.getString(2)) } catch (_: Exception) { null }
                if (c.getString(1) == "RUNNING") running = c.getString(0) else queued++
            }
        }
        var lastAt = 0L
        var lastOutcome: String? = null
        db.rawQuery(
            "SELECT finished_at, outcome, source FROM tasks WHERE conversation_id = ? AND status IN ('COMPLETED','FAILED','CANCELLED') ORDER BY finished_at DESC, seq DESC LIMIT 1",
            arrayOf(conversationId)
        ).use { c ->
            if (c.moveToFirst()) {
                lastAt = c.getLong(0); lastOutcome = if (c.isNull(1)) null else c.getString(1)
                if (source == null) source = try { MessageSource.valueOf(c.getString(2)) } catch (_: Exception) { null }
            }
        }
        val phase = when { running != null -> ConversationPhase.BUSY; queued > 0 -> ConversationPhase.QUEUED; else -> ConversationPhase.IDLE }
        return ConversationState(conversationId, source, phase, running, queued, lastAt, lastOutcome)
    }

    // ------------------------------------------------------------------ owner wait (step 9)

    /** Moves the not-before time of every waiting INCOMING_MESSAGE task of one conversation to at least [notBefore] (never earlier). */
    fun delayPendingOfConversation(conversationId: String, notBefore: Long): Int {
        val v = ContentValues().apply { put("not_before", notBefore) }
        return writableDatabase.update(
            "tasks", v, "conversation_id = ? AND status = ? AND type = ? AND not_before < CAST(? AS INTEGER)",
            arrayOf(conversationId, TaskStatus.PENDING.name, TaskType.INCOMING_MESSAGE.name, notBefore.toString())
        )
    }

    private val OWNER_COLUMNS = "conversation_id, source, package, kind, phase, reason, window_start, deadline, ref_at, newest_message_id, " +
        "notif_hash, detection_ok, last_owner_at, uncertain_at, decided_at, updated_at"

    private fun readOwner(c: Cursor): OwnerRecord? {
        val source = try { MessageSource.valueOf(c.getString(1)) } catch (_: Exception) { return null }
        val kind = try { ConversationKind.valueOf(c.getString(3)) } catch (_: Exception) { ConversationKind.UNKNOWN }
        val phase = try { OwnerPhase.valueOf(c.getString(4)) } catch (_: Exception) { return null }
        return OwnerRecord(
            conversationId = c.getString(0), source = source, packageName = c.getString(2), kind = kind, phase = phase,
            reason = if (c.isNull(5)) null else c.getString(5), windowStart = c.getLong(6), deadline = c.getLong(7), refAt = c.getLong(8),
            newestMessageId = c.getLong(9), notifHash = if (c.isNull(10)) null else c.getString(10), detectionOk = c.getInt(11) == 1,
            lastOwnerAt = c.getLong(12), uncertainAt = c.getLong(13), decidedAt = c.getLong(14), updatedAt = c.getLong(15)
        )
    }

    fun ownerGet(conversationId: String): OwnerRecord? =
        writableDatabase.rawQuery("SELECT $OWNER_COLUMNS FROM owner_state WHERE conversation_id = ?", arrayOf(conversationId))
            .use { if (it.moveToFirst()) readOwner(it) else null }

    /** Inserts or replaces the whole record of one conversation. */
    fun ownerPut(r: OwnerRecord) {
        val v = ContentValues().apply {
            put("conversation_id", r.conversationId); put("source", r.source.name); put("package", r.packageName); put("kind", r.kind.name)
            put("phase", r.phase.name); put("reason", cleanOutcome(r.reason)); put("window_start", r.windowStart); put("deadline", r.deadline)
            put("ref_at", r.refAt); put("newest_message_id", r.newestMessageId); put("notif_hash", r.notifHash)
            put("detection_ok", if (r.detectionOk) 1 else 0); put("last_owner_at", r.lastOwnerAt); put("uncertain_at", r.uncertainAt)
            put("decided_at", r.decidedAt); put("updated_at", r.updatedAt)
        }
        writableDatabase.insertWithOnConflict("owner_state", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Windows whose 30 seconds are over but that nobody has decided yet. */
    fun ownerExpiredWaiting(now: Long): List<OwnerRecord> {
        val out = ArrayList<OwnerRecord>()
        writableDatabase.rawQuery(
            "SELECT $OWNER_COLUMNS FROM owner_state WHERE phase = 'WAITING' AND deadline <= CAST(? AS INTEGER)", arrayOf(now.toString())
        ).use { c -> while (c.moveToNext()) readOwner(c)?.let { out.add(it) } }
        return out
    }

    /** Deletes decided records that have not changed for [OwnerLimits.KEEP_MS]. Running windows are kept. */
    fun ownerPrune(now: Long) {
        writableDatabase.execSQL("DELETE FROM owner_state WHERE phase != 'WAITING' AND updated_at < ?", arrayOf<Any>(now - OwnerLimits.KEEP_MS))
    }

    fun ownerCounts(): OwnerCounts {
        var w = 0; var e = 0; var r = 0; var u = 0
        readableDatabase.rawQuery("SELECT phase, COUNT(*) FROM owner_state GROUP BY phase", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(1)
                when (c.getString(0)) {
                    "WAITING" -> w = n
                    "ELIGIBLE" -> e = n
                    "OWNER_REPLIED" -> r = n
                    "UNCERTAIN" -> u = n
                }
            }
        }
        return OwnerCounts(w, e, r, u)
    }
}


/**
 * v2.39: ONE reply per burst of messages. NISU AI reads a person's message, sends ONE reply, and then stays silent in that chat until
 * the person writes something NEW. Messages that were already in the chat when the reply was written (or sent) never get a reply of
 * their own, and an old message that an app shows again (a re-posted notification) is not "new" either.
 */
internal object BurstGuard {
    /** A message first seen this long after it was written is an old message that was shown again. */
    private const val REPOST_MS = 120_000L

    /**
     * v3.42: how long NISU AI waits after a person's newest message before it starts drafting, so a burst of messages ("hi", "are you
     * there", "reply") becomes ONE reply to the whole burst instead of one reply per message. Each new message of the chat starts the wait
     * again for ITS task, and the older tasks of the burst end as "superseded_by_newer_message".
     */
    const val SETTLE_MS = 3_000L

    /**
     * true = this message was already covered by a reply that went out (or is going out).
     * [coveredId]: the highest message id that reply answered (TaskStore.replyCoveredId). A message with a higher id arrived after the reply
     * was drafted and is NEW (v3.42: the old time rule dropped such a message, so it never got a reply).
     * [barrier]: finish time of the last sent reply, used only for the "old message shown again" rule.
     */
    fun answered(m: ProcessedMessage, coveredId: Long, barrier: Long): Boolean {
        if (coveredId > 0L && m.messageId <= coveredId) return true
        if (barrier > 0L && m.timestampExact && m.receivedAt - m.timestamp > REPOST_MS && m.timestamp <= barrier) return true
        return false
    }
}
