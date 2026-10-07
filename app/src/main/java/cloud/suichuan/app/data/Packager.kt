package cloud.suichuan.app.data

import cloud.suichuan.app.util.Sha256
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Result of packing one app into a single file in the cache dir. */
data class PackedFile(
    val file: File,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val isBundle: Boolean
)

/**
 * Turns an installed app into one transferable file:
 * - single-package app: the package file is copied as-is;
 * - split app: base + every split are zipped with the STORED method
 *   (packages are already compressed, recompressing only wastes time).
 * The SHA-256 of the produced file is computed either way.
 */
object Packager {

    fun pack(app: InstalledApp, cacheDir: File): PackedFile {
        val outDir = File(cacheDir, "send").apply { mkdirs() }
        val safeName = safeName(app.label, app.packageName)
        return if (!app.isSplit) {
            val target = File(outDir, "$safeName.apk")
            val digest = Sha256.newDigest()
            File(app.sourceDir).inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            PackedFile(target, target.name, target.length(), Sha256.toHex(digest), isBundle = false)
        } else {
            val target = File(outDir, "$safeName.apks")
            val usedNames = HashSet<String>()
            target.outputStream().use { fileOut ->
                ZipOutputStream(fileOut).use { zip ->
                    for (path in listOf(app.sourceDir) + app.splitSourceDirs) {
                        val source = File(path)
                        var entryName = source.name
                        if (!usedNames.add(entryName)) {
                            entryName = "part_${usedNames.size}_$entryName"
                            usedNames.add(entryName)
                        }
                        val entry = ZipEntry(entryName)
                        entry.method = ZipEntry.STORED
                        entry.size = source.length()
                        entry.compressedSize = source.length()
                        entry.crc = crc32(source)
                        zip.putNextEntry(entry)
                        source.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                zip.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            PackedFile(target, target.name, target.length(), Sha256.ofFile(target), isBundle = true)
        }
    }

    /** Makes a file-system-safe base name out of an app label. */
    fun safeName(label: String, packageName: String): String {
        val cleaned = label.trim().map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('_', '.', ' ')
        return cleaned.ifEmpty { packageName }
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
