package ai.nisu.assistant

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Notification Listener pipeline (step 2).
 *
 *   NisuNotificationService --> MessageParser --> IncomingMessages (dedupe, short in-memory history, SharedFlow)
 *                                                       |
 *                                                       +--> future Main Brain / WhatsApp + Messenger Master Agents
 *
 * Only WhatsApp (incl. Business) and Messenger (incl. Lite) notifications are ever parsed. Nothing is persisted, nothing
 * is sent anywhere, and nothing here replies to a message.
 */

/** Which messaging app a message came from. WhatsApp and Messenger are never mixed. */
enum class MessageSource(val label: String) {
    WHATSAPP("WhatsApp"), MESSENGER("Messenger");

    companion object {
        fun fromPackage(pkg: String?): MessageSource? {
            val p = pkg ?: return null
            return when {
                p in Apps.WHATSAPP -> WHATSAPP
                p in Apps.MESSENGER -> MESSENGER
                else -> null
            }
        }
    }
}

/**
 * One incoming message as Android's notification showed it. A notification can carry several messages: each becomes
 * its own [IncomingMessage]. Fields Android did not provide are null / flagged, never guessed.
 */
data class IncomingMessage(
    /** Stable key used to drop duplicates. Built from app, chat, sender, text and (when known) the message time. */
    val id: String,
    val source: MessageSource,
    /** Exact package: tells WhatsApp from WhatsApp Business, Messenger from Messenger Lite. */
    val packageName: String,
    val appName: String,
    /** The conversation as Android names it: the contact for a private chat, the group name for a group. */
    val chatTitle: String,
    /** true / false when Android said so; null when the notification gave no way to tell. */
    val isGroup: Boolean?,
    /** Set only when [isGroup] is true. */
    val groupName: String?,
    /** Who wrote it. Null when Android did not say (never filled with the chat title as a guess). */
    val senderName: String?,
    val text: String,
    /** Time of the message when Android provided it ([timestampExact] = true), otherwise the notification's time. */
    val timestamp: Long,
    val timestampExact: Boolean,
    /** When the notification was posted. */
    val postTime: Long,
    val notificationId: Int,
    val notificationKey: String,
    val notificationTag: String?,
    /** true = arrived while the listener was connected. false = was already in the notification shade when it connected. */
    val isLive: Boolean
)

/** Turns a WhatsApp / Messenger notification into [IncomingMessage]s. Returns nothing for every other app. */
object MessageParser {
    internal val GENERIC_TITLES = setOf("whatsapp", "whatsapp business", "messenger", "chat heads active",
        "checking for new messages", "new messages", "backup in progress")
    private val COUNT_TEXT = Regex("""^\d+\s+(new\s+)?messages?$""", RegexOption.IGNORE_CASE)
    private val AT_TITLE = Regex("""^(.+?)\s@\s(.+)$""")   // older WhatsApp style: "Sender @ Group"
    private const val MAX_TEXT = 2000

    @Suppress("DEPRECATION")
    fun parse(sbn: StatusBarNotification, live: Boolean): List<IncomingMessage> {
        val pkg = sbn.packageName
        val source = MessageSource.fromPackage(pkg) ?: return emptyList()   // anything else is not looked at
        val n = sbn.notification ?: return emptyList()
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return emptyList()
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_TRANSPORT) return emptyList()
        // v2.31: WhatsApp Channels, Status updates and broadcast lists are one-way: nobody is waiting for an answer there.
        if (Build.VERSION.SDK_INT >= 26) {
            val cid = n.channelId?.lowercase().orEmpty()
            if (cid.contains("newsletter") || cid.contains("channels") || cid.contains("status") || cid.contains("broadcast")) return emptyList()
        }
        val e = n.extras ?: return emptyList()

        val convTitle = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val chat = (convTitle ?: title)?.takeIf { it.lowercase() !in GENERIC_TITLES } ?: return emptyList()
        val fallbackTime = if (n.`when` > 0) n.`when` else sbn.postTime

        fun make(sender: String?, text: String, time: Long, exact: Boolean, group: Boolean?, groupName: String?): IncomingMessage {
            val t = text.take(MAX_TEXT)
            val id = listOf(pkg, chat.lowercase(), sender?.lowercase().orEmpty(), if (exact) time.toString() else "", t.hashCode().toString()).joinToString("|")
            return IncomingMessage(
                id = id, source = source, packageName = pkg, appName = source.label, chatTitle = chat,
                isGroup = group, groupName = if (group == true) (groupName ?: chat) else null, senderName = sender,
                text = t, timestamp = time, timestampExact = exact, postTime = sbn.postTime,
                notificationId = sbn.id, notificationKey = sbn.key, notificationTag = sbn.tag, isLive = live
            )
        }

        // 1) MessagingStyle: Android gives every message with its own sender and time.
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null && arr.isNotEmpty()) {
            val group = if (e.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else convTitle != null
            val selfName = selfName(e)
            val out = ArrayList<IncomingMessage>()
            for (m in Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)) {
                val txt = m.text?.toString()?.trim().orEmpty()
                if (txt.isEmpty()) continue
                val sender = (if (Build.VERSION.SDK_INT >= 28) m.senderPerson?.name?.toString() else m.sender?.toString())?.trim()
                // Android: a message with no sender is one written by the device user. Those are outgoing, not incoming.
                if (sender.isNullOrEmpty() || sender.equals("You", true) || sender.equals("Me", true) || (selfName != null && sender == selfName)) continue
                val exact = m.timestamp > 0
                out.add(make(sender, txt, if (exact) m.timestamp else fallbackTime, exact, group, chat))
            }
            if (out.isNotEmpty()) return out
        }

        // 2) Plain notification: one message. Only what is explicit is split out; nothing is guessed.
        val txt = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (txt.isEmpty() || COUNT_TEXT.matches(txt)) return emptyList()
        val at = if (source == MessageSource.WHATSAPP && convTitle == null) title?.let { AT_TITLE.matchEntire(it) } else null
        return if (at != null) {
            val groupName = at.groupValues[2].trim()
            listOf(make(at.groupValues[1].trim(), txt, fallbackTime, false, true, groupName).copy(chatTitle = groupName))
        } else {
            listOf(make(null, txt, fallbackTime, false, null, null))
        }
    }

    /** Name of the device user as the notification states it (used to skip our own lines). */
    @Suppress("DEPRECATION")
    private fun selfName(e: android.os.Bundle): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val p = if (Build.VERSION.SDK_INT >= 33) e.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)
                    else e.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)
            p?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}

/**
 * Where parsed messages go. Drops duplicates, keeps a short in-memory history (cleared when notification access is lost,
 * never written to disk) and publishes every new message on [flow].
 *
 * Future consumers: collect [flow] (filter `isLive`), or read [recent] for what arrived before they started.
 */
object IncomingMessages {
    /** Counters since the app process started. Live messages only; no message text. */
    data class Stats(val whatsapp: Int = 0, val messenger: Int = 0, val lastTime: Long = 0, val lastApp: String = "", val lastChat: String = "")

    private const val MAX_RECENT = 200
    private const val MAX_SEEN = 600
    /** A message without its own time (plain notification) counts as the same one if it repeats within this window. */
    private const val APPROX_WINDOW_MS = 60_000L

    private val seen = object : LinkedHashMap<String, Long>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_SEEN
    }
    private val history = ArrayDeque<IncomingMessage>()
    private val _flow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _stats = MutableStateFlow(Stats())

    /** Every new (non-duplicate) message, backlog included. A slow collector may miss old items; use [recent] to catch up. */
    val flow: SharedFlow<IncomingMessage> = _flow
    val stats: StateFlow<Stats> = _stats

    /** Newest last. */
    @Synchronized fun recent(source: MessageSource? = null, limit: Int = 50): List<IncomingMessage> =
        history.filter { source == null || it.source == source }.takeLast(limit)

    /** Submits in order and returns only the messages that were new. */
    fun submitAll(list: List<IncomingMessage>): List<IncomingMessage> = list.filter { submit(it) }

    /** Returns true when the message was new. */
    fun submit(m: IncomingMessage): Boolean {
        synchronized(this) {
            val last = seen[m.id]
            val dup = if (m.timestampExact) last != null else last != null && Math.abs(m.postTime - last) < APPROX_WINDOW_MS
            if (dup) return false
            seen[m.id] = m.postTime
            history.addLast(m)
            while (history.size > MAX_RECENT) history.removeFirst()
            if (m.isLive) {
                val s = _stats.value
                _stats.value = s.copy(
                    whatsapp = s.whatsapp + if (m.source == MessageSource.WHATSAPP) 1 else 0,
                    messenger = s.messenger + if (m.source == MessageSource.MESSENGER) 1 else 0,
                    lastTime = m.postTime, lastApp = m.appName, lastChat = m.chatTitle
                )
            }
        }
        _flow.tryEmit(m)
        return true
    }

    /** Forgets everything (used when notification access is turned off). */
    @Synchronized fun clear() {
        seen.clear(); history.clear(); _stats.value = Stats()
    }
}

/** Is Notification Access granted, and has Android actually connected our listener? Single source of truth for the UI and the module. */
object MessageAccess {
    enum class State {
        /** Notification access is not granted (never granted, or revoked). */
        OFF,
        /** Granted, but Android has not (yet / any more) connected the listener. */
        WAITING,
        /** Granted and connected: notifications are being received. */
        CONNECTED
    }

    val state = MutableStateFlow(State.OFF)

    fun enabled(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Exception) { false }

    /** Re-reads the real situation. When access is gone, everything collected so far is dropped. */
    fun refresh(ctx: Context) {
        val s = when {
            !enabled(ctx) -> State.OFF
            NisuNotificationService.inst != null -> State.CONNECTED
            else -> State.WAITING
        }
        if (s == State.OFF) IncomingMessages.clear()
        state.value = s
    }

    /** Asks Android to bind the listener again (allowed only while access is granted). */
    fun requestRebind(ctx: Context) {
        try { NotificationListenerService.requestRebind(ComponentName(ctx, NisuNotificationService::class.java)) } catch (_: Exception) {}
    }

    /** Opens Android's own screen for this permission: our listener's page on Android 11+, the general list otherwise. */
    fun openSettings(ctx: Context) {
        val flags = if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, NisuNotificationService::class.java).flattenToString())
                    .addFlags(flags))
                return
            } catch (_: Exception) { /* fall back to the list below */ }
        }
        try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(flags)) } catch (_: Exception) {}
    }
}
