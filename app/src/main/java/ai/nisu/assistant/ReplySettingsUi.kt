package ai.nisu.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reply Delay + Reply Limit selectors. ONE composable shown on Preferences, Additional Settings and the Automation screen,
 * so the choice is always easy to find. Both read and write [ReplySettings] (the single stored setting).
 */
@Composable
fun ReplySettingsSelectors() {
    val ctx = LocalContext.current
    var delaySel by remember { mutableStateOf(ReplySettings.delay()) }
    var limitSel by remember { mutableStateOf(ReplySettings.limit()) }
    var modeSel by remember { mutableStateOf(ReplySettings.mode()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reply Mode (WhatsApp and Messenger)", fontSize = 14.sp, color = NisuUi.Cyan)
        for (m in ReplySettings.Mode.values()) {
            val on = modeSel == m
            MChip((if (on) "● " else "○ ") + m.title, if (on) NisuUi.Green else NisuUi.Muted) {
                ReplySettings.setMode(ctx, m); modeSel = ReplySettings.mode()
            }
        }
        Text(modeSel.desc, fontSize = 12.sp, color = NisuUi.Text)
        Text("Reply Delay", fontSize = 14.sp, color = NisuUi.Cyan)
        for (d in ReplySettings.Delay.values()) {
            val on = delaySel == d
            MChip((if (on) "● " else "○ ") + d.title + " — " + d.seconds + " sec" + (if (d == ReplySettings.DEFAULT_DELAY) " (Default)" else ""),
                if (on) NisuUi.Green else NisuUi.Muted) {
                ReplySettings.setDelay(ctx, d); delaySel = ReplySettings.delay()
            }
        }
        Text("Selected: " + delaySel.label, fontSize = 12.sp, color = NisuUi.Text)
        Text("Reply Limit", fontSize = 14.sp, color = NisuUi.Cyan)
        for (l in ReplySettings.Limit.values()) {
            val on = limitSel == l
            MChip((if (on) "● " else "○ ") + l.title + (if (l == ReplySettings.DEFAULT_LIMIT) " — Default" else ""),
                if (on) NisuUi.Green else NisuUi.Muted) {
                ReplySettings.setLimit(ctx, l); limitSel = ReplySettings.limit()
            }
        }
        Text("Selected: " + limitSel.label, fontSize = 12.sp, color = NisuUi.Text)
        Text("Saved automatically; stays after restart. A wait already running keeps its old time.", fontSize = 11.sp, color = NisuUi.Muted)
    }
}
