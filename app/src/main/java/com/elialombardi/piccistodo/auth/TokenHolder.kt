package com.elialombardi.piccistodo.auth

import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-local only. Nothing here is saved to preferences, saved state, disk or backups. */
object TokenHolder {
    // Not a data class: generated toString() must not expose bearer credentials.
    class Session(val accessToken: String, val role: String?)
    private val _session = MutableStateFlow<Session?>(null)
    val session = _session.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null
    private val _signedIn = MutableStateFlow(false)
    val signedIn = _signedIn.asStateFlow()

    @Volatile private var token: String? = null
    @Volatile var expiresAt: Long = 0L // elapsedRealtime, unaffected by clock changes
        private set

    val accessToken: String?
        @Synchronized get() {
            if (token != null && SystemClock.elapsedRealtime() >= expiresAt) clear()
            return token
        }

    @Synchronized
    fun set(accessToken: String, expiresIn: Long, role: String? = null) {
        require(accessToken.isNotBlank() && expiresIn in 1..31_536_000)
        clear()
        token = accessToken
        expiresAt = SystemClock.elapsedRealtime() + expiresIn * 1000
        _session.value = Session(accessToken, role?.takeIf { it.isNotBlank() } ?: tokenRole(accessToken))
        _signedIn.value = true
        expiryJob = scope.launch {
            delay(expiresIn * 1000)
            // Read via the getter to avoid clearing a newer session.
            this@TokenHolder.accessToken
        }
    }

    // UI hint only, not signature verification or authorization; the API enforces access.
    private fun tokenRole(accessToken: String): String? = runCatching {
        val parts = accessToken.split('.')
        if (parts.size != 3) return@runCatching null
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(payload).opt("role") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @Synchronized
    fun clearRejected(rejected: String) {
        if (token == rejected) clear()
    }

    @Synchronized
    fun clear() {
        expiryJob?.cancel()
        expiryJob = null
        token = null
        expiresAt = 0L
        _session.value = null
        _signedIn.value = false
    }
}
