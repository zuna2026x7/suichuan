package cloud.suichuan.app

import cloud.suichuan.app.util.ApkInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkInspectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Builds zip bytes in memory from name -> content entries. */
    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun writeFile(name: String, content: ByteArray): File {
        val file = temp.newFile(name)
        file.writeBytes(content)
        return file
    }

    @Test
    fun singlePackageIsDetectedWhateverTheName() {
        // The rescue case in a nutshell: the name says ".apk.1", the content
        // is still a package.
        val apk = zipBytes(
            mapOf(
                "AndroidManifest.xml" to byteArrayOf(1, 2, 3),
                "classes.dex" to byteArrayOf(4, 5, 6),
                "resources.arsc" to byteArrayOf(7, 8, 9)
            )
        )
        val file = writeFile("weixin.apk.1", apk)
        val result = ApkInspector.inspect(file)
        assertEquals(ApkInspector.Kind.SINGLE, result.kind)
    }

    @Test
    fun bundleOfPackagesIsDetected() {
        val innerApk = zipBytes(mapOf("AndroidManifest.xml" to byteArrayOf(1)))
        val bundle = zipBytes(
            mapOf(
                "base.apk" to innerApk,
                "split_config.arm64_v8a.apk" to innerApk,
                "split_config.xxhdpi.apk" to innerApk
            )
        )
        val file = writeFile("game.apks", bundle)
        val result = ApkInspector.inspect(file)
        assertEquals(ApkInspector.Kind.BUNDLE, result.kind)
        assertEquals(3, result.packageEntries.size)
    }

    @Test
    fun plainTextFileIsInvalid() {
        val file = writeFile("note.txt", "hello, this is not a package".toByteArray())
        assertEquals(ApkInspector.Kind.INVALID, ApkInspector.inspect(file).kind)
    }

    @Test
    fun zipWithoutPackagesIsInvalid() {
        val zip = zipBytes(mapOf("readme.txt" to "hi".toByteArray()))
        val file = writeFile("random.zip", zip)
        assertEquals(ApkInspector.Kind.INVALID, ApkInspector.inspect(file).kind)
    }

    @Test
    fun missingOrTinyFileIsInvalid() {
        val tiny = writeFile("tiny.bin", byteArrayOf(1, 2))
        assertEquals(ApkInspector.Kind.INVALID, ApkInspector.inspect(tiny).kind)
        assertEquals(
            ApkInspector.Kind.INVALID,
            ApkInspector.inspect(File(temp.root, "does-not-exist.bin")).kind
        )
    }

    @Test
    fun bundleWithSmallMetadataFileStillCounts() {
        val innerApk = zipBytes(mapOf("AndroidManifest.xml" to byteArrayOf(1)))
        val bundle = zipBytes(
            mapOf(
                "base.apk" to innerApk,
                "split_config.en.apk" to innerApk,
                "meta.json" to "{}".toByteArray()
            )
        )
        val result = ApkInspector.inspect(writeFile("app.apks", bundle))
        assertEquals(ApkInspector.Kind.BUNDLE, result.kind)
        assertTrue(result.packageEntries.contains("base.apk"))
    }
}
