package com.elialombardi.piccistodo.data.model

data class CreateFolderRequest(
    val name: String,
    val parentId: UInt? = null
)

data class UpdateFolderRequest(
    val name: String? = null,
    val parentId: UInt? = null,
    val position: Int,
)

data class CreateNoteRequest(
    val folderId: UInt,
    val title: String,
    val content: String,
    val style: NoteStyle,
    val position: Int = 0 // Default to 0 if not provided
)

data class UpdateNoteRequest(
    val folderId: UInt? = null,
    val title: String? = null,
    val content: String? = null,
    val style: NoteStyle? = null,
    val position: Int
)