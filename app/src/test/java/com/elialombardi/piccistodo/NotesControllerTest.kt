package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class NotesControllerTest {
    private val date = OffsetDateTime.parse("2026-11-10T00:40:00Z")
    private fun item(id: Long, folder: Long) = NotesItem(id, folder, 2, "Item $id", "Diary content", "Separate description",
        "2026-11-14", true, NotesStyle(bold = true, textColor = "#123456", fontSize = 24), date, date)
    private fun initial() = listOf(
        NotesFolder(12, "Root", NotesType.DIARY, 0, null,
            listOf(NotesFolder(13, "Child", NotesType.TASKS, 0, 12, emptyList(), listOf(item(43, 13)))), listOf(item(42, 12))),
        NotesFolder(15, "Buy", NotesType.BUY, 0, null, emptyList(), emptyList()),
        NotesFolder(16, "Empty", NotesType.DIARY, 0, null, emptyList(), emptyList()))
    private inner class Source : NotesDataSource {
        var folders = initial()
        var calls = 0
        var deletes = 0
        var failLoad = false
        var failWrite: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var fetchOverride: (suspend () -> List<NotesFolder>)? = null
        var last: JsonObject? = null
        var afterWrite: () -> Unit = {}
        fun transform(block: (NotesFolder) -> NotesFolder) {
            fun walk(rows: List<NotesFolder>): List<NotesFolder> = rows.map { block(it.copy(children = walk(it.children))) }
            folders = walk(folders)
        }
        override suspend fun tree(): List<NotesFolder> {
            fetchOverride?.let { return it() }
            if (failLoad) throw IOException()
            return folders
        }
        override suspend fun saveFolder(id: Long?, body: JsonObject): Long {
            calls++; last = body.deepCopy(); gate?.await(); failWrite?.let { throw it }
            val target = id ?: 100L
            if (id == null) {
                val new = NotesFolder(target, body["name"].asString, NotesType.parse(body["type"].asString), body["position"].asInt,
                    body["parentId"]?.asLong, emptyList(), emptyList())
                if (new.parentId == null) folders = folders + new
                else transform { if (it.id == new.parentId) it.copy(children = it.children + new) else it }
            } else transform { if (it.id == id) it.copy(name = body["name"]?.asString ?: it.name,
                type = body["type"]?.asString?.let(NotesType::parse) ?: it.type, position = body["position"]?.asInt ?: it.position) else it }
            afterWrite(); return target
        }
        override suspend fun saveItem(id: Long?, body: JsonObject): NotesItem {
            calls++; last = body.deepCopy(); gate?.await(); failWrite?.let { throw it }
            val old = folders.folderRows().flatMap { it.folder.notes }.firstOrNull { it.id == id } ?: item(200, body["folderId"].asLong)
            val saved = old.copy(folderId = body["folderId"]?.asLong ?: old.folderId,
                title = body["title"]?.asString ?: old.title, position = body["position"]?.asInt ?: old.position,
                description = body["description"]?.asString ?: old.description, content = body["content"]?.asString ?: old.content,
                endDate = if (body.has("endDate")) body["endDate"].asString.takeIf { it.isNotEmpty() } else old.endDate,
                completed = body["completed"]?.asBoolean ?: old.completed)
            transform { folder -> folder.copy(notes = folder.notes.filterNot { it.id == saved.id } + if (folder.id == saved.folderId) listOf(saved) else emptyList()) }
            afterWrite(); return saved
        }
        override suspend fun deleteItem(id: Long) {
            deletes++; failWrite?.let { throw it }; transform { it.copy(notes = it.notes.filterNot { note -> note.id == id }) }
        }
        override suspend fun deleteFolder(id: Long) {
            deletes++; failWrite?.let { throw it }; folders = folders.filterNot { it.id == id }
        }
    }
    private fun NotesController.current() = state.value

    @Test fun directItemsFolderSelectionAndCancelCreationPreserveSelection() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle()
        c.selectFolder(12); assertEquals(listOf(42L), c.current().selectedFolder!!.notes.map { it.id })
        c.openItem(c.current().selectedFolder!!.notes.first()); assertEquals(42L, c.current().selectedItemId)
        c.selectFolder(13); assertNull(c.current().itemDraft)
        assertEquals(listOf(43L), c.current().selectedFolder!!.notes.map { it.id })
        c.openFolderForm(); c.closeEditor(); assertEquals(13L, c.current().selectedFolderId)
        c.openFolderForm(child = true); assertEquals(13L, c.current().parentId)
        assertEquals(NotesType.DIARY, c.current().folderDraft!!.type)
    }
    @Test fun dirtyDraftCannotDisappearWithoutExplicitDiscard() = runTest {
        val c = NotesController(Source(), this); c.sessionChanged("Writer", true); advanceUntilIdle()
        c.selectFolder(12); c.openItem(); c.itemInput(c.current().itemDraft!!.copy(title = "Unsaved"))
        c.selectFolder(13); c.closeEditor()
        assertEquals(12L, c.current().selectedFolderId); assertEquals("Unsaved", c.current().itemDraft!!.title)
        c.selectFolder(13, discard = true); assertNull(c.current().itemDraft)
    }
    @Test fun typeChangesAndMovesNeverRewriteHiddenItemFields() = runTest {
        val source = Source(); val before = source.folders.first().notes.first()
        val c = NotesController(source, this); c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(12)
        listOf(NotesType.BUY, NotesType.TASKS, NotesType.DIARY).forEach { type ->
            c.openFolderForm(edit = true); c.folderInput(c.current().folderDraft!!.copy(type = type)); c.saveFolder(); advanceUntilIdle()
            assertEquals(before, c.current().selectedFolder!!.notes.first())
            assertEquals(NotesType.TASKS, c.current().selectedFolder!!.children.first().type)
            assertEquals(setOf("type"), source.last!!.keySet())
        }
        c.openItem(before); c.itemInput(c.current().itemDraft!!.copy(folderId = 15)); c.saveItem(); advanceUntilIdle()
        assertEquals(15L, c.current().selectedFolderId); assertEquals(42L, c.current().selectedItemId)
        assertEquals(setOf("folderId"), source.last!!.keySet())
        val moved = c.current().selectedFolder!!.notes.first()
        assertEquals(before.copy(folderId = 15), moved)
        c.itemInput(c.current().itemDraft!!.copy(folderId = 12)); c.saveItem(); advanceUntilIdle()
        assertEquals(before, c.current().selectedFolder!!.notes.first())
    }
    @Test fun completionOnlyFalseAndDateClearingRefreshAndReselect() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        c.toggle(c.current().selectedFolder!!.notes.first(), false); advanceUntilIdle()
        assertEquals("{\"completed\":false}", source.last.toString())
        val saved = c.current().selectedFolder!!.notes.first()
        assertEquals("Diary content", saved.content); assertFalse(saved.completed)
        c.openItem(saved); c.itemInput(c.current().itemDraft!!.copy(endDate = "")); c.saveItem(); advanceUntilIdle()
        assertEquals("{\"endDate\":\"\"}", source.last.toString()); assertNull(c.current().selectedFolder!!.notes.first().endDate)
        assertEquals(43L, c.current().selectedItemId)
    }
    @Test fun readerCanBrowseButCannotModifyOrToggle() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Reader", true); advanceUntilIdle(); c.selectFolder(13)
        val row = c.current().selectedFolder!!.notes.first(); c.openItem(row)
        c.itemInput(c.current().itemDraft!!.copy(title = "No")); c.saveItem(); c.toggle(row, false)
        c.openFolderForm(); c.requestDeleteItem(); c.confirmDelete(); advanceUntilIdle()
        assertEquals("Item 43", c.current().itemDraft!!.title); assertEquals(0, source.calls + source.deletes)
    }
    @Test fun duplicateCreateAndSavedButRefreshFailedNeverReplay() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Admin", true); advanceUntilIdle(); c.selectFolder(15); c.openItem()
        c.itemInput(c.current().itemDraft!!.copy(title = "Milk")); source.gate = CompletableDeferred()
        source.afterWrite = { source.failLoad = true }
        c.saveItem(); c.saveItem(); runCurrent(); assertEquals(1, source.calls)
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(NotesNotice.SAVED, c.current().notice); assertNotNull(c.current().loadError)
        assertFalse(c.current().editable); assertEquals(200L, c.current().selectedItemId)
        source.failLoad = false; c.refresh(); advanceUntilIdle()
        assertEquals(1, source.calls); assertEquals(200L, c.current().selectedFolder!!.notes.first().id)
    }
    @Test fun failedAndUncertainSavesKeepInputAndRequireReview() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(15); c.openItem()
        c.itemInput(c.current().itemDraft!!.copy(title = "Keep draft")); source.failWrite = IOException()
        c.saveItem(); advanceUntilIdle()
        assertEquals("Keep draft", c.current().itemDraft!!.title); assertTrue(c.current().uncertain)
        c.saveItem(); advanceUntilIdle(); assertEquals(1, source.calls)
        c.acknowledgeUncertain(); assertFalse(c.current().uncertain)
        assertEquals("Keep draft", c.current().itemDraft!!.title)
    }
    @Test fun emptyOnlyFolderDeletionAndItemConfirmationCancellation() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Admin", true); advanceUntilIdle(); c.selectFolder(12); c.requestDeleteFolder()
        assertEquals(NotesFailure.FOLDER_NOT_EMPTY, c.current().problem!!.kind); assertNull(c.current().deletion)
        c.openItem(c.current().selectedFolder!!.notes.first()); c.requestDeleteItem(); c.cancelDelete(); c.confirmDelete()
        assertEquals(0, source.deletes)
        c.requestDeleteItem(); c.confirmDelete(); advanceUntilIdle(); assertEquals(1, source.deletes)
        assertNull(c.current().itemDraft); assertTrue(c.current().selectedFolder!!.notes.isEmpty())
        c.selectFolder(16); c.requestDeleteFolder(); c.cancelDelete(); c.confirmDelete(); assertEquals(1, source.deletes)
        c.requestDeleteFolder(); c.confirmDelete(); advanceUntilIdle(); assertEquals(2, source.deletes)
        assertNull(c.current().selectedFolderId)
    }
    @Test fun folderThatBecameNonemptyIsNotDeleted() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(16); c.requestDeleteFolder()
        source.transform { if (it.id == 16L) it.copy(notes = listOf(item(88, 16))) else it }
        c.confirmDelete(); advanceUntilIdle()
        assertEquals(0, source.deletes); assertEquals(NotesFailure.FOLDER_NOT_EMPTY, c.current().problem!!.kind)
    }
    @Test fun oldFetchAndAccountChangesCannotRestorePreviousClientState() = runTest {
        val source = Source(); val c = NotesController(source, this)
        val old = CompletableDeferred<List<NotesFolder>>()
        source.fetchOverride = { withContext(NonCancellable) { old.await() } }
        c.sessionChanged("Writer", true); runCurrent()
        source.fetchOverride = null; source.folders = emptyList()
        c.sessionChanged("Reader", true); advanceUntilIdle()
        old.complete(initial()); advanceUntilIdle()
        assertTrue(c.current().tree.isEmpty()); assertFalse(c.current().canWrite)
        c.sessionChanged(null, false); assertFalse(c.current().loaded); assertTrue(c.current().authenticationRequired)
    }
    @Test fun failedInitialLoadIsNotSuccessfulEmptyAndInvalidDraftSendsNothing() = runTest {
        val source = Source().apply { failLoad = true }; val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle()
        assertFalse(c.current().loaded); assertNotNull(c.current().loadError); assertFalse(c.current().editable)
        source.failLoad = false; c.refresh(); advanceUntilIdle(); c.selectFolder(13); c.openItem()
        c.itemInput(c.current().itemDraft!!.copy(title = " ", position = "-1", endDate = "2026-02-29")); c.saveItem()
        assertTrue(c.current().fields.containsAll(listOf("title", "position", "endDate"))); assertEquals(0, source.calls)
    }
    @Test fun rootAndChildCreationRefreshAndReselectWithIndependentDiaryType() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        c.openFolderForm(); c.folderInput(NotesFolderDraft("New root")); c.saveFolder(); advanceUntilIdle()
        assertEquals(100L, c.current().selectedFolderId); assertNull(c.current().folderDraft)
        assertNull(c.current().selectedFolder!!.parentId); assertEquals(NotesType.DIARY, c.current().selectedFolder!!.type)
        assertFalse(source.last!!.has("parentId"))
        source.folders = initial(); c.refresh(); advanceUntilIdle(); c.selectFolder(13)
        c.openFolderForm(child = true); c.folderInput(NotesFolderDraft("New child")); c.saveFolder(); advanceUntilIdle()
        assertEquals(100L, c.current().selectedFolderId); assertEquals(13L, c.current().selectedFolder!!.parentId)
        assertEquals(NotesType.DIARY, c.current().selectedFolder!!.type)
        assertEquals(NotesType.TASKS, c.current().folders.first { it.folder.id == 13L }.folder.type)
    }
    @Test fun rejectedInputIsRetainedButMalformedConfirmedWriteClosesDuplicateCreate() = runTest {
        val source = Source(); val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(15); c.openItem()
        c.itemInput(c.current().itemDraft!!.copy(title = "Kept"))
        source.failWrite = HttpException(Response.error<Unit>(400, "rejected".toResponseBody()))
        c.saveItem(); advanceUntilIdle()
        assertEquals("Kept", c.current().itemDraft!!.title); assertFalse(c.current().uncertain)
        assertEquals(NotesFailure.REJECTED, c.current().problem!!.kind)
        source.failWrite = NotesWriteConfirmedException()
        c.saveItem(); advanceUntilIdle()
        assertEquals(NotesNotice.SAVED, c.current().notice); assertNull(c.current().itemDraft)
        assertFalse(c.current().uncertain); assertEquals(2, source.calls)
    }
    @Test fun authenticationExpiryClearsStateAndDoesNotRetry() = runTest {
        val source = Source(); var expirations = 0
        val c = NotesController(source, this) { expirations++ }
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(12)
        source.fetchOverride = { throw HttpException(Response.error<Unit>(401, "expired".toResponseBody())) }
        c.refresh(); advanceUntilIdle()
        assertEquals(1, expirations); assertTrue(c.current().authenticationRequired)
        assertTrue(c.current().tree.isEmpty()); assertNull(c.current().selectedFolderId)
        c.refresh(); advanceUntilIdle(); assertEquals(1, expirations)
    }
}
