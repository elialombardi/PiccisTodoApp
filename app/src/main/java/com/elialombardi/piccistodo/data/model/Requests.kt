package com.elialombardi.piccistodo.data.model

data class CreateFolderRequest(
    val name: String,
    val parentId: UInt? = null
)

data class UpdateFolderRequest(
    val name: String? = null,
    val parentId: UInt? = null
)

data class CreateNoteRequest(
    val folderId: UInt,
    val title: String,
    val content: String,
    val style: NoteStyle
)

data class UpdateNoteRequest(
    val folderId: UInt? = null,
    val title: String? = null,
    val content: String? = null,
    val style: NoteStyle? = null
)