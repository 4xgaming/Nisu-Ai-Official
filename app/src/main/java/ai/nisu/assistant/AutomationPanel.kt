package ai.nisu.assistant

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * The Automation screen (step 13). Plain 2D Compose with the existing NisuUi colours and GlassCard / MChip; no animation, no new
 * dependency. Everything shown comes from [Automation.snapshot] (real state, see AutomationControl.kt) or from the stored values the
 * engine itself reads. While the screen is open and the app is in the foreground it re-reads the real state every few seconds, so a
 * service that stopped or a permission that was revoked shows up without leaving the screen. It does nothing while hidden.
 */

private const val REFRESH_MS = 3_000L

@Composable
internal fun AutomationPage(active: Boolean, onAppInfo: () -> Unit, onStartService: () -> Unit) {
    val ctx: Context = LocalContext.current
    val snap by Automation.snapshot.collectAsState()
    val rev by Automation.revision.collectAsState()
    val notice by Automation.notice.collectAsState()
    val rootStatus by Automation.root.collectAsState()
    val rootPending by Automation.rootPending.collectAsState()
    val svc by BackgroundState.running.collectAsState()
    val access by MessageAccess.state.collectAsState()
    val brain by BrainState.snapshot.collectAsState()

    // The screen reflects real changes at once (service started / stopped, permission granted / revoked, agents registered) ...
    LaunchedEffect(svc, access, brain.running, brain.agents.size) { Automation.refreshNow(ctx.applicationContext) }
    // ... and re-reads the system now and then while it is visible, so a revoked permission is noticed too.
    LaunchedEffect(active) {
        while (active) {
            MessageAccess.refresh(ctx)
            Automation.refreshNow(ctx.applicationContext)
            delay(REFRESH_MS)
        }
    }

    var confirmStop by remember { mutableStateOf(false) }
    var sendDelayMs by remember { mutableStateOf(TaskManager.sendDelayMs(ctx)) }

    // The switches are read from the stored values the engine uses, never from a copy.
    val masterOn = remember(rev, snap?.loadedAt) { AutoReplySettings.masterOn(ctx) }
    val paused = remember(rev, snap?.loadedAt) { AutoReplySettings.paused(ctx) }
    val waOn = remember(rev, snap?.loadedAt) { AutoReplySettings.appOn(ctx, MessageSource.WHATSAPP) }
    val msgrOn = remember(rev, snap?.loadedAt) { AutoReplySettings.appOn(ctx, MessageSource.MESSENGER) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Automation", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

        // ---------------------------------------------------------------- 1. status
        APanel {
            val s = snap
            val (overallText, overallColor) = when {
                s == null -> "Reading the current state..." to NisuUi.Muted
                s.overall == AutoOverall.OFF -> "Automation is OFF" to NisuUi.Muted
                s.overall == AutoOverall.PAUSED -> "Automation is PAUSED" to NisuUi.Amber
                s.overall == AutoOverall.NOT_READY -> "Automation is ON, but cannot send right now" to NisuUi.Amber
                else -> "Automation is ON and ready" to NisuUi.Green
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(overallColor)
                Spacer(Modifier.width(10.dp))
                Text(overallText, fontSize = 17.sp, color = overallColor, fontWeight = FontWeight.SemiBold)
            }
            if (s != null) {
                for (p in s.overallProblems) ANote("\u2022 $p")
                if (!s.dataOk) ANote("Part of the data could not be read, so some numbers below may be missing.", NisuUi.Red)

                Divider()
                StatusRow(
                    "Background service",
                    when {
                        s.serviceRunning -> "Running"
                        s.serviceWanted -> "Turned on, not running"
                        else -> "Off"
                    },
                    if (s.serviceRunning) NisuUi.Green else NisuUi.Amber
                )
                StatusRow(
                    "Notification access",
                    when (s.access) {
                        MessageAccess.State.CONNECTED -> "Connected"
                        MessageAccess.State.WAITING -> "Allowed, not connected"
                        MessageAccess.State.OFF -> "Off"
                    },
                    if (s.access == MessageAccess.State.CONNECTED) NisuUi.Green else if (s.access == MessageAccess.State.WAITING) NisuUi.Amber else NisuUi.Red
                )
                StatusRow("Main Brain", if (s.brainRunning) "Running" else "Not running", if (s.brainRunning) NisuUi.Green else NisuUi.Amber)
                for (a in s.apps) {
                    val (t, c) = appStatus(a, masterOn, paused)
                    StatusRow(a.source.label + " automation", t, c)
                }
                StatusRow(
                    "Root access",
                    when (rootStatus) {
                        RootStatus.NOT_CHECKED -> "Not checked"
                        RootStatus.AVAILABLE -> "Available"
                        RootStatus.UNAVAILABLE -> "Not available"
                    },
                    if (rootStatus == RootStatus.AVAILABLE) NisuUi.Green else NisuUi.Muted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MChip(if (rootStatus == RootStatus.NOT_CHECKED) "Check root" else "Check root again") { Automation.checkRoot() }
                    Text("Optional. Messaging never uses root. The Main Brain only reads this status and asks you before it uses root.", fontSize = 11.sp, color = NisuUi.Muted, modifier = Modifier.weight(1f))
                }
                rootPending?.let { act ->
                    ANote("The Main Brain asks to use root to " + act.label + " Nothing happens unless you allow it.", NisuUi.Amber)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MChip("Allow", NisuUi.Green) { Automation.approveRootAction(ctx, act) }
                        MChip("Not now", NisuUi.Muted) { Automation.dismissRootAction() }
                    }
                }
                Divider()
                StatusRow("Pending tasks", s.pendingTasks.toString() + (if (s.runningTasks > 0) " (+${s.runningTasks} running)" else ""), if (s.pendingTasks > 0) NisuUi.Amber else NisuUi.Text)
                StatusRow("Active conversations", s.activeConversations.toString(), NisuUi.Text)
                Text(
                    "Active = a private chat that received a message in the last 24 hours and is not paused or stopped. Groups are never answered.",
                    fontSize = 11.sp, color = NisuUi.Muted
                )
                if (s.uncertainSends > 0) ANote("${s.uncertainSends} send(s) may or may not have gone out. They are never repeated automatically; please check the chat.", NisuUi.Red)
            }
        }

        // ---------------------------------------------------------------- 2. master switch and owner control
        APanel {
            Text("Master switch", fontSize = 15.sp, color = NisuUi.Cyan)
            ASwitch("Messaging automation (WhatsApp and Messenger). Off = NISU AI never sends anything by itself.", masterOn) { Automation.setMaster(ctx, it) }
            Text(
                "Turning it off cancels every waiting reply task at once. Voice, the assistant and all other features keep working.",
                fontSize = 11.sp, color = NisuUi.Muted
            )
            Divider()
            Text("Owner control", fontSize = 15.sp, color = NisuUi.Cyan)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MChip("Pause automation", NisuUi.Amber, enabled = masterOn && !paused) { Automation.pause(ctx) }
                MChip("Resume automation", NisuUi.Green, enabled = paused) { Automation.resume(ctx) }
            }
            MChip(if (confirmStop) "Tap again to stop all automation" else "Stop all automation", NisuUi.Red, enabled = masterOn || (snap?.pendingTasks ?: 0) > 0) {
                if (confirmStop) { Automation.stopAll(ctx); confirmStop = false } else confirmStop = true
            }
            notice?.let { ANote(it, NisuUi.Cyan) }
            Text(
                "Pause holds everything until you resume; the switches stay as they are. Waiting replies are cancelled, and they are not brought back: after Resume, only new messages are answered. " +
                    "Stop all turns the master switch off and cancels everything; it stays off until you turn it on. " +
                    "If you answer a chat yourself, NISU AI stops answering that chat (it is paused below).",
                fontSize = 11.sp, color = NisuUi.Muted
            )
        }

        // ---------------------------------------------------------------- 3. apps
        APanel {
            Text("Apps", fontSize = 15.sp, color = NisuUi.Cyan)
            AppBlock(MessageSource.WHATSAPP, "WhatsApp (and WhatsApp Business)", waOn, snap?.app(MessageSource.WHATSAPP), masterOn, paused) { Automation.setApp(ctx, MessageSource.WHATSAPP, it) }
            Divider()
            AppBlock(MessageSource.MESSENGER, "Messenger (and Messenger Lite)", msgrOn, snap?.app(MessageSource.MESSENGER), masterOn, paused) { Automation.setApp(ctx, MessageSource.MESSENGER, it) }
            Text(
                "Each app has its own switch and the other is never affected. The switch is checked again before every automatic reply, so turning one off also stops a reply that is about to go out.",
                fontSize = 11.sp, color = NisuUi.Muted
            )
        }

        // ---------------------------------------------------------------- 4. settings
        APanel {
            Text("Automation settings", fontSize = 15.sp, color = NisuUi.Cyan)
            ReplySettingsSelectors()
            StatusRow("Delay between successful sends", "${sendDelayMs / 1000} seconds", NisuUi.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sec in listOf(2L, 3L, 4L, 5L)) MChip("$sec s", if (sendDelayMs == sec * 1000L) NisuUi.Green else NisuUi.Muted) {
                    TaskManager.setSendDelayMs(ctx, sec * 1000L); sendDelayMs = TaskManager.sendDelayMs(ctx)
                }
            }
            Text(
                "Reply Delay is how long NISU AI waits after a message so you can answer by hand first (Instant skips that wait; a wait that already started keeps its old time). Reply Limit is per person per app; after it, one closing message and no more. A conversation that already reached its limit stays closed even if you raise the limit. Both choices are saved and stay after a restart. The send delay is the same setting as in Additional Settings (3 to 5 seconds). " +
                    "NISU AI never starts a conversation, never answers groups, and checks all of this again right before it sends.",
                fontSize = 11.sp, color = NisuUi.Muted
            )
        }

        // ---------------------------------------------------------------- 5. permissions
        APanel {
            val s = snap
            Text("Permissions", fontSize = 15.sp, color = NisuUi.Cyan)
            val accessNow = s?.access ?: access
            Text(
                when (accessNow) {
                    MessageAccess.State.CONNECTED -> "Notification access: verified. Android has connected NISU AI's listener, so messages can be seen and answered."
                    MessageAccess.State.WAITING -> "Notification access is allowed in Android, but the listener is not connected yet. NISU AI asks Android to reconnect while the background service runs."
                    MessageAccess.State.OFF -> "Notification access is OFF. Without it NISU AI cannot see or answer WhatsApp or Messenger messages."
                },
                fontSize = 13.sp, color = if (accessNow == MessageAccess.State.CONNECTED) NisuUi.Green else NisuUi.Amber
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MChip(if (accessNow == MessageAccess.State.OFF) "Open notification access" else "Notification access settings") { MessageAccess.openSettings(ctx) }
                MChip("Check again") { MessageAccess.refresh(ctx); Automation.refresh(ctx) }
            }
            if (accessNow == MessageAccess.State.OFF) {
                ANote("1. Tap 'Open notification access'.  2. Turn on NISU AI.  3. Come back here; this screen updates by itself.")
                ANote("Android 13+: if the switch is greyed out, tap 'App info', open the \u22EE menu, choose 'Allow restricted settings', then try again.")
                MChip("App info") { onAppInfo() }
            }
            if (s != null && !s.serviceRunning) {
                Divider()
                Text(
                    "The background service is not running. Automation only works while it runs (it keeps the Main Brain and the notification listener connected).",
                    fontSize = 13.sp, color = NisuUi.Amber
                )
                MChip("Start background service", NisuUi.Green) { onStartService() }
            }
            Text("A permission is shown as enabled only after Android itself reports it.", fontSize = 11.sp, color = NisuUi.Muted)
        }

        // ---------------------------------------------------------------- 6. conversations
        APanel {
            val s = snap
            Text("Conversations", fontSize = 15.sp, color = NisuUi.Cyan)
            if (s == null) {
                Text("Reading...", fontSize = 13.sp, color = NisuUi.Muted)
            } else {
                for (src in MessageSource.values()) {
                    val rows = s.conversations.filter { it.source == src }
                    val a = s.app(src)
                    Text(src.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (rows.isEmpty()) {
                        Text("No private " + src.label + " conversations saved yet.", fontSize = 12.sp, color = NisuUi.Muted)
                    } else {
                        for (r in rows) ConversationItem(ctx, r, a?.effective == true)
                    }
                    if (src != MessageSource.values().last()) Divider()
                }
                if (s.blockedConversations > 0) ANote("${s.blockedConversations} blocked conversation(s) are managed in Additional Settings > Privacy and conversations.", NisuUi.Muted)
            }
            Text(
                "No message text is shown here. Pause keeps a conversation on hold until you resume it. Stop closes it until you allow it again; nothing is deleted. " +
                    "A conversation that reached the reply limit (${AutoReplyLimits.maxText()}) is closed and opens again by itself 1 hour after its last automatic reply; choosing Unlimited opens all of them at once. Groups are never answered, so they are not listed.",
                fontSize = 11.sp, color = NisuUi.Muted
            )
        }

        // ---------------------------------------------------------------- 7. auto reply log (v2.0)
        var logTick by remember { mutableStateOf(0) }
        val logLines = remember(snap?.loadedAt, logTick) { AutoReplyLog.read(ctx, 30) }
        APanel {
            Text("Auto reply log", fontSize = 15.sp, color = NisuUi.Cyan)
            if (logLines.isEmpty()) {
                Text("Nothing recorded yet. After a message arrives, each step (owner check, 30 s wait, AI draft, send) is written here as a short code.", fontSize = 12.sp, color = NisuUi.Muted)
            } else {
                for (l in logLines) Text(l, fontSize = 11.sp, color = if (l.contains("| ERR ")) NisuUi.Red else NisuUi.Muted)
            }
            MChip("Clear log") { AutoReplyLog.clear(ctx); logTick = logTick + 1 }
            Text("Newest first. A red line is the step that stopped or failed a reply. Codes only: no message text, no names.", fontSize = 11.sp, color = NisuUi.Muted)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ====================================================================== pieces

@Composable
private fun AppBlock(
    source: MessageSource, label: String, on: Boolean, status: AppAutomationStatus?, master: Boolean, paused: Boolean, onChange: (Boolean) -> Unit
) {
    ASwitch(label, on, onChange)
    if (status != null) {
        val (t, c) = appStatus(status, master, paused)
        Text(t, fontSize = 12.sp, color = c)
        if (master && !paused) for (p in status.problems) ANote("\u2022 $p")
        Text(
            "Waiting tasks: ${status.pendingTasks}   Active chats: ${status.activeConversations}   Paused: ${status.pausedConversations}   Stopped: ${status.stoppedConversations}",
            fontSize = 11.sp, color = NisuUi.Muted
        )
    }
}

/** One line of text and its colour for an app, from real values only. */
private fun appStatus(a: AppAutomationStatus, master: Boolean, paused: Boolean): Pair<String, Color> = when {
    !a.switchOn -> "Off (this app's switch)" to NisuUi.Muted
    !master -> "Off (master switch is off)" to NisuUi.Muted
    paused -> "Paused" to NisuUi.Amber
    a.problems.isNotEmpty() -> "On, but cannot send: " + a.problems.first().trimEnd('.') to NisuUi.Amber
    else -> "On and ready" to NisuUi.Green
}

@Composable
private fun ConversationItem(ctx: Context, r: AutoConversationRow, appRunning: Boolean) {
    val (label, color) = when (r.state) {
        ConvState.ACTIVE -> (if (appRunning) "Active" else "Active (automation not running)") to (if (appRunning) NisuUi.Green else NisuUi.Muted)
        ConvState.PAUSED -> (if (r.reason == "owner_replied") "Paused: you replied" else "Paused") to NisuUi.Amber
        ConvState.COMPLETED -> (if (r.reason == Automation.STOPPED_BY_OWNER) "Stopped by you" else "Closed: reply limit reached") to NisuUi.Muted
        ConvState.BLOCKED -> "Blocked" to NisuUi.Red
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(if (ReplySettings.limit().unlimited) "${r.replies}" else "${r.replies}/${AutoReplyLimits.max()}", fontSize = 12.sp, color = NisuUi.Muted, textAlign = TextAlign.End)
        }
        Text(label, fontSize = 12.sp, color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (r.state) {
                ConvState.ACTIVE -> {
                    MChip("Pause", NisuUi.Amber) { Automation.pauseConversation(ctx, r) }
                    MChip("Stop", NisuUi.Red) { Automation.stopConversation(ctx, r) }
                }
                ConvState.PAUSED -> {
                    MChip("Resume", NisuUi.Green) { Automation.resumeConversation(ctx, r) }
                    MChip("Stop", NisuUi.Red) { Automation.stopConversation(ctx, r) }
                }
                ConvState.COMPLETED -> if (r.reason == Automation.STOPPED_BY_OWNER) MChip("Allow again", NisuUi.Green) { Automation.allowConversation(ctx, r) }
                ConvState.BLOCKED -> {}
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = NisuUi.Muted)
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 13.sp, color = color, textAlign = TextAlign.End)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(NisuUi.Line.copy(alpha = 0.6f)))
}

@Composable
private fun APanel(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ANote(text: String, color: Color = NisuUi.Amber) = Text(text, fontSize = 12.sp, color = color)

@Composable
private fun ASwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = NisuUi.Blue,
                uncheckedThumbColor = NisuUi.Muted, uncheckedTrackColor = NisuUi.Card, uncheckedBorderColor = NisuUi.Line
            )
        )
    }
}
