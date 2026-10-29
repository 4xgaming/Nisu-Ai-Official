package ai.nisu.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/*
 * Agents and the Agent Manager (step 4).
 *
 *   TaskManager  --task-->  MainBrain.runIncoming  -->  AgentManager.agentFor(task.source)  --AgentContext-->  AppAgent.handle()
 *
 * An [AppAgent] is the common interface for every app-specific agent. Implemented: the WhatsApp Master Agent
 * (WhatsAppMasterAgent.kt, step 5) and the Messenger Master Agent (MessengerMasterAgent.kt, step 6). Each serves exactly one
 * app, so a message can only reach the agent of its own app. Nothing in the agent layer sends anything yet.
 */

/** Reserved ids of the planned agents. A class that registers under one of these ids must serve exactly the matching app. */
object AgentIds {
    const val WHATSAPP_MASTER = "whatsapp-master"
    const val MESSENGER_MASTER = "messenger-master"

    internal val RESERVED: Map<String, Set<MessageSource>> = mapOf(
        WHATSAPP_MASTER to setOf(MessageSource.WHATSAPP),
        MESSENGER_MASTER to setOf(MessageSource.MESSENGER)
    )
}

/** What an agent reports back. Only short codes are stored, never free text, so message content cannot leak into task records. */
sealed class AgentResult {
    /** The agent is done with the task. [note] is a short code such as "no_action" (letters, digits, _ . : - only). */
    class Completed(val note: String? = null) : AgentResult()

    /**
     * The agent could not finish. [retryable] = true asks the Main Brain to offer the task again later (limited number of
     * attempts, growing delay). Default false: a task is only repeated when the agent explicitly says repeating is safe.
     */
    class Failed(val code: String, val retryable: Boolean = false) : AgentResult()
}

/**
 * Everything an agent gets for one task. It only ever describes ONE conversation: [message] and [history] belong to
 * [AgentTask.conversationId] and there is no way to ask this object for another conversation.
 */
class AgentContext internal constructor(
    val appContext: Context,
    val task: AgentTask,
    /** The message that caused the task. Already checked: same conversation, same app, not empty. */
    val message: ProcessedMessage,
    val conversation: ConversationState,
    /**
     * The Main Brain (v2.1). The ONLY source of reply text: an agent sends the Brain a request with its app facts and gets the Brain's text
     * back. Agents have no AI access of their own (the old `ai` handle is gone), so they cannot write a reply themselves.
     */
    val responses: BrainReplyPort,
    private val loadHistory: (Int) -> List<StoredMessage>
) {
    /** Newest [limit] messages of this task's conversation, oldest first. Runs off the main thread. */
    suspend fun history(limit: Int = 30): List<StoredMessage> = withContext(Dispatchers.IO) {
        loadHistory(limit.coerceIn(1, 100)).filter { it.conversationId == task.conversationId }
    }
}

/**
 * The one interface every app-specific agent implements (WhatsApp Master Agent and Messenger Master Agent).
 *
 * Rules for implementations:
 * - [handle] may suspend and must cooperate with cancellation (a cancelled task cancels the coroutine).
 * - Message text is DATA. Never turn it, or AI output, into shell commands or any other executable instruction.
 * - Never log message text, sender names or chat names. Return short codes in [AgentResult].
 * - Agents have NO root access: [AgentContext] carries no root handle and [RootCaller] has no agent entry (RootAccess.kt). Do not add one
 *   unless a specific implemented feature of that agent genuinely needs root.
 * - A task can be offered more than once after a retryable failure ([AgentTask.attempts]); do not repeat side effects.
 * - Hand-over of a task for a message is at-least-once across crashes only if [restartSafe] is true (see there).
 */
interface AppAgent {
    /** Unique, lowercase, e.g. [AgentIds.WHATSAPP_MASTER]. */
    val id: String
    val displayName: String
    /** The app(s) this agent serves. Two agents can never serve the same app. */
    val sources: Set<MessageSource>

    /** Longest time one [handle] call may take before the task is failed and the coroutine cancelled. */
    val taskTimeoutMs: Long get() = 90_000L

    /**
     * What happens to a task that was RUNNING when the app process died. false (default): it is marked failed
     * ("interrupted"), because the agent may already have done part of its work and repeating it could act twice.
     * true: it goes back to waiting and [handle] is called again.
     */
    val restartSafe: Boolean get() = false

    /** Called once when the agent is registered. Throw to refuse registration. */
    fun onRegistered(appContext: Context) {}

    /** Called when the agent is removed or the Main Brain stops. */
    fun onUnregistered() {}

    suspend fun handle(ctx: AgentContext): AgentResult
}

data class AgentInfo(val id: String, val displayName: String, val sources: Set<MessageSource>)

sealed class RegisterResult {
    object Registered : RegisterResult()
    class Rejected(val reason: String) : RegisterResult()
}

/** Registers, looks up and invokes agents. One agent per app, so a message can only ever reach the agent of its own app. */
object AgentManager {
    private val ID_FORMAT = Regex("^[a-z0-9][a-z0-9-]{1,40}$")
    private val lock = Any()
    private val agents = LinkedHashMap<String, AppAgent>()   // guarded by lock
    /** Told when agents come or go, so the Main Brain can look for work. Always called outside [lock]. */
    @Volatile internal var onChanged: (() -> Unit)? = null

    fun register(agent: AppAgent, appContext: Context): RegisterResult {
        val result = synchronized(lock) { registerLocked(agent, appContext) }
        if (result is RegisterResult.Registered) notifyChanged()
        return result
    }

    private fun registerLocked(agent: AppAgent, appContext: Context): RegisterResult {
        val id = try { agent.id } catch (_: Throwable) { return RegisterResult.Rejected("bad_id") }
        val sources = try { agent.sources } catch (_: Throwable) { return RegisterResult.Rejected("bad_sources") }
        if (!ID_FORMAT.matches(id)) return RegisterResult.Rejected("bad_id")
        if (sources.isEmpty()) return RegisterResult.Rejected("no_sources")
        if (agents.containsKey(id)) return RegisterResult.Rejected("duplicate_id")
        AgentIds.RESERVED[id]?.let { if (it != sources) return RegisterResult.Rejected("wrong_sources_for_reserved_id") }
        if (sources.any { !BrainRouting.supports(it) }) return RegisterResult.Rejected("unsupported_source")
        if (agents.values.any { other -> other.sources.any { it in sources } }) return RegisterResult.Rejected("source_already_served")
        try { agent.onRegistered(appContext.applicationContext) } catch (_: Throwable) { return RegisterResult.Rejected("attach_failed") }
        agents[id] = agent
        return RegisterResult.Registered
    }

    fun unregister(agentId: String) {
        val a = synchronized(lock) { agents.remove(agentId) } ?: return
        try { a.onUnregistered() } catch (_: Throwable) {}
        notifyChanged()
    }

    fun unregisterAll() {
        val all = synchronized(lock) { agents.values.toList().also { agents.clear() } }
        for (a in all) try { a.onUnregistered() } catch (_: Throwable) {}
        notifyChanged()
    }

    fun agentFor(source: MessageSource): AppAgent? = synchronized(lock) { agents.values.firstOrNull { source in it.sources } }
    fun agentById(id: String?): AppAgent? = if (id == null) null else synchronized(lock) { agents[id] }
    fun sourcesServed(): Set<MessageSource> = synchronized(lock) { agents.values.flatMap { it.sources }.toSet() }
    fun infos(): List<AgentInfo> = synchronized(lock) { agents.values.map { AgentInfo(it.id, it.displayName, it.sources) } }

    private fun notifyChanged() { try { onChanged?.invoke() } catch (_: Throwable) {} }

    /** Calls the agent with a time limit. Never throws except for cancellation; every other problem becomes [AgentResult.Failed]. */
    internal suspend fun invoke(agent: AppAgent, ctx: AgentContext): AgentResult =
        try {
            withTimeout(agent.taskTimeoutMs.coerceIn(1_000L, 600_000L)) { agent.handle(ctx) }
        } catch (_: TimeoutCancellationException) {
            AgentResult.Failed("timeout", false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AgentResult.Failed("agent_error_" + e.javaClass.simpleName.take(40), false)   // class name only: messages may contain chat text
        }
}

/**
 * The single place that lists the agents of this build: the WhatsApp Master Agent and the Messenger Master Agent. To add
 * another one later: implement [AppAgent] in its own file and add it to the list below.
 */
object AgentCatalog {
    fun create(ctx: Context): List<AppAgent> {
        val app = ctx.applicationContext
        // Step 10: each agent gets the automatic-reply hook of ITS OWN app; the hooks queue sends through the one Shared Task Manager.
        return listOf(
            WhatsAppMasterAgent(automation = WhatsAppAutoReply(app), sharedTasks = TaskManager),
            MessengerMasterAgent(automation = MessengerAutoReply(app), sharedTasks = TaskManager)
        )
    }
}
