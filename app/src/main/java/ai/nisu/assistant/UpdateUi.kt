package ai.nisu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** The "Updates" row in the side menu: red with a count when an update is waiting, a percent while downloading, green when the newest version is installed. */
@Composable
internal fun UpdateRow() {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val pct by Updater.progress.collectAsState()
    val info by Updater.info.collectAsState()
    val blocked by Updater.blocked.collectAsState()
    val err by Updater.error.collectAsState()
    val col = when (st) {
        Updater.St.AVAILABLE -> NisuUi.Red
        Updater.St.LATEST -> NisuUi.Green
        Updater.St.DOWNLOADING, Updater.St.READY -> NisuUi.Amber
        else -> Color.White.copy(alpha = 0.85f)
    }
    val sub = when (st) {
        Updater.St.UNKNOWN -> "Tap to check for updates"
        Updater.St.LATEST -> "Latest version installed (" + Updater.version(ctx) + ")"
        Updater.St.AVAILABLE -> "1 update available: " + (info?.tag ?: "")
        Updater.St.DOWNLOADING -> "Downloading $pct%"
        Updater.St.READY -> if (blocked) "Install was stopped. See below." else "Downloaded. Installing..."
    }
    val action: () -> Unit = {
        when (st) {
            Updater.St.UNKNOWN, Updater.St.LATEST -> Updater.check(ctx, manual = true)
            Updater.St.AVAILABLE -> Updater.start(ctx)
            Updater.St.READY -> Updater.install(ctx)
            Updater.St.DOWNLOADING -> {}
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.10f))
        .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(if (st == Updater.St.LATEST) Ic.CHECK else Ic.BOLT, col, 24.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Updates", fontSize = 16.sp, letterSpacing = 0.4.sp)
                Text(sub, fontSize = 12.sp, color = col, fontWeight = FontWeight.SemiBold)
            }
            if (st == Updater.St.AVAILABLE) Box(Modifier.size(22.dp).clip(CircleShape).background(NisuUi.Red), contentAlignment = Alignment.Center) { Text("1", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
        if (st == Updater.St.DOWNLOADING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = NisuUi.Amber, trackColor = Color.White.copy(alpha = 0.15f))
        }
        if (st == Updater.St.AVAILABLE && err.isNotEmpty()) Text(err, fontSize = 11.sp, color = NisuUi.Red, modifier = Modifier.padding(top = 6.dp))
        if (st == Updater.St.READY && blocked) {
            Spacer(Modifier.height(6.dp))
            Text("If Play Protect blocked it: open Play Protect settings, turn off \"Scan apps with Play Protect\", come back and tap Install again. (Or tap \"Install anyway\" on the blocked screen.)", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MChip("PLAY PROTECT", NisuUi.Amber) { Updater.openPlayProtect(ctx) }
                MChip("INSTALL AGAIN", NisuUi.Cyan) { Updater.install(ctx) }
            }
        }
    }
}

/** The popup shown when the app opens and a new version exists: Download button, closes by itself after 5 seconds (not while downloading). */
@Composable
internal fun UpdatePopup() {
    val ctx = LocalContext.current
    val show by Updater.popup.collectAsState()
    val st by Updater.state.collectAsState()
    val pct by Updater.progress.collectAsState()
    val info by Updater.info.collectAsState()
    LaunchedEffect(show) {
        if (show) {
            delay(5000L)
            if (Updater.state.value != Updater.St.DOWNLOADING) Updater.popup.value = false
        }
    }
    LaunchedEffect(st) { if (st == Updater.St.READY || st == Updater.St.LATEST) Updater.popup.value = false }
    if (!show || info == null) return
    Dialog(onDismissRequest = { Updater.popup.value = false }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B1B33)).border(1.dp, NisuUi.Red.copy(alpha = 0.7f), RoundedCornerShape(22.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("New update available", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Version " + (info?.tag ?: "") + "  (you have " + Updater.version(ctx) + ")", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            if (st == Updater.St.DOWNLOADING) {
                Text("Downloading $pct%", fontSize = 14.sp, color = NisuUi.Amber)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = NisuUi.Amber, trackColor = Color.White.copy(alpha = 0.15f))
            } else {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(NisuUi.Red).clickable { Updater.start(ctx) }.padding(horizontal = 28.dp, vertical = 10.dp)) {
                    Text("Download", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
