package com.elialombardi.piccistodo

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.NotesDataSource
import com.elialombardi.piccistodo.ui.screens.NotesContent
import com.elialombardi.piccistodo.ui.viewmodels.NotesController
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class NotesReorderingScreenTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val controller = NotesController(source, scope)
    private class Source : NotesDataSource {
        private val date = OffsetDateTime.parse("2026-11-10T00:40:00Z")
        private fun note(id: Long, folder: Long, position: Int) = NotesItem(id, folder, position, "Note $id", "Diary text", "Task description",
            "2026-12-01", true, NotesStyle(bold = true), date, date)
        var rows = listOf(
            NotesFolder(12, "Diary", NotesType.DIARY, 0, null, emptyList(), listOf(note(41, 12, 0), note(42, 12, 1))),
            NotesFolder(15, "Shopping", NotesType.BUY, 1, null, emptyList(), listOf(note(45, 15, 0), note(46, 15, 1))),
            NotesFolder(16, "Work list", NotesType.TASKS, 2, null, listOf(
                NotesFolder(17, "First child", NotesType.DIARY, 0, 16, emptyList(), emptyList()),
                NotesFolder(18, "Second child", NotesType.DIARY, 1, 16, emptyList(), emptyList())), listOf(note(47, 16, 0), note(48, 16, 1))))
        val writes = mutableListOf<JsonObject>()
        override suspend fun tree(): List<NotesFolder> {
            fun sorted(rows: List<NotesFolder>): List<NotesFolder> = rows.sortedBy { it.position }.map {
                it.copy(children = sorted(it.children), notes = it.notes.sortedBy { note -> note.position })
            }
            return sorted(rows)
        }
        private fun update(folder: Boolean, id: Long, body: JsonObject) {
            writes += body.deepCopy()
            fun walk(rows: List<NotesFolder>): List<NotesFolder> = rows.map {
                val changed = if (folder && it.id == id) it.copy(position = body["position"].asInt) else it
                changed.copy(children = walk(changed.children), notes = changed.notes.map { n ->
                    if (!folder && n.id == id) n.copy(position = body["position"].asInt) else n
                })
            }
            rows = walk(rows)
        }
        override suspend fun saveFolder(id: Long?, body: JsonObject): Long { update(true, requireNotNull(id), body); return id }
        override suspend fun saveItem(id: Long?, body: JsonObject): NotesItem {
            update(false, requireNotNull(id), body); return rows.folderRows().flatMap { it.folder.notes }.first { it.id == id }
        }
        override suspend fun deleteFolder(id: Long) = error("Not used")
        override suspend fun deleteItem(id: Long) = error("Not used")
    }
    @After fun close() { scope.cancel() }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun show(role: String = "Writer") {
        ui.setContent { MaterialTheme { NotesContent(controller) } }
        ui.runOnIdle { controller.sessionChanged(role, true) }; ready()
    }
    private fun ready() = ui.waitUntil(5000) { controller.state.value.fresh && !controller.state.value.refreshing && !controller.state.value.saving }
    private fun select(id: Long) { ui.runOnIdle { controller.selectFolder(id) }; ui.waitForIdle() }
    private fun menuMove(tag: String, action: Int) {
        val pane = if (tag.startsWith("notes_reorder_folder_")) "notes_folder_navigator" else "notes_item_list"
        ui.onNodeWithTag(pane).performScrollToNode(hasTestTag(tag.replace("notes_reorder_", "notes_")))
        ui.onNodeWithTag(tag).performClick()
        ui.onNodeWithText(text(action)).performClick(); ready()
    }
    private fun drag(from: String, to: String) {
        val fromNode = ui.onNodeWithTag(from)
        val delta = ui.onNodeWithTag(to).fetchSemanticsNode().boundsInRoot.center.y - fromNode.fetchSemanticsNode().boundsInRoot.center.y
        fromNode.performTouchInput { swipe(center, center + Offset(0f, delta), 800) }
        ready()
    }

    @Test fun draggingDiaryNotesPersistsPositionsAndDoesNotOpenTheEditor() {
        show(); select(12)
        ui.onNodeWithTag("notes_item_42").performScrollTo()
        drag("notes_reorder_item_41", "notes_reorder_item_42")
        ui.waitUntil(5000) { controller.state.value.selectedFolder!!.notes.first().id == 42L }
        assertNull(controller.state.value.itemDraft)
        assertEquals(2, source.writes.size)
        assertTrue(source.writes.all { it.keySet() == setOf("position") })
        assertEquals("Diary text", controller.state.value.selectedFolder!!.notes.first().content)
    }

    @Test fun draggingRootFolderKeepsChildrenAndNotesAttached() {
        show()
        ui.onNodeWithTag("notes_folder_16").performScrollTo()
        drag("notes_reorder_folder_16", "notes_reorder_folder_15")
        ui.waitUntil(5000) { controller.state.value.tree[1].id == 16L }
        assertNull(controller.state.value.selectedFolderId)
        assertEquals(listOf(17L, 18L), controller.state.value.tree[1].children.map { it.id })
        assertEquals(listOf(41L, 42L), controller.state.value.tree.first().notes.map { it.id })
        assertTrue(source.writes.all { it.keySet() == setOf("position") })
    }

    @Test fun moveMenuOrdersNestedSiblingsAndAllListTypesWithoutCrossFolderMoves() {
        show()
        menuMove("notes_reorder_folder_18", R.string.notes_move_up)
        assertEquals(listOf(18L, 17L), controller.state.value.tree.last().children.map { it.id })
        assertEquals(listOf(12L, 15L, 16L), controller.state.value.tree.map { it.id })
        listOf(12L to 42L, 15L to 46L, 16L to 48L).forEach { (folder, note) ->
            select(folder); menuMove("notes_reorder_item_$note", R.string.notes_move_up)
            assertEquals(note, controller.state.value.selectedFolder!!.notes.first().id)
        }
        assertTrue(source.writes.all { it.keySet() == setOf("position") })
    }

    @Test fun readerHasNoReorderHandlesAndWriterCannotReorderWithAnEditorOpen() {
        show("Reader")
        ui.onNodeWithTag("notes_reorder_folder_12").assertDoesNotExist()
        select(12); ui.onNodeWithTag("notes_reorder_item_41").assertDoesNotExist()
        ui.runOnIdle { controller.sessionChanged("Admin", true) }; ready(); select(12)
        ui.onNodeWithTag("notes_reorder_item_41").assertIsEnabled()
        ui.runOnIdle { controller.openItem(controller.state.value.selectedFolder!!.notes.first()) }
        // On wide screens the list remains visible beside the editor, but its handles are disabled.
        val handles = ui.onAllNodesWithTag("notes_reorder_item_41").fetchSemanticsNodes()
        if (handles.isNotEmpty()) ui.onNodeWithTag("notes_reorder_item_41").assertIsNotEnabled()
        assertTrue(source.writes.isEmpty())
    }
}
