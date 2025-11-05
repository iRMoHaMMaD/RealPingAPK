package com.realping.app.vpn

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.backend.Tunnel.State
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

object VpnController : Tunnel {

    private const val TUN_NAME = "rp0"

    override fun getName(): String = TUN_NAME
    override fun onStateChange(newState: State) { /* hook اختیاری */ }

    @Volatile
    private var backend: GoBackend? = null
    private val opMutex = Mutex()
    private val statsLock = Any()
    @Volatile
    private var lastTxBytes: Long = 0L
    @Volatile
    private var lastRxBytes: Long = 0L

    // --- helpers -------------------------------------------------------------

    private fun ensureBackend(ctx: Context): GoBackend {
        val be = backend
        if (be != null) return be
        return GoBackend(ctx.applicationContext).also { backend = it }
    }

    private fun startVpnService(ctx: Context) {
        ctx.startService(Intent(ctx, RealPingVpnService::class.java))
    }

    private fun stopVpnService(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, RealPingVpnService::class.java)) }
    }

    private fun trafficStatsCumulative(ctx: Context): Pair<Long, Long> {
        val uid = ctx.applicationInfo.uid
        val tx = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        val rx = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
        return tx to rx
    }

    // --- API ----------------------------------------------------------------

    suspend fun connectWithConfigText(ctx: Context, configText: String) = opMutex.withLock {
        val prepared = VpnService.prepare(ctx)
        require(prepared == null) { "مجوز VPN هنوز گرفته نشده است." }

        // لازم: سرویس باید بالا باشد تا protect(fd) قابل دسترس باشد
        startVpnService(ctx)
        resetStatsCache()

        val cfg = withContext(Dispatchers.Default) {
            Config.parse(ByteArrayInputStream(configText.toByteArray()))
        }
        withContext(Dispatchers.IO) {
            ensureBackend(ctx).setState(this@VpnController, State.UP, cfg)
        }
    }

    suspend fun disconnect(ctx: Context) = opMutex.withLock {
        val be = backend ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching { be.setState(this@VpnController, State.DOWN, null) }
        }
        // سرویس را هم پایین بیاورید تا سیستم revive نکند
        stopVpnService(ctx)
        // برای شروعِ پاک در ریلانچ
        backend = null
        resetStatsCache()
    }

    fun getState(ctx: Context): State = ensureBackend(ctx).getState(this)
    fun isUp(ctx: Context): Boolean = getState(ctx) == State.UP

    /**
     * cumulative bytes.
     * اگر مقدار backend صفر بود، از TrafficStats به‌عنوان fallback استفاده می‌شود.
     */
    fun getStatisticsNow(ctx: Context): Pair<Long, Long> {
        // ابتدا تلاش از backend
        var rawTx = 0L
        var rawRx = 0L
        var fromBackend = false
        try {
            val stats = ensureBackend(ctx).getStatistics(this)
            val (tx, rx) = readBackendStatsSafely(stats)
            if (tx > 0L || rx > 0L) {
                rawTx = tx
                rawRx = rx
                fromBackend = true
            }
            // اگر هردو صفر/نامعتبر بودند، می‌افتیم روی TrafficStats
        } catch (_: Throwable) {
            // در خطا هم می‌افتیم روی TrafficStats
        }
        if (!fromBackend) {
            val (fallbackTx, fallbackRx) = trafficStatsCumulative(ctx)
            rawTx = fallbackTx
            rawRx = fallbackRx
        }

        val normalized = synchronized(statsLock) {
            val tx = if (rawTx >= lastTxBytes) rawTx else lastTxBytes
            val rx = if (rawRx >= lastRxBytes) rawRx else lastRxBytes
            lastTxBytes = tx
            lastRxBytes = rx
            tx to rx
        }
        return normalized
    }

    // --- reflection helpers (همان منطق نسخهٔ شما) -------------------------

    private fun readBackendStatsSafely(stats: Any): Pair<Long, Long> {
        val txM = arrayOf(
            "getTotalTx", "getTxBytes", "getTxTotal", "getSentBytes",
            "getTx", "getTotalTransmit", "getTransmitBytes"
        )
        val rxM = arrayOf(
            "getTotalRx", "getRxBytes", "getRxTotal", "getReceivedBytes",
            "getRx", "getTotalReceive", "getReceiveBytes"
        )
        val tx1 = firstLongFromMethods(stats, txM)
        val rx1 = firstLongFromMethods(stats, rxM)
        if (tx1 != null || rx1 != null) return (tx1 ?: 0L) to (rx1 ?: 0L)

        val txF = arrayOf("txBytes", "tx", "txTotal", "sentBytes", "transmitBytes")
        val rxF = arrayOf("rxBytes", "rx", "rxTotal", "receivedBytes", "receiveBytes")
        val tx2 = firstLongFromFields(stats, txF)
        val rx2 = firstLongFromFields(stats, rxF)
        return (tx2 ?: 0L) to (rx2 ?: 0L)
    }

    private fun firstLongFromMethods(target: Any, names: Array<String>): Long? {
        for (n in names) {
            try {
                val m = target.javaClass.getMethod(n)
                val v = m.invoke(target)
                if (v is Number) {
                    val value = v.toLong()
                    if (value > 0L) return value
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun firstLongFromFields(target: Any, names: Array<String>): Long? {
        for (n in names) {
            try {
                val f = target.javaClass.getDeclaredField(n)
                f.isAccessible = true
                val v = f.get(target)
                if (v is Number) {
                    val value = v.toLong()
                    if (value > 0L) return value
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun resetStatsCache() {
        synchronized(statsLock) {
            lastTxBytes = 0L
            lastRxBytes = 0L
        }
    }
}
