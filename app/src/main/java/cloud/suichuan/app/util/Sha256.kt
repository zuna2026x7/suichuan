package cloud.suichuan.app.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Small SHA-256 helpers shared by packing, uploading and verifying. */
object Sha256 {

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun toHex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    fun ofFile(file: File): String {
        val digest = newDigest()
        file.inputStream().use { input -> update(digest, input) }
        return toHex(digest)
    }

    fun update(digest: MessageDigest, input: InputStream, buffer: ByteArray = ByteArray(64 * 1024)) {
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
}
