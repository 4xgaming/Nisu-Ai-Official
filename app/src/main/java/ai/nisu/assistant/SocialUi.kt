package ai.nisu.assistant

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * v3.80 Social Accounts page (side menu): save the links of your YouTube, Facebook, Instagram, X and TikTok accounts. "Check" reads the public page
 * and shows if it is live and what it says; the AI uses the same check when you ask it by voice or text (SocialAccounts.kt).
 */
@Composable
internal fun SocialAccountsPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by SocialAccounts.accounts.collectAsState()
    val results by SocialAccounts.last.collectAsState()
    var link by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SocialAccounts.load(ctx) } }

    fun check(accs: List<SocialAccounts.Account>) {
        if (accs.isEmpty()) return
        if (!HiddenBrowser.enabled(ctx)) { Toast.makeText(ctx, "Search is switched off in Settings, so accounts cannot be checked.", Toast.LENGTH_LONG).show(); return }
        busy = busy + accs.map { it.id }
        scope.launch {
            withContext(Dispatchers.IO) { SocialAccounts.checkStatuses(accs) }
            busy = busy - accs.map { it.id }.toSet()
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line, focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text,
        cursorColor = NisuUi.Cyan, focusedLabelColor = NisuUi.Cyan, unfocusedLabelColor = NisuUi.Muted
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Social Accounts", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = NisuUi.Text, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) BPill("Check all") { check(list.take(8)) }
        }
        Text(
            "Save the links of your YouTube, Facebook, Instagram, X and TikTok accounts. Then just ask: \"is my channel live?\" or \"check my social accounts\". " +
                "Only public pages are read (no sign-in, nothing is posted). YouTube can say live or not live. Facebook, Instagram, X and TikTok hide this behind sign-in, so they show live only when the page says so, otherwise \"can't tell\".",
            fontSize = 12.sp, color = NisuUi.Muted
        )
        OutlinedTextField(
            value = link, onValueChange = { link = it }, label = { Text("Account or channel link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = fieldColors
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label, onValueChange = { label = it }, label = { Text("Name (optional)") }, singleLine = true,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = fieldColors
            )
            BPill("Add") {
                val a = if (link.isNotBlank()) SocialAccounts.add(ctx, link, label) else null
                if (a != null) { link = ""; label = "" }
                else Toast.makeText(ctx, "Use a link of YouTube, Facebook, Instagram, X or TikTok", Toast.LENGTH_SHORT).show()
            }
        }
        if (list.isEmpty()) Text("No account saved yet.", fontSize = 13.sp, color = NisuUi.Dim, modifier = Modifier.padding(top = 18.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.asReversed(), key = { it.id }) { acc ->
                val shape = RoundedCornerShape(16.dp)
                val st = results[acc.id]
                val working = acc.id in busy
                val dot = when {
                    working -> NisuUi.Violet
                    st == null -> NisuUi.Dim
                    st.live == "yes" -> Color(0xFF3DDC84)
                    st.live == "no" -> NisuUi.Muted
                    else -> Color(0xFFFFB84D)
                }
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(NisuUi.Card).border(1.dp, NisuUi.Line, shape).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            acc.platform.replaceFirstChar { it.uppercase() } + (if (acc.label.isNotBlank()) " - " + acc.label else ""),
                            fontSize = 14.sp, color = NisuUi.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when { working -> "checking..."; st == null -> "not checked"; st.live == "yes" -> "LIVE NOW"; st.live == "no" -> "not live"; else -> "can't tell" },
                            fontSize = 11.sp, color = dot
                        )
                    }
                    Text(acc.url, fontSize = 12.sp, color = NisuUi.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (st != null && !working) {
                        if (st.error.isNotEmpty()) Text("Problem: " + HiddenBrowser.humanError(st.error), fontSize = 12.sp, color = NisuUi.Text.copy(alpha = 0.9f))
                        else {
                            if (st.title.isNotBlank()) Text(st.title, fontSize = 13.sp, color = NisuUi.Text)
                            if (st.info.isNotBlank()) Text(st.info, fontSize = 12.sp, color = NisuUi.Text.copy(alpha = 0.85f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Checked " + SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()).format(Date(st.at)), fontSize = 11.sp, color = NisuUi.Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BPill(if (working) "..." else "Check") { if (!working) check(listOf(acc)) }
                        BPill("Open") { PhoneControl.openUrl(ctx, if (st != null && st.watchUrl.isNotBlank()) st.watchUrl else acc.url, "") }
                        BPill("Delete") { SocialAccounts.remove(ctx, acc.id) }
                    }
                }
            }
        }
    }
}
