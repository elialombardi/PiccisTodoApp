package com.elialombardi.piccistodo.ui.screens

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun NotesScreen(onAuthenticationRequired: () -> Unit, onRootChanged: (Boolean) -> Unit = {},
                initialFolderId: Long? = null, initialCreate: Boolean = false, initialFolderForm: Boolean = false,
                viewModel: NotesViewModel = hiltViewModel()) {
    NotesContent(viewModel.controller, onAuthenticationRequired, onRootChanged, initialFolderId, initialCreate, initialFolderForm)
}
@Composable
private fun typeLabel(type: NotesType) = stringResource(when (type) {
    NotesType.BUY -> R.string.notes_buy
    NotesType.TASKS -> R.string.notes_tasks
    NotesType.DIARY -> R.string.notes_diary
})
private fun typeIcon(type: NotesType) = when (type) {
    NotesType.BUY -> Icons.Outlined.ShoppingBag
    NotesType.TASKS -> Icons.Outlined.Checklist
    NotesType.DIARY -> Icons.AutoMirrored.Outlined.MenuBook
}
@Composable
private fun NotesProblemText(problem: NotesProblem?) {
    if (problem == null) return
    val fallback = when (problem.kind) {
        NotesFailure.AUTH -> R.string.notes_error_auth
        NotesFailure.FORBIDDEN -> R.string.notes_error_forbidden
        NotesFailure.REJECTED -> R.string.notes_error_rejected
        NotesFailure.DELETE_FAILED -> R.string.notes_error_delete
        NotesFailure.NETWORK -> R.string.notes_error_network
        NotesFailure.SERVER -> R.string.notes_error_server
        NotesFailure.DATA -> R.string.notes_error_data
        NotesFailure.CONFIG -> R.string.notes_error_config
        NotesFailure.FOLDER_NOT_EMPTY -> R.string.notes_nonempty
    }
    Text(problem.message ?: stringResource(fallback), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesContent(controller: NotesController, onAuthenticationRequired: () -> Unit = {},
                          onRootChanged: (Boolean) -> Unit = {}, initialFolderId: Long? = null,
                          initialCreate: Boolean = false, initialFolderForm: Boolean = false) {
    val state by controller.state.collectAsState()
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var foldersVisible by rememberSaveable { mutableStateOf(true) }
    var initialHandled by rememberSaveable { mutableStateOf(false) }
    val editor = state.itemDraft != null || state.folderDraft != null
    val root = !editor && state.selectedFolderId == null
    LaunchedEffect(root) { onRootChanged(root) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    LaunchedEffect(state.authenticationRequired) { if (state.authenticationRequired) onAuthenticationRequired() }
    LaunchedEffect(state.fresh, initialHandled) {
        if (state.fresh && !initialHandled) {
            initialHandled = true
            if (initialFolderId != null) controller.selectFolder(initialFolderId)
            if (initialCreate && initialFolderId != null) controller.openItem()
            if (initialFolderForm) controller.openFolderForm()
        }
    }
    fun navigate(action: () -> Unit) {
        if (!state.saving) { if (state.dirty) discard = action else action() }
    }
    val back: () -> Unit = {
        navigate {
            if (editor) controller.closeEditor(discard = true)
            else controller.selectFolder(null, discard = true)
        }
    }
    BackHandler(enabled = !root) { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (state.folderDraft != null) {
            if (state.folderEditId == null) R.string.notes_new_folder else R.string.notes_edit_folder
        } else if (state.itemDraft != null) R.string.notes_edit_item else R.string.notes_title)) },
            navigationIcon = { if (!root) IconButton(onClick = back, enabled = !state.saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.notes_back))
            } }, actions = {
                IconButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.notes_refresh))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.saving || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Notices scroll with each pane so large text does not consume all available height.
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 840.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        if (foldersVisible) {
                            FolderNavigator(state, controller, Modifier.width(280.dp),
                                onSelect = { id -> navigate { controller.selectFolder(id, discard = true) } },
                                onNew = { navigate { controller.openFolderForm(discard = true) } },
                                onHide = { foldersVisible = false })
                            VerticalDivider()
                        }
                        Column(Modifier.weight(1f)) {
                            if (!foldersVisible) TextButton(onClick = { foldersVisible = true }) { Text(stringResource(R.string.notes_show_folders)) }
                            Row(Modifier.weight(1f)) {
                                ItemPane(state, controller, Modifier.weight(1f),
                                    onOpen = { navigate { controller.openItem(it, discard = true) } },
                                    onNew = { navigate { controller.openItem(discard = true) } },
                                    onConfigure = { navigate { controller.openFolderForm(edit = true, discard = true) } },
                                    onChild = { navigate { controller.openFolderForm(child = true, discard = true) } })
                                if (editor) {
                                    VerticalDivider()
                                    EditorPane(state, controller, Modifier.weight(1f), back)
                                }
                            }
                        }
                    }
                } else when {
                    editor -> EditorPane(state, controller, Modifier.fillMaxSize(), back)
                    state.selectedFolderId == null -> FolderNavigator(state, controller, Modifier.fillMaxSize(),
                        onSelect = { controller.selectFolder(it) }, onNew = { controller.openFolderForm() })
                    else -> ItemPane(state, controller, Modifier.fillMaxSize(),
                        onOpen = { controller.openItem(it) }, onNew = { controller.openItem() },
                        onConfigure = { controller.openFolderForm(edit = true) }, onChild = { controller.openFolderForm(child = true) })
                }
            }
        }
    }
    discard?.let { action ->
        AlertDialog(onDismissRequest = { discard = null }, title = { Text(stringResource(R.string.notes_discard_title)) },
            text = { Text(stringResource(R.string.notes_discard_message)) },
            confirmButton = { TextButton(onClick = { discard = null; action() }) { Text(stringResource(R.string.notes_discard)) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.notes_cancel)) } })
    }
    state.deletion?.let { target ->
        AlertDialog(onDismissRequest = controller::cancelDelete, title = { Text(stringResource(R.string.notes_delete_title)) },
            text = { Text(when (target) {
                is NotesDeletion.Item -> stringResource(R.string.notes_delete_item_message, target.title)
                is NotesDeletion.Folder -> stringResource(R.string.notes_delete_folder_message, target.name)
            }) }, confirmButton = {
                TextButton(onClick = controller::confirmDelete, enabled = state.editable) {
                    Text(stringResource(R.string.notes_confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = controller::cancelDelete) { Text(stringResource(R.string.notes_cancel)) } })
    }
}

@Composable
private fun LoadNotices(state: NotesState, controller: NotesController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.canWrite) Text(stringResource(R.string.notes_read_only), style = MaterialTheme.typography.bodyMedium)
        if (state.saving) Text(stringResource(R.string.notes_saving))
        else if (state.refreshing) Text(stringResource(R.string.notes_loading))
        if (!state.fresh && state.loaded && !state.refreshing) Text(stringResource(R.string.notes_stale))
        state.notice?.let {
            Text(stringResource(when (it) {
                NotesNotice.SAVED -> if (state.loadError != null) R.string.notes_saved_refresh_failed else R.string.notes_saved
                NotesNotice.DELETED -> R.string.notes_deleted
                NotesNotice.UNCERTAIN -> R.string.notes_uncertain
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        NotesProblemText(state.loadError)
        NotesProblemText(state.problem)
        if (state.reorderFailed) Text(stringResource(R.string.notes_reorder_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.fields.isNotEmpty()) Text(stringResource(R.string.notes_invalid), color = MaterialTheme.colorScheme.error)
        if (state.loadError != null || state.uncertain) OutlinedButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) {
            Text(stringResource(R.string.notes_retry))
        }
        if (state.uncertain) OutlinedButton(onClick = controller::acknowledgeUncertain, enabled = state.canReview) { Text(stringResource(R.string.notes_reviewed)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderNavigator(state: NotesState, controller: NotesController, modifier: Modifier,
                            onSelect: (Long) -> Unit, onNew: () -> Unit, onHide: (() -> Unit)? = null) {
    val listState = rememberLazyListState()
    var preview by remember { mutableStateOf<NotesSiblingOrder?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragParent by remember { mutableStateOf<Long?>(null) }
    val rows = (preview?.let { state.tree.withFolderOrder(it) } ?: state.tree).folderRows()
    val currentRows by rememberUpdatedState(rows)
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.tree, state.saving, state.refreshing, dragging) {
        if (!dragging && !state.saving && !state.refreshing) preview = null
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val source = currentRows.firstOrNull { it.folder.id == from.key }?.folder
        val target = currentRows.firstOrNull { it.folder.id == to.key }?.folder
        if (currentState.canReorder && source != null && target != null && source.parentId == target.parentId) {
            val ids = preview?.takeIf { it.parentId == source.parentId }?.ids
                ?: currentState.tree.folderSiblings(source.parentId).map { it.id }
            preview = NotesSiblingOrder(source.parentId, movedNotesIds(ids, source.id, target.id))
        }
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = controller::refresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("notes_folder_navigator"), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "folders-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.notes_folders), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.notes_shared), style = MaterialTheme.typography.bodySmall)
                    LoadNotices(state, controller)
                    if (state.canWrite) Button(onClick = onNew, enabled = state.editable) { Text(stringResource(R.string.notes_new_folder)) }
                    if (onHide != null) TextButton(onClick = onHide) { Text(stringResource(R.string.notes_hide_folders)) }
                }
            }
            if (state.loaded && state.fresh && state.tree.isEmpty()) item { Text(stringResource(R.string.notes_empty_folders)) }
            items(rows, key = { it.folder.id }) { row ->
                val folder = row.folder
                val siblings = state.tree.folderSiblings(folder.parentId).map { it.id }
                val index = siblings.indexOf(folder.id)
                ReorderableItem(reorderState, key = folder.id, enabled = !dragging || folder.parentId == dragParent) { isDragging ->
                Card(onClick = { onSelect(folder.id) }, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(5) * 12).dp)
                        .testTag("notes_folder_${folder.id}").semantics { selected = state.selectedFolderId == folder.id },
                    colors = CardDefaults.cardColors(containerColor = if (state.selectedFolderId == folder.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 0.dp)) {
                    ListItem(headlineContent = { Text(folder.name) },
                        supportingContent = { Text(typeLabel(folder.type) + " · " + pluralStringResource(R.plurals.notes_count, folder.notes.size, folder.notes.size)) },
                        leadingContent = {
                            val colors = MaterialTheme.colorScheme
                            val tile = when (folder.type) {
                                NotesType.BUY -> colors.secondaryContainer
                                NotesType.TASKS -> colors.tertiaryContainer
                                NotesType.DIARY -> colors.primaryContainer
                            }
                            Surface(color = tile, shape = MaterialTheme.shapes.medium) {
                                Icon(typeIcon(folder.type), null, Modifier.padding(10.dp).size(24.dp))
                            }
                        }, trailingContent = {
                            if (state.canWrite && siblings.size > 1) ReorderHandle(folder.name, "notes_reorder_folder_${folder.id}", state.canReorder,
                                Modifier.draggableHandle(enabled = state.canReorder,
                                    onDragStarted = { dragParent = folder.parentId; dragging = true }, onDragStopped = {
                                        dragging = false
                                        preview?.let { controller.reorderFolders(it.parentId, it.ids) }
                                    }), index > 0, index < siblings.lastIndex,
                                onUp = { controller.reorderFolders(folder.parentId, movedNotesIds(siblings, folder.id, siblings[index - 1])) },
                                onDown = { controller.reorderFolders(folder.parentId, movedNotesIds(siblings, folder.id, siblings[index + 1])) })
                        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPane(state: NotesState, controller: NotesController, modifier: Modifier, onOpen: (NotesItem) -> Unit,
                     onNew: () -> Unit, onConfigure: () -> Unit, onChild: () -> Unit) {
    val folder = state.selectedFolder
    val listState = rememberLazyListState()
    var preview by remember(folder?.id) { mutableStateOf<List<Long>?>(null) }
    var dragging by remember(folder?.id) { mutableStateOf(false) }
    val byId = folder?.notes.orEmpty().associateBy { it.id }
    val notes = preview?.mapNotNull { byId[it] } ?: folder?.notes.orEmpty()
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(folder?.notes, state.saving, state.refreshing, dragging) {
        if (!dragging && !state.saving && !state.refreshing) preview = null
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? Long
        val toId = to.key as? Long
        if (currentState.canReorder && fromId != null && toId != null) {
            preview = movedNotesIds(preview ?: currentState.selectedFolder?.notes.orEmpty().map { it.id }, fromId, toId)
        }
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = controller::refresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("notes_item_list"), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "items-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadNotices(state, controller)
                    if (folder == null) Text(stringResource(R.string.notes_choose_folder))
                    else {
                        Text(folder.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(typeLabel(folder.type), Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                            Text(pluralStringResource(R.plurals.notes_count, folder.notes.size, folder.notes.size), Modifier.padding(vertical = 4.dp))
                        }
                        if (state.canWrite) {
                            Button(onClick = onNew, enabled = state.editable) { Text(stringResource(if (folder.type == NotesType.DIARY) R.string.notes_new_note else R.string.notes_new_item)) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onConfigure, enabled = state.editable) { Text(stringResource(R.string.notes_edit_folder)) }
                                TextButton(onClick = onChild, enabled = state.editable) { Text(stringResource(R.string.notes_subfolder)) }
                                TextButton(onClick = controller::requestDeleteFolder, enabled = state.editable && !state.dirty) {
                                    Text(stringResource(R.string.notes_delete_folder), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            if (folder != null && folder.notes.isEmpty() && state.fresh) item { Text(stringResource(R.string.notes_empty_items)) }
            items(notes, key = { it.id }) { note ->
                val ids = folder?.notes.orEmpty().map { it.id }
                val index = ids.indexOf(note.id)
                ReorderableItem(reorderState, key = note.id) { isDragging ->
                Card(onClick = { onOpen(note) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("notes_item_${note.id}"),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 0.dp)) {
                    ListItem(headlineContent = { Text(note.title) }, supportingContent = {
                        Column {
                            if (folder?.type == NotesType.DIARY) Text(stringResource(R.string.notes_updated, note.localUpdated()))
                            else {
                                Text(stringResource(if (folder?.type == NotesType.BUY) {
                                    if (note.completed) R.string.notes_purchased else R.string.notes_not_purchased
                                } else if (note.completed) R.string.notes_done else R.string.notes_pending))
                                if (folder?.type == NotesType.TASKS && note.endDate != null) Text(stringResource(R.string.notes_due,
                                    LocalDate.parse(note.endDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
                            }
                        }
                    }, trailingContent = {
                        Row {
                        if (folder?.type != NotesType.DIARY) {
                            val label = stringResource(if (folder?.type == NotesType.BUY) R.string.notes_purchased else R.string.notes_done)
                            val description = stringResource(R.string.notes_toggle, label, note.title)
                            Checkbox(checked = note.completed, onCheckedChange = { controller.toggle(note, it) },
                                enabled = state.editable && !state.dirty, modifier = Modifier.semantics { contentDescription = description })
                        }
                        if (state.canWrite && ids.size > 1) ReorderHandle(note.title, "notes_reorder_item_${note.id}", state.canReorder,
                            Modifier.draggableHandle(enabled = state.canReorder,
                                onDragStarted = { dragging = true }, onDragStopped = {
                                    dragging = false
                                    preview?.let { folder?.id?.let { folderId -> controller.reorderItems(folderId, it) } }
                                }), index > 0, index < ids.lastIndex,
                            onUp = { folder?.id?.let { controller.reorderItems(it, movedNotesIds(ids, note.id, ids[index - 1])) } },
                            onDown = { folder?.id?.let { controller.reorderItems(it, movedNotesIds(ids, note.id, ids[index + 1])) } })
                        }
                    })
                }
                }
            }
        }
    }
}

@Composable
private fun ReorderHandle(name: String, tag: String, enabled: Boolean, modifier: Modifier,
                          canMoveUp: Boolean, canMoveDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val up = stringResource(R.string.notes_move_up)
    val down = stringResource(R.string.notes_move_down)
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = modifier.testTag(tag).semantics {
                customActions = if (!enabled) emptyList() else buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(up) { onUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(down) { onDown(); true })
                }
            }) { Icon(Icons.Outlined.DragHandle, stringResource(R.string.notes_reorder, name)) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(up) }, enabled = canMoveUp, onClick = { expanded = false; onUp() })
            DropdownMenuItem(text = { Text(down) }, enabled = canMoveDown, onClick = { expanded = false; onDown() })
        }
    }
}

@Composable
private fun Choice(label: String, value: String, choices: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (text, action) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; action() }) }
        }
    }
}
@Composable
private fun EditorPane(state: NotesState, controller: NotesController, modifier: Modifier, onClose: () -> Unit) {
    val editable = state.canWrite && !state.saving
    Column(modifier.testTag("notes_editor").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadNotices(state, controller)
        state.folderDraft?.let { draft ->
            OutlinedTextField(draft.name, { controller.folderInput(draft.copy(name = it)) }, label = { Text(stringResource(R.string.notes_name)) },
                modifier = Modifier.fillMaxWidth(), readOnly = !editable, isError = "name" in state.fields)
            Choice(stringResource(R.string.notes_type), typeLabel(draft.type), NotesType.entries.map { type ->
                typeLabel(type) to { controller.folderInput(draft.copy(type = type)) }
            }, editable)
            PositionField(draft.position, "position" in state.fields, editable) { controller.folderInput(draft.copy(position = it)) }
            if (state.canWrite) Button(onClick = controller::saveFolder, enabled = state.editable) { Text(stringResource(R.string.notes_save)) }
        }
        state.itemDraft?.let { draft ->
            val type = state.folders.firstOrNull { it.folder.id == draft.folderId }?.folder?.type ?: NotesType.DIARY
            Text(typeLabel(type), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft.title, { controller.itemInput(draft.copy(title = it)) }, label = { Text(stringResource(R.string.notes_item_title)) },
                modifier = Modifier.fillMaxWidth(), readOnly = !editable, isError = "title" in state.fields)
            if (type == NotesType.TASKS) {
                OutlinedTextField(draft.description, { controller.itemInput(draft.copy(description = it)) }, label = { Text(stringResource(R.string.notes_description)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, readOnly = !editable)
                EndDateField(draft, state, controller, editable)
            }
            if (type != NotesType.DIARY) {
                Row {
                    Checkbox(draft.completed, { controller.itemInput(draft.copy(completed = it)) }, enabled = editable)
                    Text(stringResource(if (type == NotesType.BUY) R.string.notes_purchased else R.string.notes_done), Modifier.padding(top = 12.dp))
                }
            } else DiaryFields(draft, state, controller, editable)
            PositionField(draft.position, "position" in state.fields, editable) { controller.itemInput(draft.copy(position = it)) }
            Choice(stringResource(R.string.notes_destination), state.folders.firstOrNull { it.folder.id == draft.folderId }?.path.orEmpty(),
                state.folders.map { row -> row.path to { controller.itemInput(draft.copy(folderId = row.folder.id)) } }, editable)
            if (state.canWrite) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = controller::saveItem, enabled = state.editable) { Text(stringResource(R.string.notes_save)) }
                if (state.selectedItemId != null) TextButton(onClick = controller::requestDeleteItem, enabled = state.editable) {
                    Text(stringResource(R.string.notes_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        TextButton(onClick = onClose, enabled = !state.saving) { Text(stringResource(R.string.notes_close)) }
    }
}
@Composable
private fun PositionField(value: String, error: Boolean, editable: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(R.string.notes_position)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, readOnly = !editable, isError = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
@Composable
private fun EndDateField(draft: NotesItemDraft, state: NotesState, controller: NotesController, editable: Boolean) {
    val context = LocalContext.current
    OutlinedTextField(draft.endDate, { controller.itemInput(draft.copy(endDate = it)) }, label = { Text(stringResource(R.string.notes_end_date)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, readOnly = !editable, isError = "endDate" in state.fields)
    if (state.canWrite) FlowRow {
        TextButton(onClick = {
            val date = runCatching { LocalDate.parse(draft.endDate) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, { _, year, month, day -> controller.itemInput(draft.copy(endDate = LocalDate.of(year, month + 1, day).toString())) },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, enabled = editable) { Text(stringResource(R.string.notes_pick_date)) }
        TextButton(onClick = { controller.itemInput(draft.copy(endDate = "")) }, enabled = editable) { Text(stringResource(R.string.notes_clear_date)) }
    }
}
@Composable
private fun DiaryFields(draft: NotesItemDraft, state: NotesState, controller: NotesController, editable: Boolean) {
    val style = draft.style
    OutlinedTextField(draft.content, { controller.itemInput(draft.copy(content = it)) }, label = { Text(stringResource(R.string.notes_content)) },
        modifier = Modifier.fillMaxWidth(), minLines = 5, readOnly = !editable)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = style.bold, onClick = { controller.itemInput(draft.copy(style = style.copy(bold = !style.bold))) }, enabled = editable,
            label = { Text(stringResource(R.string.notes_bold)) })
        FilterChip(selected = style.italic, onClick = { controller.itemInput(draft.copy(style = style.copy(italic = !style.italic))) }, enabled = editable,
            label = { Text(stringResource(R.string.notes_italic)) })
    }
    OutlinedTextField(style.textColor, { controller.itemInput(draft.copy(style = style.copy(textColor = it))) }, label = { Text(stringResource(R.string.notes_text_color)) },
        modifier = Modifier.fillMaxWidth(), readOnly = !editable, isError = "style" in state.fields)
    OutlinedTextField(style.backgroundColor, { controller.itemInput(draft.copy(style = style.copy(backgroundColor = it))) }, label = { Text(stringResource(R.string.notes_background_color)) },
        modifier = Modifier.fillMaxWidth(), readOnly = !editable, isError = "style" in state.fields)
    Choice(stringResource(R.string.notes_font_size), style.fontSize.toString(), listOf(14, 16, 18, 20, 24).map { size ->
        size.toString() to { controller.itemInput(draft.copy(style = style.copy(fontSize = size))) }
    }, editable)
    Text(stringResource(R.string.notes_preview), style = MaterialTheme.typography.titleMedium)
    val background = runCatching { Color(android.graphics.Color.parseColor(style.backgroundColor)) }.getOrDefault(MaterialTheme.colorScheme.surface)
    val foreground = runCatching { Color(android.graphics.Color.parseColor(style.textColor)) }.getOrDefault(MaterialTheme.colorScheme.onSurface)
    Surface(color = background, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(draft.content.ifEmpty { stringResource(R.string.notes_preview_empty) }, Modifier.padding(16.dp), color = foreground,
            fontSize = style.fontSize.coerceIn(1, 200).sp, fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal)
    }
}


