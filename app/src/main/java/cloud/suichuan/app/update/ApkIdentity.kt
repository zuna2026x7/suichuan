package cloud.suichuan.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import cloud.suichuan.app.util.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded update APK against the running app before handing it
 * to the system installer, so the user gets a clear answer up front instead
 * of a failed install:
 *  - it really is this app (same package name);
 *  - it is signed with the same key (otherwise Android refuses to update
 *    over the installed copy — the user must uninstall first);
 *  - it is not older than what is installed.
 */
object ApkIdentity {

    enum class Verdict { OK, NOT_THIS_APP, SIGNATURE_MISMATCH, NOT_NEWER, UNREADABLE }

    fun check(context: Context, apk: File): Verdict {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return Verdict.UNREADABLE
        if (archive.packageName != context.packageName) return Verdict.NOT_THIS_APP
        val installed = installedInfo(pm, context.packageName) ?: return Verdict.UNREADABLE
        if (versionCodeOf(archive) < versionCodeOf(installed)) return Verdict.NOT_NEWER
        val a = signerDigests(archive)
        val b = signerDigests(installed)
        // Unknown signers (some ROMs return none for archives): don't block,
        // let the system installer decide and InstallErrorMapper explain.
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            AppLog.log("UPDATE", "新版签名与当前不同")
            return Verdict.SIGNATURE_MISMATCH
        }
        return Verdict.OK
    }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, flags())
        }
    } catch (e: Exception) {
        AppLog.log("UPDATE", "读取新版安装包信息失败", e)
        null
    }

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags())
        }
    } catch (e: Exception) {
        null
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Current signer(s) only; rotation history is not compared.
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
