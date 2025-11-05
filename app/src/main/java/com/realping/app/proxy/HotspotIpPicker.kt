package com.realping.app.proxy

import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object HotspotIpPicker {
    private const val TAG = "HotspotIP"

    fun pick(): InetAddress? {
        val nics = Collections.list(NetworkInterface.getNetworkInterfaces())
        val candidates = mutableListOf<Pair<String, Inet4Address>>()

        for (nic in nics) {
            if (!nic.isUp || nic.isLoopback) continue
            val addrs = Collections.list(nic.inetAddresses)
            for (addr in addrs) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    Log.i(TAG, "candidate: ${nic.name} -> ${addr.hostAddress}")
                    candidates += nic.name to addr
                }
            }
        }

        if (candidates.isEmpty()) return null

        val arpIfaces = readArpIfaces()

        fun isSoftAp(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("swlan")
                    || n.startsWith("softap")
                    || n == "ap0"
                    || n.startsWith("ap_br")
                    || n.startsWith("apbr")
                    || n == "wlan1"
                    || n == "wlan2"
                    || n.startsWith("wlan_ap")
                    || n.startsWith("sap")
        }

        fun isUsbTether(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("eth") || n.startsWith("enx")
        }

        fun isBridgeLike(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("br") || n.startsWith("ap-bridge") || n.startsWith("bond")
        }

        fun isTunOrWg(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("tun") || n.startsWith("wg")
        }

        fun isCellular(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("clat") || n.startsWith("pdp")
        }

        var best: Pair<String, Inet4Address>? = null
        var bestScore = Int.MIN_VALUE

        for ((name, ip) in candidates) {
            if (!isPrivateIPv4(ip)) continue
            if (isTunOrWg(name) || isCellular(name)) continue

            var score = 0
            if (name in arpIfaces) score += 100
            if (isSoftAp(name)) score += 80
            if (isUsbTether(name)) score += 70
            if (name.startsWith("wlan") && name != "wlan0") score += 50
            if (isBridgeLike(name)) score += 30
            // سایر خصوصی‌ها:
            score += 10

            Log.i(TAG, "score ${ip.hostAddress} on $name = $score (arp=${name in arpIfaces})")

            if (score > bestScore) {
                bestScore = score
                best = name to ip
            }
        }

        return best?.also { (name, ip) ->
            Log.i(TAG, "pick $name -> ${ip.hostAddress} (score=$bestScore)")
        }?.second
    }

    /** جدول ARP را می‌خواند و نام اینترفیس‌هایی که حداقل یک همسایه دارند برمی‌گرداند. */
    private fun readArpIfaces(): Set<String> {
        return runCatching {
            val f = File("/proc/net/arp")
            if (!f.exists()) return emptySet()
            val lines = f.readLines()
            if (lines.isEmpty()) return emptySet()
            val out = HashSet<String>()
            // ستون ششم Device است
            lines.drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6) {
                    val dev = parts[5]
                    if (dev.isNotEmpty()) out += dev
                }
            }
            out
        }.getOrElse { emptySet() }
    }

    private fun isPrivateIPv4(ip: Inet4Address): Boolean {
        val b = ip.address
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            else -> false
        }
    }
}
