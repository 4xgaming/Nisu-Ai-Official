package ai.nisu.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The senders of the Automatic Reply System (step 10): one for WhatsApp, one for Messenger, both on the same engine.
 *
 * The ONLY sending method is the Reply action of the chat's own notification (the inline "Reply" button): the same thing Android
 * Auto, Wear OS and smart watches use. NISU AI hands the text to the app through the notification's RemoteInput and PendingIntent.
 * Nothing is typed, tapped or opened, no screen is read, no Accessibility Service is used (see below why not).
 *
 * What is NOT assumed:
 * - not every notification has a Reply action (a plain notification, Messenger Lite, an already opened or cleared chat): then the
 *   task ends as "no_reply_action" / "notification_gone", NotSent, certain, and nothing is sent;
 * - not every chat is the right one: the notification is only used when it belongs to the task's own conversation (same app
 *   package, and its chat name resolves to the task's conversation id);
 * - a handed-over reply is not a delivered reply. Android gives no receipt for a PendingIntent. A send is CONFIRMED only when the
 *   chat's notification, re-posted by the app, shows a NEW line written by the owner whose text hash is the hash of the reply and
 *   whose time is after the send began. If that line does not show up within [AutoReplyLimits.VERIFY_MS], the result is
 *   UNCERTAIN: the task is FAILED/UNCERTAIN, it is never repeated automatically, and it is never counted as confirmed.
 *
 * Checks, in this order, before ANY send (precheck, then again inside send() right before the PendingIntent is fired):
 *   switches on -> task consistent -> message still stored and of this private chat -> conversation state is ACTIVE (step 11: not paused,
 *   completed or blocked; an unreadable state is a refusal) -> it is still the person's newest message
 *   -> limit (REPLY) or limit reached and closing not done (CLOSING) -> the text (closing text, privacy refusal, or the agent's own
 *   draft for exactly this message, which must also pass the privacy check of step 11: no API key, no copy of the AI's instructions, no
 *   name or message of another chat) -> OwnerWatch says ELIGIBLE -> listener connected without a gap -> notification found and of this
 *   conversation -> NO owner line newer than the wait started (other than NISU AI's own) -> a Reply action with free-form input.
 *   Anything that cannot be determined is a refusal. All refusals are certain (nothing went out), so none of them uses up a reply.
 *
 * Why no Accessibility fallback: it would have to open the chat on Raj's screen (which also reads the chat to the sender, shows up as
 * Raj "opening" the chat to the owner check, and fights with Raj if he is using the phone), type into another app's text box, and
 * could not prove delivery any better than the notification can. Android also restricts accessibility services for such use
 * (restricted settings on Android 13+, background launch limits on 10+). It is therefore not implemented; see CHANGES.md.
 *
 * Logs contain short codes only.
 */

/** The agent's prepared reply for one message. Not a data class: the text must not show up in a log line. */
internal class DraftRef(val forMessageId: Long, val text: String)

internal abstract class NotificationReplySender(
    protected val app: Context,
    final override val id: String,
    private val source: MessageSource
) : MessageSender {

    final override val sources: Set<MessageSource> = setOf(source)

    /** The newest draft the app's own Master Agent prepared for [conversationId], or null. */
    protected abstract fun draftOf(conversationId: String): DraftRef?

    private companion object {
        const val TAG = "NisuAutoReply"
        /** Slack when looking for the new own line after a send: both times come from this phone's clock. */
        const val LINE_SLACK_MS = 3_000L
    }

    private sealed class Prep {
        class No(val result: SendResult.NotSent) : Prep()
        class Ready(
            val text: String, val closing: Boolean, val packageName: String, val conversationId: String,
            val action: Notification.Action, val input: RemoteInput, val inputs: Array<RemoteInput>
        ) : Prep()
    }

    private fun no(code: String): Prep = Prep.No(SendResult.NotSent(code, false))

    // ------------------------------------------------------------------ contract

    override suspend fun precheck(task: AgentTask): SendResult.NotSent? {
        val p = prepare(task)
        if (p is Prep.No) {
            Log.i(TAG, "send refused before the attempt: " + p.result.code)
            AutoReplyState.notSent(p.result.code)
            return p.result
        }
        return null
    }

    override suspend fun send(task: AgentTask): SendResult {
        // Everything is checked again, right now: the owner may have written since the precheck.
        val p = prepare(task)
        if (p is Prep.No) {
            Log.i(TAG, "send refused: " + p.result.code)
            AutoReplyState.notSent(p.result.code)
            return p.result
        }
        val r = p as Prep.Ready

        val startedAt = System.currentTimeMillis()
        val hash = OwnText.hash(r.text)
        val before = ownLines(r.packageName, r.conversationId)?.filter { it.hash == hash }?.maxOfOrNull { it.at } ?: 0L

        // Step 11: the conversation state once more, as late as possible: Raj may have taken over, or blocked the person, while the
        // checks above were running. This is the last suspending step before the reply action is fired; only a database read follows.
        val lifeStore = TaskManager.openStore(app)
        val lifeKey = task.counterKey
        val stop = if (lifeStore == null || lifeKey == null) ConversationLifecycle.UNAVAILABLE else ConversationLifecycle.refusal(lifeStore, lifeKey)
        if (stop != null) {
            Log.i(TAG, "send refused: " + stop)
            AutoReplyState.notSent(stop)
            return SendResult.NotSent(stop, false)
        }

        // Step 13: the switches once more, as the very last check before the reply action is fired (master, pause, this app's switch).
        AutoReplySettings.blockReason(app, source)?.let {
            Log.i(TAG, "send refused: " + it)
            AutoReplyState.notSent(it)
            return SendResult.NotSent(it, false)
        }

        // From here on NISU AI's own line must not look like a manual reply of Raj.
        OwnSends.begin(r.conversationId, hash, startedAt)
        try {
            try {
                fire(r)
            } catch (_: PendingIntent.CanceledException) {
                AutoReplyState.notSent("reply_action_expired")
                return SendResult.NotSent("reply_action_expired", false)   // the app's reply button is gone: certainly not delivered
            } catch (e: Exception) {
                val code = "send_call_failed_" + e.javaClass.simpleName.take(30)
                AutoReplyState.uncertain(code)
                return SendResult.Uncertain(code)                            // unclear what the app did with it: never repeated
            }

            // Verification: wait for the app to show our own NEW line in the chat's notification.
            val deadline = startedAt + AutoReplyLimits.VERIFY_MS
            while (System.currentTimeMillis() < deadline) {
                delay(AutoReplyLimits.VERIFY_POLL_MS)
                val lines = ownLines(r.packageName, r.conversationId)
                if (lines != null && lines.any { it.hash == hash && it.at > before && it.at >= startedAt - LINE_SLACK_MS }) {
                    Log.i(TAG, if (r.closing) "closing message confirmed" else "reply confirmed")
                    AutoReplyState.confirmed(r.closing)
                    if (!r.closing) BrainReplyMemory.record(r.conversationId, r.text)
                    return SendResult.Confirmed
                }
            }
            Log.i(TAG, "send not verified")
            AutoReplyState.uncertain("send_unverified")
            if (!r.closing) BrainReplyMemory.record(r.conversationId, r.text)   // it may have reached the person: the Brain must not repeat it
            return SendResult.Uncertain("send_unverified")
        } finally {
            OwnSends.end(r.conversationId, System.currentTimeMillis())
        }
    }

    // verify(): the default (UNKNOWN) stays. After a restart the reply text is gone (drafts live in memory only), so the chat cannot be
    // checked for it, and an interrupted send is never repeated.

    // ------------------------------------------------------------------ firing

    private fun fire(r: Prep.Ready) {
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(r.input.resultKey, r.text) }
        RemoteInput.addResultsToIntent(r.inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        val pending = r.action.actionIntent ?: throw PendingIntent.CanceledException()
        pending.send(app, 0, intent)
    }

    // ------------------------------------------------------------------ the checks

    private suspend fun prepare(task: AgentTask): Prep = withContext(Dispatchers.IO) {
        val conv = task.conversationId
        if (task.type != TaskType.SEND_REPLY || task.source != source || MessageSource.fromPackage(task.packageName) != source) return@withContext no("bad_task")
        AutoReplySettings.blockReason(app, source)?.let { return@withContext no(it) }
        val key = task.counterKey
        if (key == null || !TaskStore.validCounterKey(key)) return@withContext no("no_counter_key")
        val store = TaskManager.openStore(app) ?: return@withContext no("store_unavailable")
        val ms = MessageProcessor.store(app) ?: return@withContext no("message_store_unavailable")

        // the message this send answers: stored, from this chat, and in a PRIVATE chat
        val m = MessageGateway.message(app, conv, task.messageId) ?: return@withContext no("message_gone")
        if (!AutoReply.answerable(m, source) || m.packageName != task.packageName || AutoReply.counterKey(m) != key) return@withContext no("conversation_mismatch")

        // Step 11: the conversation's own state. Paused (Raj answered), completed (limit reached) and blocked conversations are never answered.
        ConversationLifecycle.refusal(store, key)?.let { return@withContext no(it) }

        // only the person's newest message is answered; an older one is superseded
        val newer = MessageGateway.history(app, conv, 5).any {
            it.messageId != m.messageId && (it.timestamp > m.timestamp || (it.timestamp == m.timestamp && it.messageId > m.messageId))
        }
        if (newer) return@withContext no("superseded")

        // the limit (read from disk: the same after any restart)
        val counts = store.replyCounts(key)
        val closing = task.sendKind == SendKind.CLOSING
        if (closing) {
            if (counts.used < AutoReplyLimits.max() || counts.closingDone) return@withContext no("closing_not_due")
        } else if (counts.used >= AutoReplyLimits.max()) return@withContext no("limit_reached")

        // the text: closing text, privacy refusal, or this message's own draft. Never anything else.
        val text: String = when {
            closing -> AutoReplyLimits.CLOSING_TEXT
            PrivacyGuard.asksAboutOthers(m.text) -> AutoReplyLimits.PRIVACY_TEXT
            else -> {
                val d = draftOf(conv)
                if (d == null || d.forMessageId != task.messageId) return@withContext no("draft_gone")
                val clean = MessageStore.cleanText(d.text) ?: return@withContext no("empty_draft")
                if (clean.length > AutoReplyLimits.MAX_REPLY_CHARS) return@withContext no("draft_too_long")
                // Step 11: the last privacy check, on exactly the text that would be sent. A violation is a refusal: nothing is sent.
                PrivacyChecks.violation(app, conv, clean, "")?.let { return@withContext no("privacy_blocked_" + it) }
                clean
            }
        }

        // v2.31: the owner checks are off (ReplyPolicy.OPEN): everybody in a direct chat gets the answer at once. Only the notification
        // that carries the Reply button is needed to send.
        val svc = NisuNotificationService.inst ?: return@withContext no("listener_off")
        val active: List<StatusBarNotification> = try { svc.activeNotifications?.toList() } catch (_: Exception) { null }
            ?: return@withContext no("notifications_unreadable")

        // the notifications of THIS conversation (same app package, chat name resolves to the task's conversation id)
        val mine = ArrayList<StatusBarNotification>()
        for (sbn in active) {
            if (sbn.packageName != task.packageName) continue
            val id = OwnerReplyDetector.identify(sbn) ?: continue
            if (!id.hasMessages) continue
            if (ms.findConversation(sbn.packageName, id.kind, id.chat) == conv) mine.add(sbn)
        }
        if (mine.isEmpty()) return@withContext no("notification_gone")

        val rec = try { store.ownerGet(conv) } catch (_: Exception) { null }
        // a Reply action with a free-form text input, on the notification of the newest message if there is one
        val hash = rec?.notifHash
        val ordered = mine.sortedWith(compareByDescending<StatusBarNotification> { hash != null && OwnerWatch.keyHash(it.key) == hash }.thenByDescending { it.postTime })
        for (sbn in ordered) {
            val a = replyAction(sbn.notification) ?: continue
            val inputs = a.remoteInputs ?: continue
            val free = inputs.firstOrNull { it.allowFreeFormInput } ?: continue
            return@withContext Prep.Ready(text, closing, task.packageName, conv, a, free, inputs)
        }
        no("no_reply_action")
    }

    private fun replyAction(n: Notification?): Notification.Action? {
        val withInput = n?.actions?.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return null
        if (Build.VERSION.SDK_INT >= 28) withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        return withInput.firstOrNull()
    }

    /** All lines the owner (or NISU AI in the owner's name) wrote, as the app's notifications of this conversation show them now. Null when unreadable. */
    private suspend fun ownLines(packageName: String, conversationId: String): List<OwnLine>? = withContext(Dispatchers.IO) {
        val svc = NisuNotificationService.inst ?: return@withContext null
        val ms = MessageProcessor.store(app) ?: return@withContext null
        val active = try { svc.activeNotifications?.toList() } catch (_: Exception) { null } ?: return@withContext null
        val out = ArrayList<OwnLine>()
        for (sbn in active) {
            if (sbn.packageName != packageName) continue
            val id = OwnerReplyDetector.identify(sbn) ?: continue
            if (ms.findConversation(sbn.packageName, id.kind, id.chat) != conversationId) continue
            for (sig in OwnerReplyDetector.scan(sbn)) if (sig is OwnerSignal.Replied) out.addAll(sig.lines)
        }
        out
    }
}

/** Sends the Master Agent's draft to WhatsApp (and WhatsApp Business) chats. Reads the draft from the WhatsApp agent only. */
internal class WhatsAppReplySender(app: Context) : NotificationReplySender(app, "whatsapp-reply-sender", MessageSource.WHATSAPP) {
    override fun draftOf(conversationId: String): DraftRef? =
        (AgentManager.agentById(AgentIds.WHATSAPP_MASTER) as? WhatsAppMasterAgent)?.draftFor(conversationId)?.let { DraftRef(it.forMessageId, it.text) }
}

/** Sends the Master Agent's draft to Messenger (and Messenger Lite) chats. Reads the draft from the Messenger agent only. */
internal class MessengerReplySender(app: Context) : NotificationReplySender(app, "messenger-reply-sender", MessageSource.MESSENGER) {
    override fun draftOf(conversationId: String): DraftRef? =
        (AgentManager.agentById(AgentIds.MESSENGER_MASTER) as? MessengerMasterAgent)?.draftFor(conversationId)?.let { DraftRef(it.forMessageId, it.text) }
}
