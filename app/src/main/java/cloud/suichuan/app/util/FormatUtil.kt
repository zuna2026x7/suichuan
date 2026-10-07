package cloud.suichuan.app.util

/** Human-friendly size formatting for the UI. */
object FormatUtil {

    fun size(bytes: Long): String {
        if (bytes <= 0) return "大小未知"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }
}
