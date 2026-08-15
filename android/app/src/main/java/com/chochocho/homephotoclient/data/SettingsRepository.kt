package com.chochocho.homephotoclient.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val serverUrl: String,
    val apiKey: String,
    val autoBackupEnabled: Boolean,
    val deviceName: String,
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[KEY_SERVER_URL] ?: "http://192.168.0.2:8080",
            apiKey = prefs[KEY_API_KEY] ?: "",
            autoBackupEnabled = prefs[KEY_AUTO_BACKUP] ?: false,
            deviceName = prefs[KEY_DEVICE_NAME] ?: android.os.Build.MODEL,
        )
    }

    suspend fun save(serverUrl: String, apiKey: String, deviceName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl.trim().trimEnd('/')
            prefs[KEY_API_KEY] = apiKey.trim()
            prefs[KEY_DEVICE_NAME] = deviceName.trim().ifEmpty { android.os.Build.MODEL }
        }
    }

    /** 기기 고유 ID — 최초 호출 시 생성되어 영구 고정. 서버가 사진 소유 기기를 식별하는 키. */
    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.map { it[KEY_DEVICE_ID] }.first()
        if (existing != null) return existing
        val generated = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { prefs -> prefs[KEY_DEVICE_ID] = generated }
        return generated
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_BACKUP] = enabled }
    }

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
