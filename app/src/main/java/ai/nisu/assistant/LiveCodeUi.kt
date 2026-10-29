package ai.nisu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * v3.20 / v3.30: the "Live Code" page (side menu). Shows the code the Main Brain writes WHILE it is being written (streamed): one tab per
 * file, a file that arrives whole is typed out line by line, a header tells which model wrote it and what step the builder is in. Copy / Clear are the only actions.
 */

private val Kw = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while", "import", "package", "private",
    "public", "override", "null", "true", "false", "function", "const", "let", "new", "this", "def", "async", "await", "export", "try", "catch",
    "in", "is", "as", "static", "final", "void", "int", "String", "from", "None", "True", "False", "self"
)
private val CKw = Color(0xFF8A6CFF)
private val CStr = Color(0xFF63D6A0)
private val CCom = Color(0xFF6F82A8)
private val CNum = Color(0xFFF0BE6A)
private val CTag = Color(0xFF38B6FF)

/** One line, lightly coloured (comments, strings, numbers, keywords, HTML/XML tags). Per line, so a long file stays cheap. */
internal fun highlightLine(line: String, diff: Boolean): AnnotatedString = buildAnnotatedString {
    if (diff && (line.startsWith("- ") || line.startsWith("+ "))) {
        withStyle(SpanStyle(color = if (line[0] == '+') NisuUi.Green else NisuUi.Red)) { append(line) }
        return@buildAnnotatedString
    }
    val t = line.trimStart()
    if (t.startsWith("//") || t.startsWith("#") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("<!--")) {
        withStyle(SpanStyle(color = CCom)) { append(line) }
        return@buildAnnotatedString
    }
    var i = 0
    val n = line.length
    while (i < n) {
        val ch = line[i]
        when {
            ch == '"' || ch == '\'' || ch == '`' -> {
                var j = i + 1
                while (j < n && line[j] != ch) { if (line[j] == '\\') j++; j++ }
                val e = minOf(j + 1, n)
                withStyle(SpanStyle(color = CStr)) { append(line.substring(i, e)) }
                i = e
            }
            ch == '<' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '/') -> {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] in "/-:._")) j++
                withStyle(SpanStyle(color = CTag)) { append(line.substring(i, j)) }
                i = j
            }
            ch.isDigit() -> {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                withStyle(SpanStyle(color = CNum)) { append(line.substring(i, j)) }
                i = j
            }
            ch.isLetter() || ch == '_' -> {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val w = line.substring(i, j)
                if (w in Kw) withStyle(SpanStyle(color = CKw)) { append(w) } else append(w)
                i = j
            }
            else -> { append(ch); i++ }
        }
    }
}

@Composable
internal fun LiveCodePage() {
    val snap by LiveCode.state.collectAsState()
    var sel by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(0) }       // lines typed out so far for the file that just arrived
    var follow by remember { mutableStateOf("") }     // "path#rev" that is being typed out
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val clip = LocalClipboardManager.current

    LaunchedEffect(snap.busy) { while (snap.busy) { now = System.currentTimeMillis(); delay(500) } }

    val newest = snap.files.lastOrNull()
    // a new file arrives: jump to it and type it out (about 1.5 s for a normal file, never more than 4 s)
    LaunchedEffect(snap.tick) {
        val f = newest ?: return@LaunchedEffect
        sel = f.path
        if (f.kind == "live") { follow = ""; return@LaunchedEffect }      // streamed: the real writing is the animation
        follow = f.path + "#" + f.rev
        val total = f.content.count { it == '\n' } + 1
        val perStep = maxOf(1, total / 90)
        shown = 0
        while (shown < total) { shown = minOf(total, shown + perStep); delay(16) }
        follow = ""
    }

    val file = snap.files.firstOrNull { it.path == sel } ?: newest
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live Code", fontSize = 24.sp, fontFamily = FontFamily.Serif, color = NisuUi.Text, modifier = Modifier.weight(1f))
            if (snap.files.isNotEmpty()) {
                Pill("Copy") { file?.let { clip.setText(AnnotatedString(it.content)) } }
                Spacer(Modifier.width(8.dp))
                Pill("Clear") { LiveCode.clear(); sel = "" }
            }
        }
        // status line
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val col = if (snap.error.isNotEmpty()) NisuUi.Red else if (snap.busy) NisuUi.Amber else if (snap.files.isNotEmpty()) NisuUi.Green else NisuUi.Muted
            Box(Modifier.size(9.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(8.dp))
            val text = when {
                snap.error.isNotEmpty() -> "Stopped: ${snap.error}"
                snap.busy -> (if (snap.note.isNotBlank()) snap.note + " - " else "") + "${snap.who.ifBlank { "Main Brain" }} is writing" + (if (snap.step.isNotBlank()) " - ${snap.step}" else "") + " (${(now - snap.startedAt) / 1000} s)"
                snap.files.isNotEmpty() -> "Last written by ${snap.who.ifBlank { "AI" }}" + (if (snap.step.isNotBlank()) " - ${snap.step}" else "")
                else -> "Waiting. Code written by the Main Brain appears here, while it is being written."
            }
            Text(text, fontSize = 12.sp, color = NisuUi.Muted)
        }
        if (snap.files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                snap.files.asReversed().forEach { f ->
                    val on = f.path == file?.path
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.padding(end = 6.dp).clip(shape).background(if (on) NisuUi.Cyan.copy(alpha = 0.18f) else NisuUi.Card)
                            .border(1.dp, if (on) NisuUi.Cyan else NisuUi.Line, shape)
                            .clickable(onClickLabel = f.path) { sel = f.path }.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text(f.path.substringAfterLast('/') + (if (f.rev > 1) "  v${f.rev}" else ""), fontSize = 12.sp, color = if (on) NisuUi.Cyan else NisuUi.Muted, maxLines = 1) }
                }
            }
        }
        if (file == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No code yet.\nAsk NISU AI to build a website or an app, or to write some code in the typed chat.", color = NisuUi.Dim, fontSize = 13.sp)
            }
        } else {
            val lines = remember(file.path, file.rev, file.content.length) { file.content.lines() }
            val typing = follow == file.path + "#" + file.rev
            val visible = if (typing) minOf(shown, lines.size) else lines.size
            val list = rememberLazyListState()
            val streaming = snap.busy && file.kind == "live"
            LaunchedEffect(visible, typing, lines.size, streaming) { if ((typing || streaming) && visible > 0) list.scrollToItem(visible - 1) }
            val diff = file.kind == "fix"
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF040B1B)).border(1.dp, NisuUi.Line, RoundedCornerShape(12.dp))) {
                Text((if (streaming) "WRITING  " else "") + file.path + "   -   " + lines.size + " lines   -   " + file.who, fontSize = 11.sp, color = NisuUi.Muted, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().background(NisuUi.Card).padding(horizontal = 10.dp, vertical = 6.dp))
                Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                        itemsIndexed(lines.take(visible)) { i, ln ->
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                Text((i + 1).toString().padStart(4), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NisuUi.Dim, softWrap = false)
                                Spacer(Modifier.width(10.dp))
                                Text(highlightLine(ln, diff), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = NisuUi.Text, softWrap = false)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(NisuUi.Card).border(1.dp, NisuUi.Line, shape).clickable(onClickLabel = label) { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, fontSize = 12.sp, color = NisuUi.Cyan)
    }
}
