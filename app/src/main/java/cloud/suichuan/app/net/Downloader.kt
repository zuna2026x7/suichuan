package cloud.suichuan.app.net

import cloud.suichuan.app.util.AppLog
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Downloads a file with progress. Blocking: call from a background thread. */
object Downloader {

    /** Files larger than this are downloaded in parallel segments. */
    private const val PARALLEL_THRESHOLD_BYTES = 1024L * 1024L // 1 MiB
    private const val SEGMENT_COUNT = 6

    /** Attempts per segment before the segment (and the download) gives up. */
    private const val MAX_SEGMENT_ATTEMPTS = 4

    /** Backoff between segment attempts: this many ms × the failed attempt's number. */
    private const val RETRY_BASE_DELAY_MS = 800L

    fun download(url: String, target: File, onProgress: (written: Long, total: Long) -> Unit): File {
        // A single connection to an overseas server is often throttled per
        // stream on the receiver's network, so big files are fetched as
        // parallel byte-range segments when the server supports ranges.
        val startedAt = System.currentTimeMillis()
        AppLog.log("DL", "开始下载 ${AppLog.redactUrl(url)}")
        val total = probeTotalSize(url)
        return try {
            val result = if (total != null && total > PARALLEL_THRESHOLD_BYTES) {
                AppLog.log("DL", "模式=分段并行，总大小=$total")
                downloadParallel(url, target, total, onProgress)
            } else {
                AppLog.log("DL", "模式=单连接，总大小=${total ?: "未知"}")
                downloadSingle(url, target, onProgress)
            }
            AppLog.log(
                "DL",
                "下载完成 bytes=${result.length()} 耗时=${System.currentTimeMillis() - startedAt}ms"
            )
            result
        } catch (e: Exception) {
            AppLog.log("DL", "下载失败 耗时=${System.currentTimeMillis() - startedAt}ms", e)
            throw e
        }
    }

    /** The original single-stream download; also the fallback for servers without range support. */
    private fun downloadSingle(url: String, target: File, onProgress: (written: Long, total: Long) -> Unit): File {
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

    /**
     * Asks for a single byte with a Range header. If the server answers 206,
     * its Content-Range header reveals the total size ("bytes 0-0/62914560")
     * and segmented downloading is possible; anything else returns null.
     */
    private fun probeTotalSize(url: String): Long? {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return try {
            HttpClients.instance.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    AppLog.log("DL", "探测 -> HTTP ${response.code}（服务器不支持分段）")
                    null
                } else {
                    val total = response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    AppLog.log("DL", "探测 -> 206，总大小=${total ?: "读不出"}")
                    total
                }
            }
        } catch (e: Exception) {
            AppLog.log("DL", "探测失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun downloadParallel(
        url: String,
        target: File,
        total: Long,
        onProgress: (written: Long, total: Long) -> Unit
    ): File {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        // Create the empty file up front; each segment thread then opens its
        // own RandomAccessFile handle on it and writes at its own offset.
        RandomAccessFile(target, "rw").close()

        // Split [0, total) into contiguous segments [start, end] (inclusive).
        val segmentSize = (total + SEGMENT_COUNT - 1) / SEGMENT_COUNT
        val ranges = ArrayList<Pair<Long, Long>>()
        var start = 0L
        while (start < total) {
            val end = minOf(start + segmentSize, total) - 1
            ranges.add(start to end)
            start = end + 1
        }

        val writtenTotal = AtomicLong(0)
        val lastReported = AtomicLong(0)
        val calls = CopyOnWriteArrayList<Call>()
        val pool = Executors.newFixedThreadPool(SEGMENT_COUNT)
        try {
            val futures = ranges.mapIndexed { index, range ->
                pool.submit(Callable {
                    downloadSegment(index, url, target, range.first, range.second, calls) { read ->
                        val written = writtenTotal.addAndGet(read.toLong())
                        val last = lastReported.get()
                        if ((written - last >= 256 * 1024 || written == total) &&
                            lastReported.compareAndSet(last, written)
                        ) {
                            onProgress(written, total)
                        }
                    }
                })
            }
            var failure: Exception? = null
            for (future in futures) {
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    if (failure == null) {
                        failure = e.cause as? Exception ?: e
                        // One dead segment fails the whole download: cancel
                        // the rest instead of waiting for their timeouts.
                        calls.forEach { it.cancel() }
                    }
                }
            }
            failure?.let { throw it }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            pool.shutdownNow()
        }
        if (target.length() != total) {
            target.delete()
            throw IOException("下载到的文件大小不对。网络可能不稳定，重新接收一次。")
        }
        onProgress(total, total)
        return target
    }

    /**
     * Downloads one byte range into the shared file at its offset.
     *
     * A dead connection must not doom the whole download (one segment of a
     * big file on consumer Wi-Fi can mean tens of MB in flight), so each
     * segment retries up to [MAX_SEGMENT_ATTEMPTS] times. [written] — the
     * bytes of THIS segment already on disk — is the single counter that
     * drives everything: a retry asks only for the remaining sub-range
     * (bytes=start+written-end) and keeps writing at that offset, so bytes
     * already written are never re-downloaded, and because [onBytes] is
     * called with exactly the chunk just written, at the same moment
     * [written] grows by it, progress accounting can't double-count either.
     * Only when every attempt fails does the segment throw, which fails
     * the whole download as before.
     */
    private fun downloadSegment(
        index: Int,
        url: String,
        target: File,
        start: Long,
        end: Long,
        calls: MutableCollection<Call>,
        onBytes: (read: Int) -> Unit
    ) {
        val expected = end - start + 1
        var written = 0L
        var lastError: IOException? = null
        for (attempt in 1..MAX_SEGMENT_ATTEMPTS) {
            if (written == expected) return
            if (attempt > 1) {
                // Brief backoff before resuming: 800ms after the first
                // failure, 1600ms after the second, and so on.
                try {
                    Thread.sleep(RETRY_BASE_DELAY_MS * (attempt - 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw lastError ?: IOException("下载被中断了。")
                }
            }
            val request = Request.Builder().url(url)
                .header("Range", "bytes=${start + written}-$end")
                .get()
                .build()
            val call = HttpClients.instance.newCall(request)
            calls.add(call)
            try {
                call.execute().use { response ->
                    // A resumed range answered with 200 (server ignored the
                    // Range header) would land at the wrong offset: count it
                    // as a failed attempt and write nothing.
                    if (response.code != 206) {
                        throw IOException("下载失败（${response.code}）。链接可能已经过期，让对方重新发送。")
                    }
                    val body = response.body ?: throw IOException("下载失败：服务器没有返回内容。")
                    // Own handle per thread: seeks on a shared handle would race.
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(start + written)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                if (written + read > expected) {
                                    // More bytes than the range asked for:
                                    // offsets can no longer be trusted.
                                    throw IOException("下载失败：服务器返回的数据长度不对。")
                                }
                                raf.write(buffer, 0, read)
                                written += read
                                onBytes(read)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // Includes mid-stream disconnects: whatever was written
                // stays on disk and the next attempt resumes after it.
                lastError = e
                AppLog.log(
                    "DL",
                    "段 $index（范围 bytes=$start-$end）第 $attempt 次尝试失败：" +
                        "${e.javaClass.simpleName}: ${e.message}（已下 $written/$expected）"
                )
                continue
            }
            // The stream ended without an error but short of the full
            // segment (connection closed early): resume the rest.
            if (written < expected) {
                lastError = IOException("下载不完整，有一段数据没下完。网络可能不稳定，重新接收一次。")
                AppLog.log(
                    "DL",
                    "段 $index 第 $attempt 次尝试提前结束：已下 $written/$expected"
                )
            }
        }
        if (written != expected) {
            throw lastError
                ?: IOException("下载不完整，有一段数据没下完。网络可能不稳定，重新接收一次。")
        }
    }
}
