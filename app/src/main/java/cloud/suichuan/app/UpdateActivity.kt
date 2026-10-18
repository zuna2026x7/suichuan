package cloud.suichuan.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.install.InstallPermissionHelper
import cloud.suichuan.app.install.Installer
import cloud.suichuan.app.update.ApkIdentity
import cloud.suichuan.app.update.CheckResult
import cloud.suichuan.app.update.ReleaseInfo
import cloud.suichuan.app.update.UpdateChecker
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.Sha256
import cloud.suichuan.app.util.VersionUtil
import java.io.File
import kotlin.concurrent.thread

/**
 * Manual update screen: check GitHub Releases, show what changed, download
 * the APK into private cache, verify it, then hand it to the same
 * PackageInstaller session the rest of the app uses (no FileProvider or
 * shared storage needed). One status line + up to three buttons, re-labelled
 * per state, so the screen never shows a button that does nothing.
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var releaseLayout: LinearLayout
    private lateinit var releaseTitle: TextView
    private lateinit var releaseMeta: TextView
    private lateinit var releaseNotes: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var ignoreButton: Button

    private var release: ReleaseInfo? = null
    private var downloaded: File? = null
    private var download: UpdateChecker.Download? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_update)

        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress)
        releaseLayout = findViewById(R.id.layout_release)
        releaseTitle = findViewById(R.id.text_release_title)
        releaseMeta = findViewById(R.id.text_release_meta)
        releaseNotes = findViewById(R.id.text_release_notes)
        primaryButton = findViewById(R.id.button_primary)
        secondaryButton = findViewById(R.id.button_secondary)
        ignoreButton = findViewById(R.id.button_ignore)

        findViewById<TextView>(R.id.text_current_version).text =
            "当前版本：${VersionUtil.display(UpdateChecker.currentVersionName())}"

        startCheck()
    }

    /**
     * A successful self-update restarts the app, so only failures arrive
     * here; show them on the page instead of a vanishing Toast.
     */
    private val installListener = cloud.suichuan.app.install.InstallResultBus.Listener { result ->
        if (!isFinishing && !isDestroyed && !result.success) {
            statusText.text = result.message
        }
    }

    override fun onStart() {
        super.onStart()
        cloud.suichuan.app.install.InstallResultBus.register(installListener)
    }

    override fun onStop() {
        cloud.suichuan.app.install.InstallResultBus.unregister(installListener)
        super.onStop()
    }

    override fun onDestroy() {
        // Leaving mid-download: stop it, nothing half-written is kept.
        download?.cancel()
        super.onDestroy()
    }

    // ---- states ------------------------------------------------------------

    private fun startCheck() {
        if (busy) return
        busy = true
        release = null
        downloaded = null
        showButtons()
        releaseLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在检查新版本…"
        thread {
            val result = UpdateChecker.check()
            UpdateChecker.markChecked(this, (result as? CheckResult.Available)?.release?.tag)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                progressBar.visibility = View.GONE
                when (result) {
                    is CheckResult.UpToDate -> showUpToDate(result.latestTag)
                    is CheckResult.Available -> showAvailable(result.release)
                    is CheckResult.Failed -> showCheckFailed(result.message, result.pageUrl)
                }
            }
        }
    }

    private fun showUpToDate(latestTag: String) {
        statusText.text = "已经是最新版本了。" +
            if (latestTag.isNotBlank()) "（最新发布：${VersionUtil.display(latestTag)}）" else ""
        showButtons(primary = "完成" to { finish() })
    }

    private fun showCheckFailed(message: String, pageUrl: String) {
        statusText.text = message
        showButtons(
            primary = "重新检查" to { startCheck() },
            secondary = if (pageUrl.isNotBlank()) "打开下载页" to { openPage(pageUrl) } else null
        )
    }

    private fun showAvailable(info: ReleaseInfo) {
        release = info
        releaseLayout.visibility = View.VISIBLE
        releaseTitle.text = "新版本 ${VersionUtil.display(info.tag)}"
        val meta = mutableListOf("安装包 ${FormatUtil.size(info.apkSize)}")
        info.publishedAt.takeIf { it.length >= 10 }?.let { meta.add("发布于 ${it.substring(0, 10)}") }
        if (isOnCellular()) meta.add("现在用的是手机流量")
        releaseMeta.text = meta.joinToString(" · ")
        releaseNotes.text = info.notes.ifBlank { "这个版本没有写更新说明。" }
        statusText.text = "发现新版本，可以直接在随传里更新。"
        showButtons(
            primary = "下载并安装" to { startDownload(info) },
            secondary = "以后再说" to { finish() },
            ignore = true
        )
    }

    private fun startDownload(info: ReleaseInfo) {
        if (busy) return
        busy = true
        val handle = UpdateChecker.Download().also { download = it }
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        statusText.text = "正在下载新版本（${FormatUtil.size(info.apkSize)}）…"
        showButtons(secondary = "取消下载" to { handle.cancel() })
        thread {
            try {
                val file = UpdateChecker.downloadApk(this, info, handle) { written, total ->
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            progressBar.progress = percent
                            statusText.text = "正在下载新版本… $percent%（${FormatUtil.size(written)} / ${FormatUtil.size(total)}）"
                        }
                    }
                }
                runOnUiThread {
                    progressBar.isIndeterminate = true
                    statusText.text = "下载完成，正在检查安装包…"
                }
                verify(info, file)
                val verdict = ApkIdentity.check(this, file)
                AppLog.log("UPDATE", "新版安装包检查结果=$verdict")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    progressBar.visibility = View.GONE
                    onVerified(info, file, verdict)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    progressBar.visibility = View.GONE
                    if (handle.cancelled) {
                        showAvailable(info)
                        statusText.text = "已取消下载。"
                    } else {
                        statusText.text = cloud.suichuan.app.util.ErrorText.of(e, "下载没有完成，点重试再来一次。")
                        showButtons(
                            primary = "重试" to { startDownload(info) },
                            secondary = "打开下载页" to { openPage(info.pageUrl) }
                        )
                    }
                }
            }
        }
    }

    /** SHA-256 (when GitHub reports one) and "is it an APK at all". */
    private fun verify(info: ReleaseInfo, file: File) {
        if (info.apkSha256.isNotBlank()) {
            val actual = Sha256.ofFile(file)
            if (!actual.equals(info.apkSha256, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException("下载到的安装包不完整（校验没通过）。网络可能不稳定，点重试再下一次。")
            }
        }
        if (ApkInspector.inspect(file).kind != ApkInspector.Kind.SINGLE) {
            file.delete()
            throw IllegalStateException("下载到的不是有效的安装包。点重试再下一次，还不行就打开下载页用浏览器下载。")
        }
    }

    private fun onVerified(info: ReleaseInfo, file: File, verdict: ApkIdentity.Verdict) {
        when (verdict) {
            ApkIdentity.Verdict.OK, ApkIdentity.Verdict.UNREADABLE -> {
                downloaded = file
                statusText.text = "新版本已下载并检查通过。点「安装更新」，在系统弹窗里点「更新」或「安装」。" +
                    "安装时随传会自动关闭，装好后重新打开就是新版本。"
                showButtons(primary = "安装更新" to { install(file) })
            }
            ApkIdentity.Verdict.SIGNATURE_MISMATCH -> {
                file.delete()
                // Early test builds were each signed with a different
                // throw-away key, so Android refuses to update in place.
                statusText.text = "这个新版本和你手机上的随传签名不同（早期测试版每次构建的签名都不一样），" +
                    "安卓不允许直接覆盖更新。\n\n" +
                    "办法：先点「打开下载页」用浏览器把新版下载好，再点「卸载当前版本」，" +
                    "最后在浏览器的下载记录里点开新版安装。随传里没有需要保存的数据，卸载不会丢东西。"
                showButtons(
                    primary = "打开下载页" to { openPage(info.pageUrl) },
                    secondary = "卸载当前版本" to { openAppSettings() }
                )
            }
            ApkIdentity.Verdict.NOT_THIS_APP -> {
                file.delete()
                statusText.text = "下载到的安装包不是随传，已经删掉了。请稍后再试，或者打开下载页看看。"
                showButtons(primary = "打开下载页" to { openPage(info.pageUrl) })
            }
            ApkIdentity.Verdict.NOT_NEWER -> {
                file.delete()
                statusText.text = "下载到的版本并不比手机上的新，不需要更新。"
                showButtons(primary = "完成" to { finish() })
            }
        }
    }

    private fun install(file: File) {
        if (!InstallPermissionHelper.ensure(this)) return
        try {
            Installer.install(this, file, ApkInspector.Kind.SINGLE)
            statusText.text = "已打开系统安装界面，点「更新」或「安装」即可。没看到弹窗的话，再点一次「安装更新」。"
        } catch (e: Exception) {
            AppLog.log("UPDATE", "打开系统安装失败", e)
            statusText.text = "安装没有打开：" + cloud.suichuan.app.util.ErrorText.of(e, "系统安装程序出了问题。")
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun showButtons(
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        ignore: Boolean = false
    ) {
        bind(primaryButton, primary)
        bind(secondaryButton, secondary)
        val tag = release?.tag
        if (ignore && tag != null) {
            ignoreButton.visibility = View.VISIBLE
            ignoreButton.setOnClickListener {
                UpdateChecker.ignore(this, tag)
                Toast.makeText(this, "好的，${VersionUtil.display(tag)} 不再提醒。想更新时可以随时来这里检查。", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            ignoreButton.visibility = View.GONE
        }
    }

    private fun bind(button: Button, action: Pair<String, () -> Unit>?) {
        if (action == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.text = action.first
            button.setOnClickListener { action.second() }
        }
    }

    private fun openPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有找到浏览器。下载地址：$url", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "在打开的页面里点「卸载」。", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请到系统设置 → 应用里找到随传并卸载。", Toast.LENGTH_LONG).show()
        }
    }

    private fun isOnCellular(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
