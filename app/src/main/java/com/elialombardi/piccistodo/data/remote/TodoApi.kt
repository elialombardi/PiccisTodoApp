package com.elialombardi.piccistodo.data.remote

import com.elialombardi.piccistodo.data.model.*
import retrofit2.http.*

interface TodoApi {
    // Folder Endpoints
    @GET("api/note-folders/tree")
    suspend fun getFolderTree(): List<NoteFolderTreeResponse>

    @POST("api/note-folders/")
    suspend fun createFolder(@Body request: CreateFolderRequest): NoteFolderTreeResponse

    @PUT("api/note-folders/{id}")
    suspend fun updateFolder(@Path("id") id: UInt, @Body request: UpdateFolderRequest): NoteFolderTreeResponse

    @DELETE("api/note-folders/{id}")
    suspend fun deleteFolder(@Path("id") id: UInt)

    // Note Endpoints
    @POST("api/notes/")
    suspend fun createNote(@Body request: CreateNoteRequest): NoteResponse

    @PUT("api/notes/{id}")
    suspend fun updateNote(@Path("id") id: UInt, @Body request: UpdateNoteRequest): NoteResponse

    @DELETE("api/notes/{id}")
    suspend fun deleteNote(@Path("id") id: UInt)

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse
}