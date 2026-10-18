package cloud.suichuan.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared presentation of a pickup code on the three send screens:
 * grouped digits ("123 456"), digit-by-digit TalkBack reading, tap or
 * button to copy, and a concrete expiry time instead of "72 小时内".
 */
object CodeUi {

    const val TTL_HOURS = 72

    fun bind(
        context: Context,
        code: String,
        codeView: TextView,
        expiryView: TextView?,
        copyButton: Button?
    ) {
        codeView.text = ErrorText.groupCode(code)
        codeView.contentDescription = "取件码 " + code.toCharArray().joinToString(" ")
        codeView.setOnClickListener { copy(context, code) }
        copyButton?.visibility = View.VISIBLE
        copyButton?.setOnClickListener { copy(context, code) }
        expiryView?.let {
            it.visibility = View.VISIBLE
            it.text = "有效期到 " + expiryText(System.currentTimeMillis()) + "（约 $TTL_HOURS 小时），过期后对方就收不到了。"
        }
    }

    fun clear(codeView: TextView, expiryView: TextView?, copyButton: Button?) {
        codeView.text = ""
        codeView.contentDescription = null
        codeView.setOnClickListener(null)
        expiryView?.visibility = View.GONE
        copyButton?.visibility = View.GONE
    }

    fun expiryText(nowMillis: Long): String {
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        return fmt.format(Date(nowMillis + TTL_HOURS * 3600_000L))
    }

    fun copy(context: Context, code: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("随传取件码", code))
        Toast.makeText(context, "取件码已复制", Toast.LENGTH_SHORT).show()
    }
}
