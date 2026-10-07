package cloud.suichuan.app.install

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * The one system step we cannot remove: Android requires the user to allow
 * this app to install other apps, once. We explain it in plain words and
 * take the user straight to the right settings screen.
 */
object InstallPermissionHelper {

    /**
     * Returns true when installing is allowed. Otherwise shows the
     * explanation/settings dialog and returns false; the caller should stop
     * and let the user tap install again after granting (activities re-check
     * in onResume / on the next tap).
     */
    fun ensure(activity: Activity): Boolean {
        if (Installer.canInstall(activity)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AlertDialog.Builder(activity)
                .setTitle("需要允许一次")
                .setMessage("安卓要求你先允许「随传」安装应用，这个开关只需要开一次。点「去打开」，在打开的页面里把开关打开，然后回来再点一次安装。")
                .setPositiveButton("去打开") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, "没有找到这个设置页面，请到系统设置里搜索「安装未知应用」。", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("以后再说", null)
                .show()
        } else {
            Toast.makeText(activity, "这部手机不允许安装，请到设置里打开「未知来源」。", Toast.LENGTH_LONG).show()
        }
        return false
    }
}
