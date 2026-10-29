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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * v3.48 AI Clipboard page (side menu): everything the AI copied or kept (AiClipboard.kt), newest first. Copy any item to the phone clipboard,
 * delete it, or add your own text. Nothing here is shown unless the user opens this page.
 */
@Composable
internal fun AiClipboardPage() {
    val ctx = LocalContext.current
    val list by AiClipboard.items.collectAsState()
    var input by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AiClipboard.load(ctx) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI Clipboard", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = NisuUi.Text, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) BPill("Clear all") { AiClipboard.clear(ctx); openId = -1L }
        }
        Text(
            "The AI's own clipboard. Whatever it copies, and the links and names it finds for you (an app, a website, a channel), are kept here so you never have to search again. Tap Copy to put one on your phone clipboard.",
            fontSize = 12.sp, color = NisuUi.Muted
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, label = { Text("Add your own text or link") }, singleLine = true,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line, focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text,
                    cursorColor = NisuUi.Cyan, focusedLabelColor = NisuUi.Cyan, unfocusedLabelColor = NisuUi.Muted
                )
            )
            BPill("Add") { if (input.isNotBlank()) { AiClipboard.add(ctx, input, "", "You"); input = "" } }
        }
        if (list.isEmpty()) Text("Nothing yet.", fontSize = 13.sp, color = NisuUi.Dim, modifier = Modifier.padding(top = 18.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.asReversed(), key = { it.id }) { it ->
                val shape = RoundedCornerShape(16.dp)
                val open = openId == it.id
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(NisuUi.Card).border(1.dp, NisuUi.Line, shape).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (it.source == "AI") NisuUi.Cyan else NisuUi.Violet))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (AiClipboard.isLink(it.text)) "Link" else "Text") + " - " + it.source + " - " + SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()).format(Date(it.time)),
                            fontSize = 11.sp, color = NisuUi.Muted
                        )
                    }
                    if (it.label.isNotBlank()) Text(it.label, fontSize = 14.sp, color = NisuUi.Text)
                    Text(it.text, fontSize = 12.sp, color = if (AiClipboard.isLink(it.text)) NisuUi.Cyan else NisuUi.Text.copy(alpha = 0.9f),
                        maxLines = if (open) 40 else 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BPill("Copy") { Clip.set(ctx, it.text); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }
                        if (AiClipboard.isLink(it.text)) BPill("Open in phone browser") { PhoneControl.openUrl(ctx, it.text, "") }
                        if (it.text.length > 120) BPill(if (open) "Less" else "More") { openId = if (open) -1L else it.id }
                        BPill("Delete") { AiClipboard.remove(ctx, it.id) }
                    }
                }
            }
        }
    }
}
