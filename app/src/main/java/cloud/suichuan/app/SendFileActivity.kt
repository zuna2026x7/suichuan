package cloud.suichuan.app

import android.content.Intent
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.net.BackendClient
import cloud.suichuan.app.net.CancelToken
import cloud.suichuan.app.net.LitterboxUploader
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.ErrorText
import cloud.suichuan.app.util.CodeUi
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.PayloadCodec
import cloud.suichuan.app.util.QrUtil
import cloud.suichuan.app.util.Sha256
import java.io.File
import kotlin.concurrent.thread

/**
 * Sends one ordinary file (document, image, video, archive, …) through the
 * same pickup-code / QR / share-text flow as an app transfer.
 *
 * Differences from [ShareActivity]:
 * - The file comes from the system picker (ACTION_OPEN_DOCUMENT), not from
 *   an installed app, so there is no packing step — the picked file is
 *   copied into our cache, hashed, and uploaded as-is.
 * - The payload carries kind="file" + mimeType, so the receiver saves it
 *   to Downloads instead of installing it. An .apk picked here is still
 *   just a file: it is saved, not installed.
 * - Size limits: the pickup backend caps a single file at 100MB; the
 *   Litterbox fallback caps it at 1GB (see [MAX_SEND_BYTES]).
 */
class SendFileActivity : AppCompatActivity() {

    companion object {
        /** Litterbox cap: anything bigger cannot go out by either route. */
        private const val MAX_SEND_BYTES = 1073741824L // 1 GiB
        /** Pickup backend cap: bigger files must use the fallback route. */
        private const val BACKEND_MAX_BYTES = 100L * 1024L * 1024L // 100 MiB
    }

    private lateinit var fileNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var confirmLayout: LinearLayout
    private lateinit var confirmDetail: TextView
    private lateinit var cancelUploadButton: Button
    private lateinit var retryButton: Button
    private lateinit var fallbackButton: Button
    private lateinit var uploadLogButton: Button
    private lateinit var resultLayout: LinearLayout
    private lateinit var codeLabel: TextView
    private lateinit var codeText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var shareButton: Button

    private var pickedFile: File? = null
    private var pickedName: String = ""
    private var pickedMime: String = ""
    private var pickedSize: Long = 0L
    private var pickedSha256: String = ""
    private var shareText: String = ""
    private var working = false

    /** Set by the Cancel button; the worker thread checks it before each step. */
    @Volatile
    private var cancelled = false

    /** Cancels the in-flight HTTP calls, not just the next step. */
    private var cancelToken: CancelToken? = null

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) prepareFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_send_file)

        fileNameText = findViewById(R.id.text_file_name)
        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress_upload)
        confirmLayout = findViewById(R.id.layout_confirm)
        confirmDetail = findViewById(R.id.text_confirm_detail)
        cancelUploadButton = findViewById(R.id.button_cancel_upload)
        cancelUploadButton.setOnClickListener {
            cancelled = true
            cancelToken?.cancel()
            working = false
            AppLog.log("SENDFILE", "用户取消了发送")
            finish()
        }
        findViewById<Button>(R.id.button_pick_file).setOnClickListener {
            if (working) return@setOnClickListener
            try {
                pickLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                statusText.text = "这部手机上没有找到文件选择器。"
            }
        }
        findViewById<Button>(R.id.button_start_send).setOnClickListener {
            startWork(forceTemporary = false)
        }
        findViewById<Button>(R.id.button_cancel_confirm).setOnClickListener {
            finish()
        }
        retryButton = findViewById(R.id.button_retry)
        fallbackButton = findViewById(R.id.button_fallback)
        uploadLogButton = findViewById(R.id.button_upload_log)
        uploadLogButton.setOnClickListener { LogUploader.uploadFrom(this) }
        resultLayout = findViewById(R.id.layout_result)
        codeLabel = findViewById(R.id.text_code_label)
        codeText = findViewById(R.id.text_code)
        qrImage = findViewById(R.id.image_qr)
        shareButton = findViewById(R.id.button_share)

        retryButton.setOnClickListener { startWork(forceTemporary = false) }
        fallbackButton.setOnClickListener { startWork(forceTemporary = true) }
        findViewById<Button>(R.id.button_done).setOnClickListener {
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
    }

    /** Copies the picked document into our cache and hashes it. */
    private fun prepareFile(uri: Uri) {
        // Take a persistable read grant so the copy below can open the URI.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Not all pickers grant persistable access; a one-shot read
            // still works for the copy below.
        }
        statusText.text = "正在读取这个文件…"
        confirmLayout.visibility = View.GONE
        thread {
            try {
                val (displayName, _, mime) = queryDisplayInfo(uri)
                val safeName = displayName.ifBlank { "file" }
                    .replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim()
                    .ifBlank { "file" }
                val dir = File(cacheDir, "send-file").apply { mkdirs() }
                val target = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("打不开这个文件。")
                val sha = Sha256.ofFile(target)
                val actualSize = target.length()
                runOnUiThread {
                    pickedFile = target
                    pickedName = displayName.ifBlank { safeName }
                    pickedMime = mime.ifBlank { guessMime(safeName) }
                    pickedSize = actualSize
                    pickedSha256 = sha
                    fileNameText.text = pickedName
                    showConfirm()
                }
            } catch (e: Exception) {
                AppLog.log("SENDFILE", "读取文件失败", e)
                runOnUiThread {
                    statusText.text = "没有读到这个文件：" + ErrorText.of(e, "读取时出错了。") + "换一个文件试试。"
                }
            }
        }
    }

    private fun queryDisplayInfo(uri: Uri): Triple<String, Long, String> {
        var name = ""
        var size = 0L
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                if (it.moveToFirst()) {
                    if (nameIdx >= 0) name = it.getString(nameIdx) ?: ""
                    if (sizeIdx >= 0) size = it.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // Fall back to the URI's last segment below.
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
        val mime = try {
            contentResolver.getType(uri) ?: ""
        } catch (e: Exception) {
            ""
        }
        return Triple(name, size, mime)
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
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

    private fun showConfirm() {
        val lines = mutableListOf(
            "要发送：$pickedName",
            "大小：${FormatUtil.size(pickedSize)}"
        )
        lines.add("类型：" + ErrorText.mimeLabel(pickedMime, pickedName))
        if (isOnCellular()) {
            lines.add("现在用的是手机流量，发送大约要用这么多流量。")
        } else {
            lines.add("发送大约要用这么多流量（建议在 Wi-Fi 下发送）。")
        }
        if (pickedSize > BACKEND_MAX_BYTES) {
            lines.add("超过 100MB，会改用临时托管发送：没有取件码，对方扫二维码或粘贴分享文字接收（上限 1GB）。")
        }
        confirmDetail.text = lines.joinToString("\n")
        confirmLayout.visibility = View.VISIBLE
        statusText.text = "确认一下，再开始发送。"
    }

    private fun payloadFor(downloadUrl: String): TransferPayload {
        return TransferPayload(
            appName = pickedName,
            packageName = "",
            versionName = "",
            sizeBytes = pickedSize,
            sha256 = pickedSha256,
            fileName = pickedName,
            downloadUrl = downloadUrl,
            kind = "file",
            mimeType = pickedMime.ifBlank { "application/octet-stream" }
        )
    }

    private fun reportProgress(written: Long, total: Long) {
        if (total > 0) {
            val percent = (written * 100 / total).toInt()
            runOnUiThread { progressBar.progress = percent }
        }
    }

    private fun startWork(forceTemporary: Boolean) {
        val file = pickedFile ?: run {
            Toast.makeText(this, "先选一个文件。", Toast.LENGTH_SHORT).show()
            return
        }
        if (working) return
        working = true
        cancelled = false
        cancelToken = CancelToken()
        confirmLayout.visibility = View.GONE
        retryButton.visibility = View.GONE
        fallbackButton.visibility = View.GONE
        uploadLogButton.visibility = View.GONE
        resultLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        cancelUploadButton.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = "正在上传「$pickedName」…"

        val base = BuildConfig.TRANSFER_API_BASE
        // Files over the backend cap skip the backend entirely — the
        // server would refuse them with 413 anyway.
        val useBackend = !forceTemporary && base.isNotBlank() && pickedSize <= BACKEND_MAX_BYTES

        thread {
            if (cancelled) return@thread
            if (pickedSize > MAX_SEND_BYTES) {
                AppLog.log("SENDFILE", "超过发送上限：$pickedSize > $MAX_SEND_BYTES")
                showError(
                    "这个文件有 ${FormatUtil.size(pickedSize)}，超过了 1GB 的发送上限，暂时发不了。",
                    offerFallback = false
                )
                return@thread
            }
            if (cancelled) return@thread
            runOnUiThread {
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                statusText.text = "正在上传（${FormatUtil.size(pickedSize)}）…"
            }
            if (useBackend) {
                uploadViaBackend(base, file)
            } else {
                if (!forceTemporary && base.isNotBlank() && pickedSize > BACKEND_MAX_BYTES) {
                    // Oversize for the backend: tell the user why the
                    // fallback is the route, then use it.
                    runOnUiThread { statusText.text = "文件超过 100MB，改用临时托管发送…" }
                }
                uploadViaTemporaryHosting(file)
            }
        }
    }

    /** Backend route: pickup code + the file in our own storage. */
    private fun uploadViaBackend(base: String, file: File) {
        try {
            if (cancelled) return
            val meta = payloadFor(downloadUrl = "")
            val token = cancelToken
            val created = BackendClient.createTransfer(base, meta, token)
            if (cancelled) return
            BackendClient.uploadFile(
                base, created.uploadToken, file, ::reportProgress,
                mimeType = pickedMime.ifBlank { "application/octet-stream" },
                cancel = token
            )
            val payload = payloadFor(
                downloadUrl = base.trimEnd('/') + "/f/" + created.code
            )
            AppLog.log("SENDFILE", "取件服务发送成功，取件码=${created.code} 文件=$pickedName")
            showSuccess(payload, created.code)
        } catch (e: Exception) {
            if (cancelled) return
            AppLog.log("SENDFILE", "取件服务发送失败", e)
            showError(
                "用取件服务发送失败了：" + ErrorText.of(e, "网络出了点问题。") +
                    "可以点「重试」，或者改用临时托管发送（对方扫二维码或粘贴分享文字接收）。",
                offerFallback = true
            )
        }
    }

    /** Temporary-hosting route: Litterbox + QR / share text, no pickup code. */
    private fun uploadViaTemporaryHosting(file: File) {
        try {
            if (cancelled) return
            val url = LitterboxUploader.upload(file, ::reportProgress, cancelToken)
            val payload = payloadFor(downloadUrl = url)
            AppLog.log("SENDFILE", "临时托管发送成功，地址=${AppLog.redactUrl(url)} 文件=$pickedName")
            showSuccess(payload, pickupCode = null)
        } catch (e: Exception) {
            if (cancelled) return
            AppLog.log("SENDFILE", "临时托管发送失败", e)
            showError(
                "没有发出去：" + ErrorText.of(e, "网络出了点问题。") + "检查一下网络，点重试再试一次。",
                offerFallback = false
            )
        }
    }

    override fun onDestroy() {
        // Back / swipe away while uploading: stop the upload for real
        // instead of letting it finish in the background.
        if (isFinishing && working) {
            cancelled = true
            cancelToken?.cancel()
        }
        super.onDestroy()
    }

    private fun showSuccess(payload: TransferPayload, pickupCode: String?) {
        val text = PayloadCodec.shareText(payload, pickupCode)
        runOnUiThread {
            if (cancelled || isFinishing || isDestroyed) return@runOnUiThread
            working = false
            cancelUploadButton.visibility = View.GONE
            progressBar.visibility = View.GONE
            resultLayout.visibility = View.VISIBLE
            if (pickupCode != null) {
                statusText.text = "上传完成！把取件码告诉对方，或者让对方扫下面的二维码。"
                codeLabel.visibility = View.VISIBLE
                codeText.visibility = View.VISIBLE
                CodeUi.bind(
                    this, pickupCode, codeText,
                    findViewById(R.id.text_code_expiry), findViewById(R.id.button_copy_code)
                )
            } else {
                statusText.text = "上传完成！这个发送方式没有取件码。" +
                    "点下面「发给微信 / QQ 好友」把文字发给对方，对方在接收页粘贴就能收到；" +
                    "对方也可以用接收页的扫码来扫这个二维码。"
                codeLabel.visibility = View.GONE
                CodeUi.clear(codeText, findViewById(R.id.text_code_expiry), findViewById(R.id.button_copy_code))
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
            if (cancelled || isFinishing || isDestroyed) return@runOnUiThread
            working = false
            cancelUploadButton.visibility = View.GONE
            progressBar.visibility = View.GONE
            statusText.text = message
            retryButton.visibility = View.VISIBLE
            fallbackButton.visibility = if (offerFallback) View.VISIBLE else View.GONE
            uploadLogButton.visibility = View.VISIBLE
        }
    }
}
