package cloud.suichuan.app.net

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Shared OkHttp client tuned for large package files on mobile networks. */
object HttpClients {

    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /** New call, registered with [cancel] (if any) so the UI can stop it. */
    fun call(request: Request, cancel: CancelToken? = null): Call {
        val call = instance.newCall(request)
        return cancel?.track(call) ?: call
    }
}
