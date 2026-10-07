package cloud.suichuan.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.install.InstallPermissionHelper
import cloud.suichuan.app.install.Installer
import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.net.BackendClient
import cloud.suichuan.app.net.Downloader
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.PayloadCodec
import cloud.suichuan.app.util.Sha256
import java.io.File
import kotlin.concurrent.thread

/**
 * Receives a transfer three ways: a 6-digit pickup code (needs the code
 * worker), a pasted share message, or a bare download link. The file lands
 * in our private cache — the user never sees a file name, so nothing can be
 * renamed into something uninstallable.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var inputEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var confirmLayout: LinearLayout
    private lateinit var confirmName: TextView
    private lateinit var confirmDetail: TextView

    private var downloadedFile: File? = null
    private var downloadedKind: ApkInspector.Kind = ApkInspector.Kind.INVALID
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        inputEdit = findViewById(R.id.edit_input)
        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress_download)
        confirmLayout = findViewById(R.id.layout_confirm)
        confirmName = findViewById(R.id.text_confirm_name)
        confirmDetail = findViewById(R.id.text_confirm_detail)

        findViewById<Button>(R.id.button_fetch).setOnClickListener {
            val raw = inputEdit.text.toString()
            if (raw.isBlank()) {
                statusText.text = "先粘贴取件码、分享文字或链接，再点开始接收。"
            } else {
                resolveAndDownload(raw.trim())
            }
        }
        findViewById<Button>(R.id.button_install).setOnClickListener {
            val file = downloadedFile
            if (file == null) {
                Toast.makeText(this, "还没有下载好，先完成上面的接收。", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!InstallPermissionHelper.ensure(this)) return@setOnClickListener
            try {
                Installer.install(this, file, downloadedKind)
                Toast.makeText(this, "正在打开系统安装界面…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "安装没有打开：${e.message ?: "未知问题"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resolveAndDownload(raw: String) {
        if (busy) return
        busy = true
        confirmLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在查找这个应用…"

        thread {
            try {
                val payload = resolvePayload(raw)
                val dir = File(cacheDir, "receive").apply { mkdirs() }
                val safeName = payload.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "package.bin" }
                val target = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    statusText.text = "正在下载「${payload.appName.ifBlank { "应用" }}」（${FormatUtil.size(payload.sizeBytes)}）…"
                }
                Downloader.download(payload.downloadUrl, target) { written, total ->
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        runOnUiThread { progressBar.progress = percent }
                    }
                }
                if (payload.sha256.isNotBlank()) {
                    runOnUiThread { statusText.text = "下载完成，正在检查文件完整性…" }
                    val actual = Sha256.ofFile(target)
                    if (!actual.equals(payload.sha256, ignoreCase = true)) {
                        target.delete()
                        throw IllegalStateException("下载到的文件不完整（校验没通过）。网络可能不稳定，重新接收一次。")
                    }
                }
                val inspection = ApkInspector.inspect(target)
                if (inspection.kind == ApkInspector.Kind.INVALID) {
                    target.delete()
                    throw IllegalStateException("下载到的东西不是有效的安装包。让对方重新发送一次。")
                }
                runOnUiThread {
                    busy = false
                    progressBar.visibility = View.GONE
                    downloadedFile = target
                    downloadedKind = inspection.kind
                    confirmLayout.visibility = View.VISIBLE
                    confirmName.text = payload.appName.ifBlank { "收到的应用" }
                    val parts = mutableListOf<String>()
                    if (payload.versionName.isNotBlank()) parts.add("版本 " + payload.versionName)
                    parts.add(FormatUtil.size(if (payload.sizeBytes > 0) payload.sizeBytes else target.length()))
                    confirmDetail.text = parts.joinToString(" · ")
                    statusText.text = "下载完成，检查通过。"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    progressBar.visibility = View.GONE
                    statusText.text = e.message ?: "接收失败，重新试一次。"
                }
            }
        }
    }

    /** Fetches a transfer by code from our backend, checking it is ready. */
    private fun fetchByCode(code: String): TransferPayload {
        val base = BuildConfig.TRANSFER_API_BASE
        if (base.isBlank()) {
            throw IllegalStateException("这个版本还没有配置取件码服务。请让对方把分享的整段文字发给你，粘贴到这里接收。")
        }
        val fetched = BackendClient.fetchTransfer(base, code)
        if (!fetched.ready) {
            throw IllegalStateException("对方还没上传完，请稍后再试。等对方那边显示上传完成后，再回来点开始接收。")
        }
        return fetched.payload
    }

    /** Finds a "取件码：123456" (or a lone 6-digit line) inside pasted text. */
    private fun extractPickupCode(text: String): String? {
        Regex("取件码[:：]?\\s*(\\d{6})").find(text)?.let { return it.groupValues[1] }
        return text.lines()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("^\\d{6}$")) }
    }

    private fun resolvePayload(raw: String): TransferPayload {
        // (a) 6-digit pickup code, typed on its own.
        if (raw.matches(Regex("^\\d{6}$"))) {
            return fetchByCode(raw)
        }
        // (b) Pasted share text that carries a pickup code: the code wins,
        // because the newest metadata (and the file) live behind it.
        if (BuildConfig.TRANSFER_API_BASE.isNotBlank()) {
            extractPickupCode(raw)?.let { return fetchByCode(it) }
        }
        // (c) Pasted share text containing the encoded payload.
        PayloadCodec.extractFromText(raw)?.let { return it }
        // (d) Bare download link.
        if (raw.startsWith("https://") || raw.startsWith("http://")) {
            val url = raw.lines().first().trim()
            val guessedName = url.substringAfterLast('/').substringBefore('?')
            return TransferPayload(
                appName = "",
                packageName = "",
                versionName = "",
                sizeBytes = 0L,
                sha256 = "",
                fileName = guessedName.ifBlank { "package.bin" },
                downloadUrl = url
            )
        }
        throw IllegalStateException("这段内容看不懂。请粘贴对方分享的整段文字、取件码或下载链接。")
    }
}
