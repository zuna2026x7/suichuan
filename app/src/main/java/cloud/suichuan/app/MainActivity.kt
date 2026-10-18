package cloud.suichuan.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.update.CheckResult
import cloud.suichuan.app.update.UpdateChecker
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.VersionUtil
import kotlin.concurrent.thread

/**
 * Home screen, grouped into two sections: send (send an app, send a file,
 * send text) and receive (receive app/file/text, rescue a QQ/WeChat package),
 * plus a "check for updates" entry and a once-a-day silent update check
 * that only ever surfaces as a banner button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var updateBanner: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_send).setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
        }
        findViewById<Button>(R.id.button_send_file).setOnClickListener {
            startActivity(Intent(this, SendFileActivity::class.java))
        }
        findViewById<Button>(R.id.button_send_text).setOnClickListener {
            startActivity(Intent(this, SendTextActivity::class.java))
        }
        findViewById<Button>(R.id.button_receive).setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
        findViewById<Button>(R.id.button_rescue).setOnClickListener {
            startActivity(Intent(this, RescueActivity::class.java))
        }
        // Log upload also lives here: some failures never reach a failure
        // page, and the user may only open the app again to report one.
        findViewById<Button>(R.id.button_upload_log).setOnClickListener {
            LogUploader.uploadFrom(this)
        }

        updateBanner = findViewById(R.id.button_update_banner)
        updateBanner.setOnClickListener {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        findViewById<Button>(R.id.button_check_update).apply {
            text = "检查更新（当前 ${VersionUtil.display(UpdateChecker.currentVersionName())}）"
            setOnClickListener { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) }
        }

        // A leftover update package from a finished (or abandoned) update.
        if (savedInstanceState == null) thread { UpdateChecker.cleanup(this) }
        silentUpdateCheck()
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateBanner()
    }

    /**
     * At most once a day, quietly ask whether a newer release exists. It
     * never pops a dialog: a found update only shows the banner button, and
     * any failure stays silent (the manual check explains errors).
     */
    private fun silentUpdateCheck() {
        if (!UpdateChecker.silentCheckDue(this)) return
        thread {
            val result = UpdateChecker.check()
            if (result is CheckResult.Failed) return@thread // retry tomorrow-ish; stay quiet
            UpdateChecker.markChecked(this, (result as? CheckResult.Available)?.release?.tag)
            runOnUiThread { if (!isFinishing && !isDestroyed) refreshUpdateBanner() }
        }
    }

    private fun refreshUpdateBanner() {
        val tag = UpdateChecker.pendingTag(this)
        if (tag == null) {
            updateBanner.visibility = View.GONE
        } else {
            updateBanner.text = "发现新版本 ${VersionUtil.display(tag)}，点这里查看并更新"
            updateBanner.visibility = View.VISIBLE
        }
    }
}
