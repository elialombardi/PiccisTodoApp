package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elialombardi.piccistodo.ui.viewmodels.HomeViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCollectionClick: (UInt) -> Unit,
    onAddCollectionClick: () -> Unit, // <--- ADD THIS
    viewModel: HomeViewModel = hiltViewModel()
) {
    val folders by viewModel.folderTree.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.fetchFolders()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Collections") },
                actions = {
                    // Fake Profile Circle
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary, // Cyan background
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E", // First letter of name
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = { // <--- ADD THIS
            FloatingActionButton(
                onClick = onAddCollectionClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
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
                Text(
                    text = "No folders found. Check your API.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders) { collection ->
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
                                        contentDescription = "Delete Collection",
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
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFF1F4B58)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                ) {
                                    Text(
                                        text = collection.name,
                                        modifier = Modifier.padding(20.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}