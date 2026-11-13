package com.elialombardi.piccistodo.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elialombardi.piccistodo.BuildConfig
import com.elialombardi.piccistodo.auth.TokenHolder
import com.elialombardi.piccistodo.auth.ServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
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
    private val urlMutex = Mutex()
    @Volatile private var cachedApiUrl: String? = null

    // Migration deletes legacy encrypted/plaintext JWTs without ever loading them.
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            context.dataStore.edit { it.remove(tokenKey); it.remove(roleKey) }
            getApiUrl()
        }
    }

    // 3. Flows for the UI
    val apiUrlFlow: Flow<String> = context.dataStore.data.map {
        it[apiUrlKey]?.let(ServerUrl::normalize) ?: BuildConfig.BASE_URL
    }
    val sessionFlow = combine(TokenHolder.session, apiUrlFlow) { session, url ->
        Triple(session?.accessToken, url, session?.role)
    }

    val biometricEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[biometricEnabledKey] ?: false }

    val loginSessionFlow: Flow<LoginSession> = combine(TokenHolder.signedIn, biometricEnabledFlow) { _, enabled ->
        val usable = TokenHolder.accessToken != null
        LoginSession(usable, usable, enabled)
    }

    // 4. Sync getters (same pattern as getTokenSync / getApiUrlSync)
    fun getTokenSync(): String? {
        return TokenHolder.accessToken
    }

    // Login awaits getApiUrl/saveApiUrl before any authenticated client is used.
    fun getApiUrlSync(): String = cachedApiUrl ?: BuildConfig.BASE_URL

    suspend fun getApiUrl(): String = withContext(Dispatchers.IO) {
        urlMutex.withLock {
            cachedApiUrl ?: apiUrlFlow.first().also { cachedApiUrl = it }
        }
    }

    @Synchronized
    fun getTokenForUrl(url: HttpUrl): String? =
        if (ServerUrl.origin(url) == getApiUrlSync()) TokenHolder.accessToken else null

    fun hasStoredToken(): Boolean = getTokenSync()?.isNotBlank() == true

    suspend fun saveApiUrl(url: String) {
        val normalized = requireNotNull(ServerUrl.normalize(url)) { ServerUrl.validationMessage }
        withContext(Dispatchers.IO) {
            urlMutex.withLock {
                val previous = cachedApiUrl ?: apiUrlFlow.first()
                context.dataStore.edit { prefs -> prefs[apiUrlKey] = normalized }
                synchronized(this@SettingsManager) {
                    if (previous != normalized) TokenHolder.clear()
                    cachedApiUrl = normalized
                }
            }
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[biometricEnabledKey] = enabled }
    }

    suspend fun clearToken() {
        clearSession()
    }

    fun clearRejectedToken(rejected: String) {
        TokenHolder.clearRejected(rejected)
    }

    /**
     * Logout clears RAM, not the registered device key. Future biometric login
     * must obtain a fresh single-use challenge and a newly issued server token.
     */
    suspend fun clearSession() {
        TokenHolder.clear()
        context.dataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(roleKey)
        }
    }
}