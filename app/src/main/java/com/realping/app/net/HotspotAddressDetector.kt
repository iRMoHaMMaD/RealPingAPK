package com.realping.app.net

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object HotspotAddressDetector {
    private const val TAG = "HotspotIP"

    // محدوده‌های RFC1918
    private fun Inet4Address.isPrivateV4(): Boolean {
        val b = this.address
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when (b0) {
            10 -> true
            172 -> b1 in 16..31
            192 -> b1 == 168
            else -> false
        }
    }

    /**
     * لیست کاندیدهای مناسب برای bind کردن پراکسی وقتی هات‌اسپات/تترینگ فعاله.
     * اولویت: اینترفیس‌هایی که نامشان نشانهٔ tethering دارد و آدرسشان private است.
     * مثال نام‌ها: ap0, swlan0, wlan1, rndis0 (USB tether)
     */
    fun detectCandidates(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            val likelyNames = listOf("ap", "wlan", "swlan", "rndis", "usb", "softap")

            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue

                val name = (ni.displayName ?: ni.name ?: "").lowercase()
                val score = when {
                    likelyNames.any { name.contains(it) } -> 2
                    else -> 1
                }

                ni.inetAddresses?.toList()
                    ?.filterIsInstance<Inet4Address>()
                    ?.filter { it.isPrivateV4() }
                    ?.forEach { addr ->
                        // اگر آدرس .1 یا .129 باشد امتیاز بیشتری بگیرد
                        val host = addr.hostAddress ?: return@forEach
                        val bonus = if (host.endsWith(".1") || host.endsWith(".129")) 1 else 0
                        repeat(score + bonus) { out.add(addr) }
                        Log.i(TAG, "candidate: $name -> $host")
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "detectCandidates failed", t)
        }
        // اگر چیزی پیدا نشد، fallback ها:
        if (out.isEmpty()) {
            listOf("192.168.43.1", "192.168.137.1", "192.168.42.129").forEach { s ->
                try { out.add(InetAddress.getByName(s)) } catch (_: Throwable) {}
            }
        }
        // حذف تکراری‌ها با حفظ ترتیب
        return out.distinctBy { it.hostAddress }
    }

    /** بهترین گزینهٔ فعلی (یا null اگر هیچ) */
    fun best(): InetAddress? = detectCandidates().firstOrNull()
}
