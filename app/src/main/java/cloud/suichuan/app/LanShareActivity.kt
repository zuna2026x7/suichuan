package cloud.suichuan.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.lan.LanAddress
import cloud.suichuan.app.lan.MiniHttpServer
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.FormatUtil
import cloud.suichuan.app.util.QrUtil
import java.io.File

/**
 * 面对面分享随传: serves this app's own installed APK over the local network
 * and shows a QR code. A nearby phone on the same Wi-Fi (or on this phone's
 * hotspot) scans it with its camera or browser and downloads the APK — no
 * internet, no account, no size limit.
 *
 * The server lives only as long as this screen: leaving the page, tapping
 * 停止分享, or 10 minutes without any request shuts it down. The screen stays
 * on while sharing so the phone doesn't sleep mid-download.
 */
class LanShareActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var server: MiniHttpServer? = null
    private var lastActivity = System.currentTimeMillis()
    private var downloadsDone = 0
    private var activeDownloads = 0

    private lateinit var textStatus: TextView
    private lateinit var textUrl: TextView
    private lateinit var textDownloads: TextView
    private lateinit var imageQr: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var layoutShare: View
    private lateinit var buttonRefresh: Button

    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server ?: return
            if (activeDownloads == 0 && System.currentTimeMillis() - lastActivity > IDLE_MS) {
                AppLog.log("LanShare", "idle timeout, stopping server on port ${s.port}")
                stopServer()
                showStopped("10 分钟没有人下载，已自动停止分享。")
                return
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lan_share)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textStatus = findViewById(R.id.text_status)
        textUrl = findViewById(R.id.text_url)
        textDownloads = findViewById(R.id.text_downloads)
        imageQr = findViewById(R.id.image_qr)
        progress = findViewById(R.id.progress_download)
        layoutShare = findViewById(R.id.layout_share)
        buttonRefresh = findViewById(R.id.button_refresh)

        buttonRefresh.setOnClickListener { startOrRefresh() }
        findViewById<Button>(R.id.button_copy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("随传下载链接", textUrl.text))
            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.button_stop).setOnClickListener { finish() }

        startOrRefresh()
    }

    override fun onResume() {
        super.onResume()
        // The user may have gone to Settings to join Wi-Fi or turn on the
        // hotspot; pick up the new address when they come back.
        if (server != null) startOrRefresh()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopServer()
        super.onDestroy()
    }

    private fun startOrRefresh() {
        val ip = LanAddress.find()
        if (ip == null) {
            stopServer()
            showStopped("没有找到局域网。请先连上 Wi‑Fi，或者打开手机热点让对方连上，然后点「重新检测网络」。")
            return
        }
        val s = server ?: createServer() ?: return
        val url = "http://$ip:${s.port}${s.pagePath()}"
        if (textUrl.text?.toString() == url && layoutShare.visibility == View.VISIBLE) return
        textUrl.text = url
        imageQr.setImageBitmap(QrUtil.bitmapFor(url))
        layoutShare.visibility = View.VISIBLE
        buttonRefresh.text = "重新检测网络"
        textStatus.text = "让对方连上和你同一个 Wi‑Fi（或你的热点），用手机相机或浏览器扫下面的二维码。"
        updateDownloads()
    }

    private fun createServer(): MiniHttpServer? {
        return try {
            val info = applicationInfo
            val apk = File(info.sourceDir)
            val pkg = packageManager.getPackageInfo(packageName, 0)
            val version = pkg.versionName ?: "?"
            val label = getString(R.string.app_name)
            val s = MiniHttpServer(
                apk = apk,
                fileName = "suichuan-$version.apk",
                appLabel = label,
                versionName = version,
                listener = serverListener,
            )
            val port = s.start()
            AppLog.log("LanShare", "serving ${apk.length()} bytes on port $port")
            server = s
            lastActivity = System.currentTimeMillis()
            main.removeCallbacks(idleCheck)
            main.postDelayed(idleCheck, 30_000)
            s
        } catch (e: Exception) {
            AppLog.log("LanShare", "start failed", e)
            showStopped("分享没能启动：${e.message ?: "未知原因"}。可以返回再试一次。")
            null
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        main.removeCallbacks(idleCheck)
    }

    private fun showStopped(message: String) {
        layoutShare.visibility = View.GONE
        progress.visibility = View.GONE
        textUrl.text = ""
        textStatus.text = message
        buttonRefresh.text = "重新开始分享"
    }

    private fun updateDownloads() {
        textDownloads.text = when {
            activeDownloads > 0 -> "有人正在下载，请停留在这个页面…"
            downloadsDone > 0 -> "已被下载 $downloadsDone 次。可以继续分享给其他人。"
            else -> "等待对方扫码下载…"
        }
        if (activeDownloads == 0) progress.visibility = View.GONE
    }

    private val serverListener = object : MiniHttpServer.Listener {
        override fun onRequest() {
            main.post { lastActivity = System.currentTimeMillis() }
        }

        override fun onDownloadStarted(client: String) {
            main.post {
                activeDownloads++
                progress.progress = 0
                progress.visibility = View.VISIBLE
                updateDownloads()
            }
        }

        override fun onProgress(sent: Long, total: Long) {
            main.post {
                lastActivity = System.currentTimeMillis()
                if (total > 0) progress.progress = (sent * 100 / total).toInt()
                textDownloads.text = "有人正在下载：${FormatUtil.size(sent)} / ${FormatUtil.size(total)}"
            }
        }

        override fun onDownloadFinished(client: String, complete: Boolean) {
            main.post {
                activeDownloads = (activeDownloads - 1).coerceAtLeast(0)
                lastActivity = System.currentTimeMillis()
                if (complete) downloadsDone++
                updateDownloads()
                if (!complete && activeDownloads == 0) {
                    val done = if (downloadsDone > 0) "（已完成 $downloadsDone 次）" else ""
                    textDownloads.text = "对方的下载中断了，可以让对方重新点下载。$done"
                }
            }
        }
    }

    companion object {
        private const val IDLE_MS = 10 * 60 * 1000L
    }
}
