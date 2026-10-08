package cloud.suichuan.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.net.BackendClient
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.QrUtil
import kotlin.concurrent.thread

/**
 * Sends a piece of text (a note, a link, anything) through the pickup
 * service. Unlike an app transfer there is no file to upload: the message
 * itself is the payload, so creating the pickup code already delivers
 * it. The receiver enters or scans the code and reads the text on the
 * receive screen.
 */
class SendTextActivity : AppCompatActivity() {

    companion object {
        /** Server-side cap for one text transfer, in UTF-8 bytes. */
        private const val MAX_TEXT_BYTES = 65536
    }

    private lateinit var textEdit: EditText
    private lateinit var sizeHint: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var uploadLogButton: Button
    private lateinit var resultLayout: LinearLayout
    private lateinit var codeText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var shareButton: Button

    private var shareText: String = ""
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_send_text)

        textEdit = findViewById(R.id.edit_text)
        sizeHint = findViewById(R.id.text_size_hint)
        statusText = findViewById(R.id.text_status)
        progressBar = findViewById(R.id.progress_send)
        retryButton = findViewById(R.id.button_retry)
        uploadLogButton = findViewById(R.id.button_upload_log)
        uploadLogButton.setOnClickListener { LogUploader.uploadFrom(this) }
        resultLayout = findViewById(R.id.layout_result)
        codeText = findViewById(R.id.text_code)
        qrImage = findViewById(R.id.image_qr)
        shareButton = findViewById(R.id.button_share)

        textEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSizeHint()
        })
        updateSizeHint()

        findViewById<Button>(R.id.button_paste).setOnClickListener {
            pasteFromClipboard()
        }
        findViewById<Button>(R.id.button_send).setOnClickListener { startSend() }
        retryButton.setOnClickListener { startSend() }
        shareButton.setOnClickListener {
            if (shareText.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(send, "发给对方"))
            }
        }
        findViewById<Button>(R.id.button_done).setOnClickListener {
            // Same way home as the share screen: clear the send flow off
            // the stack so Back from home exits the app.
            val home = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(home)
            finish()
        }
    }

    /** Fills the editor from the clipboard; sending is a separate tap. */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard
            ?.takeIf { it.hasPrimaryClip() }
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "剪贴板是空的，先去复制一段文字", Toast.LENGTH_LONG).show()
            return
        }
        textEdit.setText(text)
        textEdit.setSelection(textEdit.text.length)
    }

    private fun updateSizeHint() {
        val text = textEdit.text.toString()
        val bytes = text.toByteArray(Charsets.UTF_8).size
        sizeHint.text = "已输入 ${text.length} 字 · $bytes 字节（一段文字最多 64KB）"
    }

    private fun startSend() {
        if (working) return
        val text = textEdit.text.toString()
        if (text.isBlank()) {
            statusText.text = "先输入或粘贴一段文字，再点发送。"
            return
        }
        // Same checks the server makes, done up front so the user gets
        // the answer immediately instead of after a round trip.
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_TEXT_BYTES) {
            statusText.text =
                "这段文字有 $bytes 字节，太长了：一段文字最多 64KB，删短一点再发。"
            return
        }
        val base = BuildConfig.TRANSFER_API_BASE
        if (base.isBlank()) {
            statusText.text = "这个版本还没有配置取件码服务，文字暂时发不了。"
            return
        }
        working = true
        retryButton.visibility = View.GONE
        uploadLogButton.visibility = View.GONE
        resultLayout.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在发送…"

        thread {
            try {
                val created = BackendClient.createTextTransfer(base, text)
                AppLog.log("SEND", "文字发送成功，取件码=${created.code}，长度=$bytes 字节")
                showSuccess(created.code)
            } catch (e: Exception) {
                AppLog.log("SEND", "文字发送失败", e)
                showError("没有发出去：${e.message ?: "网络出了点问题"}。检查一下网络，点重试再试一次。")
            }
        }
    }

    private fun showSuccess(code: String) {
        shareText = "我用随传给你发了一段文字，取件码 $code。打开随传，输入取件码就能看到。"
        runOnUiThread {
            working = false
            progressBar.visibility = View.GONE
            resultLayout.visibility = View.VISIBLE
            statusText.text = "发送完成！72 小时内有效，过期就没了，抓紧让对方接收。"
            codeText.text = code
            try {
                // The QR carries the bare code string: the receiver's
                // scanner treats a lone 6-digit string as a pickup code.
                qrImage.setImageBitmap(QrUtil.bitmapFor(code))
            } catch (e: Exception) {
                qrImage.setImageBitmap(null)
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            working = false
            progressBar.visibility = View.GONE
            statusText.text = message
            retryButton.visibility = View.VISIBLE
            uploadLogButton.visibility = View.VISIBLE
        }
    }
}
