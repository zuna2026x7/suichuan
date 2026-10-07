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
    // Optional: empty when the file lives behind our own pickup backend and
    // is addressed by code instead of a direct URL (backend mode fills in
    // "<base>/f/<code>" once the code is known).
    val downloadUrl: String = ""
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
            // A map with nothing identifiable in it is not a payload at all.
            if (map["appName"].isNullOrBlank() &&
                map["packageName"].isNullOrBlank() &&
                map["fileName"].isNullOrBlank() &&
                map["downloadUrl"].isNullOrBlank()
            ) {
                return null
            }
            return TransferPayload(
                appName = map["appName"] ?: "",
                packageName = map["packageName"] ?: "",
                versionName = map["versionName"] ?: "",
                sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L,
                sha256 = map["sha256"] ?: "",
                fileName = map["fileName"] ?: "",
                downloadUrl = map["downloadUrl"] ?: ""
            )
        }
    }
}
