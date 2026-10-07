package cloud.suichuan.app.net

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

    fun download(url: String, target: File, onProgress: (written: Long, total: Long) -> Unit): File {
        // A single connection to an overseas server is often throttled per
        // stream on the receiver's network, so big files are fetched as
        // parallel byte-range segments when the server supports ranges.
        val total = probeTotalSize(url)
        return if (total != null && total > PARALLEL_THRESHOLD_BYTES) {
            downloadParallel(url, target, total, onProgress)
        } else {
            downloadSingle(url, target, onProgress)
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
                    null
                } else {
                    response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                }
            }
        } catch (e: Exception) {
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
            val futures = ranges.map { range ->
                pool.submit(Callable {
                    downloadSegment(url, target, range.first, range.second, calls) { read ->
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

    /** Downloads one byte range into the shared file at its offset. */
    private fun downloadSegment(
        url: String,
        target: File,
        start: Long,
        end: Long,
        calls: MutableCollection<Call>,
        onBytes: (read: Int) -> Unit
    ) {
        val expected = end - start + 1
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        val call = HttpClients.instance.newCall(request)
        calls.add(call)
        call.execute().use { response ->
            if (response.code != 206) {
                throw IOException("下载失败（${response.code}）。链接可能已经过期，让对方重新发送。")
            }
            val body = response.body ?: throw IOException("下载失败：服务器没有返回内容。")
            // Own handle per thread: seeks on a shared handle would race.
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        onBytes(read)
                    }
                    if (written != expected) {
                        throw IOException("下载不完整，有一段数据没下完。网络可能不稳定，重新接收一次。")
                    }
                }
            }
        }
    }
}
