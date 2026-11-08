package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elialombardi.piccistodo.data.model.NoteResponse
import com.elialombardi.piccistodo.data.model.NoteStyle
import com.elialombardi.piccistodo.ui.viewmodels.CreateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    collectionId: UInt,
    onSaved: () -> Unit,
    onBackClick: () -> Unit,
    noteToEdit: NoteResponse? = null, // NEW: Pass existing note if editing
    viewModel: CreateViewModel = hiltViewModel()
) {
    var todoTitle by remember { mutableStateOf("") }
    // Optional: If you want to support notes with content, add a state for it
    var noteContent by remember { mutableStateOf("") }
    val isSaving by viewModel.isSaving.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Note") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = todoTitle,
                onValueChange = { todoTitle = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Optional: Add a content field since your Go API supports it
            OutlinedTextField(
                value = noteContent,
                onValueChange = { noteContent = it },
                label = { Text("Content (Optional)") },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                enabled = !isSaving
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    // CHANGED: Call saveNote and pass the content
                    viewModel.saveNote(
                        collectionId = collectionId,
                        title = todoTitle,
                        content = noteContent,
                        noteId = noteToEdit?.id, // Null if creating, ID if editing
                        currentPosition = noteToEdit?.position ?: 0,
                        existingStyle = noteToEdit?.style ?: NoteStyle()
                    ) {
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = todoTitle.isNotBlank() && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Save Note")
                }
            }
        }
    }
}