package cloud.suichuan.app.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import cloud.suichuan.app.BuildConfig
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.FormatUtil
import kotlin.concurrent.thread

/**
 * The quiet check on app start: at most once every [INTERVAL_MS], never
 * shows anything on failure or when already up to date, and never nags
 * about a version the user chose to skip. When a newer version exists it
 * shows one dialog that leads to [UpdateActivity].
 */
object StartupUpdateCheck {

    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_TAG = "skipped_tag"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    fun maybeRun(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if ((now - last) in 0L until INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val installed = BuildConfig.VERSION_NAME
        thread {
            val result = try {
                UpdateChecker.check(BuildConfig.UPDATE_API_URL, installed)
            } catch (e: Exception) {
                // Silent by design: the manual check explains failures.
                AppLog.log("UPDATE", "启动时静默检查失败：${e.message}")
                return@thread
            }
            if (result !is UpdateChecker.Result.Available) return@thread
            val latest = result.latest
            if (prefs.getString(KEY_SKIPPED_TAG, null) == latest.tag) return@thread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                showDialog(activity, latest)
            }
        }
    }

    /** Forgets a skipped version (used when the user checks manually). */
    fun clearSkipped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SKIPPED_TAG).apply()
    }

    private fun showDialog(activity: Activity, latest: UpdateChecker.Release) {
        val size = if (latest.apkSize > 0) "（${FormatUtil.size(latest.apkSize)}）" else ""
        val notes = latest.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n")
        val message = buildString {
            append("当前版本 ${BuildConfig.VERSION_NAME}，最新版本 ${latest.version}$size。")
            if (notes.isNotBlank()) {
                append("\n\n")
                append(notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("随传有新版本")
            .setMessage(message)
            .setPositiveButton("查看并更新") { _, _ ->
                activity.startActivity(Intent(activity, UpdateActivity::class.java))
            }
            .setNegativeButton("以后再说", null)
            .setNeutralButton("跳过这个版本") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SKIPPED_TAG, latest.tag).apply()
            }
            .show()
    }
}
