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
import com.elialombardi.piccistodo.data.repository.TaskDataSource
import com.elialombardi.piccistodo.ui.screens.TasksContent
import com.elialombardi.piccistodo.ui.viewmodels.TasksController
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class TasksScreenTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val controller = TasksController(source, scope)
    private class Source : TaskDataSource {
        val project = Project("11111111-1111-4111-8111-111111111111", "Demo client")
        var projectRows = listOf(project)
        var rows = listOf(
            Task("22222222-2222-4222-8222-222222222222", project.id, project.name,
                "Pending website update", BigDecimal("12.50"), "2026-11-09", description = "Replace the hero image.\nKeep the rest of the page unchanged."),
            Task("33333333-3333-4333-8333-333333333333", project.id, project.name,
                "Sent maintenance", BigDecimal("20.00"), "2026-11-08", "2026-11-10"))
        var failLoad = false
        var failWrite = false
        var writes = 0
        var last: TaskRequest? = null
        override suspend fun tasks(): List<Task> { if (failLoad) throw IOException(); return rows }
        override suspend fun projects() = projectRows
        override suspend fun task(id: String) = rows.first { it.id == id }
        override suspend fun saveTask(id: String?, request: TaskRequest): Task {
            writes++; last = request; if (failWrite) throw IOException()
            val saved = Task(id ?: "44444444-4444-4444-8444-444444444444", request.projectId,
                projectRows.first { it.id == request.projectId }.name, request.name, request.cost, request.date,
                request.sentOn.takeIf { it.isNotEmpty() }, request.description.takeIf { it.isNotEmpty() })
            rows = rows.filterNot { it.id == saved.id } + saved; return saved
        }
        override suspend fun createProject(request: ProjectRequest): Project {
            val saved = Project("55555555-5555-4555-8555-555555555555", request.name, request.description)
            projectRows = projectRows + saved; return saved
        }
    }
    @After fun close() { scope.cancel() }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun show(largeDark: Boolean = false) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeDark) 1.8f else density.fontScale)) {
                MaterialTheme(colorScheme = if (largeDark) darkColorScheme() else lightColorScheme()) {
                    TasksContent(controller, {}, {})
                }
            }
        }
        ready()
    }
    private fun ready() = ui.waitUntil(5000) {
        controller.state.value.tasksLoaded && controller.state.value.projectsLoaded &&
            !controller.state.value.refreshing && !controller.state.value.saving
    }
    private fun click(id: Int) {
        val node = ui.onAllNodesWithText(text(id)).onLast()
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun overviewClick(value: String) {
        ui.onNode(hasScrollAction()).performScrollToNode(hasText(value))
        ui.onNodeWithText(value).performClick()
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "tasks-qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun overviewDefaultsToPendingAndHistoryIncludesBothStatuses() {
        show(); screenshot("01-overview")
        ui.onNode(isSelectable() and hasAnyDescendant(hasText(text(R.string.tasks_not_sent))), useUnmergedTree = true).assertIsSelected()
        overviewClick(text(R.string.tasks_history))
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Sent maintenance"))
        ui.onNodeWithText("Sent maintenance").assertExists()
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("Pending website update"))
        ui.onNodeWithText("Pending website update").assertExists()
        assertEquals(1, controller.state.value.tasks.summary().pendingCount)
    }

    @Test fun statusConfirmationOnlyChangesDateAndCanBeCleared() {
        show(); overviewClick("Pending website update")
        click(R.string.tasks_mark_sent)
        ui.onAllNodesWithText(text(R.string.tasks_status_explanation)).onLast().assertExists()
        screenshot("02-status-confirmation")
        click(R.string.tasks_confirm); ready()
        assertTrue(controller.state.value.selected!!.isSent)
        assertEquals("Pending website update", source.last!!.name)
        assertEquals(BigDecimal("12.50"), source.last!!.cost)
        click(R.string.tasks_mark_not_sent)
        ui.onNodeWithText(text(R.string.tasks_clear_confirm)).assertExists()
        click(R.string.tasks_confirm); ready()
        assertEquals("", source.last!!.sentOn); assertFalse(controller.state.value.selected!!.isSent)
        assertEquals(2, source.writes)
    }

    @Test fun projectCreationPreservesFormAndDecimalCommaSavesAsNumber() {
        show(); overviewClick(text(R.string.tasks_add))
        ui.onNodeWithText(text(R.string.tasks_name)).performClick().performTextReplacement("New maintenance")
        ui.onNodeWithText(text(R.string.tasks_cost)).performTextReplacement("-10,25")
        click(R.string.tasks_add_project)
        ui.onNodeWithText(text(R.string.tasks_project_name)).performTextReplacement("New client")
        click(R.string.tasks_save); ready()
        assertEquals("New maintenance", controller.state.value.draft!!.name)
        assertEquals("55555555-5555-4555-8555-555555555555", controller.state.value.draft!!.projectId)
        ui.onNodeWithText(text(R.string.tasks_date)).performScrollTo().performTextReplacement("2026-11-10")
        screenshot("03-task-editor")
        click(R.string.tasks_save); ready()
        assertNull(controller.state.value.draft); assertEquals(BigDecimal("-10.25"), source.last!!.cost)
        assertEquals("2026-11-10", source.last!!.date)
    }

    @Test fun noProjectsBlocksSaveAndDirtyBackRequiresConfirmation() {
        source.projectRows = emptyList(); source.rows = emptyList(); show()
        overviewClick(text(R.string.tasks_add))
        ui.onNodeWithText(text(R.string.tasks_no_projects)).assertExists()
        ui.onNodeWithText(text(R.string.tasks_save)).performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText(text(R.string.tasks_name)).performScrollTo().performTextReplacement("Unsaved work")
        ui.onNodeWithContentDescription(text(R.string.tasks_back)).performClick()
        ui.onNodeWithText(text(R.string.tasks_discard_title)).assertIsDisplayed()
        click(R.string.tasks_keep_editing)
        assertEquals("Unsaved work", controller.state.value.draft!!.name)
        ui.onNodeWithContentDescription(text(R.string.tasks_back)).performClick(); click(R.string.tasks_discard)
        assertNull(controller.state.value.draft); assertEquals(0, source.writes)
    }

    @Test fun refreshFailureIsStaleNotEmptyAndUncertainSaveRetainsInput() {
        show(); ui.runOnIdle { source.failLoad = true; controller.refresh() }; ready()
        ui.onAllNodesWithText(text(R.string.tasks_stale)).onFirst().assertExists()
        ui.onNodeWithText(text(R.string.tasks_tasks_failed)).assertExists()
        screenshot("04-stale-error")
        assertEquals(2, controller.state.value.tasks.size)
        ui.runOnIdle { source.failLoad = false; controller.refresh() }; ready()
        overviewClick("Pending website update"); click(R.string.tasks_edit)
        ui.onNodeWithText(text(R.string.tasks_name)).performTextReplacement("Retain this edit")
        source.failWrite = true; click(R.string.tasks_save); ready()
        ui.onNodeWithText(text(R.string.tasks_save)).performScrollTo().assertIsNotEnabled()
        assertEquals("Retain this edit", controller.state.value.draft!!.name)
        assertTrue(controller.state.value.uncertain); assertEquals(1, source.writes)
    }

    @Test fun largeTextDarkHistoryAndDetailsRemainScrollable() {
        show(largeDark = true); overviewClick(text(R.string.tasks_history))
        overviewClick("Pending website update")
        ui.onNodeWithText(text(R.string.tasks_mark_sent)).performScrollTo().assertIsDisplayed()
        screenshot("05-large-text-dark-details")
        assertEquals("Replace the hero image.\nKeep the rest of the page unchanged.", controller.state.value.selected!!.description)
    }
}
