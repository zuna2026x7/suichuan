package cloud.suichuan.app.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import cloud.suichuan.app.InstallResultReceiver
import cloud.suichuan.app.util.ApkInspector
import cloud.suichuan.app.util.AppLog
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Installs packages through a PackageInstaller session. The session API is
 * what lets a split bundle (several package files) be installed as one app,
 * and it streams straight from our private cache — no shared-storage file
 * the user would ever have to find or rename.
 */
object Installer {

    const val ACTION_INSTALL_RESULT = "cloud.suichuan.app.INSTALL_RESULT"

    /** True when this app is allowed to ask the system to install packages. */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Installs [file]. [kind] comes from ApkInspector: SINGLE installs the
     * file itself, BUNDLE unzips it in memory-streaming fashion and installs
     * every package entry it contains, all in one session.
     */
    fun install(context: Context, file: File, kind: ApkInspector.Kind) {
        AppLog.init(context)
        AppLog.log("INSTALL", "开始安装 kind=$kind size=${file.length()}")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            when (kind) {
                ApkInspector.Kind.SINGLE -> writeOne(session, "base.apk", file.length()) { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                ApkInspector.Kind.BUNDLE -> writeBundle(session, file)
                ApkInspector.Kind.INVALID -> throw IOException("这不是一个有效的安装包。")
            }
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_RESULT
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // PackageInstaller fills in the result status, so the pending
                // intent must be mutable on Android 12+.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            AppLog.log("INSTALL", "安装会话 $sessionId 已提交，等系统结果")
        } catch (e: Exception) {
            AppLog.log("INSTALL", "安装会话 $sessionId 失败", e)
            try {
                session.abandon()
            } catch (ignored: Exception) {
                // Session already gone; nothing to clean up.
            }
            throw e
        } finally {
            session.close()
        }
    }

    private fun writeBundle(session: PackageInstaller.Session, bundle: File) {
        ZipFile(bundle).use { zip ->
            val entries = zip.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            if (entries.isEmpty()) {
                throw IOException("这个安装包里没有找到可安装的内容。")
            }
            var index = 0
            for (entry in entries) {
                val stream = BufferedInputStream(zip.getInputStream(entry), 64 * 1024)
                stream.use { input ->
                    input.mark(4)
                    val magic = ByteArray(2)
                    val got = input.read(magic)
                    input.reset()
                    if (got != 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                        throw IOException("安装包里的内容不完整（有一部分不是安装包）。")
                    }
                    val name = "split_$index.apk"
                    index++
                    writeOne(session, name, entry.size) { out -> input.copyTo(out) }
                }
            }
        }
    }

    private inline fun writeOne(
        session: PackageInstaller.Session,
        name: String,
        size: Long,
        copy: (java.io.OutputStream) -> Unit
    ) {
        val out = session.openWrite(name, 0, if (size > 0) size else -1)
        out.use { stream ->
            copy(stream)
            session.fsync(stream)
        }
    }
}
