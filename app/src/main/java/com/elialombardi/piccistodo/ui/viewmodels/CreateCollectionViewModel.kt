package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.CreateFolderRequest
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateCollectionViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    fun saveFolder(name: String, onSuccess: () -> Unit) {
        if (name.isBlank()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                // Create the request object your Go API expects
                val request = CreateFolderRequest(name = name)

                // Call the repository
                repository.createFolder(request)
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSaving.value = false
            }
        }
    }
}