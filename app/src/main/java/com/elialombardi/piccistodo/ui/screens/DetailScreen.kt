package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.ui.components.WorkspaceEmptyState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.data.model.NoteResponse
import com.elialombardi.piccistodo.ui.viewmodels.DetailViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    collectionId: UInt,
    onAddItemClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val items by viewModel.notes.collectAsState()
    val listState = rememberLazyListState()

    // State for editing
    var noteToEdit by remember { mutableStateOf<NoteResponse?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.fetchNotesForFolder(collectionId)
    }

    if (showEditDialog && noteToEdit != null) {
        EditNoteDialog(
            note = noteToEdit!!,
            onDismiss = { showEditDialog = false },
            onSave = { newTitle, newContent ->
                viewModel.updateNoteLocally(noteToEdit!!.id, newTitle, newContent)
                showEditDialog = false
            }
        )
    }

    // 1. Setup the reorderable state
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = items.indexOfFirst { it.id.toString() == from.key }
        val toIndex = items.indexOfFirst { it.id.toString() == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            viewModel.moveNote(fromIndex, toIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_notes)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddItemClick,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.ui_new_note)) }
            )
        }
    ) { paddingValues ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                WorkspaceEmptyState(stringResource(R.string.ui_new_note), stringResource(R.string.ui_notes_description), Icons.Default.Notes)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "intro") { Text(stringResource(R.string.ui_notes_description), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(items, key = { _, note -> note.id.toString() }) { index, note ->

                    // 2. Wrap the item in ReorderableItem
                    ReorderableItem(reorderableLazyListState, key = note.id.toString()) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp

                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                if (dismissValue == SwipeToDismissBoxValue.EndToStart ||
                                    dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                    viewModel.deleteNote(note.id, collectionId)
                                    true
                                } else false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color, shape = MaterialTheme.shapes.medium)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            },
                            content = {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = note.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (note.content.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = note.content,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        // Edit Button
                                        IconButton(onClick = {
                                            noteToEdit = note
                                            showEditDialog = true
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Note")
                                        }

                                        // 3. Drag Handle (prevents Swipe/Drag conflict)
                                        IconButton(
                                            modifier = Modifier.draggableHandle(),
                                            onClick = {}
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "Drag to reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// --- ADDED THE EDIT DIALOG HERE ---
@Composable
fun EditNoteDialog(
    note: NoteResponse,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(note.title) }
    var content by remember { mutableStateOf(note.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, content) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}