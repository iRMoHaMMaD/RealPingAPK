package com.realping.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ناظر رویدادمحور وضعیت VPN.
 * – از default network callback برای دستگاه‌هایی که VPN را به‌عنوان شبکه‌ی پیش‌فرض ست می‌کنند.
 * – و از callback اختصاصی TRANSPORT_VPN برای پوشش OEMهایی که روی default هم رفتار خاص دارند.
 *
 * خروجی: StateFlow<Boolean?>  → true=VPN فعّال، false=غیرفعّال، null=نامشخص (اول راه‌اندازی).
 */
object VpnStateObserver {
    private val _vpnActive = MutableStateFlow<Boolean?>(null)
    val vpnActive: StateFlow<Boolean?> = _vpnActive.asStateFlow()

    @Volatile private var started = false
    private lateinit var cm: ConnectivityManager

    private val defaultCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _vpnActive.value = computeVpnActive()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _vpnActive.value = computeVpnActive()
        }
        override fun onLost(network: Network) {
            _vpnActive.value = computeVpnActive()
        }
    }

    private val vpnCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // یک VPN بالا آمده
            _vpnActive.value = true
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                _vpnActive.value = true
            } else {
                _vpnActive.value = computeVpnActive()
            }
        }
        override fun onLost(network: Network) {
            // ممکن است هنوز VPN دیگری باشد؛ دوباره محاسبه کن
            _vpnActive.value = computeVpnActive()
        }
    }

    fun start(context: Context) {
        if (started) return
        cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 1) پیش‌فرض: تغییر شبکه‌ی فعال
        runCatching { cm.registerDefaultNetworkCallback(defaultCb) }

        // 2) کمکی: گوش‌دادن به خودِ VPN (ممکن است روی بعضی دستگاه‌ها کار نکند؛ اشکالی ندارد)
        runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            cm.registerNetworkCallback(req, vpnCb)
        }

        started = true
        _vpnActive.value = computeVpnActive()
    }

    fun stop() {
        if (!started) return
        runCatching { cm.unregisterNetworkCallback(defaultCb) }
        runCatching { cm.unregisterNetworkCallback(vpnCb) }
        started = false
        _vpnActive.value = computeVpnActive()
    }

    private fun computeVpnActive(): Boolean {
        val an = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(an) ?: return false
        // اگر شبکه‌ی فعال «NOT_VPN» نداشته باشد یعنی VPN است.
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
