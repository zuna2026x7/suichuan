package cloud.suichuan.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.net.LogUploader
import cloud.suichuan.app.util.AppLog

/** Home screen: four big entries — send an app, send text, receive, rescue. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_send).setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
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
    }
}
