package cloud.suichuan.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/** Home screen: three big entries — send, receive, rescue. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_send).setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
        }
        findViewById<Button>(R.id.button_receive).setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
        findViewById<Button>(R.id.button_rescue).setOnClickListener {
            startActivity(Intent(this, RescueActivity::class.java))
        }
    }
}
