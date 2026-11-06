package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.local.SettingsManager
import com.elialombardi.piccistodo.data.model.LoginRequest
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    // We only need ONE login function now that handles the URL and the credentials
    fun saveUrlAndLogin(url: String, username: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // 1. Save the URL first so RetrofitProvider knows where to send the request
                settingsManager.saveApiUrl(url.trim())

                // 2. Now login using the repository
                val response = repository.login(LoginRequest(username, password))

                // 3. Save the token for future requests
                settingsManager.saveToken(response.token)

                onSuccess()
            } catch (e: Exception) {
                println("LOGIN_ERROR: ${e.message}")
                e.printStackTrace()
                _error.value = "Error: ${e.message}" // Shows the exact error (e.g. 401 Unauthorized)
            } finally {
                _isLoading.value = false
            }
        }
    }
}