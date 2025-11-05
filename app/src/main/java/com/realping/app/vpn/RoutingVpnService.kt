package com.realping.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.realping.app.R
import com.realping.app.ui.MainActivity

class RoutingVpnService : VpnService() {
    companion object { const val NOTIF_ID = 101; const val CHANNEL_ID = "realping_vpn" }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    fun foregroundNotification(isConnected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isConnected) getString(R.string.connected) else getString(R.string.connecting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // ✅
            .setContentTitle(title)
            .setContentText(getString(R.string.brand_realping))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
