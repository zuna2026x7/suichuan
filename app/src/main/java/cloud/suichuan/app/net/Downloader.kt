package cloud.suichuan.app.net

import okhttp3.Request
import java.io.File
import java.io.IOException

/** Downloads a file with progress. Blocking: call from a background thread. */
object Downloader {

    fun download(url: String, target: File, onProgress: (written: Long, total: Long) -> Unit): File {
        val request = Request.Builder().url(url).get().build()
        HttpClients.instance.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败（${response.code}）。链接可能已经过期，让对方重新发送。")
            }
            val body = response.body ?: throw IOException("下载失败：服务器没有返回内容。")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 256 * 1024 || written == total) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }
            return target
        }
    }
}
