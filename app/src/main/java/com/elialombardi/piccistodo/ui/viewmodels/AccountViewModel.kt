package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.local.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(private val settings: SettingsManager) : ViewModel() {
    val server = settings.apiUrlFlow.map { url -> runCatching { URI(url.orEmpty()).host }.getOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val biometric = settings.biometricEnabledFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    fun biometrics(enabled: Boolean) = change { settings.setBiometricEnabled(enabled) }
    fun signOut(onDone: () -> Unit) = change { settings.clearSession(); onDone() }
    private fun change(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _failed.value = false
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _failed.value = true
            } finally { _busy.value = false }
        }
    }
}
