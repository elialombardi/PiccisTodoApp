package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.CreateNoteRequest
import com.elialombardi.piccistodo.data.model.NoteResponse
import com.elialombardi.piccistodo.data.model.NoteStyle
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _notes = MutableStateFlow<List<NoteResponse>>(emptyList())
    val notes: StateFlow<List<NoteResponse>> = _notes

    // Because the Go API returns the whole tree, we have to find the specific folder to get its notes
    fun fetchNotesForFolder(folderId: UInt) {
        viewModelScope.launch {
            try {
                val tree = repository.getFolderTree()
                _notes.value = findFolderNotes(tree, folderId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Helper function to recursively search the tree for the folder
    private fun findFolderNotes(folders: List<com.elialombardi.piccistodo.data.model.NoteFolderTreeResponse>, targetId: UInt): List<NoteResponse> {
        for (folder in folders) {
            if (folder.id == targetId) return folder.notes
            val found = findFolderNotes(folder.children, targetId)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    fun deleteNote(id: UInt, folderId: UInt) {
        _notes.value = _notes.value.filter { it.id != id }
        viewModelScope.launch {
            try {
                repository.deleteNote(id)
            } catch (e: Exception) {
                fetchNotesForFolder(folderId) // Revert on fail
            }
        }
    }
}