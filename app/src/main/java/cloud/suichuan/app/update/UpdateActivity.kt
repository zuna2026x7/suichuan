package cloud.suichuan.app.update

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.BuildConfig
import cloud.suichuan.app.R
import cloud.suichuan.app.install.InstallPermissionHelper
import cloud.suichuan.app.install.Installer
import cloud.suichuan.app.net.Downloader
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.Sha256
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * "检查更新": shows the installed version, checks the newest release,
 * shows its notes, downloads the APK into private cache, verifies it
 * (SHA-256 from the release, same package name, same signer, no
 * downgrade) and hands it to the system installer through the same
 * PackageInstaller session the receive flow uses — so no FileProvider or
 * shared-storage file is involved.
 *
 * States: checking -> (up to date | update available | error)
 *         update available -> downloading -> verifying -> ready to install
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var notesTitle: TextView
    private lateinit var notesText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var checkButton: Button
    private lateinit var updateButton: Button
    private lateinit var browserButton: Button
    private lateinit var cancelButton: Button

    private var latest: UpdateChecker.Release? = null
    private var readyApk: File? = null
    private var busy = false

    /**
     * Bumped by every new download and by Cancel. A download thread only
     * touches the UI while its own generation is still current, so a
     * cancelled download that is still winding down can never overwrite
     * the screen of a newer attempt.
     */
    @Volatile
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_update)

        statusText = findViewById(R.id.text_status)
        notesTitle = findViewById(R.id.text_notes_title)
        notesText = findViewById(R.id.text_notes)
        progressBar = findViewById(R.id.progress_update)
        progressText = findViewById(R.id.text_progress)
        checkButton = findViewById(R.id.button_check)
        updateButton = findViewById(R.id.button_update)
        browserButton = findViewById(R.id.button_browser)
        cancelButton = findViewById(R.id.button_cancel)

        findViewById<TextView>(R.id.text_current_version).text =
            "当前版本：${BuildConfig.VERSION_NAME}"

        checkButton.setOnClickListener { runCheck() }
        updateButton.setOnClickListener { onUpdateTapped() }
        browserButton.setOnClickListener { openInBrowser() }
        cancelButton.setOnClickListener {
            generation++
            busy = false
            showIdle("已取消下载。想更新时再点「下载并安装」。")
            updateButton.visibility = if (latest?.apkUrl != null) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.button_close).setOnClickListener { finish() }

        // A manual visit is an explicit request: forget any skipped version.
        StartupUpdateCheck.clearSkipped(this)
        runCheck()
    }

    private fun runCheck() {
        if (busy) return
        busy = true
        readyApk = null
        latest = null
        setNotes(null)
        updateButton.visibility = View.GONE
        browserButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        checkButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.visibility = View.GONE
        statusText.text = "正在检查更新…"

        thread {
            val result = try {
                UpdateChecker.check(BuildConfig.UPDATE_API_URL, BuildConfig.VERSION_NAME)
            } catch (e: UpdateChecker.CheckException) {
                runOnUiThread { showError(e.userMessage, offerBrowser = false) }
                return@thread
            } catch (e: Exception) {
                AppLog.log("UPDATE", "检查更新失败", e)
                runOnUiThread { showError("检查更新没有成功，稍后再试。", offerBrowser = false) }
                return@thread
            }
            runOnUiThread {
                busy = false
                checkButton.isEnabled = true
                progressBar.visibility = View.GONE
                when (result) {
                    is UpdateChecker.Result.UpToDate -> {
                        latest = result.latest
                        statusText.text = "已经是最新版本（${BuildConfig.VERSION_NAME}），不用更新。"
                        checkButton.text = "重新检查"
                    }
                    is UpdateChecker.Result.Available -> {
                        val r = result.latest
                        latest = r
                        setNotes(r)
                        if (r.apkUrl == null) {
                            statusText.text = "发现新版本 ${r.version}，但它还没有上传安装包。可以过一会儿再来，或者打开发布页看看。"
                            browserButton.visibility = View.VISIBLE
                        } else {
                            val size = if (r.apkSize > 0) "，大小 ${FormatUtil.size(r.apkSize)}" else ""
                            statusText.text = "发现新版本 ${r.version}$size。"
                            updateButton.text = "下载并安装"
                            updateButton.visibility = View.VISIBLE
                        }
                        checkButton.text = "重新检查"
                    }
                }
            }
        }
    }

    private fun onUpdateTapped() {
        val apk = readyApk
        if (apk != null && apk.isFile) {
            install(apk)
        } else {
            download()
        }
    }

    private fun download() {
        val release = latest ?: return
        val url = release.apkUrl ?: return
        if (busy) return
        busy = true
        val gen = ++generation
        updateButton.visibility = View.GONE
        browserButton.visibility = View.GONE
        checkButton.isEnabled = false
        cancelButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = "0%"
        statusText.text = "正在下载新版本 ${release.version}…"

        thread {
            val dir = File(cacheDir, "update").apply {
                // Only ever keep the one APK being installed.
                listFiles()?.forEach { it.delete() }
                mkdirs()
            }
            val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, "suichuan-$safeTag-${System.currentTimeMillis()}.apk")
            try {
                Downloader.download(url, target) { written, total ->
                    if (gen != generation) throw InterruptedException("cancelled")
                    val t = if (total > 0) total else release.apkSize
                    if (t > 0) {
                        val pct = (written * 100 / t).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            if (gen != generation) return@runOnUiThread
                            progressBar.progress = pct
                            progressText.text = "$pct%（${FormatUtil.size(written)} / ${FormatUtil.size(t)}）"
                        }
                    }
                }
                if (gen != generation) {
                    target.delete()
                    return@thread
                }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    progressBar.isIndeterminate = true
                    progressText.visibility = View.GONE
                    cancelButton.visibility = View.GONE
                    statusText.text = "下载完成，正在检查安装包…"
                }
                val problem = verify(target, release)
                if (problem != null) {
                    target.delete()
                    runOnUiThread {
                        if (gen == generation) showError(problem.first, offerBrowser = problem.second)
                    }
                    return@thread
                }
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    busy = false
                    readyApk = target
                    checkButton.isEnabled = true
                    progressBar.visibility = View.GONE
                    statusText.text = "新版本 ${release.version} 已经下好并检查通过。点「安装」，在系统弹窗里确认即可，原来的数据不会丢。"
                    updateButton.text = "安装"
                    updateButton.visibility = View.VISIBLE
                    install(target)
                }
            } catch (e: Exception) {
                target.delete()
                if (gen != generation) return@thread
                AppLog.log("UPDATE", "下载新版本失败", e)
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    showError(
                        "新版本没有下载下来，网络可能不稳定。点「重新检查」再试，或者用浏览器下载。",
                        offerBrowser = true
                    )
                }
            }
        }
    }

    /**
     * Returns null when [apk] is safe to install over this app, otherwise
     * the message to show plus whether the browser fallback helps.
     */
    private fun verify(apk: File, release: UpdateChecker.Release): Pair<String, Boolean>? {
        if (release.apkSha256.isNotBlank()) {
            val actual = Sha256.ofFile(apk)
            if (!actual.equals(release.apkSha256, ignoreCase = true)) {
                AppLog.log("UPDATE", "sha256 不一致")
                return "下载到的安装包不完整（校验没通过），重新下载一次。" to true
            }
        }
        if (ApkInspector.inspect(apk).kind != ApkInspector.Kind.SINGLE) {
            return "下载到的不是有效的安装包，稍后再试，或者用浏览器下载。" to true
        }
        val archive = archiveInfo(apk)
            ?: return "这个安装包读不出来，可能已损坏。重新下载一次。" to true
        if (archive.packageName != packageName) {
            AppLog.log("UPDATE", "包名不一致：${archive.packageName}")
            return "下载到的不是随传的安装包，已经丢弃。请稍后再试。" to false
        }
        val installed = packageManager.getPackageInfo(packageName, signingFlags())
        if (versionCodeOf(archive) < versionCodeOf(installed)) {
            return "这个安装包的内部版本号比手机里的还旧，安卓不允许覆盖降级。等下一个版本发布后再更新。" to false
        }
        if (!sameSigner(archive, installed)) {
            AppLog.log("UPDATE", "签名不一致，不能直接覆盖安装")
            return ("新版本的签名和手机里这个随传不一样（早期测试版常见），安卓不允许直接覆盖。" +
                "请点「用浏览器下载」把新版存到手机，然后先卸载当前的随传，再安装下载好的新版。" +
                "卸载只会清掉本机的随传设置，不影响已经发出去的取件码。") to true
        }
        return null
    }

    private fun install(apk: File) {
        if (!InstallPermissionHelper.ensure(this)) {
            statusText.text = "安装前需要允许随传安装应用。打开开关后回来，再点「安装」。"
            return
        }
        try {
            Installer.install(this, apk, ApkInspector.Kind.SINGLE)
            Toast.makeText(this, "正在打开系统安装界面…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.log("UPDATE", "打开系统安装失败", e)
            showError("系统安装界面没有打开。点「安装」再试一次，或者用浏览器下载后手动安装。", offerBrowser = true)
            updateButton.visibility = View.VISIBLE
        }
    }

    private fun openInBrowser() {
        val release = latest
        val url = release?.apkUrl ?: release?.pageUrl?.takeIf { it.isNotBlank() }
            ?: BuildConfig.UPDATE_PAGE_URL
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "这部手机上没有找到浏览器。", Toast.LENGTH_LONG).show()
        }
    }

    private fun setNotes(release: UpdateChecker.Release?) {
        val notes = release?.notes.orEmpty()
        if (release == null || notes.isBlank()) {
            notesTitle.visibility = View.GONE
            notesText.visibility = View.GONE
            notesText.text = ""
        } else {
            notesTitle.text = "${release.name} 更新内容"
            notesText.text = notes
            notesTitle.visibility = View.VISIBLE
            notesText.visibility = View.VISIBLE
        }
    }

    private fun showIdle(message: String) {
        checkButton.isEnabled = true
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        cancelButton.visibility = View.GONE
        statusText.text = message
    }

    private fun showError(message: String, offerBrowser: Boolean) {
        busy = false
        showIdle(message)
        checkButton.text = "重新检查"
        browserButton.visibility = if (offerBrowser) View.VISIBLE else View.GONE
    }

    // --- package helpers -------------------------------------------------

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun archiveInfo(apk: File): PackageInfo? = try {
        packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** Compares the signer certificates' SHA-256; unknown counts as "same". */
    private fun sameSigner(archive: PackageInfo, installed: PackageInfo): Boolean {
        val a = signerDigests(archive)
        val b = signerDigests(installed)
        if (a.isEmpty() || b.isEmpty()) return true // let the installer decide
        return a == b
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }
}
