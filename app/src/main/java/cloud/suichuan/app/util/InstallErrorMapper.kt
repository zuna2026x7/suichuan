package cloud.suichuan.app.util

/**
 * Translates raw PackageInstaller failure strings into plain Chinese the
 * receiver can act on. Pure Kotlin so it is JVM unit-testable; the raw text
 * usually comes from PackageInstaller.EXTRA_STATUS_MESSAGE, which contains
 * tokens such as "INSTALL_FAILED_MISSING_SPLIT".
 *
 * The wording follows DESIGN.md section 4.
 */
object InstallErrorMapper {

    fun messageFor(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return when {
            text.contains("INSTALL_FAILED_MISSING_SPLIT") ||
                text.contains("MISSING_SPLIT") ->
                "这个应用不完整，缺少组件。请让对方用随传重新发送（随传会自动完整打包）。"

            text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                text.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") ||
                text.contains("签名") && text.contains("冲突") ||
                text.contains("signatures do not match", ignoreCase = true) ||
                text.contains("signature", ignoreCase = true) && text.contains("conflict", ignoreCase = true) ->
                "手机里已装的同名应用来源不同（签名不一样）。先卸载旧的再装，注意旧应用里的数据会没。"

            text.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
                text.contains("INSTALL_FAILED_VERSION_OLDER") ->
                "手机里已经是更新的版本了，这个是旧版本，安卓不允许覆盖降级。"

            text.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ||
                text.contains("INSTALL_FAILED_MEDIA_UNAVAILABLE") ->
                "手机空间不足。删掉一些不用的东西腾出空间后，再重新安装。"

            text.contains("INSTALL_PARSE_FAILED") ||
                text.contains("INSTALL_FAILED_INVALID_APK") ||
                text.contains("INSTALL_FAILED_INVALID_URI") ||
                text.contains("解析包", ignoreCase = true) ||
                text.contains("parse", ignoreCase = true) && text.contains("fail", ignoreCase = true) ->
                "安装包已损坏或没下载完整。重新接收一次再装。"

            text.contains("INSTALL_FAILED_USER_RESTRICTED") ||
                text.contains("安装被拦截") ->
                "安装被系统拦住了。请到设置里允许随传安装应用，再回来试一次。"

            text.contains("INSTALL_FAILED_ABORTED") ->
                "安装被取消了。再点一次安装，看到系统弹窗时点「安装」即可。"

            text.isBlank() ->
                "安装没有成功。重新试一次；还不行就让对方重新发送。"

            else ->
                "安装没有成功（系统提示：$text）。重新试一次；还不行就让对方重新发送。"
        }
    }
}
