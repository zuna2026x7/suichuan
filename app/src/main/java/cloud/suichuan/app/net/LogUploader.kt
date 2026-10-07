package cloud.suichuan.app.net

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import cloud.suichuan.app.BuildConfig
import cloud.suichuan.app.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.concurrent.thread

/**
 * Uploads the on-device diagnostic log (see AppLog) to our own pickup
 * server's POST /log — ONLY when the user explicitly taps the upload
 * button. Nothing is ever sent automatically.
 *
 * The log contains no tokens by construction (AppLog redacts), so the
 * endpoint needs no auth. The server stores it as a file for later
 * diagnosis by whoever the user tells.
 */
object LogUploader {

    /** Uploads on a background thread; [onDone] runs on that thread. */
    fun upload(context: Context, onDone: (Boolean) -> Unit) {
        thread {
            val ok = try {
                doUpload(context)
            } catch (e: Exception) {
                false
            }
            onDone(ok)
        }
    }

    /** The one-tap behavior used by every upload-log button in the app. */
    fun uploadFrom(activity: Activity) {
        AppLog.log("LOG", "用户点了「上传日志帮我看看」")
        upload(activity) { ok ->
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (ok) "日志已上传，告诉对方一声就行" else "日志上传失败，网络可能不通",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun doUpload(context: Context): Boolean {
        val base = BuildConfig.TRANSFER_API_BASE.trimEnd('/')
        if (base.isBlank()) return false
        val text = AppLog.snapshot()
        if (text.isNullOrBlank()) return false
        val versionName = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val info = "$versionName;${Build.MODEL ?: "?"};${Build.VERSION.RELEASE ?: "?"}"
        val request = Request.Builder()
            .url("$base/log")
            .header("x-suichuan-info", info)
            .post(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        HttpClients.instance.newCall(request).execute().use { response ->
            // Drain the body so the connection can be reused.
            response.body?.string()
            AppLog.log("LOG", "日志上传 -> HTTP ${response.code}")
            return response.isSuccessful
        }
    }
}
