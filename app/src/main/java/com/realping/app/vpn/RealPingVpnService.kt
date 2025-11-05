package com.realping.app.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService

class RealPingVpnService : VpnService() {

    override fun onCreate() {
        super.onCreate()
        // تابع protect(fd) را در اختیار Backend بگذاریم
        VpnProtectHolder.protectFd = { fd -> protect(fd) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // نمی‌خواهیم سرویس بعد از kill/relauch خودش خودکار برگردد.
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        // کاربر از تنظیمات VPN را خاموش کرد
        VpnProtectHolder.protectFd = null
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        VpnProtectHolder.protectFd = null
        super.onDestroy()
    }
}
