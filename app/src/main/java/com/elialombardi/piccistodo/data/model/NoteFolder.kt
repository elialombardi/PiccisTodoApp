package com.elialombardi.piccistodo.data.model

data class NoteFolderTreeResponse(
    val id: UInt,
    val name: String,
    val parentId: UInt?, // Nullable because top-level folders have no parent
    val children: List<NoteFolderTreeResponse>,
    val notes: List<NoteResponse>,
    val position: Int,
)