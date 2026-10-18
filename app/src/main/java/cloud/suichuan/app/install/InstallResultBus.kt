package cloud.suichuan.app.install

import android.os.Handler
import android.os.Looper

/**
 * Hands the system's install verdict from [cloud.suichuan.app.InstallResultReceiver]
 * to whichever screen started the install, so the result can be shown as a
 * lasting card (with "打开" / "重新安装") instead of a Toast that vanishes.
 * If no screen is listening (the user left), the receiver falls back to a
 * Toast.
 */
object InstallResultBus {

    data class Result(
        val success: Boolean,
        val packageName: String?,
        /** User-facing Chinese message (already mapped). */
        val message: String,
        /** True when the user just closed the system dialog. */
        val userCancelled: Boolean = false
    )

    fun interface Listener {
        fun onInstallResult(result: Result)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: Listener? = null

    fun register(l: Listener) {
        listener = l
    }

    fun unregister(l: Listener) {
        if (listener === l) listener = null
    }

    /** Returns false when nobody is listening. */
    fun post(result: Result): Boolean {
        val l = listener ?: return false
        main.post { l.onInstallResult(result) }
        return true
    }
}
