package cloud.suichuan.app.model

/**
 * Everything the receiving phone needs to know about one transfer.
 * Pure data class (no Android types) so it can be unit-tested on the JVM.
 */
data class TransferPayload(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileName: String,
    val downloadUrl: String
) {
    fun toMap(): Map<String, String> = linkedMapOf(
        "appName" to appName,
        "packageName" to packageName,
        "versionName" to versionName,
        "sizeBytes" to sizeBytes.toString(),
        "sha256" to sha256,
        "fileName" to fileName,
        "downloadUrl" to downloadUrl
    )

    companion object {
        fun fromMap(map: Map<String, String>): TransferPayload? {
            val downloadUrl = map["downloadUrl"]?.takeIf { it.isNotBlank() } ?: return null
            return TransferPayload(
                appName = map["appName"] ?: "",
                packageName = map["packageName"] ?: "",
                versionName = map["versionName"] ?: "",
                sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L,
                sha256 = map["sha256"] ?: "",
                fileName = map["fileName"] ?: "",
                downloadUrl = downloadUrl
            )
        }
    }
}
