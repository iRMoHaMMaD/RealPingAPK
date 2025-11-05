package com.realping.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * واچرِ وضعیت VPN سیستم: به‌محض onLost → active=false می‌شود.
 * ازش فقط برای تشخیص «قطع از بیرون» استفاده می‌کنیم.
 */
object VpnStateWatcher {
    private val started = AtomicBoolean(false)
    private lateinit var cm: ConnectivityManager
    private var cb: ConnectivityManager.NetworkCallback? = null

    // true = حداقل یک شبکه با TRANSPORT_VPN فعاله
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun start(ctx: Context) {
        if (started.getAndSet(true)) return
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // مقدار اولیه
        _active.value = isVpnActiveNow()

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _active.value = true
            }
            override fun onLost(network: Network) {
                // ممکنه هم‌زمان VPN دیگری هم روشن باشد، پس دوباره چک می‌کنیم
                _active.value = isVpnActiveNow()
            }
        }
        cb = callback
        cm.registerNetworkCallback(req, callback)
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
    }

    private fun isVpnActiveNow(): Boolean {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }
}
