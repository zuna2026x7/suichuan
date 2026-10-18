package cloud.suichuan.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import cloud.suichuan.app.install.InstallResultBus
import cloud.suichuan.app.util.AppLog
import cloud.suichuan.app.util.InstallErrorMapper

/**
 * Receives the result of a PackageInstaller session commit and turns it into
 * a message the user understands.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLog.init(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        AppLog.log(
            "INSTALL",
            "系统安装结果 status=$status" +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.let { " 原始信息=$it" } ?: "")
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    Toast.makeText(context, "系统安装界面没有打开，请再试一次。", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val delivered = InstallResultBus.post(
                    InstallResultBus.Result(success = true, packageName = pkg, message = "安装完成！")
                )
                if (!delivered) Toast.makeText(context, "安装完成！", Toast.LENGTH_LONG).show()
            }
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED
                val message = if (aborted) {
                    "安装已取消。想装的话，点「重新安装」再来一次。"
                } else {
                    InstallErrorMapper.messageFor(raw)
                }
                val delivered = InstallResultBus.post(
                    InstallResultBus.Result(
                        success = false,
                        packageName = pkg,
                        message = message,
                        userCancelled = aborted
                    )
                )
                if (!delivered) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
