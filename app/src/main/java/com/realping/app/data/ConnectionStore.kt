package com.realping.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

class ConnectionStore(private val context: Context) {

    // ✅ فقط از همون DataStore سراسری پروژه استفاده کن
    private val ds get() = context.applicationContext.realPingDataStore

    private object Keys {
        val LAST_CONNECTED_AT = longPreferencesKey("last_connected_at")
        val LAST_TX = longPreferencesKey("last_tx")
        val LAST_RX = longPreferencesKey("last_rx")
        val SELECTED_ID = stringPreferencesKey("selected_id")
    }

    suspend fun writeConnectedMeta(startAt: Long) {
        ds.edit {
            it[Keys.LAST_CONNECTED_AT] = startAt
            it[Keys.LAST_TX] = 0L
            it[Keys.LAST_RX] = 0L
        }
    }

    suspend fun writeStats(tx: Long, rx: Long) {
        ds.edit {
            it[Keys.LAST_TX] = tx
            it[Keys.LAST_RX] = rx
        }
    }

    suspend fun readConnectionMeta(): Triple<Long, Long, Long> {
        val prefs = ds.data.first()
        val t0 = prefs[Keys.LAST_CONNECTED_AT] ?: 0L
        val tx = prefs[Keys.LAST_TX] ?: 0L
        val rx = prefs[Keys.LAST_RX] ?: 0L
        return Triple(t0, tx, rx)
    }

    suspend fun clearConnectionMeta() {
        ds.edit {
            it[Keys.LAST_CONNECTED_AT] = 0L
            it[Keys.LAST_TX] = 0L
            it[Keys.LAST_RX] = 0L
        }
    }

    suspend fun getSelectedId(): String? {
        val prefs = ds.data.first()
        return prefs[Keys.SELECTED_ID]
    }

    suspend fun setSelectedId(id: String?) {
        ds.edit {
            if (id == null) it.remove(Keys.SELECTED_ID) else it[Keys.SELECTED_ID] = id
        }
    }
}
