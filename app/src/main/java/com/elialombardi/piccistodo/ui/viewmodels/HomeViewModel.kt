package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.model.CreateFolderRequest
import com.elialombardi.piccistodo.data.model.NoteFolderTreeResponse
import com.elialombardi.piccistodo.data.model.UpdateFolderRequest
import com.elialombardi.piccistodo.data.repository.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: TodoRepository
) : ViewModel() {

    private val _folderTree = MutableStateFlow<List<NoteFolderTreeResponse>>(emptyList())
    val folderTree: StateFlow<List<NoteFolderTreeResponse>> = _folderTree

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun fetchFolders() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _folderTree.value = repository.getFolderTree()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createFolder(name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.createFolder(CreateFolderRequest(name = name))
                fetchFolders()
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    fun moveFolder(fromIndex: Int, toIndex: Int) {
        val currentList = _folderTree.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            // 1. Move the item locally
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)

            // 2. Update UI immediately
            _folderTree.value = currentList

            // 3. Sync to Backend
            viewModelScope.launch {
                try {
                    currentList.forEachIndexed { index, folder ->
                        if (folder.position != index) {
                            // Assuming you have an UpdateFolderRequest and updateFolder method
                            val request = UpdateFolderRequest(
                                name = folder.name,
                                position = index
                            )
                            repository.updateFolder(folder.id, request)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    fun deleteFolder(id: UInt) {
        // 1. Optimistically remove from UI
        _folderTree.value = _folderTree.value.filter { it.id != id }

        // 2. Call API to delete
        viewModelScope.launch {
            try {
                repository.deleteFolder(id)
                println("DEBUG: Folder $id deleted successfully")
            } catch (e: Exception) {
                println("DEBUG ERROR: Failed to delete folder: ${e.message}")
                // If it fails on the server, re-fetch the tree to restore it in the UI
                fetchFolders()
            }
        }
    }
}