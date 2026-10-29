package ai.nisu.assistant

/**
 * Pure helpers for ProjectBuilder (no Android classes, so they are unit-tested on the JVM):
 * which files an automatic build fix may touch, how an edit is applied, and parsing of Termux output.
 */
internal object ProjectRules {
    const val MAX_WRITE_BYTES = 90_000      // one Termux command line is limited to about 128 KB (base64 adds a third)
    const val MAX_EDIT_CHARS = 6_000
    const val MAX_NEW_FILE_CHARS = 20_000
    const val MAX_EDITS = 12
    const val MAX_NEW_FILES = 4

    private val EXT_OK = setOf("kt", "java", "xml", "kts", "gradle", "properties", "toml", "json", "txt", "html", "css", "js", "md", "pro", "cfg", "svg", "csv")
    private val SEG_OK = Regex("^[A-Za-z0-9_.@+-]+$")
    private val BLOCK_DIRS = setOf(".gradle", ".git", ".idea", "node_modules")

    /** Returns the cleaned project-relative path, or null when a fix may not touch it. */
    fun clean(p0: String): String? {
        val p = p0.trim().replace('\\', '/').removePrefix("./")
        if (p.isEmpty() || p.startsWith("/") || p.length > 200) return null
        val segs = p.split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." || !SEG_OK.matches(it) }) return null
        if (segs.dropLast(1).any { it in BLOCK_DIRS }) return null
        if (segs[0] == "build" || (segs.size > 2 && segs[1] == "build")) return null     // generated output of the root / of a module
        val last = segs.last()
        if (p == "local.properties" || p.startsWith("gradle/wrapper/") || last.startsWith("gradlew")) return null
        val ext = last.substringAfterLast('.', "").lowercase()
        return if (ext in EXT_OK) p else null
    }

    fun isGradle(path: String) = path.endsWith(".gradle") || path.endsWith(".kts")

    // Logic that runs commands, touches files or the network inside a Gradle script is never added by an automatic fix.
    private val GRADLE_BAD = Regex("exec\\s*\\(|\\bRuntime\\.|ProcessBuilder|commandLine|java\\.io|java\\.net|\\bURL\\s*\\(|\\bSystem\\.|deleteRecursively|\\bFiles\\.|tasks\\.register|\\btask\\s*\\(|doLast|doFirst|\\.execute\\(|\\bSocket\\b")
    private val CODE_BAD = Regex("Runtime\\.getRuntime|ProcessBuilder|\\.exec\\(")

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }

    /** True when the new text does not add anything an automatic fix must never add. */
    private fun screen(path: String, find: String, replace: String): Boolean {
        if (isGradle(path) && GRADLE_BAD.containsMatchIn(replace)) return false
        val low = replace.lowercase(); val lowFind = find.lowercase()
        if (count(low, "permission") > count(lowFind, "permission")) return false
        if (!isGradle(path)) {
            val urlsNew = count(low, "http://") + count(low, "https://")
            val urlsOld = count(lowFind, "http://") + count(lowFind, "https://")
            if (urlsNew > urlsOld) return false
            if (CODE_BAD.containsMatchIn(replace) && !CODE_BAD.containsMatchIn(find)) return false
        }
        return true
    }

    fun editOk(path: String, find: String, replace: String): Boolean =
        replace.length <= MAX_EDIT_CHARS && screen(path, find, replace)

    fun newFileOk(path: String, content: String): Boolean =
        content.length <= MAX_NEW_FILE_CHARS && screen(path, "", content)

    /** Replaces [find] (it must occur exactly once) by [replace]. Null when it cannot or may not be applied. */
    fun applyEdit(path: String, text: String, find: String, replace: String): String? {
        if (find.isEmpty() || !editOk(path, find, replace)) return null
        val r = once(text, find, replace)
        if (r != null) return r
        if (text.contains("\r\n") && !find.contains("\r\n"))
            return once(text, find.replace("\n", "\r\n"), replace.replace("\n", "\r\n"))
        return null
    }

    private fun once(text: String, find: String, replace: String): String? {
        val i = text.indexOf(find)
        if (i < 0 || text.indexOf(find, i + find.length) >= 0) return null
        return text.substring(0, i) + replace + text.substring(i + find.length)
    }

    // ---------------------------------------------------------------- Termux output
    class Src(val epoch: Long, val type: String, val name: String)

    /** Lines "epoch|type|name" from build-app.sh sources (already newest first). */
    fun parseSources(out: String): List<Src> = out.lines().mapNotNull { line ->
        val p = line.trim().split("|", limit = 3)
        if (p.size < 3) return@mapNotNull null
        val t = p[0].toLongOrNull() ?: return@mapNotNull null
        if (p[1] != "zip" && p[1] != "folder") return@mapNotNull null
        if (p[2].isEmpty()) null else Src(t, p[1], p[2])
    }

    /** Output of build-app.sh context: blocks that start with "=====FILE path". */
    fun parseFiles(out: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (chunk in out.split("=====FILE ").drop(1)) {
            val nl = chunk.indexOf('\n')
            if (nl < 0) continue
            map[chunk.substring(0, nl).trim()] = chunk.substring(nl + 1).trimEnd()
        }
        return map
    }

    fun validSourceName(s: String) = s.isNotEmpty() && s.length <= 200 && !s.contains('/') && !s.contains("..") && !s.startsWith("-")

    /** A short, unique, lower-case project folder name for a Download entry. */
    fun slugFor(name: String): String {
        val base = name.removeSuffix(".zip").removeSuffix(".ZIP").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(16).ifEmpty { "src" }
        return base + Integer.toHexString(name.hashCode()).takeLast(6)
    }

    /** The same error with different line numbers gives the same signature. */
    fun signature(firstError: String): String = firstError.replace(Regex("\\d+"), "#").take(120)
}
