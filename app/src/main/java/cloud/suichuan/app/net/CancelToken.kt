package cloud.suichuan.app.net

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Thrown when the user cancelled a transfer; never shown as an error. */
class TransferCancelledException : IOException("已取消")

/**
 * Lets the UI really stop a transfer: every OkHttp call made with this
 * token is cancelled the moment [cancel] runs, so the bytes stop flowing
 * instead of the worker thread silently finishing the upload/download in
 * the background.
 */
class CancelToken {

    @Volatile
    var isCancelled: Boolean = false
        private set

    private val calls = CopyOnWriteArrayList<Call>()

    fun track(call: Call): Call {
        calls.add(call)
        if (isCancelled) call.cancel()
        return call
    }

    fun cancel() {
        isCancelled = true
        calls.forEach { it.cancel() }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw TransferCancelledException()
    }
}
