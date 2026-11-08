package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.*
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

    fun fetchNotesForFolder(folderId: UInt) {
        viewModelScope.launch {
            try {
                val tree = repository.getFolderTree()
                // Sort by position in case the API doesn't return them sorted
                _notes.value = findFolderNotes(tree, folderId).sortedBy { it.position }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun findFolderNotes(folders: List<NoteFolderTreeResponse>, targetId: UInt): List<NoteResponse> {
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
                fetchNotesForFolder(folderId)
            }
        }
    }
    fun updateNoteLocally(noteId: UInt, newTitle: String, newContent: String) {
        // 1. Update the UI immediately
        _notes.value = _notes.value.map {
            if (it.id == noteId) {
                it.copy(title = newTitle, content = newContent)
            } else {
                it
            }
        }

        // 2. Send to API in the background
        val noteToUpdate = _notes.value.find { it.id == noteId } ?: return
        viewModelScope.launch {
            try {
                repository.updateNote(
                    noteId,
                    UpdateNoteRequest(
                        title = newTitle,
                        content = newContent,
                        style = noteToUpdate.style,
                        position = noteToUpdate.position
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // Optional: fetchNotesForFolder(folderId) to revert on failure
            }
        }
    }

    // NEW: Handle Drag & Drop Reordering
    fun moveNote(fromIndex: Int, toIndex: Int) {
        val currentList = _notes.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            // 1. Move the item locally
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)

            // 2. Update UI immediately
            _notes.value = currentList

            // 3. Sync to Backend
            viewModelScope.launch {
                try {
                    // Reassign position based on the new list order
                    currentList.forEachIndexed { index, note ->
                        if (note.position != index) {
                            val request = UpdateNoteRequest(
                                title = note.title,
                                content = note.content,
                                style = note.style,
                                position = index
                            )
                            repository.updateNote(note.id, request)
                            // Optional: Update the local item's position field
                            // currentList[index] = note.copy(position = index)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}