package cloud.suichuan.app.lan

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately tiny HTTP/1.1 server that serves exactly one APK on the LAN:
 *
 *   GET /<token>/              -> a small Chinese download page
 *   GET /<token>/<fileName>    -> the APK (supports "Range: bytes=N-")
 *   anything else              -> 404
 *
 * The random token keeps other people on the same Wi-Fi from guessing the
 * link. Plain java.net, no Android classes, so it is unit-tested on the JVM.
 */
class MiniHttpServer(
    private val apk: File,
    val fileName: String,
    private val appLabel: String,
    private val versionName: String,
    val token: String = newToken(),
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onRequest() {}
        fun onDownloadStarted(client: String) {}
        fun onProgress(sent: Long, total: Long) {}
        fun onDownloadFinished(client: String, complete: Boolean) {}
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: -1

    /** Binds to all interfaces on a random free port and starts accepting. */
    fun start(port: Int = 0): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        val workers = Executors.newFixedThreadPool(4)
        pool = workers
        running.set(true)
        Thread({
            while (running.get()) {
                val s = try { ss.accept() } catch (_: SocketException) { break }
                workers.execute { handle(s) }
            }
        }, "lan-share-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    fun pagePath() = "/$token/"
    fun apkPath() = "/$token/$fileName"

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val req = readRequest(s.getInputStream()) ?: return
                listener.onRequest()
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val head = req.method == "HEAD"
                if (req.method != "GET" && !head) {
                    sendText(out, 405, "Method Not Allowed", "只支持下载。", head)
                    return
                }
                val path = req.path.substringBefore('?')
                when (path) {
                    pagePath(), "/$token" -> sendHtml(out, page(req.headers["user-agent"].orEmpty()), head)
                    apkPath() -> sendApk(out, req, s.inetAddress?.hostAddress ?: "?", head)
                    else -> sendText(out, 404, "Not Found", "链接已失效，请让对方重新打开「面对面分享随传」再扫一次。", head)
                }
            }
        }
    }

    internal data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > 16 * 1024) return null
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
            } else sb.append(b.toChar())
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun sendApk(out: OutputStream, req: Request, client: String, head: Boolean) {
        val total = apk.length()
        var start = 0L
        val range = req.headers["range"]
        val m = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
        if (m != null) start = m.groupValues[1].toLong()
        if (start >= total && total > 0) {
            writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
            out.flush()
            return
        }
        val length = total - start
        val headers = linkedMapOf(
            "Content-Type" to "application/vnd.android.package-archive",
            "Content-Length" to length.toString(),
            "Content-Disposition" to "attachment; filename=\"$fileName\"",
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
        )
        if (m != null) headers["Content-Range"] = "bytes $start-${total - 1}/$total"
        writeHead(out, if (m != null) 206 else 200, if (m != null) "Partial Content" else "OK", headers)
        if (head) { out.flush(); return }

        listener.onDownloadStarted(client)
        var sent = start
        var complete = false
        try {
            apk.inputStream().use { inp ->
                if (start > 0) inp.skip(start)
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    if (sent - lastReport >= 256 * 1024) {
                        lastReport = sent
                        listener.onProgress(sent, total)
                    }
                }
            }
            out.flush()
            complete = sent == total
            listener.onProgress(sent, total)
        } finally {
            listener.onDownloadFinished(client, complete)
        }
    }

    private fun sendHtml(out: OutputStream, html: String, head: Boolean) {
        val body = html.toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "OK", mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-store",
        ))
        if (!head) out.write(body)
        out.flush()
    }

    private fun sendText(out: OutputStream, code: Int, reason: String, text: String, head: Boolean) {
        val body = text.toByteArray(Charsets.UTF_8)
        writeHead(out, code, reason, mapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString(),
        ))
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    internal fun page(userAgent: String): String {
        val inApp = isBlockingInAppBrowser(userAgent)
        val size = "%.1f MB".format(apk.length() / 1024.0 / 1024.0)
        val warn = if (inApp) """
            <div class="warn">你正在微信 / QQ 里打开，它们会拦截安装包下载。<br>
            请点右上角 <b>···</b>，选择 <b>在浏览器打开</b>，再点下载。</div>
        """.trimIndent() else ""
        return """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>下载${esc(appLabel)}</title>
<style>
body{font-family:system-ui,-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;margin:0;padding:32px 24px;color:#222;background:#f7f9fc}
h1{font-size:30px;margin:0 0 8px}
.meta{color:#666;font-size:15px;margin-bottom:28px}
.btn{display:block;text-align:center;background:#1565C0;color:#fff;text-decoration:none;font-size:20px;padding:18px;border-radius:12px}
.warn{background:#FFF3E0;border:1px solid #FFB74D;color:#8a4b00;padding:14px;border-radius:10px;margin-bottom:20px;line-height:1.6}
ol{color:#555;line-height:1.8;padding-left:20px;margin-top:28px}
</style></head><body>
<h1>${esc(appLabel)}</h1>
<div class="meta">版本 ${esc(versionName)} · ${size} · 来自身边朋友的手机</div>
$warn
<a class="btn" href="${apkPath()}" download="${esc(fileName)}">下载安装</a>
<ol>
<li>下载完成后点通知栏或下载列表里的文件安装。</li>
<li>如提示“禁止安装未知应用”，按提示允许浏览器安装即可。</li>
<li>下载期间请让分享的手机停留在分享页面。</li>
</ol>
</body></html>"""
    }

    companion object {
        private val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray()

        fun newToken(len: Int = 8): String {
            val r = SecureRandom()
            return String(CharArray(len) { ALPHABET[r.nextInt(ALPHABET.size)] })
        }

        fun isBlockingInAppBrowser(ua: String): Boolean =
            ua.contains("MicroMessenger", ignoreCase = true) ||
                Regex("""\bQQ/\d""").containsMatchIn(ua)

        private fun esc(s: String) = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
