package cloud.suichuan.app.data

import java.io.File

/** One installed app the user can send. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    /** Path of the base package file. */
    val sourceDir: String,
    /** Paths of split package files (empty for a single-package app). */
    val splitSourceDirs: List<String>
) {
    val isSplit: Boolean get() = splitSourceDirs.isNotEmpty()

    val allFiles: List<File>
        get() = (listOf(sourceDir) + splitSourceDirs).map { File(it) }

    val sizeBytes: Long
        get() = allFiles.sumOf { if (it.exists()) it.length() else 0L }
}
