package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.local.SettingsManager
import com.elialombardi.piccistodo.data.local.LoginSession
import com.elialombardi.piccistodo.data.model.LoginRequest
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // 1. State to hold the saved URL so the UI can pre-fill it
    private val _savedUrl = MutableStateFlow("")
    val savedUrl: StateFlow<String> = _savedUrl

    // 2. Biometric-related state
    private val _biometricAvailable = MutableStateFlow(false)
    val biometricAvailable: StateFlow<Boolean> = _biometricAvailable

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled

    private val _hasStoredSession = MutableStateFlow(false)
    val hasStoredSession: StateFlow<Boolean> = _hasStoredSession
    private val _sessionChecked = MutableStateFlow(false)
    val sessionChecked: StateFlow<Boolean> = _sessionChecked

    /**
     * Emits true once after a fresh successful password login when we should
     * offer the user an "Enable fingerprint login?" prompt. The UI consumes
     * this and resets it via [onBiometricPromptHandled].
     */
    private val _shouldOfferBiometricEnrollment = MutableStateFlow(false)
    val shouldOfferBiometricEnrollment: StateFlow<Boolean> = _shouldOfferBiometricEnrollment


        init {
            viewModelScope.launch {
                val url = settingsManager.getApiUrlSync()
                if (url != null) {
                    _savedUrl.value = url
                }

                settingsManager.loginSessionFlow.collect { applySession(it) }
            }
    }

    private suspend fun applySession(session: LoginSession): Boolean {
        _hasStoredSession.value = session.usable
        _biometricEnabled.value = session.usable && session.biometricEnabled
        _sessionChecked.value = true
        if (!session.usable) {
            _shouldOfferBiometricEnrollment.value = false
            if (session.hasToken) {
                _error.value = "Session expired. Enter your username and password to sign in again."
                settingsManager.clearSession()
            }
        }
        return session.usable
    }

    fun refreshSession() {
        viewModelScope.launch { applySession(settingsManager.loginSessionFlow.first()) }
    }

    fun requestBiometricLogin(showPrompt: () -> Unit) {
        if (_isLoading.value) return
        viewModelScope.launch {
            if (applySession(settingsManager.loginSessionFlow.first()) && _biometricEnabled.value && _biometricAvailable.value)
                showPrompt()
        }
    }

    /**
     * Called from the UI (LaunchedEffect) with the hosting FragmentActivity.
     * Fragments/Compose need the activity anyway to construct BiometricPrompt,
     * so passing it here keeps the ViewModel free of Android UI types.
     */
    fun checkBiometricAvailability(isAvailable: Boolean) {
        _biometricAvailable.value = isAvailable
    }

    fun saveUrlAndLogin(url: String, username: String, password: String, onSuccess: () -> Unit) {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                settingsManager.saveApiUrl(url.trim())

                val response = repository.login(LoginRequest(username, password))

                settingsManager.saveToken(response.token, response.role)

                // Refresh session state
                _hasStoredSession.value = true

                // Offer biometric enrollment on first successful login
                // (only if biometrics are available and not already enabled)
                if (_biometricAvailable.value && !_biometricEnabled.value) {
                    _shouldOfferBiometricEnrollment.value = true
                }

                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = when (e) {
                    is java.io.IOException -> "Cannot connect. Check your connection and server address."
                    is retrofit2.HttpException -> if (e.code() == 401) "Incorrect username or password." else "Sign-in failed. Check your access and try again."
                    else -> "Could not sign in. Check your server address and try again."
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Called after a successful BiometricPrompt authentication.
     * The token is already stored, so we just refresh local state and
     * bubble success up to the UI.
     */
    fun onBiometricSuccess(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _error.value = null
            // Fingerprint unlocks a valid stored session; it cannot renew a JWT.
            if (!applySession(settingsManager.loginSessionFlow.first()) || !_biometricEnabled.value) {
                _error.value = "Session expired. Enter your username and password to sign in again."
                return@launch
            }
            onSuccess()
        }
    }

    fun onBiometricError(message: String) {
        // Only surface real errors; user-cancellation is not an error worth showing
        // (you can filter by errorCode in BiometricHelper if you want to be stricter)
        _error.value = message
    }

    /**
     * Persists the user's choice after they respond to the "Enable fingerprint
     * login?" dialog. Call with `true` if they accepted, `false` if they declined.
     */
    fun handleBiometricEnrollmentChoice(enabled: Boolean, onDone: () -> Unit = {}) {
        if (_isLoading.value || !_shouldOfferBiometricEnrollment.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                settingsManager.setBiometricEnabled(enabled)
                _biometricEnabled.value = enabled
                _shouldOfferBiometricEnrollment.value = false
                onDone()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = "Could not save your preference. Please try again."
            } finally { _isLoading.value = false }
        }
    }

    fun clearError() {
        _error.value = null
    }
}