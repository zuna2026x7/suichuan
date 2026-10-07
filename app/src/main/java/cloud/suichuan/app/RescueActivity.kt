package cloud.suichuan.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.install.InstallPermissionHelper
import cloud.suichuan.app.install.Installer
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.AppLog
import java.io.File
import kotlin.concurrent.thread

/**
 * Rescue flow: installs a package the user already received through QQ /
 * WeChat, where it was renamed (often to *.apk.1) and cannot be opened.
 * We never look at the file name — we copy the content into our cache,
 * inspect the bytes, and install whatever it actually is.
 */
class RescueActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_FILE = 42
    }

    private lateinit var statusText: TextView
    private lateinit var installButton: Button
    private lateinit var uploadLogButton: Button

    private var readyFile: File? = null
    private var readyKind: ApkInspector.Kind = ApkInspector.Kind.INVALID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_rescue)

        statusText = findViewById(R.id.text_status)
        installButton = findViewById(R.id.button_install)
        uploadLogButton = findViewById(R.id.button_upload_log)
        uploadLogButton.setOnClickListener { LogUploader.uploadFrom(this) }

        findViewById<Button>(R.id.button_pick).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_PICK_FILE)
            } catch (e: Exception) {
                statusText.text = "这部手机上没有找到文件选择器，换一种方式：在 QQ/微信里点那个文件，选「用其他应用打开」，再选随传。"
            }
        }
        installButton.setOnClickListener {
            val file = readyFile
            if (file == null) {
                Toast.makeText(this, "先选一个收到的文件。", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!InstallPermissionHelper.ensure(this)) return@setOnClickListener
            try {
                Installer.install(this, file, readyKind)
                Toast.makeText(this, "正在打开系统安装界面…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLog.log("RESCUE", "打开系统安装失败", e)
                Toast.makeText(this, "安装没有打开：${e.message ?: "未知问题"}", Toast.LENGTH_LONG).show()
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Deprecated("Using the classic result callback to keep this simple and dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) processUri(uri)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: intent.clipData?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).uri else null
        }
        if (uri != null) processUri(uri)
    }

    private fun processUri(uri: Uri) {
        statusText.text = "正在检查这个文件…"
        installButton.visibility = View.GONE
        uploadLogButton.visibility = View.GONE
        // Only the scheme is logged: a content URI carries ids, not ours to keep.
        AppLog.log("RESCUE", "开始处理文件 scheme=${uri.scheme}")
        thread {
            try {
                val dir = File(cacheDir, "rescue").apply { mkdirs() }
                val target = File(dir, "rescue_" + System.currentTimeMillis() + ".bin")
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("打不开这个文件。它可能已经被删掉了，重新从 QQ/微信里打开一次。")
                input.use { stream ->
                    target.outputStream().use { output -> stream.copyTo(output) }
                }
                val result = ApkInspector.inspect(target)
                AppLog.log("RESCUE", "文件检查结果 kind=${result.kind} size=${target.length()}")
                runOnUiThread {
                    when (result.kind) {
                        ApkInspector.Kind.INVALID -> {
                            target.delete()
                            statusText.text = "这个文件不是安装包，或者已经损坏了。如果它是 QQ/微信里收到的应用，让对方重新发一次。"
                            uploadLogButton.visibility = View.VISIBLE
                        }
                        ApkInspector.Kind.SINGLE -> {
                            readyFile = target
                            readyKind = result.kind
                            statusText.text = "检查通过：这是一个完整的安装包，名字不用管，点安装就行。"
                            installButton.visibility = View.VISIBLE
                        }
                        ApkInspector.Kind.BUNDLE -> {
                            readyFile = target
                            readyKind = result.kind
                            statusText.text = "检查通过：这是一个由几个部分组成的安装包（共 ${result.packageEntries.size} 部分），会一次装好，点安装就行。"
                            installButton.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.log("RESCUE", "处理文件失败", e)
                runOnUiThread {
                    statusText.text = "没有处理成功：${e.message ?: "未知问题"}"
                    uploadLogButton.visibility = View.VISIBLE
                }
            }
        }
    }
}
