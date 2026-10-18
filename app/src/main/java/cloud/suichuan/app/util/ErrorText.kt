package cloud.suichuan.app.util

import cloud.suichuan.app.net.TransferCancelledException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns any exception into one Chinese sentence for the user. Our own
 * IOExceptions already carry Chinese text and pass through unchanged; raw
 * English messages from OkHttp / the JVM ("timeout", "Failed to connect to
 * /198.200…") are never shown.
 */
object ErrorText {

    private val CJK = Regex("[\\u4e00-\\u9fff]")

    fun of(e: Throwable?, fallback: String = "出了点问题，再试一次。"): String {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 5) {
            when (cur) {
                is TransferCancelledException -> return "已取消。"
                is UnknownHostException -> return "连不上网络，检查一下 Wi-Fi 或手机流量是否打开。"
                is NoRouteToHostException, is ConnectException ->
                    return "连不上服务器，可能是网络不通或服务器暂时不可用，稍后再试。"
                is SocketTimeoutException -> return "网络太慢，等了很久没有响应。换个网络再试一次。"
                is SSLException -> return "安全连接没有建立起来。检查手机的日期时间是否正确，或者换个网络。"
                is InterruptedIOException -> return "网络中断了，再试一次。"
            }
            val msg = cur.message
            if (!msg.isNullOrBlank() && CJK.containsMatchIn(msg)) return ensurePeriod(msg.trim())
            cur = cur.cause
            depth++
        }
        return fallback
    }

    /** Sentence end without doubling up punctuation. */
    fun ensurePeriod(s: String): String =
        if (s.endsWith("。") || s.endsWith("！") || s.endsWith("？") || s.endsWith(".")) s else "$s。"

    /** Reads the backend's {"error": "..."} field, if any. */
    fun serverError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val map = PayloadCodec.parseMap(body) ?: return null
        return map["error"]?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Friendly Chinese name for a MIME type instead of "application/pdf". */
    fun mimeLabel(mime: String, fileName: String = ""): String {
        val m = mime.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            m == "application/vnd.android.package-archive" || ext == "apk" -> "安装包（APK）"
            m.startsWith("image/") -> "图片"
            m.startsWith("video/") -> "视频"
            m.startsWith("audio/") -> "音频"
            m == "application/pdf" || ext == "pdf" -> "PDF 文档"
            m.startsWith("text/") || ext == "txt" -> "文本"
            m.contains("zip") || m.contains("rar") || m.contains("7z") || m.contains("tar") ||
                ext in setOf("zip", "rar", "7z", "tar", "gz") -> "压缩包"
            m.contains("word") || ext in setOf("doc", "docx") -> "Word 文档"
            m.contains("sheet") || m.contains("excel") || ext in setOf("xls", "xlsx", "csv") -> "表格"
            m.contains("presentation") || m.contains("powerpoint") || ext in setOf("ppt", "pptx") -> "演示文稿"
            ext.isNotBlank() -> "${ext.uppercase()} 文件"
            else -> "文件"
        }
    }

    /** "123456" -> "123 456" for reading aloud / copying by eye. */
    fun groupCode(code: String): String =
        if (code.length == 6 && code.all { it.isDigit() }) code.substring(0, 3) + " " + code.substring(3) else code
}
