package com.elialombardi.piccistodo.data.repository

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.remote.RetrofitProvider
import javax.inject.Inject

class TodoRepository @Inject constructor(
    private val apiProvider: RetrofitProvider
) {
    private val api get() = apiProvider.getApi()

    // Auth
    suspend fun login(request: LoginRequest) = api.login(request)

    // Folders
    suspend fun getFolderTree() = api.getFolderTree()
    suspend fun createFolder(request: CreateFolderRequest) = api.createFolder(request)
    suspend fun updateFolder(id: UInt, request: UpdateFolderRequest) = api.updateFolder(id, request)
    suspend fun deleteFolder(id: UInt) = api.deleteFolder(id)

    // Notes
    suspend fun createNote(request: CreateNoteRequest) = api.createNote(request)
    suspend fun updateNote(id: UInt, request: UpdateNoteRequest) = api.updateNote(id, request)
    suspend fun deleteNote(id: UInt) = api.deleteNote(id)
}