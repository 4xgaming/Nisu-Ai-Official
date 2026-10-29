package ai.nisu.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/*
 * v3.20 / v3.30: the LIVE CODE VIEWER. Every time the Main Brain writes code (a website, an app, a build fix, or a code block in the typed
 * chat) the files appear on the "Live Code" page WHILE they are being written (the model's answer is streamed): which model, which step,
 * on the screen. Display only: nothing here runs, saves or sends the code anywhere (it stays in memory; "Clear" wipes it).
 */
class CodeFile(val path: String, val content: String, val rev: Int, val kind: String, val who: String, val at: Long)

data class LiveSnap(
    val files: List<CodeFile> = emptyList(),
    val busy: Boolean = false,
    val step: String = "",
    val who: String = "",
    val startedAt: Long = 0L,
    val error: String = "",
    /** A short remark for the status line ("the model is busy, trying again in 20 s"). */
    val note: String = "",
    /** Counts every publish so the page knows a new file arrived and types it out. */
    val tick: Int = 0
)

/** Pure helpers (no Android classes): JVM-testable. */
object LiveCodeRules {
    const val MAX_FILES = 60
    const val MAX_FILE_CHARS = 200_000
    const val MAX_TOTAL_CHARS = 600_000

    class Block(val lang: String, val code: String)

    /** Fenced code blocks (``` or ~~~) of a chat answer. An unclosed last block is kept (a long answer that was cut off). */
    fun fences(text: String): List<Block> {
        val out = ArrayList<Block>()
        var lang = ""
        var inBlock = false
        val sb = StringBuilder()
        for (line in text.lines()) {
            val t = line.trimStart()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (!inBlock) { inBlock = true; lang = t.drop(3).trim().lowercase().takeWhile { it.isLetterOrDigit() || it in "+#-_." }; sb.clear() }
                else { inBlock = false; if (sb.isNotBlank()) out.add(Block(lang, sb.toString().trimEnd('\n'))) ; sb.clear() }
            } else if (inBlock) sb.append(line).append('\n')
        }
        if (inBlock && sb.isNotBlank()) out.add(Block(lang, sb.toString().trimEnd('\n')))
        return out
    }

    private class Str(val v: String, val end: Int, val complete: Boolean)

    /** Reads a JSON string whose opening quote is just before [start]; an unfinished string (text cut off) comes back with complete = false. */
    private fun readStr(t: String, start: Int): Str {
        val sb = StringBuilder()
        var j = start
        val n = t.length
        while (j < n) {
            val c = t[j]
            if (c == '"') return Str(sb.toString(), j + 1, true)
            if (c == '\\') {
                if (j + 1 >= n) return Str(sb.toString(), n, false)
                when (val e = t[j + 1]) {
                    'n' -> { sb.append('\n'); j += 2 }
                    't' -> { sb.append('\t'); j += 2 }
                    'r' -> { j += 2 }
                    'b', 'f' -> { j += 2 }
                    'u' -> {
                        if (j + 6 > n) return Str(sb.toString(), n, false)
                        val code = t.substring(j + 2, j + 6).toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        j += 6
                    }
                    else -> { sb.append(e); j += 2 }
                }
            } else { sb.append(c); j++ }
        }
        return Str(sb.toString(), n, false)
    }

    /**
     * The files of an answer that is still being written: {"files":[{"path":"a.html","content":"<html>..."}, ...]}. Whatever is complete is
     * returned, and the last file may be cut off in the middle (the viewer shows it growing). Files without a complete path are left out.
     */
    fun partialFiles(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val k = text.indexOf("\"files\"")
        if (k < 0) return out
        var i = text.indexOf('[', k)
        if (i < 0) return out
        i++
        val n = text.length
        while (i < n) {
            while (i < n && text[i] != '{' && text[i] != ']') i++
            if (i >= n || text[i] == ']') break
            i++
            var path = ""
            var pathOk = false
            var content = ""
            var closed = false
            while (i < n) {
                while (i < n && text[i] != '"' && text[i] != '}') i++
                if (i >= n) break
                if (text[i] == '}') { i++; closed = true; break }
                val key = readStr(text, i + 1)
                if (!key.complete) { i = n; break }
                i = key.end
                while (i < n && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n' || text[i] == ':')) i++
                if (i >= n) break
                if (text[i] == '"') {
                    val v = readStr(text, i + 1)
                    if (key.v == "path") { path = v.v; pathOk = v.complete } else if (key.v == "content") content = v.v
                    i = v.end
                    if (!v.complete) { i = n; break }
                } else {
                    while (i < n && text[i] != ',' && text[i] != '}') i++
                }
            }
            if (pathOk && path.isNotBlank()) out.add(path to content)
            if (!closed) break
        }
        return out
    }

    fun extFor(lang: String): String = when (lang) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "javascript", "js", "jsx" -> "js"; "typescript", "ts", "tsx" -> "ts"
        "python", "py" -> "py"; "html" -> "html"; "css" -> "css"; "json" -> "json"; "xml" -> "xml"; "bash", "sh", "shell" -> "sh"
        "c" -> "c"; "cpp", "c++" -> "cpp"; "go" -> "go"; "rust", "rs" -> "rs"; "php" -> "php"; "sql" -> "sql"; "yaml", "yml" -> "yml"
        "" -> "txt"; else -> lang.take(5)
    }

    /** Adds or replaces [f] by path (a new version of a file keeps its place and counts up), then applies the size limits (oldest files go first). */
    fun merge(list: List<CodeFile>, path: String, content: String, kind: String, who: String, now: Long): List<CodeFile> {
        val c = if (content.length > MAX_FILE_CHARS) content.take(MAX_FILE_CHARS) + "\n... (cut, ${content.length - MAX_FILE_CHARS} more characters)" else content
        val old = list.firstOrNull { it.path == path }
        // "live" = a half-written file that is still streaming in: it keeps its version number, and the finished file replaces it with the same number
        val rev = if (old == null) 1 else if (old.kind == "live") old.rev else old.rev + 1
        val nf = CodeFile(path, c, rev, kind, who, now)
        val res = ArrayList(list.filter { it.path != path })
        res.add(nf)
        while (res.size > MAX_FILES || (res.size > 1 && res.sumOf { it.content.length } > MAX_TOTAL_CHARS)) res.removeAt(0)
        return res
    }
}

object LiveCode {
    val state = MutableStateFlow(LiveSnap())

    /** What the builder is doing right now ("writing the website", "fixing build errors"); set by the builders' status lines. */
    @Volatile var step: String = ""

    private fun upd(f: (LiveSnap) -> LiveSnap) { synchronized(this) { state.value = f(state.value) } }

    fun begin(who: String) = upd { it.copy(busy = true, step = step, who = who, startedAt = System.currentTimeMillis(), error = "", note = "") }

    fun note(msg: String) = upd { it.copy(note = msg.take(160)) }

    fun fail(msg: String) = upd { it.copy(busy = false, error = msg.take(200)) }

    fun done(who: String) = upd { it.copy(busy = false, who = who, error = "", note = "") }

    fun clear() = upd { LiveSnap() }

    fun files(items: List<Pair<String, String>>, who: String, kind: String = "new") = upd { s ->
        var l = s.files
        var fresh = false      // a file that was not on the page yet, or one that was not streaming: the page jumps to it
        val now = System.currentTimeMillis()
        items.forEach { (p, c) ->
            if (p.isNotBlank() && c.isNotEmpty()) {
                val path = p.take(160)
                val old = l.firstOrNull { it.path == path }
                if (old == null || old.kind != "live") fresh = true
                l = LiveCodeRules.merge(l, path, c, kind, who, now)
            }
        }
        if (l === s.files) s else s.copy(files = l, tick = if (fresh) s.tick + 1 else s.tick)
    }

    /** The files of the answer that is still streaming in (see [LiveCodeRules.partialFiles]). */
    fun partial(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        files(items, state.value.who, "live")
    }

    /** Reads {"files":[{path,content}], "edits":[{path,find,replace}]} (the code writer's answer). */
    fun fromJson(o: JSONObject, who: String) {
        val fl = ArrayList<Pair<String, String>>()
        o.optJSONArray("files")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { fl.add(it.optString("path") to it.optString("content")) } }
        if (fl.isNotEmpty()) files(fl, who)
        val el = ArrayList<Pair<String, String>>()
        o.optJSONArray("edits")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                val p = it.optString("path")
                if (p.isNotBlank()) el.add("$p (fix)" to ("- " + it.optString("find").lines().joinToString("\n- ") + "\n+ " + it.optString("replace").lines().joinToString("\n+ ")))
            }
        }
        if (el.isNotEmpty()) files(el, who, "fix")
    }

    /** A chat answer with code blocks (the typed chat): every block becomes a file. */
    fun fromText(text: String, who: String) {
        val blocks = LiveCodeRules.fences(text)
        if (blocks.isEmpty()) return
        val n = state.value.files.size
        files(blocks.mapIndexed { i, b -> "chat-${n + i + 1}.${LiveCodeRules.extFor(b.lang)}" to b.code }, who, "chat")
    }
}
