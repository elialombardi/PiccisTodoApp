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

data class LoginSession(val hasToken: Boolean, val usable: Boolean, val biometricEnabled: Boolean)

@Singleton
class SettingsManager @Inject constructor(private val context: Context) {

    // 1. Keys
    private val tokenKey = stringPreferencesKey("jwt_token")
    private val roleKey = stringPreferencesKey("session_role")
    private val apiUrlKey = stringPreferencesKey("api_url")
    private val biometricEnabledKey = booleanPreferencesKey("biometric_enabled")

    // 2. In-memory caches
    private var cachedToken: String? = null
    private var cachedApiUrl: String? = null
    private var cachedBiometricEnabled: Boolean? = null

    // 3. Flows for the UI
    val apiUrlFlow: Flow<String?> = context.dataStore.data.map { it[apiUrlKey] }
    // Opaque encrypted-token identity: never display or log this value.
    val sessionFlow = context.dataStore.data.map { Triple(it[tokenKey], it[apiUrlKey], it[roleKey]) }

    val biometricEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[biometricEnabledKey] ?: false }

    val loginSessionFlow: Flow<LoginSession> = context.dataStore.data.map { prefs ->
        val stored = prefs[tokenKey]
        val token = runCatching {
            if (stored != null && TokenCipher.encrypted(stored)) TokenCipher.decrypt(stored) else stored
        }.getOrNull()
        LoginSession(stored != null, JwtSession.usable(token), prefs[biometricEnabledKey] ?: false)
    }

    // 4. Sync getters (same pattern as getTokenSync / getApiUrlSync)
    fun getTokenSync(): String? {
        if (cachedToken != null) return cachedToken
        cachedToken = runBlocking {
            val stored = context.dataStore.data.map { it[tokenKey] }.first() ?: return@runBlocking null
            try {
                if (TokenCipher.encrypted(stored)) TokenCipher.decrypt(stored)
                else {
                    val encrypted = TokenCipher.encrypt(stored)
                    context.dataStore.edit { it[tokenKey] = encrypted }
                    stored
                }
            } catch (_: java.security.GeneralSecurityException) {
                context.dataStore.edit { it.remove(tokenKey) }
                null
            } catch (_: IllegalArgumentException) {
                context.dataStore.edit { it.remove(tokenKey) }
                null
            }
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
    suspend fun saveToken(token: String, role: String? = null) {
        val encrypted = TokenCipher.encrypt(token)
        context.dataStore.edit { prefs ->
            prefs[tokenKey] = encrypted
            if (role == null) prefs.remove(roleKey) else prefs[roleKey] = role
        }
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
        clearSession()
    }

    suspend fun clearRejectedToken(rejected: String) {
        var cleared = false
        context.dataStore.edit { prefs ->
            val stored = prefs[tokenKey]
            val current = runCatching {
                if (stored != null && TokenCipher.encrypted(stored)) TokenCipher.decrypt(stored) else stored
            }.getOrNull()
            if (current == rejected) {
                prefs.remove(tokenKey)
                prefs.remove(roleKey)
                prefs[biometricEnabledKey] = false
                cleared = true
            }
        }
        if (cleared) {
            cachedToken = null
            cachedBiometricEnabled = false
        }
    }

    /**
     * Full logout: clears the token AND disables biometric auto-login.
     * Without this, a stale biometric flag could re-authenticate against a
     * null token, or worse, log the next user into the previous session.
     */
    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(roleKey)
            prefs[biometricEnabledKey] = false
        }
        cachedToken = null
        cachedBiometricEnabled = false
    }
}