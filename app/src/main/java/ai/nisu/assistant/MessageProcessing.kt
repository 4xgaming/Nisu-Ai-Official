package ai.nisu.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/*
 * Message Processing System (step 3).
 *
 *   NisuNotificationService -> MessageParser -> IncomingMessages (in-memory duplicate filter, step 2)
 *        -> MessageProcessor.enqueue()            returns immediately, safe on the main thread
 *        -> queue + ONE worker thread             order is kept; I/O never runs on the main thread
 *        -> MessageStore (SQLite)                 conversations + messages, second duplicate filter, survives restarts
 *        -> MessageGateway.consumer               the single hand-over point for the future Main Brain / Agent Manager
 *
 * Nothing here answers, sends or generates anything. Message text, sender and chat names are never written to the log.
 */

enum class ConversationKind { PRIVATE, GROUP, UNKNOWN }

/** A stored message handed to the Main Brain. Only ever contains data of its own conversation. */
data class ProcessedMessage(
    /** Local stable id (unique, increasing). Use it to recognise a message you already handled. */
    val messageId: Long,
    val conversationId: String,
    val source: MessageSource,
    val packageName: String,
    val appName: String,
    /** PRIVATE / GROUP when Android said so, UNKNOWN when the notification gave no way to tell. */
    val kind: ConversationKind,
    /** Contact name (private) or group name (group), as Android showed it. */
    val chatTitle: String,
    val groupName: String?,
    /** Sender exactly as Android provided it, null when missing. */
    val senderName: String?,
    /** Always non-empty and safe to show: the sender, else the contact (private chat), else a neutral "Unknown ..." text. */
    val senderLabel: String,
    val text: String,
    val timestamp: Long,
    val timestampExact: Boolean,
    /** When NISU AI stored it. Use this to ignore stale messages. */
    val receivedAt: Long,
    /** true = it was already in the notification shade when the listener connected (e.g. after a restart). */
    val isBacklog: Boolean,
    val notificationId: Int,
    val notificationKey: String,
    /** How many earlier hand-over attempts failed (0 on the first try). */
    val attempt: Int
)

/** A message as read back from history. Belongs to exactly one conversation. */
data class StoredMessage(
    val messageId: Long, val conversationId: String, val senderName: String?, val text: String,
    val timestamp: Long, val timestampExact: Boolean, val receivedAt: Long
)

/** Conversation state without any message text. */
data class ConversationInfo(
    val id: String, val source: MessageSource, val packageName: String, val kind: ConversationKind,
    val title: String, val lastMessageAt: Long, val messageCount: Int, val pendingCount: Int
)

/** Step 11: texts and names for the privacy check (Privacy.kt). Not a data class: nothing prints it. Never leaves the process. */
internal class PrivacyContext(
    val ownIdentities: List<String>,
    val ownTexts: List<String>,
    val otherIdentities: List<String>,
    val otherTexts: List<String>
)

data class StoreStats(
    val whatsappChats: Int = 0, val messengerChats: Int = 0, val messages: Int = 0,
    /** Stored but not yet handed to the consumer (the Main Brain takes them over while the background service runs). */
    val pending: Int = 0, val failed: Int = 0
)

/**
 * What the future Main Brain / Agent Manager implements.
 *
 * - Called on the processor's worker thread, one message at a time, in time order within each conversation. Do not block for long.
 * - Return normally = the message is taken over. Throw = it stays pending: it is offered again after a growing delay and later
 *   messages of the SAME conversation wait behind it (other conversations continue). After 5 failed attempts it is marked failed.
 * - Delivery is at-least-once: use [ProcessedMessage.messageId] to ignore a repeat.
 */
interface MessageConsumer {
    fun onMessage(message: ProcessedMessage)
}

/** The only door through which processed messages leave the system. */
object MessageGateway {
    @Volatile internal var consumer: MessageConsumer? = null

    /** Makes [c] the receiver. Everything stored and not yet handed over is delivered to it right away, oldest first. */
    fun register(ctx: Context, c: MessageConsumer) {
        consumer = c
        MessageProcessor.start(ctx)
        MessageProcessor.requestDrain()
    }

    fun unregister(c: MessageConsumer) { if (consumer === c) consumer = null }

    /** The newest [limit] messages of ONE conversation, oldest first. Blocking disk read: call it off the main thread. */
    fun history(ctx: Context, conversationId: String, limit: Int = 30): List<StoredMessage> =
        try { MessageProcessor.store(ctx)?.history(conversationId, limit.coerceIn(1, 200)) ?: emptyList() } catch (_: Exception) { emptyList() }

    /** One stored message, only if it belongs to [conversationId] (null otherwise, or when it was pruned / deleted). Blocking disk read. */
    fun message(ctx: Context, conversationId: String, messageId: Long): ProcessedMessage? =
        try { MessageProcessor.store(ctx)?.message(conversationId, messageId) } catch (_: Exception) { null }

    /** Conversation list without message text. Blocking disk read: call it off the main thread. */
    fun conversations(ctx: Context, source: MessageSource? = null): List<ConversationInfo> =
        try { MessageProcessor.store(ctx)?.conversations(source) ?: emptyList() } catch (_: Exception) { emptyList() }
}

/** Receives messages, stores them and hands them on. One worker thread, so the order of arrival is the order of processing. */
object MessageProcessor {
    private const val TAG = "NisuMsg"
    private const val MAX_ATTEMPTS = 5
    private const val PRUNE_EVERY = 50

    private sealed class Job {
        class Ingest(val m: IncomingMessage) : Job()
        object Drain : Job()
        object Clear : Job()
        /** Step 9: what the listener saw about the owner (own lines, notification removed). Same queue, so the order of arrival is kept. */
        class Owner(val signals: List<OwnerSignal>) : Job()
    }

    private val queue = LinkedBlockingQueue<Job>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    @Volatile private var storeRef: MessageStore? = null
    private val blockedUntil = HashMap<String, Long>()   // worker thread only: conversation -> do not retry before (elapsedRealtime)
    private var inserted = 0                              // worker thread only

    val stats = MutableStateFlow(StoreStats())

    /** Starts the worker (idempotent). Pending messages from an earlier run are picked up by the first Drain. */
    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({ workerLoop(app) }, "nisu-messages").apply { isDaemon = true }.start()
        queue.offer(Job.Drain)
    }

    /** Queues messages for processing. Returns at once. Never throws. */
    fun enqueue(ctx: Context, list: List<IncomingMessage>) {
        if (list.isEmpty()) return
        try {
            start(ctx)
            for (m in list) queue.offer(Job.Ingest(m))
        } catch (_: Exception) {}
    }

    fun requestDrain() { queue.offer(Job.Drain) }

    /**
     * Step 9: queues owner signals behind the messages already queued. Returns at once, safe on the main thread, never throws.
     * The worker finds the conversation and hands the signal to [OwnerWatch]. Nothing here is stored as text.
     */
    internal fun enqueueOwnerSignals(ctx: Context, list: List<OwnerSignal>) {
        if (list.isEmpty()) return
        try {
            start(ctx)
            queue.offer(Job.Owner(list))
        } catch (_: Exception) {}
    }

    /** Deletes every stored conversation and message (runs on the worker). */
    fun clearAll(ctx: Context) { start(ctx); queue.offer(Job.Clear) }

    @Synchronized internal fun store(ctx: Context): MessageStore? {
        storeRef?.let { return it }
        val app = ctx.applicationContext
        return try {
            MessageStore(app).also { it.writableDatabase; storeRef = it }
        } catch (e: SQLiteException) {
            // damaged database file: start again with an empty one rather than never storing anything
            Log.w(TAG, "database could not be opened (" + e.javaClass.simpleName + "), recreating it")
            try { app.deleteDatabase(MessageStore.NAME); MessageStore(app).also { it.writableDatabase; storeRef = it } } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    private fun workerLoop(app: Context) {
        try {
            try { store(app)?.prune() } catch (_: Exception) {}   // once per start: apply the size limits
            while (true) {
                val job = queue.take()
                try { handle(app, job) } catch (e: Throwable) { Log.w(TAG, "job failed: " + e.javaClass.simpleName) }
                if (queue.isEmpty()) refreshStats()
            }
        } finally {
            started = false   // a later enqueue()/start() brings the worker back
        }
    }

    private fun handle(app: Context, job: Job) {
        val s = store(app)
        if (s == null) { Log.w(TAG, "no database: job skipped"); return }
        when (job) {
            is Job.Ingest -> {
                val conv = s.insert(job.m, System.currentTimeMillis()) ?: return   // duplicate or empty
                if (++inserted % PRUNE_EVERY == 0) s.prune()
                // Backlog (found when the listener connects) is handed over together in one Drain, sorted by time.
                if (job.m.isLive) drain(s, conv)
            }
            Job.Drain -> drain(s, null)
            is Job.Owner -> for (sig in job.signals) {
                try { OwnerWatch.handleSignal(app, s, sig) } catch (e: Throwable) { Log.w(TAG, "owner signal failed: " + e.javaClass.simpleName) }
            }
            Job.Clear -> { s.clearAll(); blockedUntil.clear(); IncomingMessages.clear() }
        }
    }

    private fun drain(s: MessageStore, only: String?) {
        val consumer = MessageGateway.consumer ?: return
        val convs = if (only != null) listOf(only) else s.pendingConversations()
        for (c in convs) {
            if ((blockedUntil[c] ?: 0L) > SystemClock.elapsedRealtime()) continue
            var blocked = false
            while (!blocked) {
                val batch = s.pending(c, 100)
                if (batch.isEmpty()) break
                for (m in batch) {
                    try {
                        consumer.onMessage(m)
                        s.markDelivered(m.messageId)
                        blockedUntil.remove(c)
                    } catch (e: Throwable) {
                        val last = m.attempt + 1 >= MAX_ATTEMPTS
                        s.markAttempt(m.messageId, last)
                        Log.w(TAG, "hand-over failed (" + e.javaClass.simpleName + "), attempt " + (m.attempt + 1) + if (last) ", giving up on this message" else "")
                        if (!last) {
                            val wait = minOf(5_000L shl (m.attempt).coerceAtMost(4), 60_000L)   // 5, 10, 20, 40, 60 s
                            blockedUntil[c] = SystemClock.elapsedRealtime() + wait
                            main.postDelayed({ queue.offer(Job.Drain) }, wait)
                            blocked = true   // keep the order: later messages of this conversation wait
                            break
                        }
                    }
                }
            }
        }
    }

    private fun refreshStats() {
        try {
            val s = storeRef ?: return
            val n = s.stats()
            if (n != stats.value) stats.value = n
        } catch (_: Exception) {}
    }
}

/** SQLite storage (part of Android, no library). Private app storage, excluded from backups (allowBackup=false). */
internal class MessageStore(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {
    companion object {
        const val NAME = "nisu_messages.db"
        /** Newest messages kept per conversation, and in total. Older ones are deleted. */
        const val MAX_PER_CONVERSATION = 500
        const val MAX_TOTAL = 20_000
        /** Step 11: messages that were already handed over are deleted after this long (private text is not kept longer than it is useful). */
        const val RETENTION_DAYS = 30
        private const val APPROX_WINDOW_MS = 60_000L
        private const val MAX_TEXT = 4000
        private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
        private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
        private val SPACES = Regex("\\s+")

        fun cleanText(raw: String): String? {
            val s = CONTROL.replace(raw, "").trim()
            return if (s.isEmpty()) null else s.take(MAX_TEXT)
        }
        fun cleanTitle(raw: String): String = INVISIBLE.replace(raw, "").trim().ifEmpty { "(no name)" }
        fun chatKey(title: String): String = SPACES.replace(cleanTitle(title), " ").lowercase(Locale.ROOT)
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (" +
                "id TEXT PRIMARY KEY, package TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, " +
                "chat_key TEXT NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, last_message_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX ux_conversation ON conversations(package, kind, chat_key)")
        db.execSQL(
            "CREATE TABLE messages (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, " +
                "dedupe_key TEXT NOT NULL, sender TEXT, body TEXT NOT NULL, " +
                "msg_time INTEGER NOT NULL, time_exact INTEGER NOT NULL, post_time INTEGER NOT NULL, received_at INTEGER NOT NULL, " +
                "notif_id INTEGER NOT NULL, notif_key TEXT NOT NULL, notif_tag TEXT, backlog INTEGER NOT NULL, " +
                "state INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0)"   // state: 0 new, 1 handed over, 2 failed
        )
        db.execSQL("CREATE INDEX ix_msg_conversation ON messages(conversation_id, msg_time, seq)")
        db.execSQL("CREATE INDEX ix_msg_dedupe ON messages(dedupe_key)")
        db.execSQL("CREATE INDEX ix_msg_state ON messages(state, seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* version 1 only */ }

    // ------------------------------------------------------------------ writing

    /** Stores one message. Returns its conversation id, or null when it was empty or already stored. */
    fun insert(m: IncomingMessage, now: Long): String? {
        val body = cleanText(m.text) ?: return null
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (isDuplicate(db, m)) return null
            val kind = when (m.isGroup) { true -> ConversationKind.GROUP; false -> ConversationKind.PRIVATE; null -> ConversationKind.UNKNOWN }
            val title = cleanTitle(m.chatTitle)
            val conv = resolve(db, m.packageName, m.source, kind, title, now)
            val sender = m.senderName?.let { INVISIBLE.replace(it, "").trim() }?.ifEmpty { null }
            val msgTime = if (m.timestamp > 0) m.timestamp else m.postTime
            db.insertOrThrow("messages", null, ContentValues().apply {
                put("conversation_id", conv); put("dedupe_key", m.id); put("sender", sender); put("body", body)
                put("msg_time", msgTime); put("time_exact", if (m.timestampExact) 1 else 0); put("post_time", m.postTime)
                put("received_at", now); put("notif_id", m.notificationId); put("notif_key", m.notificationKey)
                put("notif_tag", m.notificationTag); put("backlog", if (m.isLive) 0 else 1)
            })
            db.execSQL("UPDATE conversations SET last_message_at = MAX(last_message_at, ?), title = ? WHERE id = ?", arrayOf<Any>(msgTime, title, conv))
            db.setTransactionSuccessful()
            return conv
        } finally {
            db.endTransaction()
        }
    }

    /** Exact messages (own timestamp): same key = same message. Messages without their own time: same key within a minute. */
    private fun isDuplicate(db: SQLiteDatabase, m: IncomingMessage): Boolean =
        if (m.timestampExact)
            db.rawQuery("SELECT 1 FROM messages WHERE dedupe_key = ? LIMIT 1", arrayOf(m.id)).use { it.moveToFirst() }
        else
            db.rawQuery("SELECT 1 FROM messages WHERE dedupe_key = ? AND ABS(post_time - CAST(? AS INTEGER)) < CAST(? AS INTEGER) LIMIT 1",
                arrayOf(m.id, m.postTime.toString(), APPROX_WINDOW_MS.toString())).use { it.moveToFirst() }

    /**
     * One conversation per (app package, private/group, chat name). Never across apps. A message whose kind Android did not
     * reveal joins the existing conversation of that name instead of creating a second one; a known kind later upgrades an UNKNOWN one.
     */
    private fun resolve(db: SQLiteDatabase, pkg: String, source: MessageSource, kind: ConversationKind, title: String, now: Long): String {
        val key = chatKey(title)
        if (kind == ConversationKind.UNKNOWN) {
            find(db, "SELECT id FROM conversations WHERE package = ? AND chat_key = ? ORDER BY last_message_at DESC LIMIT 1", arrayOf(pkg, key))?.let { return it }
        } else {
            find(db, "SELECT id FROM conversations WHERE package = ? AND kind = ? AND chat_key = ?", arrayOf(pkg, kind.name, key))?.let { return it }
            find(db, "SELECT id FROM conversations WHERE package = ? AND kind = 'UNKNOWN' AND chat_key = ?", arrayOf(pkg, key))?.let { id ->
                db.execSQL("UPDATE conversations SET kind = ? WHERE id = ?", arrayOf<Any>(kind.name, id))
                return id
            }
        }
        val id = "c_" + UUID.randomUUID().toString().replace("-", "")
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", id); put("package", pkg); put("source", source.name); put("kind", kind.name)
            put("chat_key", key); put("title", title); put("created_at", now); put("last_message_at", now)
        })
        return id
    }

    private fun find(db: SQLiteDatabase, sql: String, args: Array<String>): String? =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Step 9: the id of an EXISTING conversation, found the same way [resolve] finds it, but never created. Null when the chat has no
     * stored message yet. A group and a private chat of the same name are different conversations, as everywhere else.
     */
    fun findConversation(pkg: String, kind: ConversationKind, title: String): String? {
        val db = readableDatabase
        val key = chatKey(title)
        return if (kind == ConversationKind.UNKNOWN)
            find(db, "SELECT id FROM conversations WHERE package = ? AND chat_key = ? ORDER BY last_message_at DESC LIMIT 1", arrayOf(pkg, key))
        else
            find(db, "SELECT id FROM conversations WHERE package = ? AND kind = ? AND chat_key = ?", arrayOf(pkg, kind.name, key))
                ?: find(db, "SELECT id FROM conversations WHERE package = ? AND kind = 'UNKNOWN' AND chat_key = ?", arrayOf(pkg, key))
    }

    fun markDelivered(seq: Long) { writableDatabase.execSQL("UPDATE messages SET state = 1 WHERE seq = ?", arrayOf<Any>(seq)) }

    fun markAttempt(seq: Long, giveUp: Boolean) {
        writableDatabase.execSQL("UPDATE messages SET attempts = attempts + 1, state = ? WHERE seq = ?", arrayOf<Any>(if (giveUp) 2 else 0, seq))
    }

    /** Keeps the newest messages per conversation and in total, then removes conversations left empty. */
    fun prune() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val big = ArrayList<String>()
            db.rawQuery("SELECT conversation_id FROM messages GROUP BY conversation_id HAVING COUNT(*) > CAST(? AS INTEGER)", arrayOf(MAX_PER_CONVERSATION.toString())).use {
                while (it.moveToNext()) big.add(it.getString(0))
            }
            for (c in big) db.execSQL(
                "DELETE FROM messages WHERE conversation_id = ? AND seq NOT IN " +
                    "(SELECT seq FROM messages WHERE conversation_id = ? ORDER BY msg_time DESC, seq DESC LIMIT ?)",
                arrayOf<Any>(c, c, MAX_PER_CONVERSATION)
            )
            // Step 11: handed-over (state 1) or given-up (state 2) messages older than the retention time. Messages still waiting (state 0) are kept.
            db.execSQL("DELETE FROM messages WHERE state != 0 AND received_at < ?", arrayOf<Any>(System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000))
            val total = count(db, "SELECT COUNT(*) FROM messages")
            if (total > MAX_TOTAL) db.execSQL(
                "DELETE FROM messages WHERE seq IN (SELECT seq FROM messages ORDER BY msg_time ASC, seq ASC LIMIT ?)", arrayOf<Any>(total - MAX_TOTAL)
            )
            db.execSQL("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversation_id FROM messages)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Step 11: deletes every stored message of one chat of one app package (the conversation rows go with them; messages cascade). Returns how many conversations were removed. */
    fun deleteChat(pkg: String, chatKey: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = db.delete("conversations", "package = ? AND chat_key = ?", arrayOf(pkg, chatKey))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Step 11: what the privacy check needs to compare a reply with: the identities (chat titles and sender names) and message texts of THIS
     * conversation, and of every OTHER conversation (newest [maxOtherMessages] messages). Read only; the result never leaves the process.
     */
    fun privacyContext(conversationId: String, maxOtherMessages: Int): PrivacyContext {
        val db = readableDatabase
        val ownIdentities = ArrayList<String>()
        val ownTexts = ArrayList<String>()
        val otherIdentities = LinkedHashSet<String>()
        val otherTexts = ArrayList<String>()
        db.rawQuery("SELECT title FROM conversations WHERE id = ?", arrayOf(conversationId)).use { while (it.moveToNext()) ownIdentities.add(it.getString(0)) }
        db.rawQuery("SELECT DISTINCT sender FROM messages WHERE conversation_id = ? AND sender IS NOT NULL", arrayOf(conversationId))
            .use { while (it.moveToNext()) ownIdentities.add(it.getString(0)) }
        db.rawQuery("SELECT body FROM messages WHERE conversation_id = ? ORDER BY msg_time DESC, seq DESC LIMIT 100", arrayOf(conversationId))
            .use { while (it.moveToNext()) ownTexts.add(it.getString(0)) }
        db.rawQuery("SELECT title FROM conversations WHERE id != ?", arrayOf(conversationId)).use { while (it.moveToNext()) otherIdentities.add(it.getString(0)) }
        db.rawQuery("SELECT DISTINCT sender FROM messages WHERE conversation_id != ? AND sender IS NOT NULL LIMIT 3000", arrayOf(conversationId))
            .use { while (it.moveToNext()) otherIdentities.add(it.getString(0)) }
        db.rawQuery(
            "SELECT body FROM messages WHERE conversation_id != ? ORDER BY msg_time DESC, seq DESC LIMIT CAST(? AS INTEGER)",
            arrayOf(conversationId, maxOtherMessages.toString())
        ).use { while (it.moveToNext()) otherTexts.add(it.getString(0)) }
        return PrivacyContext(ownIdentities, ownTexts, otherIdentities.toList(), otherTexts)
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try { db.execSQL("DELETE FROM messages"); db.execSQL("DELETE FROM conversations"); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    // ------------------------------------------------------------------ reading

    private fun count(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun pendingConversations(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT conversation_id FROM messages WHERE state = 0 GROUP BY conversation_id ORDER BY MIN(seq)", null).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    private val PROCESSED_COLUMNS =
        "SELECT m.seq, m.conversation_id, m.sender, m.body, m.msg_time, m.time_exact, m.received_at, m.notif_id, m.notif_key, m.backlog, m.attempts, " +
            "c.package, c.source, c.kind, c.title FROM messages m JOIN conversations c ON c.id = m.conversation_id "

    /** Row of [PROCESSED_COLUMNS] -> message. Null when the row has a source this build does not know. */
    private fun readProcessed(c: android.database.Cursor): ProcessedMessage? {
        val source = try { MessageSource.valueOf(c.getString(12)) } catch (_: Exception) { return null }
        val kind = try { ConversationKind.valueOf(c.getString(13)) } catch (_: Exception) { ConversationKind.UNKNOWN }
        val title = c.getString(14)
        val sender = if (c.isNull(2)) null else c.getString(2)
        return ProcessedMessage(
            messageId = c.getLong(0), conversationId = c.getString(1), source = source, packageName = c.getString(11),
            appName = source.label, kind = kind, chatTitle = title, groupName = if (kind == ConversationKind.GROUP) title else null,
            senderName = sender,
            senderLabel = sender ?: when (kind) {
                ConversationKind.PRIVATE -> title            // in a private chat the only other person is the contact
                ConversationKind.GROUP -> "Unknown member"
                ConversationKind.UNKNOWN -> "Unknown sender"
            },
            text = c.getString(3), timestamp = c.getLong(4), timestampExact = c.getInt(5) == 1, receivedAt = c.getLong(6),
            isBacklog = c.getInt(9) == 1, notificationId = c.getInt(7), notificationKey = c.getString(8), attempt = c.getInt(10)
        )
    }

    /** Not yet handed-over messages of ONE conversation, oldest first. */
    fun pending(conversationId: String, limit: Int): List<ProcessedMessage> {
        val out = ArrayList<ProcessedMessage>()
        readableDatabase.rawQuery(
            PROCESSED_COLUMNS + "WHERE m.conversation_id = ? AND m.state = 0 ORDER BY m.msg_time, m.seq LIMIT CAST(? AS INTEGER)",
            arrayOf(conversationId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) readProcessed(c)?.let { out.add(it) }
        }
        return out
    }

    /** One message by id. The conversation id must match, so a message can never be read through another conversation. */
    fun message(conversationId: String, messageId: Long): ProcessedMessage? =
        readableDatabase.rawQuery(
            PROCESSED_COLUMNS + "WHERE m.conversation_id = ? AND m.seq = CAST(? AS INTEGER) LIMIT 1",
            arrayOf(conversationId, messageId.toString())
        ).use { c -> if (c.moveToFirst()) readProcessed(c) else null }

    fun history(conversationId: String, limit: Int): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        readableDatabase.rawQuery(
            "SELECT seq, conversation_id, sender, body, msg_time, time_exact, received_at FROM messages " +
                "WHERE conversation_id = ? ORDER BY msg_time DESC, seq DESC LIMIT CAST(? AS INTEGER)", arrayOf(conversationId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(StoredMessage(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2),
                c.getString(3), c.getLong(4), c.getInt(5) == 1, c.getLong(6)))
        }
        return out.asReversed()
    }

    fun conversations(source: MessageSource?): List<ConversationInfo> {
        val out = ArrayList<ConversationInfo>()
        val sql = "SELECT c.id, c.package, c.source, c.kind, c.title, c.last_message_at, " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id), " +
            "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.state = 0) " +
            "FROM conversations c " + (if (source != null) "WHERE c.source = ? " else "") + "ORDER BY c.last_message_at DESC"
        readableDatabase.rawQuery(sql, if (source != null) arrayOf(source.name) else null).use { c ->
            while (c.moveToNext()) {
                val s = try { MessageSource.valueOf(c.getString(2)) } catch (_: Exception) { continue }
                val k = try { ConversationKind.valueOf(c.getString(3)) } catch (_: Exception) { ConversationKind.UNKNOWN }
                out.add(ConversationInfo(c.getString(0), s, c.getString(1), k, c.getString(4), c.getLong(5), c.getInt(6), c.getInt(7)))
            }
        }
        return out
    }

    fun stats(): StoreStats {
        val db = readableDatabase
        return StoreStats(
            whatsappChats = count(db, "SELECT COUNT(*) FROM conversations WHERE source = 'WHATSAPP'"),
            messengerChats = count(db, "SELECT COUNT(*) FROM conversations WHERE source = 'MESSENGER'"),
            messages = count(db, "SELECT COUNT(*) FROM messages"),
            pending = count(db, "SELECT COUNT(*) FROM messages WHERE state = 0"),
            failed = count(db, "SELECT COUNT(*) FROM messages WHERE state = 2")
        )
    }
}
