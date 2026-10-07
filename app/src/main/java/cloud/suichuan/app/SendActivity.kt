package cloud.suichuan.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cloud.suichuan.app.data.AppScanner
import cloud.suichuan.app.data.InstalledApp
import cloud.suichuan.app.util.FormatUtil
import kotlin.concurrent.thread

/** Lists installed user apps; tapping one goes to the pack/upload screen. */
class SendActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)

        val listView = findViewById<ListView>(R.id.list_apps)
        val status = findViewById<TextView>(R.id.text_status)
        val progress = findViewById<ProgressBar>(R.id.progress_loading)
        adapter = AppListAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            val intent = Intent(this, ShareActivity::class.java)
            intent.putExtra(ShareActivity.EXTRA_PACKAGE_NAME, app.packageName)
            startActivity(intent)
        }

        findViewById<EditText>(R.id.edit_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        thread {
            val apps = try {
                AppScanner.listUserApps(packageManager, packageName)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                allApps = apps
                progress.visibility = View.GONE
                status.text = if (apps.isEmpty()) {
                    "没有读到应用列表。请确认系统允许随传查看已安装应用。"
                } else {
                    "共 ${apps.size} 个应用，点一个就能发送。"
                }
                applyFilter(findViewById<EditText>(R.id.edit_search).text.toString())
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.submit(filtered)
    }

    private class AppListAdapter(private val context: Context) : BaseAdapter() {
        private var items: List<InstalledApp> = emptyList()

        fun submit(list: List<InstalledApp>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): InstalledApp = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_app, parent, false)
            val app = items[position]
            val icon = view.findViewById<ImageView>(R.id.image_icon)
            val label = view.findViewById<TextView>(R.id.text_label)
            val detail = view.findViewById<TextView>(R.id.text_detail)
            label.text = app.label
            val parts = mutableListOf<String>()
            if (app.versionName.isNotBlank()) parts.add("版本 " + app.versionName)
            parts.add(FormatUtil.size(app.sizeBytes))
            if (app.isSplit) parts.add("会完整打包")
            detail.text = parts.joinToString(" · ")
            try {
                icon.setImageDrawable(context.packageManager.getApplicationIcon(app.packageName))
            } catch (e: Exception) {
                icon.setImageDrawable(null)
            }
            return view
        }
    }
}
