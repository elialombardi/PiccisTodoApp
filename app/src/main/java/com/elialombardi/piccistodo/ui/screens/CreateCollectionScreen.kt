package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.ui.components.WorkspaceHero
import com.elialombardi.piccistodo.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCollectionScreen(
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    var collectionName by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    val isSaving by viewModel.isLoading.collectAsState()
    val back = { if (!isSaving) { if (collectionName.isNotEmpty()) discard = true else onBackClick() } }
    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Collection") },
                navigationIcon = {
                    IconButton(onClick = back, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            WorkspaceHero(stringResource(R.string.ui_collections_description), stringResource(R.string.ui_collections_empty_description), Icons.Default.FolderOpen)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = collectionName,
                onValueChange = { collectionName = it },
                label = { Text("Collection Name") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = !isSaving,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedLabelColor = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.createFolder(collectionName) {
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = collectionName.isNotBlank() && !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Create Collection")
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.tasks_discard_title)) }, text = { Text(stringResource(R.string.tasks_discard_text)) },
        confirmButton = { TextButton(onClick = onBackClick) { Text(stringResource(R.string.tasks_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.tasks_keep_editing)) } })
}