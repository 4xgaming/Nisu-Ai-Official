package ai.nisu.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * Conversation state of the WhatsApp Master Agent (step 5).
 *
 * One [WhatsAppConversationState] per WhatsApp conversation id (the id the Message Processing System made). Nothing in
 * one conversation's state is ever read while handling another conversation: every access goes through
 * [WhatsAppConversations.access] with that conversation's own id.
 *
 * Memory only. Nothing here is written to disk, logged, or sent anywhere; it is dropped when the agent is unregistered
 * (background service stops) and at most [WhatsAppConversations.MAX_CONVERSATIONS] conversations are kept.
 */

/**
 * A reply the agent PREPARED for one incoming message. Nothing in this step sends it. Deliberately not a data class, so
 * the text cannot end up in a log line through toString().
 */
class WhatsAppDraft(
    val conversationId: String,
    /** The message this draft answers. A draft for an older message is replaced when a newer one is answered. */
    val forMessageId: Long,
    val text: String,
    val purpose: WhatsAppActionType,
    val createdAt: Long
)

class WhatsAppConversationState(val conversationId: String) {
    var kind: ConversationKind = ConversationKind.UNKNOWN
    var lastHandledMessageId: Long = 0L
    var handledMessages: Int = 0
    var lastType: WaMessageType? = null
    /** Short code of the last result ("draft_ready", "no_draft:unsupported_image", ...). Never message text. */
    var lastOutcome: String? = null
    var lastActivityAt: Long = 0L
    /** The newest prepared draft of this conversation, or null. */
    var draft: WhatsAppDraft? = null
    /** What the automation hook answered for the last draft ("not_enabled" today). */
    var automation: String? = null
}

class WhatsAppConversations {
    companion object {
        const val MAX_CONVERSATIONS = 100
        /** A draft older than this is treated as gone: the chat has moved on. */
        const val DRAFT_TTL_MS = 60L * 60 * 1000
    }

    private val lock = Any()
    private val map: LinkedHashMap<String, WhatsAppConversationState> =
        object : LinkedHashMap<String, WhatsAppConversationState>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WhatsAppConversationState>?) = size > MAX_CONVERSATIONS
        }

    /** Runs [block] on the state of exactly this conversation (created when new), under a lock. Keep [block] short. */
    fun <T> access(conversationId: String, block: (WhatsAppConversationState) -> T): T = synchronized(lock) {
        block(map.getOrPut(conversationId) { WhatsAppConversationState(conversationId) })
    }

    /** The newest fresh draft of ONE conversation, or null. */
    fun draftFor(conversationId: String, now: Long): WhatsAppDraft? = synchronized(lock) {
        val d = map[conversationId]?.draft ?: return@synchronized null
        if (now - d.createdAt > DRAFT_TTL_MS) null else d
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() { synchronized(lock) { map.clear() } }
}

/** What the UI may show about the agent. Counters only, no text, no names. Counted since the agent was registered. */
data class WhatsAppAgentStats(
    val draftsPrepared: Int = 0,
    /** Messages that were understood but deliberately got no draft (media, notice, no reply needed, AI switched off, ...). */
    val noDraft: Int = 0,
    val failedRuns: Int = 0
)

object WhatsAppAgentState {
    val stats = MutableStateFlow(WhatsAppAgentStats())

    internal fun draftPrepared() { stats.update { it.copy(draftsPrepared = it.draftsPrepared + 1) } }
    internal fun noDraft() { stats.update { it.copy(noDraft = it.noDraft + 1) } }
    internal fun failedRun() { stats.update { it.copy(failedRuns = it.failedRuns + 1) } }
    internal fun reset() { stats.value = WhatsAppAgentStats() }
}
