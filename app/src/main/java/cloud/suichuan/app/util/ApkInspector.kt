package cloud.suichuan.app.util

import java.io.File
import java.util.zip.ZipFile

/**
 * Decides what a file actually is by looking at its content, never at its
 * name. This is the heart of the rescue flow: QQ/WeChat rename received
 * packages to something like "xxx.apk.1", but the bytes stay a valid zip.
 *
 * Pure JVM (java.util.zip only) so it is unit-testable.
 */
object ApkInspector {

    enum class Kind {
        /** A single installable package (a zip with AndroidManifest.xml at its root). */
        SINGLE,

        /** A bundle whose entries are packages (base + splits), installable as one session. */
        BUNDLE,

        /** Not a package at all, or too damaged to use. */
        INVALID
    }

    data class Result(
        val kind: Kind,
        /** All entry names found in the zip (empty when INVALID). */
        val entries: List<String>,
        /** Subset of [entries] that are packages themselves (only for BUNDLE). */
        val packageEntries: List<String>
    )

    fun inspect(file: File): Result {
        val invalid = Result(Kind.INVALID, emptyList(), emptyList())
        if (!file.isFile || file.length() < 4) return invalid

        // Zip local-file-header magic: "PK\u0003\u0004". An empty archive
        // starts with the end-of-central-directory record "PK\u0005\u0006".
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) return invalid
            val isZip = magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
                ((magic[2].toInt() == 3 && magic[3].toInt() == 4) ||
                    (magic[2].toInt() == 5 && magic[3].toInt() == 6))
            if (!isZip) return invalid
        }

        val names = try {
            ZipFile(file).use { zip ->
                zip.entries().toList().filter { !it.isDirectory }.map { it.name }
            }
        } catch (e: Exception) {
            return invalid
        }
        if (names.isEmpty()) return invalid

        if (names.any { it == "AndroidManifest.xml" }) {
            return Result(Kind.SINGLE, names, emptyList())
        }

        val packages = names.filter { it.endsWith(".apk", ignoreCase = true) }
        // "All or most entries are packages" counts as a bundle: bundles may
        // carry a small metadata file next to the packages.
        if (packages.isNotEmpty() && packages.size * 2 >= names.size) {
            return Result(Kind.BUNDLE, names, packages)
        }
        return invalid
    }
}
