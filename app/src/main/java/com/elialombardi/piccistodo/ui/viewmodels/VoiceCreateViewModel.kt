package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel // <--- THIS IS CRITICAL
class VoiceCreateViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText

    fun onTextRecognized(text: String) {
        _recognizedText.value = text
    }

    fun parseAndSave(text: String) {
        viewModelScope.launch {
            // Here we will put the logic to parse the string and call the API
            // For now, let's just log it to ensure it works
            println("DEBUG: Saving voice text: $text")
        }
    }
}