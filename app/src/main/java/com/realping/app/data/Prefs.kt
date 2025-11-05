package com.realping.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private const val DS_NAME = "realping_prefs"
val Context.realPingDataStore by preferencesDataStore(DS_NAME)

object PrefKeys {
    val SELECTED_ID = stringPreferencesKey("selected_id")
    val LAST_CONNECTED_ID = stringPreferencesKey("last_connected_id")
    val START_MS = longPreferencesKey("start_ms")
    val BASE_TX = longPreferencesKey("base_tx")
    val BASE_RX = longPreferencesKey("base_rx")
    val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
    val PROXY_BIND = stringPreferencesKey("proxy_bind") // NEW
}

suspend fun saveSelectedId(ctx: Context, id: String?) {
    ctx.realPingDataStore.edit { p ->
        if (id == null) p.remove(PrefKeys.SELECTED_ID)
        else p[PrefKeys.SELECTED_ID] = id
    }
}

suspend fun markConnected(
    ctx: Context,
    id: String,
    startMs: Long,
    baseTx: Long,
    baseRx: Long
) {
    ctx.realPingDataStore.edit { p ->
        p[PrefKeys.LAST_CONNECTED_ID] = id
        p[PrefKeys.START_MS] = startMs
        p[PrefKeys.BASE_TX] = baseTx
        p[PrefKeys.BASE_RX] = baseRx
    }
}

suspend fun markDisconnected(ctx: Context) {
    ctx.realPingDataStore.edit { p ->
        p.remove(PrefKeys.LAST_CONNECTED_ID)
        p.remove(PrefKeys.START_MS)
        p.remove(PrefKeys.BASE_TX)
        p.remove(PrefKeys.BASE_RX)
    }
}

suspend fun loadSession(ctx: Context): SessionSnapshot? {
    val p = ctx.realPingDataStore.data.first()
    val id = p[PrefKeys.LAST_CONNECTED_ID] ?: return null
    val start = p[PrefKeys.START_MS] ?: return null
    val btx = p[PrefKeys.BASE_TX] ?: 0L
    val brx = p[PrefKeys.BASE_RX] ?: 0L
    return SessionSnapshot(id, start, btx, brx)
}

data class SessionSnapshot(
    val id: String,
    val startMs: Long,
    val baseTx: Long,
    val baseRx: Long
)

suspend fun setProxyEnabled(ctx: Context, enabled: Boolean) {
    ctx.realPingDataStore.edit { it[PrefKeys.PROXY_ENABLED] = enabled }
}

suspend fun isProxyEnabled(ctx: Context): Boolean {
    val p = ctx.realPingDataStore.data.first()
    return p[PrefKeys.PROXY_ENABLED] ?: false
}

// ذخیره/بازیابی آدرس bind برای نمایش بعد از ری‌لانچ
suspend fun saveProxyBind(ctx: Context, bind: String?) {
    ctx.realPingDataStore.edit { p ->
        if (bind == null) p.remove(PrefKeys.PROXY_BIND)
        else p[PrefKeys.PROXY_BIND] = bind
    }
}

suspend fun loadProxyBind(ctx: Context): String? {
    val p = ctx.realPingDataStore.data.first()
    return p[PrefKeys.PROXY_BIND]
}
