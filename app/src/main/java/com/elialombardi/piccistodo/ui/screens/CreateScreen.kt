package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elialombardi.piccistodo.R
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
    var todoTitle by rememberSaveable { mutableStateOf(noteToEdit?.title.orEmpty()) }
    var noteContent by rememberSaveable { mutableStateOf(noteToEdit?.content.orEmpty()) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val isSaving by viewModel.isSaving.collectAsState()
    val back = {
        if (!isSaving) {
            if (todoTitle != noteToEdit?.title.orEmpty() || noteContent != noteToEdit?.content.orEmpty()) discard = true
            else onBackClick()
        }
    }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Note") },
                navigationIcon = {
                    IconButton(onClick = back, enabled = !isSaving) {
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.ui_notes_description), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
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
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
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
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.tasks_discard_title)) }, text = { Text(stringResource(R.string.tasks_discard_text)) },
        confirmButton = { TextButton(onClick = onBackClick) { Text(stringResource(R.string.tasks_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.tasks_keep_editing)) } })
}