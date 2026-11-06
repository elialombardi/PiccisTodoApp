package com.elialombardi.piccistodo.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class SettingsManager @Inject constructor(private val context: Context) {
    private val tokenKey = stringPreferencesKey("jwt_token")
    private val apiUrlKey = stringPreferencesKey("api_url")

    // Cache values in memory so the OkHttp interceptor doesn't deadlock
    private var cachedToken: String? = null
    private var cachedApiUrl: String? = null

    fun getTokenSync(): String? {
        if (cachedToken != null) return cachedToken
        cachedToken = runBlocking {
            context.dataStore.data.map { it[tokenKey] }.first()
        }
        return cachedToken
    }

    fun getApiUrlSync(): String? {
        if (cachedApiUrl != null) return cachedApiUrl
        cachedApiUrl = runBlocking {
            context.dataStore.data.map { it[apiUrlKey] }.first()
        }
        return cachedApiUrl
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { it[tokenKey] = token }
        cachedToken = token
    }

    suspend fun saveApiUrl(url: String) {
        context.dataStore.edit { it[apiUrlKey] = url }
        cachedApiUrl = url
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(tokenKey) }
        cachedToken = null
    }
}