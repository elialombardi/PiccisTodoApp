package com.elialombardi.piccistodo.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elialombardi.piccistodo.data.local.SettingsManager
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

sealed interface NotesDeletion {
    data class Item(val id: Long, val title: String) : NotesDeletion
    data class Folder(val id: Long, val name: String) : NotesDeletion
}
enum class NotesNotice { SAVED, DELETED, UNCERTAIN }
data class NotesState(
    val tree: List<NotesFolder> = emptyList(), val loaded: Boolean = false, val fresh: Boolean = false,
    val refreshing: Boolean = false, val saving: Boolean = false, val canWrite: Boolean = false,
    val selectedFolderId: Long? = null, val selectedItemId: Long? = null,
    val itemDraft: NotesItemDraft? = null, val originalItem: NotesItemDraft? = null,
    val folderDraft: NotesFolderDraft? = null, val originalFolder: NotesFolderDraft? = null,
    val folderEditId: Long? = null, val parentId: Long? = null,
    val fields: Set<String> = emptySet(), val problem: NotesProblem? = null, val loadError: NotesProblem? = null,
    val notice: NotesNotice? = null, val uncertain: Boolean = false, val reorderFailed: Boolean = false,
    val authenticationRequired: Boolean = false, val deletion: NotesDeletion? = null
) {
    val folders get() = tree.folderRows()
    val selectedFolder get() = folders.firstOrNull { it.folder.id == selectedFolderId }?.folder
    val dirty get() = (itemDraft != null && itemDraft != originalItem) || (folderDraft != null && folderDraft != originalFolder)
    val editable get() = canWrite && fresh && !saving && !refreshing && !uncertain && !authenticationRequired
    val canReorder get() = editable && itemDraft == null && folderDraft == null
    val canReview get() = uncertain && fresh && !saving && !refreshing
}

class NotesController(private val source: NotesDataSource, private val scope: CoroutineScope,
                      private val expireSession: suspend () -> Unit = {}) {
    private val mutable = MutableStateFlow(NotesState())
    val state = mutable.asStateFlow()
    private var fetch: Job? = null
    private var mutation: Job? = null
    private var generation = 0
    private fun change(block: (NotesState) -> NotesState) { mutable.value = block(mutable.value) }
    fun sessionChanged(role: String?, authenticated: Boolean) {
        generation++; fetch?.cancel(); mutation?.cancel()
        mutable.value = NotesState(canWrite = role == "Writer" || role == "Admin", authenticationRequired = !authenticated)
        if (authenticated) refresh()
    }
    private suspend fun authenticate(problem: NotesProblem): Boolean {
        if (problem.kind != NotesFailure.AUTH) return false
        generation++
        mutable.value = NotesState(authenticationRequired = true)
        expireSession()
        return true
    }
    fun refresh() {
        if (state.value.saving || state.value.authenticationRequired) return
        fetch?.cancel()
        val version = ++generation
        change { it.copy(refreshing = true, fresh = false) }
        fetch = scope.launch {
            try {
                val tree = source.tree()
                if (version != generation) return@launch
                change { current ->
                    val folder = tree.folderRows().firstOrNull { it.folder.id == current.selectedFolderId }?.folder
                    val item = folder?.notes?.firstOrNull { it.id == current.selectedItemId }
                    val replaceEditor = current.itemDraft != null && current.selectedItemId != null && !current.dirty
                    current.copy(tree = tree, loaded = true, fresh = true, refreshing = false, loadError = null,
                        selectedFolderId = folder?.id,
                        selectedItemId = if (replaceEditor) item?.id else current.selectedItemId,
                        itemDraft = if (replaceEditor) item?.let(NotesItemDraft::from) else current.itemDraft,
                        originalItem = if (replaceEditor) item?.let(NotesItemDraft::from) else current.originalItem)
                }
            } catch (error: Exception) {
                if (version != generation) return@launch
                val problem = notesProblem(error)
                if (!authenticate(problem)) change { it.copy(refreshing = false, fresh = false, loadError = problem) }
            }
        }
    }
    // UI confirms discard before calling navigation methods; controller also refuses silent loss.
    fun selectFolder(id: Long?, discard: Boolean = false) {
        if (state.value.saving || (state.value.dirty && !discard)) return
        if (id != null && state.value.folders.none { it.folder.id == id }) return
        change { it.copy(selectedFolderId = id, selectedItemId = null, itemDraft = null, originalItem = null,
            folderDraft = null, originalFolder = null, fields = emptySet(), problem = null) }
    }
    fun openItem(item: NotesItem? = null, discard: Boolean = false) {
        val current = state.value
        if (current.saving || (current.dirty && !discard)) return
        val folder = current.selectedFolder ?: return
        if (item != null && folder.notes.none { it.id == item.id }) return
        if (item == null && !current.editable) return
        val draft = item?.let(NotesItemDraft::from) ?: NotesItemDraft(folder.id)
        change { it.copy(selectedItemId = item?.id, itemDraft = draft, originalItem = draft,
            folderDraft = null, originalFolder = null, fields = emptySet(), problem = null) }
    }
    fun openFolderForm(edit: Boolean = false, child: Boolean = false, discard: Boolean = false) {
        val current = state.value
        if (!current.editable || (current.dirty && !discard)) return
        val folder = current.selectedFolder
        if ((edit || child) && folder == null) return
        val draft = if (edit) NotesFolderDraft(folder!!.name, folder.type, folder.position.toString()) else NotesFolderDraft()
        change { it.copy(folderDraft = draft, originalFolder = draft, folderEditId = if (edit) folder!!.id else null,
            parentId = if (child) folder!!.id else null, itemDraft = null, originalItem = null, selectedItemId = null,
            fields = emptySet(), problem = null) }
    }
    fun closeEditor(discard: Boolean = false) {
        if (state.value.saving || (state.value.dirty && !discard)) return
        change { it.copy(itemDraft = null, originalItem = null, selectedItemId = null, folderDraft = null,
            originalFolder = null, fields = emptySet(), problem = null) }
    }
    fun itemInput(value: NotesItemDraft) { if (state.value.canWrite && !state.value.saving) change { it.copy(itemDraft = value, fields = emptySet()) } }
    fun folderInput(value: NotesFolderDraft) { if (state.value.canWrite && !state.value.saving) change { it.copy(folderDraft = value, fields = emptySet()) } }
    fun saveItem() {
        val current = state.value
        val draft = current.itemDraft ?: return
        if (!current.editable) return
        val errors = draft.errors(current.folders).let {
            if (current.selectedItemId != null && draft.style == current.originalItem?.style) it - "style" else it
        }
        if (errors.isNotEmpty()) { change { it.copy(fields = errors) }; return }
        val type = current.folders.first { it.folder.id == draft.folderId }.folder.type
        val body = NotesWrites.item(draft, if (current.selectedItemId == null) null else current.originalItem, type)
        if (body.size() == 0) return
        mutate {
            val saved = source.saveItem(current.selectedItemId, body)
            if (generation == it) {
                val savedDraft = NotesItemDraft.from(saved)
                change { state -> state.copy(selectedFolderId = saved.folderId, selectedItemId = saved.id,
                    itemDraft = savedDraft, originalItem = savedDraft, notice = NotesNotice.SAVED) }
            }
        }
    }
    fun saveFolder() {
        val current = state.value
        val draft = current.folderDraft ?: return
        if (!current.editable) return
        val errors = draft.errors()
        if (errors.isNotEmpty()) { change { it.copy(fields = errors) }; return }
        val body = NotesWrites.folder(draft, if (current.folderEditId == null) null else current.originalFolder, current.parentId)
        if (body.size() == 0) return
        mutate {
            val id = source.saveFolder(current.folderEditId, body)
            if (generation == it) change { state -> state.copy(selectedFolderId = id, folderDraft = null,
                originalFolder = null, selectedItemId = null, itemDraft = null, originalItem = null, notice = NotesNotice.SAVED) }
        }
    }
    fun toggle(item: NotesItem, completed: Boolean) {
        val current = state.value
        if (!current.editable || current.dirty || current.selectedFolder?.type == NotesType.DIARY ||
            current.selectedFolder?.notes?.none { it.id == item.id } != false) return
        mutate { version ->
            source.saveItem(item.id, NotesWrites.completion(completed))
            if (version == generation) change { it.copy(notice = NotesNotice.SAVED) }
        }
    }
    fun reorderFolders(parentId: Long?, ids: List<Long>) {
        val current = state.value
        if (!current.canReorder) return
        val siblings = current.tree.folderSiblings(parentId)
        reorder(siblings.map { it.id to it.position }, ids, folders = true)
    }
    fun reorderItems(folderId: Long, ids: List<Long>) {
        val current = state.value
        if (!current.canReorder || current.selectedFolderId != folderId) return
        reorder(current.selectedFolder?.notes.orEmpty().map { it.id to it.position }, ids, folders = false)
    }
    private fun reorder(siblings: List<Pair<Long, Int>>, ids: List<Long>, folders: Boolean) {
        val original = siblings.map { it.first }
        if (ids == original || ids.size != original.size || ids.toSet() != original.toSet()) return
        val positions = siblings.toMap()
        val updates = ids.mapIndexedNotNull { index, id -> if (positions[id] != index) id to index else null }
        mutate { version ->
            try {
                for ((id, position) in updates) {
                    if (version != generation) return@mutate
                    try {
                        if (folders) source.saveFolder(id, NotesWrites.position(position))
                        else source.saveItem(id, NotesWrites.position(position))
                    } catch (_: NotesWriteConfirmedException) {
                        // HTTP success is confirmed; response content is not needed for a position write.
                    }
                }
                if (version == generation) change { it.copy(notice = NotesNotice.SAVED) }
            } catch (error: Exception) {
                if (version == generation) change { it.copy(reorderFailed = true) }
                throw error
            }
        }
    }
    fun requestDeleteItem() {
        val current = state.value
        if (!current.editable) return
        val item = current.selectedFolder?.notes?.firstOrNull { it.id == current.selectedItemId } ?: return
        change { it.copy(deletion = NotesDeletion.Item(item.id, item.title)) }
    }
    fun requestDeleteFolder() {
        val current = state.value
        val folder = current.selectedFolder ?: return
        if (!current.editable || current.dirty) return
        if (folder.children.isNotEmpty() || folder.notes.isNotEmpty()) {
            change { it.copy(problem = NotesProblem(NotesFailure.FOLDER_NOT_EMPTY)) }; return
        }
        change { it.copy(deletion = NotesDeletion.Folder(folder.id, folder.name)) }
    }
    fun cancelDelete() { if (!state.value.saving) change { it.copy(deletion = null) } }
    fun confirmDelete() {
        if (!state.value.editable) return
        when (val target = state.value.deletion) {
            is NotesDeletion.Item -> mutate { version ->
                source.deleteItem(target.id)
                if (version == generation) change { it.copy(itemDraft = null, originalItem = null, selectedItemId = null, notice = NotesNotice.DELETED) }
            }
            is NotesDeletion.Folder -> mutate { version ->
                // Refuse a folder that became nonempty since confirmation. No recursive deletion.
                val latest = source.tree().folderRows().firstOrNull { it.folder.id == target.id }?.folder
                if (latest == null || latest.notes.isNotEmpty() || latest.children.isNotEmpty()) {
                    if (version == generation) change { it.copy(problem = NotesProblem(NotesFailure.FOLDER_NOT_EMPTY)) }
                } else {
                    source.deleteFolder(target.id)
                    if (version == generation) change { it.copy(selectedFolderId = null, notice = NotesNotice.DELETED) }
                }
            }
            null -> Unit
        }
    }
    fun acknowledgeUncertain() {
        if (state.value.canReview) change { it.copy(uncertain = false, notice = null, problem = null) }
    }
    private fun mutate(action: suspend (Int) -> Unit) {
        if (!state.value.editable) return
        fetch?.cancel()
        val version = ++generation
        change { it.copy(saving = true, fresh = false, refreshing = false, deletion = null, problem = null, notice = null, reorderFailed = false) }
        mutation = scope.launch {
            try { action(version) }
            catch (error: Exception) {
                if (version != generation) return@launch
                if (error is NotesWriteConfirmedException) {
                    // The server accepted the write but its response could not identify the saved item.
                    // Close the draft rather than leave a duplicate-create action available.
                    change { it.copy(notice = NotesNotice.SAVED, itemDraft = null, originalItem = null,
                        folderDraft = null, originalFolder = null, selectedItemId = null,
                        problem = NotesProblem(NotesFailure.DATA)) }
                    return@launch
                }
                val problem = notesProblem(error, writing = true)
                if (authenticate(problem)) return@launch
                change { it.copy(problem = problem, uncertain = problem.uncertain,
                    canWrite = it.canWrite && problem.kind != NotesFailure.FORBIDDEN,
                    notice = if (problem.uncertain) NotesNotice.UNCERTAIN else null) }
            } finally {
                if (version == generation) {
                    change { it.copy(saving = false) }; refresh()
                }
            }
        }
    }
}
@HiltViewModel
class NotesViewModel @Inject constructor(source: NotesRepository, settings: SettingsManager) : ViewModel() {
    val controller = NotesController(source, viewModelScope) { settings.clearSession() }
    init {
        viewModelScope.launch {
            settings.sessionFlow.distinctUntilChanged().collect { (token, _, role) -> controller.sessionChanged(role, token != null) }
        }
    }
}


