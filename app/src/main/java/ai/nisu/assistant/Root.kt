package ai.nisu.assistant

import java.util.concurrent.TimeUnit

/** Runs shell commands as root through `su`. Only works on a rooted phone where the root manager granted NISU AI access. */
object Root {
    @Volatile private var cached: Boolean? = null
    @Volatile private var checkedAt = 0L

    fun available(force: Boolean = false): Boolean {
        if (!force) cached?.let { c ->
            if (c) return true
            if (System.currentTimeMillis() - checkedAt < 20_000L) return false   // no root: do not spawn `su` again for every action
        }
        val ok = try { exec("id", 5000).second.contains("uid=0") } catch (_: Exception) { false }
        cached = ok; checkedAt = System.currentTimeMillis()
        RootAccess.report(ok)   // one shared status for the Main Brain and the Automation panel (RootAccess.kt)
        return ok
    }

    fun exec(cmd: String, timeoutMs: Long): Pair<Int, String> {
        val p = try { ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start() }
                catch (e: java.io.IOException) { return -1 to "Root is not available: su could not be started (${e.message})" }
        val out = StringBuffer()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (out.length < 6000) out.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }
        reader.start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly(); reader.join(500)
            return -1 to (out.toString().take(6000) + "\n[timed out]")
        }
        reader.join(1000)
        return p.exitValue() to out.toString().take(6000)
    }

    /** Commands that can brick or wipe the phone are refused outright, whatever anyone says. */
    private val BLOCKED = listOf(
        Regex("""\brm\s+(-\S+\s+)*(/|/\*|/system\S*|/vendor\S*|/data|/data/\*|/sdcard|/storage\S*|~)(\s|$)"""),
        Regex("""\bmkfs\S*"""), Regex("""\bdd\b.*\bof=/dev/"""), Regex(""">\s*/dev/block"""),
        Regex("""\bfastboot\b"""), Regex("""\bflash_image\b|\berase_image\b"""),
        Regex("""\bwipe\b|\bformat\b|\bfactory_?reset\b|MASTER_CLEAR""", RegexOption.IGNORE_CASE),
        Regex("""\bsetenforce\b|\bresetprop\b.*ro\.""")
    )

    fun blocked(cmd: String): Boolean = BLOCKED.any { it.containsMatchIn(cmd) }

    private val READ_ONLY = setOf("ls", "cat", "id", "uname", "getprop", "df", "du", "ps", "top", "uptime", "date",
        "pwd", "whoami", "env", "free", "netstat", "ip", "ifconfig", "getenforce", "lsof", "stat", "wc", "head", "tail")

    /** True only for simple inspection commands; anything else needs the user's spoken confirmation. */
    fun readOnly(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.any { it in ";&|<>`$\n" }) return false
        val t = c.split(Regex("\\s+"))
        val head = t.firstOrNull() ?: return false
        return when {
            head in READ_ONLY -> true
            head == "dumpsys" || head == "logcat" -> t.none { it == "-c" }
            head == "pm" -> t.getOrNull(1).let { it == "list" || it == "path" || it == "dump" }
            head == "settings" -> t.getOrNull(1) == "get"
            else -> false
        }
    }
}
