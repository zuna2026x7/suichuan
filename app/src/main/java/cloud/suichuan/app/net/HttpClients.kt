package cloud.suichuan.app.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp client tuned for large package files on mobile networks. */
object HttpClients {

    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
}
