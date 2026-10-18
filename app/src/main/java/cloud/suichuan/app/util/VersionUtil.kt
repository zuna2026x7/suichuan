package cloud.suichuan.app.util

/**
 * Version-string helpers for the update check. Pure Kotlin so the JVM unit
 * tests can cover it.
 *
 * Release tags look like "v1.3" or "v1.4.1"; the installed app reports its
 * versionName ("1.4"). Both are reduced to their numeric parts and compared
 * part by part, missing parts counting as 0 ("1.4" == "1.4.0").
 */
object VersionUtil {

    /** "v1.4.1-beta" -> [1, 4, 1]; anything without digits -> empty list. */
    fun parts(version: String): List<Int> {
        val core = version.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .substringBefore(' ')
        return core.split('.')
            .map { part -> part.takeWhile { it.isDigit() } }
            .takeWhile { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
    }

    /** Negative when a < b, 0 when equal, positive when a > b. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** True only when [remote] parses and is strictly newer than [local]. */
    fun isNewer(remote: String, local: String): Boolean {
        if (parts(remote).isEmpty()) return false
        return compare(remote, local) > 0
    }

    /** Display form: "1.4" -> "v1.4", "v1.4" stays "v1.4". */
    fun display(version: String): String {
        val v = version.trim()
        return if (v.startsWith("v") || v.startsWith("V")) v else "v$v"
    }
}
