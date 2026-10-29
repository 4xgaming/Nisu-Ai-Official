package ai.nisu.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Automation Settings and Control Panel (step 13).
 *
 * This file is the control layer behind the "Automation" screen (AutomationPanel.kt). It is NOT a second automation engine and it
 * keeps no settings of its own:
 *
 *   - The switches are the ones the engine already reads: Prefs.AUTO_REPLY (master), Prefs.WA_AUTO / Prefs.MSGR_AUTO (per app) and,
 *     new in this step, Prefs.AUTO_PAUSED. Every decision "may NISU AI send now?" is still made by AutoReplySettings
 *     (AutoReply.kt), which the agents' hooks, AutoReply.consider / preflight and the sender (twice, plus once more right before
 *     the reply action is fired) all call. A value written here is visible to all of them at once.
 *   - The owner wait (OwnerLimits.waitMs, Settings: 0/15/30 s), the reply limit (AutoReplyLimits.max, Settings: unlimited/20/50) and the 3-5 s pause between
 *     confirmed sends (TaskManager.sendDelayMs / TaskLimits) are shown from the constants and preferences the engine uses. Only the
 *     pause is configurable (it already was, within 3-5 s); the other two are fixed rules and are shown read-only.
 *   - Per-conversation pause / stop use the existing conversation lifecycle (Privacy.kt, table conversation_lifecycle): the same
 *     state that a manual reply of Raj sets, and that five places in the pipeline already check before anything is drafted or sent.
 *
 * What an action does, in this order: (1) writes the stored value, so every check sees it immediately; (2) ends the tasks that
 * are only WAITING (and cancels reply drafting in progress) so nothing stale is left in the queue; (3) refreshes the screen.
 * A send that is already running is never cancelled from here: if its reply action was already fired, cancelling would turn a
 * message that may have been delivered into an unverified one. It is stopped by the sender's own checks instead (see
 * TaskManager.cancelForAutomation).
 *
 * The status shown on screen is read from the real sources (BackgroundState, MessageAccess, BrainState, the task and message
 * databases). Nothing is assumed: a value that cannot be read is shown as unknown, never as OK. No message text is read or shown;
 * conversations appear with their chat name only, the same name Android already shows in its notification.
 */

/** How the whole automation is doing, in one word for the top of the screen. */
enum class AutoOverall { OFF, PAUSED, NOT_READY, READY }

/** Root is optional and not used for messaging. The status is the one shared by every part of the app ([RootAccess.status]). */
enum class RootStatus { NOT_CHECKED, AVAILABLE, UNAVAILABLE }

/** Status of one app's automation. [problems] are real, current reasons why it cannot send, in plain words. */
data class AppAutomationStatus(
    val source: MessageSource,
    /** The app's own switch. */
    val switchOn: Boolean,
    /** Master on, not paused, this app on: what AutoReplySettings.enabled says right now. */
    val effective: Boolean,
    /** The app's Master Agent is registered in the Main Brain right now. */
    val agentRegistered: Boolean,
    /** The "write reply drafts with Gemini" switch of this app (no draft = nothing to send). */
    val draftsOn: Boolean,
    val pendingTasks: Int,
    val activeConversations: Int,
    val pausedConversations: Int,
    /** Stopped by Raj, or the reply limit was reached. */
    val stoppedConversations: Int,
    val problems: List<String>
)

/** One private conversation as the panel shows it. No message text. */
data class AutoConversationRow(
    val key: String,
    val source: MessageSource,
    val packageName: String,
    val conversationId: String,
    val title: String,
    val state: ConvState,
    /** Short code stored with the state (owner_replied, paused_by_owner, stopped_by_owner, reply_limit_reached, ...), or null. */
    val reason: String?,
    /** Automatic replies that count against the limit so far (confirmed + uncertain). */
    val replies: Int,
    val lastMessageAt: Long
)

data class AutomationSnapshot(
    val loadedAt: Long,
    val masterOn: Boolean,
    val paused: Boolean,
    val overall: AutoOverall,
    /** Real reasons why the overall state is not READY (empty for READY / OFF / PAUSED). */
    val overallProblems: List<String>,
    val serviceRunning: Boolean,
    val serviceWanted: Boolean,
    val brainRunning: Boolean,
    val access: MessageAccess.State,
    /** false = the task or message database could not be read; the numbers below are then 0 and must not be trusted. */
    val dataOk: Boolean,
    val pendingTasks: Int,
    val runningTasks: Int,
    val uncertainSends: Int,
    val apps: List<AppAutomationStatus>,
    val conversations: List<AutoConversationRow>,
    /** Blocked conversations are managed in Additional Settings (they hold no name here). */
    val blockedConversations: Int
) {
    fun app(source: MessageSource): AppAutomationStatus? = apps.firstOrNull { it.source == source }
    val activeConversations: Int get() = apps.sumOf { it.activeConversations }
}

object Automation {
    private const val TAG = "NisuAutomation"

    /** A private chat counts as "active" when it received a message within this time and no hold is on it. */
    const val ACTIVE_WINDOW_MS = 24L * 60 * 60 * 1000
    /** Most conversations listed per app (held ones first). Keeps the screen light. */
    private const val MAX_ROWS_PER_APP = 20

    // reasons written into cancelled tasks (TaskStore.cleanOutcome keeps them as they are)
    private const val R_STOPPED = "auto_stopped_by_owner"
    private const val R_CONV_PAUSED = "conversation_paused"
    private const val R_CONV_STOPPED = "conversation_stopped"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The latest real status. Null until the first load. */
    val snapshot = MutableStateFlow<AutomationSnapshot?>(null)

    /** Bumped synchronously by every action, so the screen re-reads the switches at once (they are read from the stored values). */
    val revision = MutableStateFlow(0)

    /** One-line result of the last action (what was cancelled, what failed), for the screen. */
    val notice = MutableStateFlow<String?>(null)

    /** The shared root status (RootAccess.kt). Not a copy: the Main Brain and every root check in the app update the same value. */
    val root: StateFlow<RootStatus> = RootAccess.status

    /** A Root action the Main Brain asked for, waiting for Raj's approval on the panel. */
    val rootPending: StateFlow<RootAction?> = RootAccess.pending

    private fun changed() { revision.value = revision.value + 1 }

    private val ALL_SOURCES: Set<MessageSource> = MessageSource.values().toSet()

    // ------------------------------------------------------------------ the master switch and the app switches

    /**
     * The global switch. Off: the stored value is written first (nothing new is scheduled or sent from this instant), then every waiting
     * task is cancelled. Turning it off or on also ends a pause: a pause belongs to a running automation.
     */
    fun setMaster(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        Prefs.putBool(app, Prefs.AUTO_REPLY, on)
        Prefs.putBool(app, Prefs.AUTO_PAUSED, false)
        changed()
        scope.launch {
            if (!on) cancelAndReport(app, ALL_SOURCES, AutoReplySettings.OFF, "Automation is off.")
            else notice.value = "Automation is on."
            refreshNow(app)
        }
    }

    /** One app's switch. The other app is never touched. Off: this app's waiting tasks are cancelled. */
    fun setApp(ctx: Context, source: MessageSource, on: Boolean) {
        val app = ctx.applicationContext
        Prefs.putBool(app, if (source == MessageSource.WHATSAPP) Prefs.WA_AUTO else Prefs.MSGR_AUTO, on)
        changed()
        scope.launch {
            if (!on) cancelAndReport(app, setOf(source), AutoReplySettings.APP_OFF, source.label + " automation is off.")
            else notice.value = source.label + " automation is on."
            refreshNow(app)
        }
    }

    // ------------------------------------------------------------------ owner control

    /** Pause: nothing is scheduled or sent until Resume. The switches stay as they are. Waiting tasks are cancelled (see [resume]). */
    fun pause(ctx: Context) {
        val app = ctx.applicationContext
        if (!AutoReplySettings.masterOn(app)) { notice.value = "Automation is off, so there is nothing to pause."; return }
        Prefs.putBool(app, Prefs.AUTO_PAUSED, true)
        changed()
        scope.launch {
            cancelAndReport(app, ALL_SOURCES, AutoReplySettings.PAUSED, "Automation is paused.")
            refreshNow(app)
        }
    }

    /**
     * Resume: new messages are handled again. Tasks cancelled by the pause are NOT brought back: a reply that waited through a pause
     * would answer an old message as if it were new. The next message of a person starts a new task with a new 30-second wait.
     */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        Prefs.putBool(app, Prefs.AUTO_PAUSED, false)
        changed()
        notice.value = if (AutoReplySettings.masterOn(app)) "Automation resumed. New messages are handled again." else "Pause ended, but the master switch is off."
        scope.launch { refreshNow(app) }
    }

    /**
     * Stop all: the master switch goes off, a pause ends, every waiting task is cancelled and reply drafting in progress is cancelled.
     * Nothing starts again until Raj turns the master switch on. The per-app switches and the conversation holds are left as they are.
     */
    fun stopAll(ctx: Context) {
        val app = ctx.applicationContext
        Prefs.putBool(app, Prefs.AUTO_REPLY, false)
        Prefs.putBool(app, Prefs.AUTO_PAUSED, false)
        changed()
        scope.launch {
            cancelAndReport(app, ALL_SOURCES, R_STOPPED, "All automation stopped.")
            refreshNow(app)
        }
    }

    private suspend fun cancelAndReport(app: Context, sources: Set<MessageSource>, reason: String, head: String) {
        val (waiting, drafting) = withContext(Dispatchers.IO) { TaskManager.cancelForAutomation(app, sources, reason) }
        notice.value = head + when {
            waiting == 0 && drafting == 0 -> " No waiting tasks."
            else -> " Cancelled $waiting waiting task(s)" + (if (drafting > 0) " and $drafting reply draft(s) in progress." else ".")
        }
    }

    // ------------------------------------------------------------------ one conversation

    /** Pause one conversation. Only raises the state (a stopped or blocked conversation is not lowered to paused). */
    fun pauseConversation(ctx: Context, row: AutoConversationRow) = holdConversation(ctx, row, ConvState.PAUSED, "paused_by_owner", R_CONV_PAUSED, "Conversation paused.")

    /** Stop one conversation: it is never answered again until Raj allows it again. Unlike Block, no message is deleted. */
    fun stopConversation(ctx: Context, row: AutoConversationRow) = holdConversation(ctx, row, ConvState.COMPLETED, STOPPED_BY_OWNER, R_CONV_STOPPED, "Conversation stopped.")

    private fun holdConversation(ctx: Context, row: AutoConversationRow, state: ConvState, reason: String, taskReason: String, done: String) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val store = TaskManager.openStore(app)
                if (store == null) { notice.value = "Could not save: the task database is not available."; return@launch }
                store.lifecycleRaise(row.key, row.source, state, reason, System.currentTimeMillis())
                val n = TaskManager.cancelWaitingOfConversation(app, row.conversationId, taskReason)
                ConversationLifecycle.publish(store)
                TaskManager.lifecycleChanged()
                notice.value = done + (if (n > 0) " Cancelled $n waiting task(s)." else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "conversation hold failed: " + e.javaClass.simpleName)
                notice.value = "Could not save the change."
            }
            changed()
            refreshNow(app)
        }
    }

    /** Resume one paused conversation. A stopped, completed or blocked one is not touched by this (see [allowConversation]). */
    fun resumeConversation(ctx: Context, row: AutoConversationRow) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val store = TaskManager.openStore(app)
                if (store == null) { notice.value = "Could not save: the task database is not available."; return@launch }
                val ok = store.lifecycleResumeOne(row.key)
                ConversationLifecycle.publish(store)
                TaskManager.lifecycleChanged()
                notice.value = if (ok) "Conversation resumed." else "That conversation was not paused."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "conversation resume failed: " + e.javaClass.simpleName)
                notice.value = "Could not save the change."
            }
            changed()
            refreshNow(app)
        }
    }

    /** Lifts a stop that Raj set himself. A conversation that reached the reply limit stays closed (the counter is separate and still applies). */
    fun allowConversation(ctx: Context, row: AutoConversationRow) {
        if (row.reason != STOPPED_BY_OWNER) return
        val app = ctx.applicationContext
        scope.launch {
            try {
                val store = TaskManager.openStore(app)
                if (store == null) { notice.value = "Could not save: the task database is not available."; return@launch }
                store.lifecycleSet(row.key, row.source, ConvState.ACTIVE, null, System.currentTimeMillis())
                ConversationLifecycle.publish(store)
                TaskManager.lifecycleChanged()
                notice.value = "Conversation allowed again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "conversation allow failed: " + e.javaClass.simpleName)
                notice.value = "Could not save the change."
            }
            changed()
            refreshNow(app)
        }
    }

    const val STOPPED_BY_OWNER = "stopped_by_owner"

    // ------------------------------------------------------------------ root (optional, shared with the Main Brain)

    /**
     * Asks `su` whether root is available. Only called when Raj presses the button, because the root manager may show its own
     * permission prompt. The answer goes to the shared status (RootAccess), so the Main Brain sees it too and nothing asks again.
     * Messaging automation does not use root at all.
     */
    fun checkRoot() {
        scope.launch { RootAccess.verify(); changed() }
    }

    /** Raj tapped "Allow" on a request of the Main Brain. The only place where an owner approval is given. */
    fun approveRootAction(ctx: Context, action: RootAction) {
        val app = ctx.applicationContext
        scope.launch {
            notice.value = when (val r = RootAccess.request(app, RootCaller.CONTROL_PANEL, action, ownerConfirmed = true)) {
                is RootOutcome.Ran -> if (r.ok) "Done. Check the status above." else "It ran, but something could not be completed. Check the status above."
                RootOutcome.AwaitingOwner -> "Waiting for your approval."
                is RootOutcome.Refused -> "Root action not run: " + rootRefusal(r.code)
            }
            changed()
            refreshNow(app)
        }
    }

    /** Raj tapped "Not now". Nothing runs. */
    fun dismissRootAction() { RootAccess.dismiss(); changed() }

    private fun rootRefusal(code: String): String = when (code) {
        RootAccess.UNAVAILABLE -> "root is not available (not granted or not present)."
        RootAccess.NOT_CHECKED -> "root has not been checked yet. Press \"Check root\" first."
        RootAccess.BUSY -> "another root action is still running."
        else -> "not allowed."
    }

    // ------------------------------------------------------------------ reading the real state

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { refreshNow(app) }
    }

    suspend fun refreshNow(app: Context) {
        try {
            snapshot.value = load(app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "status could not be read: " + e.javaClass.simpleName)
        }
    }

    /** Reads everything from its real source. Blocking disk reads: runs on the IO dispatcher. */
    suspend fun load(app: Context): AutomationSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val master = AutoReplySettings.masterOn(app)
        val paused = AutoReplySettings.paused(app)
        val serviceRunning = BackgroundState.running.value
        val brain = BrainState.snapshot.value
        val access = MessageAccess.state.value

        var dataOk = true
        var pendingTotal = 0; var runningTotal = 0; var uncertain = 0
        var pendingBy: Map<MessageSource, Int> = emptyMap()
        var lifeRows: List<LifecycleRow> = emptyList()
        var chats: List<ConversationInfo> = emptyList()
        val store = TaskManager.openStore(app)
        if (store == null) dataOk = false
        else try {
            val c = store.counts(ALL_SOURCES)
            pendingTotal = c.pending; runningTotal = c.running; uncertain = c.uncertainSends
            pendingBy = store.pendingBySource()
            lifeRows = store.lifecycleRows()
        } catch (e: Exception) { dataOk = false }
        try {
            val ms = MessageProcessor.store(app)
            if (ms == null) dataOk = false else chats = ms.conversations(null)
        } catch (e: Exception) { dataOk = false }

        val lifeByKey = lifeRows.associateBy { it.key }
        val rowsBySource = HashMap<MessageSource, MutableList<AutoConversationRow>>()
        for (ci in chats) {
            if (ci.kind == ConversationKind.GROUP) continue   // groups are never answered, so they are not automation conversations
            val key = ConversationLifecycle.keyFor(ci.packageName, ci.title)
            val life = lifeByKey[key]
            val used = try { store?.replyCounts(key)?.used ?: 0 } catch (_: Exception) { 0 }
            rowsBySource.getOrPut(ci.source) { ArrayList() }.add(
                AutoConversationRow(key, ci.source, ci.packageName, ci.id, ci.title, life?.state ?: ConvState.ACTIVE, life?.reason, used, ci.lastMessageAt)
            )
        }

        val apps = ArrayList<AppAutomationStatus>()
        val listed = ArrayList<AutoConversationRow>()
        for (src in MessageSource.values()) {
            val rows = rowsBySource[src] ?: ArrayList()
            val switchOn = AutoReplySettings.appOn(app, src)
            val effective = AutoReplySettings.enabled(app, src)
            val agent = brain.running && brain.agents.any { src in it.sources }
            val drafts = Prefs.getBool(app, if (src == MessageSource.WHATSAPP) Prefs.WA_DRAFTS else Prefs.MSGR_DRAFTS, true)
            val active = rows.count { it.state == ConvState.ACTIVE && now - it.lastMessageAt <= ACTIVE_WINDOW_MS }
            val pausedN = rows.count { it.state == ConvState.PAUSED }
            val stoppedN = rows.count { it.state == ConvState.COMPLETED }
            val problems = ArrayList<String>()
            if (switchOn && master) {
                if (!serviceRunning) problems.add("The background service is not running.")
                else if (!agent) problems.add("The " + src.label + " agent is not registered in the Main Brain.")
                when (access) {
                    MessageAccess.State.OFF -> problems.add("Notification access is off.")
                    MessageAccess.State.WAITING -> problems.add("Notification access is allowed but Android has not connected NISU AI's listener.")
                    MessageAccess.State.CONNECTED -> {}
                }
                if (!drafts) problems.add("Reply drafts with Gemini are off for " + src.label + ", so there is nothing to send.")
            }
            apps.add(AppAutomationStatus(src, switchOn, effective, agent, drafts, pendingBy[src] ?: 0, active, pausedN, stoppedN, problems))

            // held conversations first, then the newest of the rest
            val sorted = rows.sortedWith(compareByDescending<AutoConversationRow> { it.state != ConvState.ACTIVE }.thenByDescending { it.lastMessageAt })
            listed.addAll(sorted.take(MAX_ROWS_PER_APP))
        }

        val problems = ArrayList<String>()
        if (master && !paused) {
            if (!serviceRunning) problems.add("The background service is not running.")
            else if (!brain.running) problems.add("The Main Brain is not running.")
            when (access) {
                MessageAccess.State.OFF -> problems.add("Notification access is off: NISU AI cannot see or answer messages.")
                MessageAccess.State.WAITING -> problems.add("Notification access is allowed, but Android has not connected NISU AI's listener.")
                MessageAccess.State.CONNECTED -> {}
            }
            if (apps.none { it.switchOn }) problems.add("Both app switches are off.")
            else if (apps.filter { it.switchOn }.all { !it.draftsOn }) problems.add("Reply drafts with Gemini are off, so there is nothing to send.")
            if (!dataOk) problems.add("The task or message database could not be read.")
        }
        val overall = when {
            !master -> AutoOverall.OFF
            paused -> AutoOverall.PAUSED
            problems.isNotEmpty() -> AutoOverall.NOT_READY
            else -> AutoOverall.READY
        }

        AutomationSnapshot(
            loadedAt = now, masterOn = master, paused = paused, overall = overall, overallProblems = problems,
            serviceRunning = serviceRunning, serviceWanted = NisuBackgroundService.isEnabled(app), brainRunning = brain.running, access = access,
            dataOk = dataOk, pendingTasks = pendingTotal, runningTasks = runningTotal, uncertainSends = uncertain,
            apps = apps, conversations = listed, blockedConversations = lifeRows.count { it.state == ConvState.BLOCKED }
        )
    }
}
