package com.elialombardi.piccistodo.ui.viewmodels

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.BuildConfig
import com.elialombardi.piccistodo.auth.*
import com.elialombardi.piccistodo.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository,
    private val login: BiometricLoginUseCase,
    private val biometrics: BiometricAuthenticator,
    private val settings: SettingsManager
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<AuthError?>(null)
    val error = _error.asStateFlow()
    private val _hasKey = MutableStateFlow(false)
    val hasKey = _hasKey.asStateFlow()
    private val _serverUrl = MutableStateFlow(BuildConfig.BASE_URL)
    val serverUrl = _serverUrl.asStateFlow()
    private var serverEdited = false
    private val _sessionChecked = MutableStateFlow(false)
    val sessionChecked = _sessionChecked.asStateFlow()
    // Name retained for navigation compatibility; nothing is stored on disk.
    val hasStoredSession = TokenHolder.signedIn
    private var activeLogin: Job? = null

    init {
        viewModelScope.launch {
            val saved = settings.getApiUrl()
            if (!serverEdited) _serverUrl.value = saved
        }
        refreshSession()
    }

    fun updateServerUrl(value: String) {
        if (_isLoading.value) return
        serverEdited = true
        _serverUrl.value = value
        if (_error.value is AuthError.ServerConfiguration) _error.value = null
    }

    private suspend fun configureServer() {
        val selected = if (serverEdited) _serverUrl.value else settings.getApiUrl()
        val normalized = ServerUrl.normalize(selected) ?: throw AuthError.ServerConfiguration()
        settings.saveApiUrl(normalized)
        _serverUrl.value = normalized
    }

    fun refreshSession() {
        viewModelScope.launch {
            try { _hasKey.value = login.hasKey() }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { _hasKey.value = false }
            TokenHolder.accessToken // Enforce expiry after resuming from sleep.
            _sessionChecked.value = true
        }
    }

    fun enroll(activity: FragmentActivity, username: String, password: String, onSuccess: () -> Unit) = runLogin {
        configureServer()
        biometrics.checkAvailability(activity)
        TokenHolder.clear()
        var registrationJwt: String? = repository.passwordLogin(username.trim(), password)
        try { login.enrollDevice(requireNotNull(registrationJwt)) }
        finally { registrationJwt = null } // Best-effort reference release; never SavedStateHandle.
        _hasKey.value = true
        settings.setBiometricEnabled(true)
        // The password JWT is not used as an app session. Require signed challenge login.
        login.login(activity)
        onSuccess()
    }

    fun signIn(activity: FragmentActivity, onSuccess: () -> Unit) = runLogin {
        configureServer()
        biometrics.checkAvailability(activity)
        if (!settings.biometricEnabledFlow.first()) throw AuthError.DeviceNotFound()
        TokenHolder.clear()
        login.login(activity)
        onSuccess()
    }

    private fun runLogin(block: suspend () -> Unit) {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        activeLogin = viewModelScope.launch {
            try { block() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: AuthError) { _error.value = error }
            catch (_: Exception) { _error.value = AuthError.InvalidResponse() }
            finally { _isLoading.value = false }
        }
    }

    // A rotated/backgrounded screen never resumes a stale prompt/challenge.
    fun cancelLogin() { activeLogin?.cancel() }
}