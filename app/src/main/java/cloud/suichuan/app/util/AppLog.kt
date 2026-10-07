package cloud.suichuan.app.util

import android.content.Context
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's own diagnostic log, written to `suichuan.log` in the internal
 * files dir. The user decided failures should be diagnosable from the
 * client side too (some failures never reach the server), so the main
 * flows leave a breadcrumb trail here.
 *
 * Privacy rules, by construction:
 *  - Upload/download TOKENS are never logged. Call sites log paths in the
 *    server-log shape ("POST /t", "PUT /f/:token", "GET /t/267499"), and
 *    any raw URL must go through [redactUrl], which only ever reveals a
 *    6-digit pickup code — never a token or a capability file name.
 *  - No file contents and no share text are ever logged; app names /
 *    package names being transferred are fine (they are the diagnosis).
 *
 * Uploading the log is always the user's explicit choice (see
 * LogUploader); nothing here ever leaves the device on its own.
 *
 * All writes are serialized on a single daemon thread, are cheap, and
 * never throw into the caller. The file is capped at ~256KB: once past
 * that, the older half is dropped, keeping the newest ~128KB.
 */
object AppLog {

    private const val FILE_NAME = "suichuan.log"
    private const val MAX_FILE_BYTES = 256L * 1024L
    private const val KEEP_BYTES = 128L * 1024L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "suichuan-log").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /**
     * Points the logger at the app's files dir. Called from every
     * Activity's onCreate (and from a few entry points that receive a
     * Context first); a no-op once initialized.
     */
    fun init(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            logFile = try {
                File(context.applicationContext.filesDir, FILE_NAME)
            } catch (e: Exception) {
                null
            }
        }
        if (logFile != null) log("APP", "随传启动，日志开始记录")
    }

    fun log(tag: String, message: String) {
        log(tag, message, null)
    }

    fun log(tag: String, message: String, error: Throwable?) {
        try {
            executor.execute { writeLine(tag, message, error) }
        } catch (e: Exception) {
            // Logging must never break the app, even while shutting down.
        }
    }

    /**
     * The current log content for an upload, after waiting briefly for
     * queued lines to flush — the failure being reported was usually
     * logged a moment earlier and must be in the snapshot.
     */
    fun snapshot(): String? {
        val file = logFile ?: return null
        try {
            val flushed = CountDownLatch(1)
            executor.execute { flushed.countDown() }
            flushed.await(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Fall through and read whatever is on disk.
        }
        return try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reduces a URL to a log-safe shape: scheme + host (+ port) and, for
     * our backend, the route with only a 6-digit pickup code visible
     * ("http://host:8080/f/267499"). Tokens, upload paths and third-party
     * capability file names all become ":redacted".
     */
    fun redactUrl(raw: String): String {
        return try {
            val uri = URI(raw)
            val host = uri.host ?: return "<url>"
            val base = buildString {
                uri.scheme?.let { append(it).append("://") }
                append(host)
                if (uri.port > 0) append(':').append(uri.port)
            }
            val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            when {
                segments.isEmpty() -> base
                segments[0] == "t" || segments[0] == "f" -> {
                    val second = segments.getOrNull(1)
                    if (second != null && second.matches(Regex("^\\d{6}$"))) {
                        "$base/${segments[0]}/$second"
                    } else {
                        "$base/${segments[0]}/:redacted"
                    }
                }
                else -> "$base/:redacted"
            }
        } catch (e: Exception) {
            "<url>"
        }
    }

    private fun writeLine(tag: String, message: String, error: Throwable?) {
        val file = logFile ?: return
        try {
            trimIfNeeded(file)
            val sb = StringBuilder()
            sb.append(timeFormat.format(Date()))
                .append(" [").append(tag).append("] ")
                .append(message).append('\n')
            if (error != null) {
                sb.append(error.stackTraceToString())
                if (!sb.endsWith("\n")) sb.append('\n')
            }
            file.appendText(sb.toString())
        } catch (e: Exception) {
            // Never throw: a failed log write is silently dropped.
        }
    }

    /** Keeps the newest ~128KB (whole lines only) once past the cap. */
    private fun trimIfNeeded(file: File) {
        try {
            if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
            val bytes = file.readBytes()
            var start = bytes.size - KEEP_BYTES.toInt()
            if (start < 0) start = 0
            var nl = start
            while (nl < bytes.size && bytes[nl] != '\n'.code.toByte()) nl++
            val keepFrom = if (nl < bytes.size) nl + 1 else start
            file.writeBytes(bytes.copyOfRange(keepFrom, bytes.size))
        } catch (e: Exception) {
            // Best effort only.
        }
    }
}
