package cloud.suichuan.app.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Lists installed user apps the sender can pick from. */
object AppScanner {

    fun listUserApps(pm: PackageManager, ownPackage: String): List<InstalledApp> {
        val infos = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = ArrayList<InstalledApp>()
        for (info in infos) {
            if (info.packageName == ownPackage) continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue
            val sourceDir = info.publicSourceDir ?: info.sourceDir ?: continue
            val splits = info.splitPublicSourceDirs?.toList()
                ?: info.splitSourceDirs?.toList()
                ?: emptyList()
            val versionName = try {
                pm.getPackageInfo(info.packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
            result.add(
                InstalledApp(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    versionName = versionName,
                    sourceDir = sourceDir,
                    splitSourceDirs = splits
                )
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }
}
