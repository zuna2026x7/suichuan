package cloud.suichuan.app

import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.data.InstalledApp
import cloud.suichuan.app.data.PackedFile
import cloud.suichuan.app.data.Packager
import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.net.BackendClient
import cloud.suichuan.app.net.LitterboxUploader
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.PayloadCodec
import cloud.suichuan.app.util.QrUtil
import kotlin.concurrent.thread

/**
 * Packs the chosen app, uploads it, then shows the pickup code / QR / share
 * button.
 *
 * Two upload routes:
 *  - Backend configured (TRANSFER_API_BASE set): register a pickup code with
 *    our own worker, then PUT the file to it (the file lives in our own R2
 *    storage — no third-party host involved). Any failure offers a fallback
 *    button that retries through the temporary hosting below.
 *  - No backend: the file goes to Litterbox temporary hosting for 72h and the
 *    receiver uses the QR code / shared text.
 */
class ShareActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        /** Hard send ceiling: the pickup server rejects anything bigger. */
        private const val MAX_SEND_BYTES = 2147483648L // 2 GiB
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var fallbackButton: Button
    private lateinit var resultLayout: LinearLayout
    private lateinit var codeLabel: TextView
    private lateinit var codeText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var shareButton: Button

    private var targetApp: InstalledApp? = null
    private var packed: PackedFile? = null
    private var shareText: String = ""
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress_upload)
        retryButton = findViewById(R.id.button_retry)
        fallbackButton = findViewById(R.id.button_fallback)
        resultLayout = findViewById(R.id.layout_result)
        codeLabel = findViewById(R.id.text_code_label)
        codeText = findViewById(R.id.text_code)
        qrImage = findViewById(R.id.image_qr)
        shareButton = findViewById(R.id.button_share)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val app = pkg?.let { loadApp(it) }
        if (app == null) {
            findViewById<TextView>(R.id.text_app_name).text = "没有找到这个应用"
            statusText.text = "这个应用可能已经被卸载了，回去重新选一个。"
            progressBar.visibility = View.GONE
            return
        }
        targetApp = app
        findViewById<TextView>(R.id.text_app_name).text = app.label

        retryButton.setOnClickListener { startWork(forceTemporary = false) }
        fallbackButton.setOnClickListener { startWork(forceTemporary = true) }
        findViewById<Button>(R.id.button_done).setOnClickListener {
            // Success is a dead end on phones without a visible back button
            // (gesture navigation): give an explicit way home, clearing the
            // send flow off the stack so Back from home exits the app.
            val home = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(home)
            finish()
        }
        shareButton.setOnClickListener {
            if (shareText.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(send, "发给对方"))
            }
        }

        if (isOnCellular()) {
            AlertDialog.Builder(this)
                .setTitle("现在用的是手机流量")
                .setMessage("这个应用有 ${FormatUtil.size(app.sizeBytes)}，发送大约要用这么多流量。继续吗？")
                .setPositiveButton("继续发送") { _, _ -> startWork(forceTemporary = false) }
                .setNegativeButton("先不发") { _, _ ->
                    statusText.text = "已取消。连上 Wi-Fi 后再来发，就不耗流量了。"
                    progressBar.visibility = View.GONE
                }
                .show()
        } else {
            startWork(forceTemporary = false)
        }
    }

    private fun loadApp(packageName: String): InstalledApp? {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            val sourceDir = info.publicSourceDir ?: info.sourceDir ?: return null
            val splits = info.splitPublicSourceDirs?.toList()
                ?: info.splitSourceDirs?.toList()
                ?: emptyList()
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
            InstalledApp(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = packageName,
                versionName = versionName,
                sourceDir = sourceDir,
                splitSourceDirs = splits
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun isOnCellular(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return cellular && !wifi
    }

    private fun payloadFor(packedFile: PackedFile, downloadUrl: String): TransferPayload {
        val app = targetApp!!
        return TransferPayload(
            appName = app.label,
            packageName = app.packageName,
            versionName = app.versionName,
            sizeBytes = packedFile.sizeBytes,
            sha256 = packedFile.sha256,
            fileName = packedFile.fileName,
            downloadUrl = downloadUrl
        )
    }

    private fun reportProgress(written: Long, total: Long) {
        if (total > 0) {
            val percent = (written * 100 / total).toInt()
            runOnUiThread { progressBar.progress = percent }
        }
    }

    private fun startWork(forceTemporary: Boolean) {
        val app = targetApp ?: return
        if (working) return
        working = true
        retryButton.visibility = View.GONE
        fallbackButton.visibility = View.GONE
        resultLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在打包「${app.label}」…"

        val base = BuildConfig.TRANSFER_API_BASE
        val useBackend = !forceTemporary && base.isNotBlank()

        thread {
            val packedFile: PackedFile
            try {
                packedFile = packed ?: Packager.pack(app, cacheDir).also { packed = it }
            } catch (e: Exception) {
                showError(
                    "打包没有完成：${e.message ?: "未知问题"}。点重试再试一次。",
                    offerFallback = false
                )
                return@thread
            }
            // Fail fast, before either upload route starts: an oversize
            // package would only die partway through the upload.
            if (packedFile.sizeBytes > MAX_SEND_BYTES) {
                showError(
                    "这个应用有 ${FormatUtil.size(packedFile.sizeBytes)}，超过了随传 2GB 的发送上限，暂时发不了。",
                    offerFallback = false
                )
                return@thread
            }
            runOnUiThread {
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = "正在上传（${FormatUtil.size(packedFile.sizeBytes)}）…"
            }
            if (useBackend) {
                uploadViaBackend(base, packedFile)
            } else {
                uploadViaTemporaryHosting(packedFile)
            }
        }
    }

    /** Backend route: pickup code + the file in our own storage. */
    private fun uploadViaBackend(base: String, packedFile: PackedFile) {
        try {
            val meta = payloadFor(packedFile, downloadUrl = "")
            val created = BackendClient.createTransfer(base, meta)
            BackendClient.uploadFile(base, created.uploadToken, packedFile.file, ::reportProgress)
            // From here on the file is addressed by its pickup code.
            val payload = payloadFor(
                packedFile,
                downloadUrl = base.trimEnd('/') + "/f/" + created.code
            )
            showSuccess(payload, created.code)
        } catch (e: Exception) {
            showError(
                "用取件服务发送失败了：${e.message ?: "网络出了点问题"}。" +
                    "可以点「重试」，或者改用临时托管发送（对方扫二维码或粘贴分享文字接收）。",
                offerFallback = true
            )
        }
    }

    /** Temporary-hosting route: Litterbox + QR / share text, no pickup code. */
    private fun uploadViaTemporaryHosting(packedFile: PackedFile) {
        try {
            val url = LitterboxUploader.upload(packedFile.file, ::reportProgress)
            val payload = payloadFor(packedFile, downloadUrl = url)
            showSuccess(payload, pickupCode = null)
        } catch (e: Exception) {
            showError(
                "没有发出去：${e.message ?: "网络出了点问题"}。检查一下网络，点重试再试一次。",
                offerFallback = false
            )
        }
    }

    private fun showSuccess(payload: TransferPayload, pickupCode: String?) {
        val text = PayloadCodec.shareText(payload, pickupCode)
        runOnUiThread {
            working = false
            progressBar.visibility = View.GONE
            resultLayout.visibility = View.VISIBLE
            if (pickupCode != null) {
                statusText.text = "上传完成！72 小时内有效，过期就没了，抓紧让对方接收。"
                codeLabel.visibility = View.VISIBLE
                codeText.visibility = View.VISIBLE
                codeText.text = pickupCode
            } else {
                // Temporary-hosting route: there is no pickup code, so the
                // giant code area must not pretend there is one. The share
                // text (or the QR below) is the only way across — say so.
                statusText.text = "上传完成！这个发送方式没有取件码。" +
                    "点下面「发给微信 / QQ 好友」把文字发给对方，对方在接收页粘贴就能收到；" +
                    "对方也可以用接收页的扫码来扫这个二维码。"
                codeLabel.visibility = View.GONE
                codeText.text = ""
                codeText.visibility = View.GONE
            }
            shareText = text
            try {
                qrImage.setImageBitmap(QrUtil.bitmapFor(text))
            } catch (e: Exception) {
                qrImage.setImageBitmap(null)
            }
        }
    }

    private fun showError(message: String, offerFallback: Boolean) {
        runOnUiThread {
            working = false
            progressBar.visibility = View.GONE
            statusText.text = message
            retryButton.visibility = View.VISIBLE
            fallbackButton.visibility = if (offerFallback) View.VISIBLE else View.GONE
        }
    }
}
