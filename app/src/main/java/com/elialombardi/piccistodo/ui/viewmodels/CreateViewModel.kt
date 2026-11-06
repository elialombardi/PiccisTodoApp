package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.CreateNoteRequest
import com.elialombardi.piccistodo.data.model.NoteStyle
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    // Changed the signature to match what your UI needs to send
    fun saveNote(collectionId: UInt, title: String, content: String, onSuccess: () -> Unit) {
        if (title.isBlank()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                // Create the request object exactly as the Go API expects it
                val request = CreateNoteRequest(
                    folderId = collectionId,
                    title = title,
                    content = content,
                    style = NoteStyle() // Default style
                )

                // Call the repository
                repository.createNote(request)
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSaving.value = false
            }
        }
    }
}