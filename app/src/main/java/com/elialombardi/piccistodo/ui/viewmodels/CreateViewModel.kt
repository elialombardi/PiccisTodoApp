package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.CreateNoteRequest
import com.elialombardi.piccistodo.data.model.NoteStyle
import com.elialombardi.piccistodo.data.model.UpdateNoteRequest
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

    // UPDATED: Added optional noteId, currentPosition, and style for editing
    fun saveNote(
        collectionId: UInt,
        title: String,
        content: String,
        noteId: UInt? = null,
        currentPosition: Int = 0,
        existingStyle: NoteStyle = NoteStyle(),
        onSuccess: () -> Unit
    ) {
        if (title.isBlank()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (noteId == null) {
                    // CREATE NEW NOTE
                    val request = CreateNoteRequest(
                        folderId = collectionId,
                        title = title,
                        content = content,
                        style = existingStyle,
                        position = 0 // Or whatever your API requires for new notes
                    )
                    repository.createNote(request)
                } else {
                    // EDIT EXISTING NOTE
                    val request = UpdateNoteRequest(
                        title = title,
                        content = content,
                        style = existingStyle,
                        position = currentPosition
                    )
                    repository.updateNote(noteId, request)
                }
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSaving.value = false
            }
        }
    }
}