package cloud.suichuan.app.lan

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the IPv4 address another phone on the same Wi-Fi (or on this phone's
 * hotspot) can reach. Mobile data interfaces (rmnet, ccmni...) are skipped:
 * their addresses are carrier-private and unreachable from a nearby phone.
 */
object LanAddress {

    data class Candidate(val iface: String, val ip: String)

    fun find(): String? = runCatching {
        val list = mutableListOf<Candidate>()
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    list += Candidate(ni.name, addr.hostAddress ?: continue)
                }
            }
        }
        pick(list)
    }.getOrNull()

    /** Pure selection logic, unit-tested: Wi-Fi/hotspot first, private ranges only. */
    fun pick(candidates: List<Candidate>): String? {
        val usable = candidates.filter { isPrivate(it.ip) && !isMobileData(it.iface) }
        return usable.minByOrNull { rank(it.iface) }?.ip
    }

    private fun rank(iface: String): Int {
        val n = iface.lowercase()
        return when {
            n.startsWith("wlan") -> 0
            n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") -> 1
            n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") -> 2
            else -> 3
        }
    }

    private fun isMobileData(iface: String): Boolean {
        val n = iface.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("v4-") || n.startsWith("clat") || n.startsWith("tun") || n.startsWith("dummy")
    }

    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }
}
