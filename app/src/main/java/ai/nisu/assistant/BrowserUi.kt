package ai.nisu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
 * v3.48 Browser page (side menu): the window onto the AI's hidden browser (HiddenBrowser.kt).
 * It lists everything the AI searched or opened (newest first, live while the AI works), shows the results or the page text, shows errors in
 * red, and lets the user search or open a page himself. Nothing here is ever shown unless the user opens this page.
 */
@Composable
internal fun BrowserPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by HiddenBrowser.history.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(-1L) }
    var browserOn by remember { mutableStateOf(HiddenBrowser.enabled(ctx)) }   // v3.50: same switch as Settings > Web search and the voice command set_search
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HiddenBrowser.load(ctx) } }

    fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy || !HiddenBrowser.enabled(ctx)) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                // an address (has a dot, no spaces) is opened, anything else is searched
                if (!t.contains(' ') && t.contains('.') && HiddenBrowser.normalizeUrl(t) != null) HiddenBrowser.visit(ctx, t, "You") else HiddenBrowser.search(ctx, t, "You")
            }
            busy = false
            input = ""
            open = HiddenBrowser.history.value.lastOrNull()?.id ?: -1L
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Browser", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = NisuUi.Text, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) BPill("Clear") { HiddenBrowser.clear(ctx); open = -1L }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (browserOn) "Browser is ON: the AI may search and read websites silently." else "Browser is OFF: the AI will not search or open any page, and anything running was stopped.",
                modifier = Modifier.weight(1f), fontSize = 13.sp, color = if (browserOn) NisuUi.Text else NisuUi.Red
            )
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = browserOn,
                onCheckedChange = { browserOn = it; HiddenBrowser.setOn(ctx, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A1305), checkedTrackColor = NisuUi.Cyan,
                    uncheckedThumbColor = NisuUi.Muted, uncheckedTrackColor = NisuUi.Card, uncheckedBorderColor = NisuUi.Line
                )
            )
        }
        Text(
            "The AI's own hidden browser. When it does not know something it searches and reads websites here, silently, and checks them before it answers. Everything it did is listed below. You can search or open a website yourself too.",
            fontSize = 12.sp, color = NisuUi.Muted
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, label = { Text("Search or website address") }, singleLine = true,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line, focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text,
                    cursorColor = NisuUi.Cyan, focusedLabelColor = NisuUi.Cyan, unfocusedLabelColor = NisuUi.Muted
                )
            )
            BPill(if (busy) "..." else "Go") { go(input) }
        }
        if (list.isEmpty()) {
            Text("Nothing yet. When the AI searches or opens a page it appears here.", fontSize = 13.sp, color = NisuUi.Dim, modifier = Modifier.padding(top = 18.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.asReversed(), key = { it.id }) { e ->
                EntryCard(e, open == e.id, onToggle = { open = if (open == e.id) -1L else e.id }, onOpenLink = { go(it) })
            }
        }
    }
}

@Composable
private fun EntryCard(e: HiddenBrowser.Entry, expanded: Boolean, onToggle: () -> Unit, onOpenLink: (String) -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val bad = e.error.isNotEmpty()
    val dot = if (bad) NisuUi.Red else NisuUi.Green
    Column(
        Modifier.fillMaxWidth().clip(shape).background(NisuUi.Card).border(1.dp, if (bad) NisuUi.Red.copy(alpha = 0.5f) else NisuUi.Line, shape)
            .clickable { onToggle() }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (e.kind == "visit") "Opened page" else "Searched") + " - " + e.by + " - " + SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()).format(Date(e.time)),
                fontSize = 11.sp, color = NisuUi.Muted, modifier = Modifier.weight(1f)
            )
        }
        Text(e.query, fontSize = 15.sp, color = NisuUi.Text, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
        if (bad) {
            Text("Problem: " + HiddenBrowser.humanError(e.error), fontSize = 12.sp, color = NisuUi.Red)
            if (expanded) Text("The AI tells you about it and asks before it uses your phone's default browser.", fontSize = 12.sp, color = NisuUi.Muted)
        } else if (!expanded) {
            val sub = if (e.kind == "visit") e.title else (e.hits.size.toString() + " results" + (if (e.via.isNotEmpty()) " (" + e.via + ")" else ""))
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = NisuUi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded && !bad) {
            if (e.kind == "visit") {
                if (e.title.isNotBlank()) Text(e.title, fontSize = 13.sp, color = NisuUi.Cyan)
                Text(e.text.ifBlank { "(no readable text)" }, fontSize = 12.sp, color = NisuUi.Text.copy(alpha = 0.9f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BPill("Copy link") { copyLink(ctx, e.query, e.title) }
                    BPill("Open in phone browser") { PhoneControl.openUrl(ctx, e.query, "") }
                }
            } else {
                if (e.hits.isEmpty()) Text("No results.", fontSize = 12.sp, color = NisuUi.Muted)
                e.hits.forEach { h ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenLink(h.url) }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(h.title, fontSize = 13.sp, color = NisuUi.Cyan)
                        if (h.snippet.isNotBlank()) Text(h.snippet, fontSize = 12.sp, color = NisuUi.Text.copy(alpha = 0.9f))
                        Text(h.url, fontSize = 10.sp, color = NisuUi.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 2.dp)) { BPill("Copy link") { copyLink(ctx, h.url, h.title) } }
                    }
                }
                if (e.hits.isNotEmpty()) Text("Tap a result to read it here.", fontSize = 11.sp, color = NisuUi.Dim)
            }
        }
    }
}

/** The user copies a link: it goes on the phone clipboard and is also kept in the AI Clipboard list. */
private fun copyLink(ctx: android.content.Context, url: String, label: String) {
    Clip.set(ctx, url)
    AiClipboard.add(ctx, url, label, "You")
    android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
internal fun BPill(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(NisuUi.Card).border(1.dp, NisuUi.Line, shape).clickable(onClickLabel = label) { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, fontSize = 12.sp, color = NisuUi.Cyan)
    }
}
