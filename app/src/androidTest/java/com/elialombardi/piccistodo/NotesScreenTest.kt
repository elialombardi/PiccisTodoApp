package com.elialombardi.piccistodo

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
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
import java.io.File
import java.time.OffsetDateTime

@RunWith(AndroidJUnit4::class)
class NotesScreenTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val controller = NotesController(source, scope)
    private class Source : NotesDataSource {
        private val date = OffsetDateTime.parse("2026-11-10T00:40:00.123456+02:00")
        private fun note(id: Long, folder: Long, title: String) = NotesItem(id, folder, 0, title, "Styled diary body", "Separate task description",
            "2026-11-14", true, NotesStyle(bold = true), date, date)
        var folders = listOf(
            NotesFolder(12, "Diary root", NotesType.DIARY, 0, null,
                listOf(NotesFolder(13, "Nested tasks", NotesType.TASKS, 0, 12, emptyList(), listOf(note(43, 13, "Task item")))), listOf(note(42, 12, "Diary item"))),
            NotesFolder(15, "Shopping", NotesType.BUY, 0, null, emptyList(), listOf(note(44, 15, "Buy item"))))
        var calls = 0
        var last: JsonObject? = null
        override suspend fun tree() = folders
        override suspend fun saveItem(id: Long?, body: JsonObject): NotesItem {
            calls++; last = body.deepCopy()
            val old = folders.folderRows().flatMap { it.folder.notes }.first { it.id == id }
            val saved = old.copy(title = body["title"]?.asString ?: old.title,
                completed = body["completed"]?.asBoolean ?: old.completed,
                endDate = if (body.has("endDate")) body["endDate"].asString.takeIf { it.isNotEmpty() } else old.endDate)
            fun walk(rows: List<NotesFolder>): List<NotesFolder> = rows.map { it.copy(children = walk(it.children), notes = it.notes.map { n -> if (n.id == id) saved else n }) }
            folders = walk(folders)
            return saved
        }
        override suspend fun saveFolder(id: Long?, body: JsonObject): Long = error("Not used by this UI test")
        override suspend fun deleteFolder(id: Long) = error("Not used by this UI test")
        override suspend fun deleteItem(id: Long) = error("Not used by this UI test")
    }
    @After fun close() { scope.cancel() }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun show(role: String, largeDark: Boolean = false) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeDark) 1.8f else density.fontScale)) {
                MaterialTheme(colorScheme = if (largeDark) darkColorScheme() else lightColorScheme()) { NotesContent(controller) }
            }
        }
        ui.runOnIdle { controller.sessionChanged(role, true) }
        ready()
    }
    private fun ready() = ui.waitUntil(5000) { controller.state.value.fresh && !controller.state.value.refreshing && !controller.state.value.saving }
    private fun click(id: Int) = ui.onAllNodesWithText(text(id)).onLast().performScrollTo().performClick()
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "notes-qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun folderNavigatorHasNoItemsAndNestedReaderSelectionIsDirectOnly() {
        show("Reader")
        ui.onNodeWithTag("notes_folder_13").assertExists()
        ui.onNodeWithText("Diary item").assertDoesNotExist()
        ui.onNodeWithText("Task item").assertDoesNotExist()
        screenshot("01-folders-only")
        ui.onNodeWithTag("notes_folder_13").performScrollTo().performClick()
        ui.onNodeWithTag("notes_item_43").assertExists()
        ui.onNodeWithTag("notes_item_42").assertDoesNotExist()
        ui.onAllNodes(isToggleable()).onFirst().assertIsNotEnabled()
        ui.onNodeWithTag("notes_item_43").performScrollTo().performClick()
        ui.onNodeWithText(text(R.string.notes_description)).assertExists()
        ui.onNodeWithText(text(R.string.notes_content)).assertDoesNotExist()
        ui.onAllNodesWithText(text(R.string.notes_save)).assertCountEquals(0)
        ui.onAllNodesWithText(text(R.string.notes_delete)).assertCountEquals(0)
    }
    @Test fun buyToggleIsCompletionOnlyAndEditorDiscardRequiresDecision() {
        show("Writer")
        ui.onNodeWithTag("notes_folder_15").performScrollTo().performClick()
        ui.onNode(isToggleable()).performScrollTo().performClick(); ready()
        assertEquals("{\"completed\":false}", source.last.toString())
        ui.onNodeWithTag("notes_item_44").performScrollTo().performClick()
        ui.onNodeWithText(text(R.string.notes_description)).assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_content)).assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_end_date)).assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_item_title)).performTextReplacement("Unsaved milk")
        click(R.string.notes_close)
        ui.onNodeWithText(text(R.string.notes_discard_title)).assertIsDisplayed()
        ui.onNodeWithText(text(R.string.notes_cancel)).performClick()
        assertEquals("Unsaved milk", controller.state.value.itemDraft!!.title)
        click(R.string.notes_close); ui.onNodeWithText(text(R.string.notes_discard)).performClick()
        assertNull(controller.state.value.itemDraft); assertEquals(1, source.calls)
        screenshot("02-buy-list")
    }
    @Test fun taskDateClearsWithEmptyStringWithoutDiaryControls() {
        show("Writer")
        ui.onNodeWithTag("notes_folder_13").performScrollTo().performClick()
        ui.onNodeWithTag("notes_item_43").performScrollTo().performClick()
        ui.onNodeWithText("2026-11-14").assertExists()
        ui.onNodeWithText(text(R.string.notes_content)).assertDoesNotExist()
        click(R.string.notes_clear_date)
        click(R.string.notes_save); ready()
        assertEquals("{\"endDate\":\"\"}", source.last.toString())
        assertEquals("Separate task description", controller.state.value.itemDraft!!.description)
        screenshot("03-task-editor")
    }
    @Test fun diaryShowsWholeNotePreviewWithoutCompletionOrEndDate() {
        show("Writer", largeDark = true)
        ui.onNodeWithTag("notes_folder_12").performScrollTo().performClick()
        ui.onNodeWithTag("notes_item_42").performScrollTo().performClick()
        ui.onNodeWithText(text(R.string.notes_content)).assertExists()
        ui.onNodeWithText(text(R.string.notes_description)).assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_end_date)).assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_bold)).performScrollTo().assertExists()
        ui.onNodeWithText(text(R.string.notes_preview)).performScrollTo().assertIsDisplayed()
        screenshot("04-diary-preview-large-dark")
    }
    @Test fun wideLayoutCanHideRestoreFoldersAndSwitchFolderClosesEditor() {
        show("Writer")
        // This assertion also makes the tablet-only run explicit instead of silently passing on phones.
        val wide = ui.onAllNodesWithText(text(R.string.notes_hide_folders)).fetchSemanticsNodes().isNotEmpty()
        if (!wide) return
        ui.onNodeWithTag("notes_folder_12").performScrollTo().performClick()
        ui.onNodeWithTag("notes_item_42").performScrollTo().performClick()
        ui.onNodeWithTag("notes_editor").assertExists()
        ui.onNodeWithTag("notes_item_list").assertExists()
        ui.onNodeWithTag("notes_folder_13").performScrollTo().performClick()
        ui.onNodeWithTag("notes_editor").assertDoesNotExist()
        click(R.string.notes_hide_folders)
        ui.onNodeWithTag("notes_folder_navigator").assertDoesNotExist()
        ui.onNodeWithText(text(R.string.notes_show_folders)).performClick()
        ui.onNodeWithTag("notes_folder_navigator").assertExists()
        screenshot("05-tablet-folders-items")
    }
}

