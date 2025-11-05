package com.realping.app.mtu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

object MtuDiscovery {
    private const val IP_ICMP_HEADER = 28 // IPv4: 20 (IP) + 8 (ICMP)

    @Volatile
    private var supportsMDoCached: Boolean? = null

    private suspend fun supportsMDo(): Boolean {
        supportsMDoCached?.let { return it }
        val result = withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("/system/bin/ping", "-h").start()
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                output.contains(" -M ") || output.contains("-M do")
            } catch (_: Exception) {
                false
            }
        }
        supportsMDoCached = result
        return result
    }

    private suspend fun pingOk(payloadSize: Int): Boolean = withContext(Dispatchers.IO) {
        // Linux/Android معادل: ping -M do -s <size> -c 1 -W 1 8.8.8.8
        val args = mutableListOf("/system/bin/ping", "-s", payloadSize.toString(), "-c", "1", "-W", "1", "8.8.8.8")
        if (supportsMDo()) {
            args.add(1, "-M")
            args.add(2, "do")
        }
        try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().use(BufferedReader::readText)
            val code = proc.waitFor()
            // موفق وقتی: کد خروج 0 و شامل ttl/time باشد
            code == 0 && (text.contains("ttl=") || text.contains("time="))
        } catch (_: Exception) {
            false
        }
    }

    /** جست‌وجوی دودویی برای بیشینهٔ payload بدون فروگمنت. بازگشت: MTU واقعی (payload+28) */
    suspend fun discoverBestMtu(maxProbeMtu: Int = 1500, minProbeMtu: Int = 576): Int {
        var low = max(68, minProbeMtu) // حداقل عملی IPv4
        var high = maxProbeMtu

        // به payload تبدیل می‌کنیم
        var lowPayload = low - IP_ICMP_HEADER
        var highPayload = high - IP_ICMP_HEADER
        lowPayload = max(0, lowPayload)

        var bestPayload = 0
        while (lowPayload <= highPayload) {
            val mid = (lowPayload + highPayload) / 2
            if (pingOk(mid)) {
                bestPayload = mid
                lowPayload = mid + 1
            } else {
                highPayload = mid - 1
            }
        }
        val bestMtu = bestPayload + IP_ICMP_HEADER
        // طبق خواسته: 35 واحد کمتر از بیشینهٔ بدون فروگمنت
        val adjusted = max(576, bestMtu - 35)
        return min(adjusted, maxProbeMtu)
    }
}
