package com.elialombardi.piccistodo.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class SettingsManager @Inject constructor(private val context: Context) {

    // 1. Keys
    private val tokenKey = stringPreferencesKey("jwt_token")
    private val apiUrlKey = stringPreferencesKey("api_url")
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")

    // 2. In-memory caches
    private var cachedToken: String? = null
    private var cachedApiUrl: String? = null
    private var cachedBiometricEnabled: Boolean? = null

    // 3. Flows for the UI
    val apiUrlFlow: Flow<String?> = context.dataStore.data.map { it[apiUrlKey] }

    val biometricEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[biometricEnabledKey] ?: false }

    // 4. Sync getters (same pattern as getTokenSync / getApiUrlSync)
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

    /**
     * Boolean pref — note that we use `Boolean?` in the cache so we can
     * distinguish "not yet loaded" (null) from "loaded and false".
     */
    fun isBiometricEnabled(): Boolean {
        cachedBiometricEnabled?.let { return it }
        val loaded = runBlocking {
            context.dataStore.data.map { it[biometricEnabledKey] ?: false }.first()
        }
        cachedBiometricEnabled = loaded
        return loaded
    }

    fun hasStoredToken(): Boolean = getTokenSync()?.isNotBlank() == true

    // 5. Setters that update both DataStore and the memory cache
    suspend fun saveToken(token: String) {
        context.dataStore.edit { prefs -> prefs[tokenKey] = token }
        cachedToken = token
    }

    suspend fun saveApiUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[apiUrlKey] = url }
        cachedApiUrl = url
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[biometricEnabledKey] = enabled }
        cachedBiometricEnabled = enabled
    }

    suspend fun clearToken() {
        context.dataStore.edit { prefs -> prefs.remove(tokenKey) }
        cachedToken = null
    }

    /**
     * Full logout: clears the token AND disables biometric auto-login.
     * Without this, a stale biometric flag could re-authenticate against a
     * null token, or worse, log the next user into the previous session.
     */
    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs[biometricEnabledKey] = false
        }
        cachedToken = null
        cachedBiometricEnabled = false
    }
}