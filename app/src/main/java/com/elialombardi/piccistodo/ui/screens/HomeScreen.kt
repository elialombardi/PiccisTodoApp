package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.ui.viewmodels.HomeViewModel
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.ui.components.WorkspaceHero
import com.elialombardi.piccistodo.ui.components.WorkspaceEmptyState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCollectionClick: (UInt) -> Unit,
    onAddCollectionClick: () -> Unit,
    onAccountClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val folders by viewModel.folderTree.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.fetchFolders()
    }

    // Setup the reorderable state for folders
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = folders.indexOfFirst { it.id.toString() == from.key }
        val toIndex = folders.indexOfFirst { it.id.toString() == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            viewModel.moveFolder(fromIndex, toIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_collections)) },
                actions = {
                    IconButton(onClick = viewModel::fetchFolders, enabled = !isLoading) { Icon(Icons.Default.Refresh, stringResource(R.string.ui_refresh)) }
                    IconButton(onClick = onAccountClick) { Icon(Icons.Default.PersonOutline, stringResource(R.string.nav_account)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCollectionClick,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.ui_new_collection)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (folders.isEmpty()) {
                WorkspaceEmptyState(stringResource(R.string.ui_collections_empty), stringResource(R.string.ui_collections_empty_description), Icons.Default.FolderOpen)
            } else {
                LazyColumn(
                    state = listState, // Attach the state here
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "count") { Text(stringResource(R.string.ui_collection_count, folders.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    // Use itemsIndexed and a String key
                    itemsIndexed(folders, key = { _, folder -> folder.id.toString() }) { index, collection ->

                        // Wrap in ReorderableItem
                        ReorderableItem(reorderableLazyListState, key = collection.id.toString()) { isDragging ->
                            val elevation = if (isDragging) 8.dp else 0.dp

                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { dismissValue ->
                                    if (dismissValue == SwipeToDismissBoxValue.EndToStart ||
                                        dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                                        viewModel.deleteFolder(collection.id)
                                        true
                                    } else {
                                        false
                                    }
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
                                            contentDescription = stringResource(R.string.ui_delete_collection),
                                            tint = MaterialTheme.colorScheme.onError
                                        )
                                    }
                                },
                                content = {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onCollectionClick(collection.id) },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                                Icon(Icons.Default.FolderOpen, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(collection.name, style = MaterialTheme.typography.titleMedium)
                                                Text(stringResource(R.string.ui_note_count, collection.notes.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            // Drag Handle for reordering
                                            IconButton(
                                                modifier = Modifier.draggableHandle(),
                                                onClick = {}
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Menu,
                                                    contentDescription = stringResource(R.string.ui_reorder),
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
}