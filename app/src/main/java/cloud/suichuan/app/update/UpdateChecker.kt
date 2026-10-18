package cloud.suichuan.app.update

import android.content.Context
import cloud.suichuan.app.BuildConfig
import cloud.suichuan.app.net.HttpClients
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.VersionUtil
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** The newest published release, as far as the update check cares. */
data class ReleaseInfo(
    /** Tag as published, e.g. "v1.4". */
    val tag: String,
    /** Release title, e.g. "随传 v1.4". */
    val title: String,
    /** Release notes (markdown-ish plain text). */
    val notes: String,
    /** Release page in a browser, the fallback when in-app install can't work. */
    val pageUrl: String,
    /** The .apk asset; empty when the release carries no installable file. */
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 GitHub reports for the asset, or "". */
    val apkSha256: String,
    /** "2026-10-16T11:09:00Z" as GitHub returns it. */
    val publishedAt: String
)

/** An IOException whose message is already plain Chinese for the user. */
class UpdateException(message: String) : IOException(message)

/** Outcome of one check, already decided for the UI. */
sealed class CheckResult {
    data class UpToDate(val latestTag: String) : CheckResult()
    data class Available(val release: ReleaseInfo) : CheckResult()
    /** [message] is plain Chinese, ready to show. */
    data class Failed(val message: String, val pageUrl: String = "") : CheckResult()
}

/**
 * Checks GitHub Releases of the app's own repo (BuildConfig.UPDATE_REPO) for
 * a newer version and downloads its APK. Blocking: call off the main thread.
 *
 * Source of truth is `GET /repos/{repo}/releases/latest`, which skips drafts
 * and pre-releases, so a release only reaches users once it is published.
 */
object UpdateChecker {

    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_IGNORED_TAG = "ignored_tag"
    private const val KEY_SEEN_TAG = "available_tag"
    private const val SILENT_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Short timeouts: a check is a small JSON call, not a file transfer. */
    private val apiClient by lazy {
        HttpClients.instance.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    fun releasesPageUrl(): String = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"

    fun check(): CheckResult {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return CheckResult.Failed("这个版本没有配置更新地址，没法检查更新。")
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "suichuan-android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return try {
            apiClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 404 ->
                        CheckResult.Failed("还没有发布过正式版本，暂时没有可更新的内容。")
                    response.code == 403 || response.code == 429 ->
                        CheckResult.Failed("检查得太频繁，GitHub 暂时限制了访问。过一会儿（大约一小时内）再试。")
                    !response.isSuccessful ->
                        CheckResult.Failed("更新服务器暂时出了问题（${response.code}），过一会儿再试。")
                    else -> decide(parse(body))
                }
            }
        } catch (e: Exception) {
            AppLog.log("UPDATE", "检查更新失败", e)
            CheckResult.Failed(networkMessage(e))
        }
    }

    private fun decide(release: ReleaseInfo): CheckResult {
        val local = currentVersionName()
        AppLog.log("UPDATE", "最新版本=${release.tag} 当前=$local apk=${release.apkName.ifBlank { "无" }}")
        if (!VersionUtil.isNewer(release.tag, local)) return CheckResult.UpToDate(release.tag)
        if (release.apkUrl.isBlank()) {
            return CheckResult.Failed(
                "有新版本 ${release.tag}，但它没有附带安装包，没法在随传里直接更新。可以打开下载页看看。",
                release.pageUrl
            )
        }
        return CheckResult.Available(release)
    }

    private fun parse(json: String): ReleaseInfo {
        val obj = JSONObject(json)
        val assets = obj.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        var apkSha = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkName = name
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                    apkSha = a.optString("digest").takeIf { it.startsWith("sha256:") }
                        ?.removePrefix("sha256:")?.lowercase().orEmpty()
                    break
                }
            }
        }
        return ReleaseInfo(
            tag = obj.optString("tag_name"),
            title = obj.optString("name").ifBlank { obj.optString("tag_name") },
            notes = obj.optString("body").trim(),
            pageUrl = obj.optString("html_url").ifBlank { releasesPageUrl() },
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize,
            apkSha256 = apkSha,
            publishedAt = obj.optString("published_at")
        )
    }

    /** Plain-Chinese explanation for a failed network call. */
    fun networkMessage(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException ->
            "连不上更新服务器（GitHub）。检查一下网络；国内网络有时打不开 GitHub，可以换个网络（比如切到手机流量）再试。"
        is SocketTimeoutException ->
            "连接更新服务器超时了，网络可能比较慢。稍后再试。"
        is SSLException ->
            "和更新服务器的安全连接没建立起来，可能是当前网络有拦截。换个网络再试。"
        else ->
            "检查更新没有成功：${e.message ?: "网络出了点问题"}。稍后再试。"
    }

    private fun downloadNetworkMessage(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException ->
            "下载中断了：连不上 GitHub 的下载服务器。换个网络（比如切到手机流量）再试，或者打开下载页用浏览器下载。"
        is SocketTimeoutException ->
            "下载超时了，网络可能比较慢。点重试再来一次。"
        else ->
            "下载没有完成：${e.message ?: "网络出了点问题"}。点重试再来一次。"
    }

    // ---- download ----------------------------------------------------------

    /** Holder so the screen can cancel a running download. */
    class Download {
        @Volatile var call: Call? = null
        @Volatile var cancelled = false
        fun cancel() {
            cancelled = true
            call?.cancel()
        }
    }

    /**
     * Downloads the release APK into the private cache (cache/update/),
     * reporting progress. Throws IOException with a Chinese message.
     */
    fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        handle: Download,
        onProgress: (written: Long, total: Long) -> Unit
    ): File {
        val dir = updateDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "suichuan-${release.tag}.apk")
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "suichuan-android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        val call = HttpClients.instance.newCall(request)
        handle.call = call
        if (handle.cancelled) throw UpdateException("已取消下载。")
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException("新版本安装包下载失败（${response.code}）。稍后再试，或者打开下载页用浏览器下载。")
                }
                val body = response.body ?: throw UpdateException("下载失败：服务器没有返回内容。")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
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
                            if (written - lastReport >= 128 * 1024) {
                                lastReport = written
                                onProgress(written, total)
                            }
                        }
                        onProgress(written, total)
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            if (handle.cancelled) throw UpdateException("已取消下载。")
            if (e is UpdateException) throw e
            AppLog.log("UPDATE", "下载新版本失败", e)
            throw UpdateException(downloadNetworkMessage(e))
        }
        return target
    }

    fun updateDir(context: Context): File = File(context.cacheDir, "update").apply { mkdirs() }

    /** Deletes a leftover update APK (e.g. after the update installed). */
    fun cleanup(context: Context) {
        File(context.cacheDir, "update").listFiles()?.forEach { it.delete() }
    }

    // ---- silent check bookkeeping -----------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the once-a-day silent check is due. */
    fun silentCheckDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= SILENT_INTERVAL_MS
    }

    fun markChecked(context: Context, availableTag: String?) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putString(KEY_SEEN_TAG, availableTag)
            .apply()
    }

    /** A newer tag found by an earlier check, unless the user ignored it. */
    fun pendingTag(context: Context): String? {
        val p = prefs(context)
        val tag = p.getString(KEY_SEEN_TAG, null) ?: return null
        if (!VersionUtil.isNewer(tag, currentVersionName())) return null
        if (tag == p.getString(KEY_IGNORED_TAG, null)) return null
        return tag
    }

    fun ignore(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_IGNORED_TAG, tag).apply()
    }

    fun isIgnored(context: Context, tag: String): Boolean =
        prefs(context).getString(KEY_IGNORED_TAG, null) == tag
}
