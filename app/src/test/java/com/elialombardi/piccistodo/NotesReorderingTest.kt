package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class NotesReorderingTest {
    private val date = OffsetDateTime.parse("2026-11-10T00:40:00Z")
    private fun note(id: Long, folder: Long, position: Int) = NotesItem(id, folder, position, "Item $id", "Diary content", "Description",
        "2026-12-01", true, NotesStyle(true, true, "#123456", "#abcdef", 24), date, date)
    private fun initialTree() = listOf(
        NotesFolder(12, "Root", NotesType.DIARY, 0, null, listOf(
            NotesFolder(13, "Child", NotesType.TASKS, 0, 12, emptyList(), listOf(note(43, 13, 0), note(44, 13, 1))),
            NotesFolder(14, "Other child", NotesType.DIARY, 1, 12, emptyList(), emptyList())), listOf(note(41, 12, 0), note(42, 12, 1))),
        NotesFolder(15, "Buy", NotesType.BUY, 1, null, emptyList(), listOf(note(45, 15, 0), note(46, 15, 1))),
        NotesFolder(16, "Empty", NotesType.DIARY, 2, null, emptyList(), emptyList()))
    private inner class Source : NotesDataSource {
        var rows = initialTree()
        val writes = mutableListOf<Triple<Boolean, Long, JsonObject>>()
        var failAt: Int? = null
        var failure: Exception = IOException()
        var failRefresh = false
        var gate: CompletableDeferred<Unit>? = null
        var malformedConfirmed = false
        fun transform(block: (NotesFolder) -> NotesFolder) {
            fun walk(rows: List<NotesFolder>): List<NotesFolder> = rows.map { block(it.copy(children = walk(it.children))) }
            rows = walk(rows)
        }
        override suspend fun tree(): List<NotesFolder> {
            if (failRefresh) throw IOException()
            fun sorted(rows: List<NotesFolder>): List<NotesFolder> = rows.sortedWith(compareBy<NotesFolder> { it.position }.thenBy { it.id })
                .map { it.copy(children = sorted(it.children), notes = it.notes.sortedWith(compareBy<NotesItem> { n -> n.position }.thenBy { n -> n.id })) }
            return sorted(rows)
        }
        private suspend fun write(folder: Boolean, id: Long, body: JsonObject) {
            writes += Triple(folder, id, body.deepCopy()); gate?.await()
            if (writes.size == failAt) throw failure
            assertEquals(setOf("position"), body.keySet())
            transform { f -> if (folder && f.id == id) f.copy(position = body["position"].asInt)
                else f.copy(notes = f.notes.map { if (!folder && it.id == id) it.copy(position = body["position"].asInt) else it }) }
            if (malformedConfirmed) throw NotesWriteConfirmedException()
        }
        override suspend fun saveFolder(id: Long?, body: JsonObject): Long { write(true, requireNotNull(id), body); return id }
        override suspend fun saveItem(id: Long?, body: JsonObject): NotesItem {
            write(false, requireNotNull(id), body); return rows.folderRows().flatMap { it.folder.notes }.first { it.id == id }
        }
        override suspend fun deleteFolder(id: Long) = error("Not used")
        override suspend fun deleteItem(id: Long) = error("Not used")
    }
    private fun NotesController.current() = state.value

    @Test fun previewMovesWholeSiblingSubtreesWithoutMovingParentsOrNotes() {
        val before = initialTree()
        val moved = before.withFolderOrder(NotesSiblingOrder(null, listOf(15, 12, 16)))
        assertEquals(listOf(15L, 12L, 16L), moved.map { it.id })
        assertEquals(before.first(), moved[1])
        assertEquals(listOf(15L, 12L, 13L, 14L, 16L), moved.folderRows().map { it.folder.id })
        val children = before.withFolderOrder(NotesSiblingOrder(12, listOf(14, 13)))
        assertEquals(listOf(14L, 13L), children.first().children.map { it.id })
        assertEquals(before.first().notes, children.first().notes)
        assertEquals(before, before.withFolderOrder(NotesSiblingOrder(12, listOf(15, 13))))
        assertEquals(listOf(1L, 3L, 2L), movedNotesIds(listOf(1, 2, 3), 2, 3))
        assertEquals(listOf(1L, 2L), movedNotesIds(listOf(1, 2), 9, 2))
    }

    @Test fun positionPayloadContainsOnlyExplicitNumericPositionIncludingZero() {
        assertEquals("{\"position\":0}", NotesWrites.position(0).toString())
        assertEquals("{\"position\":2}", NotesWrites.position(2).toString())
        assertThrows(IllegalArgumentException::class.java) { NotesWrites.position(-1) }
    }

    @Test fun rootAndNestedReordersPersistOnlyPositionsAndKeepSelectedFolder() = runTest {
        val source = Source(); val before = source.rows
        val c = NotesController(source, this); c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        c.reorderFolders(null, listOf(15, 12, 16)); advanceUntilIdle()
        assertEquals(listOf(15L, 12L, 16L), c.current().tree.map { it.id })
        assertEquals(13L, c.current().selectedFolderId)
        assertEquals(before.first().children, c.current().tree[1].children)
        assertEquals(before.first().notes, c.current().tree[1].notes)
        c.reorderFolders(12, listOf(14, 13)); advanceUntilIdle()
        assertEquals(listOf(14L, 13L), c.current().tree.folderSiblings(12).map { it.id })
        assertTrue(source.writes.all { it.first && it.third.keySet() == setOf("position") })
        assertEquals(NotesNotice.SAVED, c.current().notice)
    }

    @Test fun notesInEveryFolderTypeRetainAllHiddenFieldsAndNeverTouchOtherFolders() = runTest {
        val source = Source(); val c = NotesController(source, this); c.sessionChanged("Admin", true); advanceUntilIdle()
        listOf(12L, 13L, 15L).forEach { folderId ->
            c.selectFolder(folderId)
            val before = c.current().selectedFolder!!.notes
            val others = c.current().folders.filterNot { it.folder.id == folderId }.associate { it.folder.id to it.folder.notes }
            c.reorderItems(folderId, before.map { it.id }.reversed()); advanceUntilIdle()
            val after = c.current().selectedFolder!!.notes
            assertEquals(before.map { it.id }.reversed(), after.map { it.id })
            after.forEach { saved -> assertEquals(before.first { it.id == saved.id }.copy(position = saved.position), saved) }
            others.forEach { (id, notes) -> assertEquals(notes, c.current().folders.first { it.folder.id == id }.folder.notes) }
        }
        assertTrue(source.writes.all { !it.first && it.third.keySet() == setOf("position") })
    }

    @Test fun noOpMissingDuplicateAndCrossParentOrdersAreRejectedWithoutWrites() = runTest {
        val source = Source(); val c = NotesController(source, this); c.sessionChanged("Writer", true); advanceUntilIdle()
        c.reorderFolders(null, listOf(12, 15, 16)); c.reorderFolders(null, listOf(15, 12)); c.reorderFolders(null, listOf(12, 12, 16))
        c.reorderFolders(12, listOf(15, 13)); c.reorderFolders(999, listOf(12)); c.selectFolder(13)
        c.reorderItems(12, listOf(42, 41)); c.reorderItems(13, listOf(44, 45)); c.reorderItems(13, listOf(44, 44))
        advanceUntilIdle(); assertTrue(source.writes.isEmpty())
    }

    @Test fun readersOpenEditorsAndStaleOrUncertainStatesCannotReorder() = runTest {
        val source = Source(); val c = NotesController(source, this); c.sessionChanged("Reader", true); advanceUntilIdle()
        c.reorderFolders(null, listOf(15, 12, 16)); c.selectFolder(13); c.reorderItems(13, listOf(44, 43))
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13); c.openItem(c.current().selectedFolder!!.notes.first())
        c.reorderItems(13, listOf(44, 43)); c.itemInput(c.current().itemDraft!!.copy(title = "Unsaved"))
        c.reorderFolders(null, listOf(15, 12, 16)); assertEquals("Unsaved", c.current().itemDraft!!.title)
        c.closeEditor(discard = true); source.failRefresh = true; c.refresh(); advanceUntilIdle()
        c.reorderItems(13, listOf(44, 43)); advanceUntilIdle(); assertTrue(source.writes.isEmpty())
    }

    @Test fun repeatedDropsCannotStartOverlappingPositionWrites() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }; val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        c.reorderItems(13, listOf(44, 43)); c.reorderItems(13, listOf(44, 43)); runCurrent()
        assertEquals(1, source.writes.size); assertTrue(c.current().saving)
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(2, source.writes.size); assertEquals(listOf(44L, 43L), c.current().selectedFolder!!.notes.map { it.id })
    }

    @Test fun partialFailureStopsAndRefetchesWithoutClaimingCompleteOrReplaying() = runTest {
        val source = Source().apply { failAt = 2; failure = HttpException(Response.error<Unit>(400, "Rejected".toResponseBody())) }
        val c = NotesController(source, this); c.sessionChanged("Writer", true); advanceUntilIdle()
        c.reorderFolders(null, listOf(16, 15, 12)); advanceUntilIdle()
        assertEquals(2, source.writes.size); assertTrue(c.current().reorderFailed)
        assertEquals(NotesFailure.REJECTED, c.current().problem!!.kind); assertNull(c.current().notice)
        assertEquals(source.tree(), c.current().tree); assertTrue(c.current().fresh)
        c.refresh(); advanceUntilIdle(); assertEquals(2, source.writes.size)
    }

    @Test fun uncertainPositionWriteRequiresReviewAndDoesNotReplay() = runTest {
        val source = Source().apply { failAt = 1 }; val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        c.reorderItems(13, listOf(44, 43)); advanceUntilIdle()
        assertTrue(c.current().uncertain); assertTrue(c.current().reorderFailed)
        c.reorderItems(13, listOf(44, 43)); advanceUntilIdle(); assertEquals(1, source.writes.size)
        c.acknowledgeUncertain(); source.failAt = null; c.reorderItems(13, listOf(44, 43)); advanceUntilIdle()
        assertEquals(listOf(44L, 43L), c.current().selectedFolder!!.notes.map { it.id }); assertFalse(c.current().reorderFailed)
    }

    @Test fun confirmedPositionResponsesNeedNoItemBodyAndFailedRefreshDoesNotReplay() = runTest {
        val source = Source().apply { malformedConfirmed = true }; val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.selectFolder(13)
        source.failRefresh = true; c.reorderItems(13, listOf(44, 43)); advanceUntilIdle()
        assertEquals(2, source.writes.size); assertEquals(NotesNotice.SAVED, c.current().notice)
        assertNotNull(c.current().loadError); assertFalse(c.current().canReorder)
        source.failRefresh = false; c.refresh(); advanceUntilIdle()
        assertEquals(2, source.writes.size); assertEquals(listOf(44L, 43L), c.current().selectedFolder!!.notes.map { it.id })
    }

    @Test fun accountChangeDuringDropCannotContinueWritingTheOldOrder() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }; val c = NotesController(source, this)
        c.sessionChanged("Writer", true); advanceUntilIdle(); c.reorderFolders(null, listOf(16, 15, 12)); runCurrent()
        c.sessionChanged("Reader", true); source.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, source.writes.size); assertFalse(c.current().canReorder); assertFalse(c.current().saving)
    }
}

