package com.elialombardi.piccistodo.data.model

data class TodoItem(
    val id: String,
    val title: String,
    val isDone: Boolean,
    val collectionId: String
)

