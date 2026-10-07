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
 * button. The file goes to Litterbox for 72h; the pickup code only exists
 * when the optional code worker has been deployed (TRANSFER_API_BASE set).
 */
class ShareActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var resultLayout: LinearLayout
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
        resultLayout = findViewById(R.id.layout_result)
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

        retryButton.setOnClickListener { startWork() }
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
                .setPositiveButton("继续发送") { _, _ -> startWork() }
                .setNegativeButton("先不发") { _, _ ->
                    statusText.text = "已取消。连上 Wi-Fi 后再来发，就不耗流量了。"
                    progressBar.visibility = View.GONE
                }
                .show()
        } else {
            startWork()
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

    private fun startWork() {
        val app = targetApp ?: return
        if (working) return
        working = true
        retryButton.visibility = View.GONE
        resultLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在打包「${app.label}」…"

        thread {
            try {
                val packedFile = packed ?: Packager.pack(app, cacheDir).also { packed = it }
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    statusText.text = "正在上传（${FormatUtil.size(packedFile.sizeBytes)}）…"
                }
                val url = LitterboxUploader.upload(packedFile.file) { written, total ->
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        runOnUiThread { progressBar.progress = percent }
                    }
                }
                val payload = TransferPayload(
                    appName = app.label,
                    packageName = app.packageName,
                    versionName = app.versionName,
                    sizeBytes = packedFile.sizeBytes,
                    sha256 = packedFile.sha256,
                    fileName = packedFile.fileName,
                    downloadUrl = url
                )
                var code: String? = null
                val base = BuildConfig.TRANSFER_API_BASE
                if (base.isNotBlank()) {
                    code = try {
                        BackendClient.register(base, payload)
                    } catch (e: Exception) {
                        null // Codes are a bonus; QR / share text still work.
                    }
                }
                val text = PayloadCodec.shareText(payload, code)
                runOnUiThread {
                    working = false
                    progressBar.visibility = View.GONE
                    statusText.text = "上传完成！72 小时内有效，过期就没了，抓紧让对方接收。"
                    resultLayout.visibility = View.VISIBLE
                    codeText.text = code ?: "扫码接收"
                    shareText = text
                    try {
                        qrImage.setImageBitmap(QrUtil.bitmapFor(text))
                    } catch (e: Exception) {
                        qrImage.setImageBitmap(null)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    working = false
                    progressBar.visibility = View.GONE
                    statusText.text = "没有发出去：${e.message ?: "网络出了点问题"}。检查一下网络，点重试再试一次。"
                    retryButton.visibility = View.VISIBLE
                }
            }
        }
    }
}
