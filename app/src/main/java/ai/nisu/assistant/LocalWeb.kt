package ai.nisu.assistant

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/*
 * LOCAL WEB (v2.99): a tiny HTTP server inside NISU AI that lets Chrome open the websites the Main Brain builds.
 *
 *   http://127.0.0.1:<port>/<site>/<path>   files of filesDir/websites/<site>/ (index.html for a folder)
 *   POST /__nisu/err?site=<site>            the page reports a JavaScript error / failed resource (kept for website_fix)
 *   GET  /__nisu/ver?site=<site>            the page polls this; when the number changes (a fix was written) it reloads itself
 *
 * Bound to the loopback address only, so nothing outside the phone can reach it. Only files inside the websites folder are served
 * (WebRules.safePath), every response is no-store, and the server is started on demand by WebBuilder.
 */
object LocalWeb {
    private const val PREFERRED_PORT = 8765
    private const val MAX_ERRORS = 25

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "nisu-localweb").apply { isDaemon = true } }
    private val errors = ConcurrentHashMap<String, MutableList<String>>()
    private val versions = ConcurrentHashMap<String, Long>()

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "websites").apply { mkdirs() }

    /** Starts the server once and returns its port. */
    @Synchronized
    fun ensure(ctx: Context): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val loop = InetAddress.getByName("127.0.0.1")
        val s = try { ServerSocket(PREFERRED_PORT, 32, loop) } catch (_: Exception) { ServerSocket(0, 32, loop) }
        server = s
        val dir = root(ctx)
        Thread({
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { serve(sock, dir) }
            }
        }, "nisu-localweb-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun url(ctx: Context, site: String): String = "http://127.0.0.1:" + ensure(ctx) + "/" + site + "/"

    fun bump(site: String) { versions[site] = System.currentTimeMillis() }
    fun clearErrors(site: String) { errors.remove(site) }
    fun errorsOf(site: String): List<String> = errors[site]?.let { synchronized(it) { it.toList() } }.orEmpty()

    private fun addError(site: String, msg: String) {
        val clean = msg.replace(Regex("[\\r\\n]+"), " ").trim().take(600)
        if (clean.isEmpty()) return
        val list = errors.getOrPut(site) { ArrayList() }
        synchronized(list) {
            if (clean !in list) list.add(clean)
            while (list.size > MAX_ERRORS) list.removeAt(0)
        }
    }

    private fun serve(sock: Socket, dir: File) {
        sock.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                var length = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
                val site = WebRules.cleanSlug(queryParam(target, "site"))
                val out = s.getOutputStream()
                when {
                    path == "/__nisu/err" && method == "POST" -> {
                        val body = readBody(input, length.coerceIn(0, 4000))
                        if (site.isNotEmpty()) addError(site, body)
                        send(out, 204, "text/plain", ByteArray(0))
                    }
                    path == "/__nisu/ver" -> send(out, 200, "text/plain", (versions[site] ?: 0L).toString().toByteArray())
                    method == "GET" || method == "HEAD" -> serveFile(out, dir, path, method == "HEAD")
                    else -> send(out, 405, "text/plain", "Method not allowed".toByteArray())
                }
            } catch (_: Exception) { /* a broken connection only ends that one request */ }
        }
    }

    private fun serveFile(out: OutputStream, dir: File, path: String, head: Boolean) {
        val trimmed = path.trimStart('/')
        val site = WebRules.cleanSlug(trimmed.substringBefore('/'))
        if (site.isEmpty()) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        var rel = trimmed.substringAfter('/', "")
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"
        val safe = WebRules.safePath(rel)
        val file = safe?.let { File(File(dir, site), it) }
        if (file == null || !file.isFile || !file.canonicalPath.startsWith(File(dir, site).canonicalPath + File.separator)) {
            addError(site, "Missing file (404): $rel")
            send(out, 404, "text/plain", "Not found: $rel".toByteArray())
            return
        }
        val mime = WebRules.mimeOf(file.name)
        var bytes = file.readBytes()
        if (mime.startsWith("text/html")) bytes = WebRules.inject(String(bytes, Charsets.UTF_8), site).toByteArray(Charsets.UTF_8)
        send(out, 200, mime, if (head) ByteArray(0) else bytes, if (head) bytes.size else null)
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, lengthOverride: Int? = null) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "OK" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${lengthOverride ?: body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun queryParam(target: String, name: String): String =
        target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
}
