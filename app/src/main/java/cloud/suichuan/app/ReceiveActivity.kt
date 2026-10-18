package cloud.suichuan.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cloud.suichuan.app.install.InstallPermissionHelper
import cloud.suichuan.app.install.InstallResultBus
import cloud.suichuan.app.install.Installer
import cloud.suichuan.app.model.TransferPayload
import cloud.suichuan.app.net.BackendClient
import cloud.suichuan.app.net.CancelToken
import cloud.suichuan.app.net.Downloader
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.ErrorText
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.PayloadCodec
import cloud.suichuan.app.util.Sha256
import java.io.File
import kotlin.concurrent.thread

/**
 * Receives a transfer four ways: a 6-digit pickup code (needs the code
 * worker), a pasted share message, a bare download link, or an in-app QR
 * scan. Scan and paste only fill the input box — the user always starts
 * the transfer explicitly with "开始接收", which funnels into
 * [processInput]. The file lands in our private cache — the user never
 * sees a file name, so nothing can be renamed into something uninstallable.
 *
 * A code can also resolve to a TEXT transfer: the message arrives inside
 * the metadata, so instead of downloading anything we show the text card
 * (selectable text + a copy button) and stop there.
 *
 * A code can also resolve to a FILE transfer (kind == "file"): the file is
 * downloaded and SHA-checked like an app package, but it is never
 * installed — the receiver saves it to Downloads and optionally opens it.
 * An .apk that arrived as a file stays a file: it is saved, not installed,
 * so it can never be confused with an app transfer.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var inputEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var confirmLayout: LinearLayout
    private lateinit var confirmName: TextView
    private lateinit var confirmDetail: TextView
    private lateinit var uploadLogButton: Button
    private lateinit var textLayout: LinearLayout
    private lateinit var receivedTextView: TextView
    private lateinit var fileLayout: LinearLayout
    private lateinit var fileNameText: TextView
    private lateinit var fileDetailText: TextView
    private lateinit var fileApkNote: TextView

    private var receivedTextContent: String = ""
    private var downloadedFile: File? = null
    private var downloadedKind: ApkInspector.Kind = ApkInspector.Kind.INVALID
    private var downloadedMimeType: String = ""
    private var downloadedFileName: String = ""
    private var downloadedIsFile: Boolean = false
    private var savedFileUri: Uri? = null
    private var busy = false
    private var downloadedPackageName: String = ""

    /** Cancels the in-flight lookup / download for real. */
    private var cancelToken: CancelToken? = null
    private lateinit var cancelDownloadButton: Button
    private lateinit var installResultText: TextView
    private lateinit var openAppButton: Button
    private lateinit var installButton: Button

    /** Shows the system install verdict as a lasting result on this page. */
    private val installListener = InstallResultBus.Listener { result -> showInstallResult(result) }

    /** Android 8-9: ask for storage access, then retry the save. */
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                saveDownloadedFile()
            } else {
                statusText.text = "没有存储权限，存不进「下载」文件夹。到系统设置里给随传开启存储权限后，再点「保存到下载」。"
            }
        }

    // QR scanner, registered as a field so the launcher exists before any
    // click handler can run. A null contents means the scan produced
    // nothing: either the user backed out (stay quiet) or the camera
    // permission was denied inside the scanner (offer the paste route).
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        when {
            // Unified manual flow: scanning only fills the input box.
            // The user reviews it and taps "开始接收" to start.
            contents != null -> {
                inputEdit.setText(contents)
                Toast.makeText(this, "已填入，点开始接收", Toast.LENGTH_SHORT).show()
            }
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED ->
                Toast.makeText(
                    this,
                    "没有相机权限，扫不了码。可以让对方把分享文字发给你，复制后点「粘贴剪贴板内容」，或者直接输入取件码。",
                    Toast.LENGTH_LONG
                ).show()
            else -> Unit // user cancelled the scan: stay quiet
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_receive)

        inputEdit = findViewById(R.id.edit_input)
        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress_download)
        confirmLayout = findViewById(R.id.layout_confirm)
        confirmName = findViewById(R.id.text_confirm_name)
        confirmDetail = findViewById(R.id.text_confirm_detail)
        uploadLogButton = findViewById(R.id.button_upload_log)
        uploadLogButton.setOnClickListener { LogUploader.uploadFrom(this) }
        cancelDownloadButton = findViewById(R.id.button_cancel_download)
        cancelDownloadButton.setOnClickListener { cancelReceive() }
        installResultText = findViewById(R.id.text_install_result)
        openAppButton = findViewById(R.id.button_open_app)
        openAppButton.setOnClickListener { openInstalledApp() }
        installButton = findViewById(R.id.button_install)
        textLayout = findViewById(R.id.layout_text)
        receivedTextView = findViewById(R.id.text_received_text)
        fileLayout = findViewById(R.id.layout_file)
        fileNameText = findViewById(R.id.text_file_name)
        fileDetailText = findViewById(R.id.text_file_detail)
        fileApkNote = findViewById(R.id.text_file_apk_note)
        findViewById<Button>(R.id.button_done_file).setOnClickListener { goHome() }
        findViewById<Button>(R.id.button_save_file).setOnClickListener {
            saveDownloadedFile()
        }
        findViewById<Button>(R.id.button_open_file).setOnClickListener {
            openDownloadedFile()
        }
        findViewById<Button>(R.id.button_copy_text).setOnClickListener {
            val content = receivedTextContent
            if (content.isEmpty()) return@setOnClickListener
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("随传收到的文字", content))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.button_fetch).setOnClickListener {
            processInput(inputEdit.text.toString())
        }
        // Same way home as the send screens: clear the receive flow off
        // the stack so Back from home exits the app.
        findViewById<Button>(R.id.button_done).setOnClickListener { goHome() }
        findViewById<Button>(R.id.button_done_text).setOnClickListener { goHome() }
        findViewById<Button>(R.id.button_scan).setOnClickListener {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("把取件二维码放进框里")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setOrientationLocked(false)
                .setCaptureActivity(SuichuanCaptureActivity::class.java)
            scanLauncher.launch(options)
        }
        findViewById<Button>(R.id.button_paste).setOnClickListener {
            pasteFromClipboard()
        }
        installButton.setOnClickListener {
            val file = downloadedFile
            if (file == null) {
                Toast.makeText(this, "还没有下载好，先完成上面的接收。", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!InstallPermissionHelper.ensure(this)) return@setOnClickListener
            try {
                installResultText.visibility = View.GONE
                openAppButton.visibility = View.GONE
                Installer.install(this, file, downloadedKind)
                statusText.text = "正在打开系统安装界面，按提示点「安装」。"
            } catch (e: Exception) {
                AppLog.log("RECV", "打开系统安装失败", e)
                showInstallResult(
                    InstallResultBus.Result(
                        success = false,
                        packageName = null,
                        message = "安装没有打开：" + ErrorText.of(e, "系统安装程序出了问题。")
                    )
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        InstallResultBus.register(installListener)
    }

    override fun onStop() {
        InstallResultBus.unregister(installListener)
        super.onStop()
    }

    override fun onDestroy() {
        // Leaving the page stops the download instead of letting it run on.
        if (isFinishing) cancelToken?.cancel()
        super.onDestroy()
    }

    private fun cancelReceive() {
        cancelToken?.cancel()
        AppLog.log("RECV", "用户取消了接收")
        busy = false
        progressBar.visibility = View.GONE
        cancelDownloadButton.visibility = View.GONE
        statusText.text = "已取消接收。需要的话再点「开始接收」。"
    }

    private fun showInstallResult(result: InstallResultBus.Result) {
        if (isFinishing || isDestroyed) return
        installResultText.visibility = View.VISIBLE
        if (result.success) {
            installResultText.text = "✓ 安装完成"
            installResultText.setTextColor(ContextCompat.getColor(this, R.color.suichuan_success))
            statusText.text = "「${confirmName.text}」已经装好了。"
            installButton.visibility = View.GONE
            val pkg = result.packageName?.takeIf { it.isNotBlank() } ?: downloadedPackageName
            openAppButton.visibility =
                if (pkg.isNotBlank() && packageManager.getLaunchIntentForPackage(pkg) != null) View.VISIBLE else View.GONE
            if (pkg.isNotBlank()) downloadedPackageName = pkg
        } else {
            installResultText.text = if (result.userCancelled) "安装已取消" else "✗ 没有装上"
            installResultText.setTextColor(ContextCompat.getColor(this, R.color.suichuan_error))
            statusText.text = result.message
            installButton.visibility = View.VISIBLE
            installButton.text = "重新安装"
            openAppButton.visibility = View.GONE
            if (!result.userCancelled) uploadLogButton.visibility = View.VISIBLE
        }
    }

    private fun openInstalledApp() {
        val launch = downloadedPackageName.takeIf { it.isNotBlank() }
            ?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            Toast.makeText(this, "这个应用没有可以打开的界面，去桌面找找它。", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(launch)
    }

    /** Returns to the home screen, clearing the receive flow off the stack. */
    private fun goHome() {
        val home = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(home)
        finish()
    }

    /**
     * The single entry point for everything the receiver provides: text
     * typed into the input box, a scanned QR code, or clipboard content.
     * All of them behave identically from here on.
     */
    private fun processInput(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            statusText.text = "先粘贴取件码、分享文字或链接，再点开始接收。"
        } else {
            resolveAndDownload(trimmed)
        }
    }

    /** Reads the clipboard and drops its text into the input box only. */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard
            ?.takeIf { it.hasPrimaryClip() }
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板是空的，先去复制取件码或分享文字", Toast.LENGTH_LONG).show()
            return
        }
        inputEdit.setText(text)
        Toast.makeText(this, "已粘贴，点开始接收", Toast.LENGTH_SHORT).show()
    }

    private fun resolveAndDownload(raw: String) {
        if (busy) return
        busy = true
        confirmLayout.visibility = View.GONE
        fileLayout.visibility = View.GONE
        textLayout.visibility = View.GONE
        uploadLogButton.visibility = View.GONE
        installResultText.visibility = View.GONE
        openAppButton.visibility = View.GONE
        installButton.visibility = View.VISIBLE
        installButton.text = "安装"
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        cancelDownloadButton.visibility = View.VISIBLE
        statusText.text = "正在查找…"
        val token = CancelToken().also { cancelToken = it }

        thread {
            var target: File? = null
            try {
                val resolved = resolvePayload(raw, token)
                token.throwIfCancelled()
                if (resolved.kind == "text") {
                    // A text transfer carries its message in the metadata:
                    // there is no file to download or install — just show it.
                    val message = resolved.text.orEmpty()
                    AppLog.log(
                        "RECV",
                        "收到文字取件：长度=${message.toByteArray(Charsets.UTF_8).size} 字节"
                    )
                    runOnUiThread { if (!token.isCancelled) showTextTransfer(message) }
                    return@thread
                }
                val payload = resolved.payload
                val isFile = resolved.kind == "file" || payload.kind == "file"
                val mimeType = resolved.mimeType?.takeIf { it.isNotBlank() }
                    ?: payload.mimeType.takeIf { it.isNotBlank() }
                    ?: ""
                AppLog.log(
                    "RECV",
                    "已解析：kind=${if (isFile) "file" else "app"} 应用=${payload.appName} " +
                        "包名=${payload.packageName} 版本=${payload.versionName} " +
                        "size=${payload.sizeBytes} 有sha=${payload.sha256.isNotBlank()} " +
                        "地址=${AppLog.redactUrl(payload.downloadUrl)}"
                )
                val dir = File(cacheDir, "receive").apply { mkdirs() }
                val safeName = payload.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "package.bin" }
                val file = File(dir, System.currentTimeMillis().toString() + "_" + safeName)
                target = file
                val displayName = payload.appName.ifBlank {
                    if (isFile) payload.fileName.ifBlank { "收到的文件" } else "应用"
                }
                runOnUiThread {
                    if (token.isCancelled) return@runOnUiThread
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                    statusText.text = "正在下载「$displayName」（${FormatUtil.size(payload.sizeBytes)}）…"
                }
                Downloader.download(payload.downloadUrl, file, token) { written, total ->
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        runOnUiThread {
                            if (token.isCancelled) return@runOnUiThread
                            progressBar.progress = percent
                            statusText.text = "正在下载「$displayName」：" +
                                "${FormatUtil.size(written)} / ${FormatUtil.size(total)}（$percent%）"
                        }
                    }
                }
                token.throwIfCancelled()
                if (payload.sha256.isNotBlank()) {
                    runOnUiThread { statusText.text = "下载完成，正在检查文件完整性…" }
                    val actual = Sha256.ofFile(file)
                    if (!actual.equals(payload.sha256, ignoreCase = true)) {
                        file.delete()
                        throw IllegalStateException("下载到的文件不完整（校验没通过）。网络可能不稳定，重新接收一次。")
                    }
                    AppLog.log("RECV", "sha256 校验通过")
                }
                if (isFile) {
                    // Ordinary file: no APK inspection, no install. Show
                    // the save / open card.
                    runOnUiThread {
                        if (!token.isCancelled) showFileTransfer(payload, mimeType, file)
                    }
                    return@thread
                }
                val inspection = ApkInspector.inspect(file)
                AppLog.log("RECV", "文件检查结果 kind=${inspection.kind}")
                if (inspection.kind == ApkInspector.Kind.INVALID) {
                    file.delete()
                    throw IllegalStateException("下载到的东西不是有效的安装包。让对方重新发送一次。")
                }
                runOnUiThread {
                    if (token.isCancelled) return@runOnUiThread
                    busy = false
                    progressBar.visibility = View.GONE
                    cancelDownloadButton.visibility = View.GONE
                    downloadedPackageName = payload.packageName
                    downloadedFile = file
                    downloadedKind = inspection.kind
                    downloadedIsFile = false
                    confirmLayout.visibility = View.VISIBLE
                    confirmName.text = payload.appName.ifBlank { "收到的应用" }
                    val parts = mutableListOf<String>()
                    if (payload.versionName.isNotBlank()) parts.add("版本 " + payload.versionName)
                    parts.add(FormatUtil.size(if (payload.sizeBytes > 0) payload.sizeBytes else file.length()))
                    confirmDetail.text = parts.joinToString(" · ")
                    statusText.text = "下载完成，检查通过。"
                }
            } catch (e: Exception) {
                if (token.isCancelled) {
                    target?.delete()
                    return@thread
                }
                val shown = ErrorText.of(e, "接收失败，重新试一次。")
                AppLog.log("RECV", "接收失败（用户看到：$shown）", e)
                runOnUiThread {
                    busy = false
                    progressBar.visibility = View.GONE
                    cancelDownloadButton.visibility = View.GONE
                    statusText.text = shown
                    uploadLogButton.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Shows a received ordinary file: save to Downloads / open. */
    private fun showFileTransfer(payload: TransferPayload, mimeType: String, target: File) {
        busy = false
        progressBar.visibility = View.GONE
        cancelDownloadButton.visibility = View.GONE
        confirmLayout.visibility = View.GONE
        downloadedFile = target
        downloadedKind = ApkInspector.Kind.INVALID
        downloadedIsFile = true
        downloadedMimeType = mimeType.ifBlank { "application/octet-stream" }
        downloadedFileName = payload.fileName.ifBlank { payload.appName }.ifBlank { "收到的文件" }
        savedFileUri = null
        findViewById<Button>(R.id.button_save_file).apply {
            text = "保存到下载"
            isEnabled = true
        }
        val displayName = payload.appName.ifBlank { downloadedFileName }
        fileNameText.text = displayName
        val parts = mutableListOf<String>()
        parts.add(FormatUtil.size(if (payload.sizeBytes > 0) payload.sizeBytes else target.length()))
        parts.add(ErrorText.mimeLabel(downloadedMimeType, downloadedFileName))
        fileDetailText.text = parts.joinToString(" · ")
        // An .apk that arrived as a file is saved, never installed.
        val isApk = downloadedFileName.lowercase().endsWith(".apk") ||
            downloadedMimeType == "application/vnd.android.package-archive"
        fileApkNote.visibility = if (isApk) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.button_open_file).visibility =
            if (isApk) View.GONE else View.VISIBLE
        fileLayout.visibility = View.VISIBLE
        statusText.text = "下载完成，检查通过。"
    }

    /** Saves the downloaded file into the public Downloads folder. */
    private fun saveDownloadedFile(): Uri? {
        val file = downloadedFile
        if (file == null || !downloadedIsFile) {
            Toast.makeText(this, "还没有下载好，先完成上面的接收。", Toast.LENGTH_LONG).show()
            return null
        }
        savedFileUri?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "保存到「下载」需要存储权限，请在弹窗里允许。"
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return null
        }
        val name = downloadedFileName.ifBlank { "收到的文件" }
        return try {
            val uri = writeToDownloads(file, name, downloadedMimeType)
            savedFileUri = uri
            statusText.text = "已保存到手机的「下载」文件夹：$name"
            findViewById<Button>(R.id.button_save_file).apply {
                text = "已保存到下载"
                isEnabled = false
            }
            AppLog.log("RECV", "文件已保存到下载：$name mime=$downloadedMimeType")
            uri
        } catch (e: Exception) {
            AppLog.log("RECV", "保存到下载失败", e)
            statusText.text = "保存失败：" + ErrorText.of(e, "写入「下载」文件夹时出错了。") + "可以检查手机空间后再试。"
            null
        }
    }

    private fun writeToDownloads(file: File, displayName: String, mimeType: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("打不开下载文件夹。")
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("打不开下载文件夹。")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            var target = File(dir, displayName)
            // Don't silently overwrite an existing download.
            var n = 1
            while (target.exists()) {
                val base = displayName.substringBeforeLast('.', displayName)
                val ext = displayName.substringAfterLast('.', "")
                target = if (ext.isEmpty()) {
                    File(dir, "$base ($n)")
                } else {
                    File(dir, "$base ($n).$ext")
                }
                n++
            }
            file.copyTo(target, overwrite = false)
            return Uri.fromFile(target)
        }
    }

    /** Opens the saved file with the MIME type the sender recorded. */
    private fun openDownloadedFile() {
        val file = downloadedFile
        if (file == null || !downloadedIsFile) {
            Toast.makeText(this, "还没有下载好，先完成上面的接收。", Toast.LENGTH_LONG).show()
            return
        }
        val isApk = downloadedFileName.lowercase().endsWith(".apk") ||
            downloadedMimeType == "application/vnd.android.package-archive"
        if (isApk) {
            Toast.makeText(this, "这个 .apk 是经发送文件收到的，只保存不安装。想安装请让对方走发送应用重新发。", Toast.LENGTH_LONG).show()
            return
        }
        val uri = savedFileUri ?: saveDownloadedFile() ?: return
        val mime = downloadedMimeType.ifBlank { "application/octet-stream" }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(view, "打开文件"))
        } catch (e: Exception) {
            AppLog.log("RECV", "打开文件失败 mime=$mime", e)
            Toast.makeText(this, "这部手机上没有能打开这种文件的应用。文件已保存在下载里。", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows a received text transfer: the message plus a copy button. */
    private fun showTextTransfer(message: String) {
        busy = false
        progressBar.visibility = View.GONE
        cancelDownloadButton.visibility = View.GONE
        confirmLayout.visibility = View.GONE
        fileLayout.visibility = View.GONE
        receivedTextContent = message
        receivedTextView.text = message
        textLayout.visibility = View.VISIBLE
        statusText.text = "收到一段文字。可以长按选中一部分，或者点「复制全文」整段复制。"
    }

    /** Fetches a transfer by code from our backend, checking it is ready. */
    private fun fetchByCode(code: String, token: CancelToken?): BackendClient.FetchedTransfer {
        val base = BuildConfig.TRANSFER_API_BASE
        if (base.isBlank()) {
            throw IllegalStateException("这个版本还没有配置取件码服务。请让对方把分享的整段文字发给你，粘贴到这里接收。")
        }
        val fetched = BackendClient.fetchTransfer(base, code, token)
        AppLog.log(
            "RECV",
            "取件码 $code：ready=${fetched.ready} kind=${fetched.kind ?: "app"} " +
                "应用=${fetched.payload.appName} size=${fetched.payload.sizeBytes}"
        )
        if (!fetched.ready) {
            throw IllegalStateException("对方还没上传完，请稍后再试。等对方那边显示上传完成后，再回来点开始接收。")
        }
        return fetched
    }

    /** Finds a "取件码：123456" (or a lone 6-digit line) inside pasted text. */
    private fun extractPickupCode(text: String): String? {
        Regex("取件码[:：]?\\s*(\\d{6})").find(text)?.let { return it.groupValues[1] }
        return text.lines()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("^\\d{6}$")) }
    }

    private fun resolvePayload(raw: String, token: CancelToken?): BackendClient.FetchedTransfer {
        // (a) 6-digit pickup code, typed on its own. (A scanned text-transfer
        // QR lands here too: it encodes the bare code string.)
        // Spaces / dashes are tolerated: "123 456" is how the sender shows it.
        val compact = raw.replace(Regex("[\\s-]"), "")
        if (compact.matches(Regex("^\\d{6}$"))) {
            return fetchByCode(compact, token)
        }
        // (b) Pasted share text that carries a pickup code: the code wins,
        // because the newest metadata (and the file) live behind it.
        if (BuildConfig.TRANSFER_API_BASE.isNotBlank()) {
            extractPickupCode(raw)?.let { return fetchByCode(it, token) }
        }
        // (c) Pasted share text containing the encoded payload.
        // The payload carries its own kind (app/file), so pass it through
        // — a file share must not fall into the app/install path.
        PayloadCodec.extractFromText(raw)?.let {
            return BackendClient.FetchedTransfer(
                it,
                ready = true,
                kind = it.kind.takeIf { k -> k.isNotBlank() },
                mimeType = it.mimeType.takeIf { m -> m.isNotBlank() }
            )
        }
        // (d) Bare download link.
        if (raw.startsWith("https://") || raw.startsWith("http://")) {
            val url = raw.lines().first().trim()
            val guessedName = url.substringAfterLast('/').substringBefore('?')
            return BackendClient.FetchedTransfer(
                TransferPayload(
                    appName = "",
                    packageName = "",
                    versionName = "",
                    sizeBytes = 0L,
                    sha256 = "",
                    fileName = guessedName.ifBlank { "package.bin" },
                    downloadUrl = url
                ),
                ready = true
            )
        }
        throw IllegalStateException("这段内容看不懂。请粘贴对方分享的整段文字、取件码或下载链接。")
    }
}
