package com.elialombardi.piccistodo.data.model

import com.google.gson.annotations.SerializedName

data class NoteStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val textColor: String = "",
    val backgroundColor: String = "",
    val fontSize: Int = 14
)

data class NoteResponse(
    val id: UInt,
    val folderId: UInt,
    val title: String,
    val content: String,
    val style: NoteStyle,
    val createdAt: String, // We can keep it as String for now, or parse to Date
    val updatedAt: String
)