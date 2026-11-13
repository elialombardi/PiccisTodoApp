package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class TasksControllerTest {
    private val project = Project("11111111-1111-4111-8111-111111111111", "Client")
    private val row = Task("22222222-2222-4222-8222-222222222222", project.id, project.name,
        "Work", BigDecimal("12.50"), "2026-11-09", description = "Original description")
    private fun http(code: Int, message: String = "Failure") = HttpException(Response.error<Unit>(code, message.toResponseBody()))
    private inner class Source : TaskDataSource {
        var rows = listOf(row)
        var projectRows = listOf(project)
        var taskError: Exception? = null
        var projectError: Exception? = null
        var writeError: Exception? = null
        var detailError: Exception? = null
        var taskFetch: (suspend () -> List<Task>)? = null
        var projectFetch: (suspend () -> List<Project>)? = null
        var gate: CompletableDeferred<Unit>? = null
        var writes = 0
        var reads = 0
        var details = 0
        var last: TaskRequest? = null
        var lastId: String? = null
        var projectRequest: ProjectRequest? = null
        override suspend fun tasks(): List<Task> {
            reads++; taskError?.let { throw it }; return taskFetch?.invoke() ?: rows
        }
        override suspend fun projects(): List<Project> {
            projectError?.let { throw it }; return projectFetch?.invoke() ?: projectRows
        }
        override suspend fun task(id: String): Task {
            details++; detailError?.let { throw it }; return rows.first { it.id == id }
        }
        override suspend fun saveTask(id: String?, request: TaskRequest): Task {
            writes++; last = request; lastId = id; gate?.await(); writeError?.let { throw it }
            val saved = Task(id ?: "33333333-3333-4333-8333-333333333333", request.projectId,
                projectRows.first { it.id == request.projectId }.name, request.name, request.cost, request.date,
                request.sentOn.takeIf { it.isNotEmpty() }, request.description.takeIf { it.isNotEmpty() })
            rows = rows.filterNot { it.id == saved.id } + saved
            return saved
        }
        override suspend fun createProject(request: ProjectRequest): Project {
            writes++; projectRequest = request; gate?.await(); writeError?.let { throw it }
            val saved = Project("44444444-4444-4444-8444-444444444444", request.name, request.description)
            projectRows = projectRows + saved; return saved
        }
    }
    private fun TasksController.current() = state.value
    private fun TasksController.validDraft() { edit(); draft(TaskDraft(project.id, " New work ", "10,25", "2026-11-10")) }

    @Test fun loadsConcurrentlyAndKeepsCompleteSummaryAndSortedProjects() = runTest {
        val source = Source(); val taskGate = CompletableDeferred<List<Task>>(); val projectGate = CompletableDeferred<List<Project>>()
        var tasksStarted = false; var projectsStarted = false
        source.taskFetch = { tasksStarted = true; taskGate.await() }; source.projectFetch = { projectsStarted = true; projectGate.await() }
        val c = TasksController(source, this); c.refresh(); runCurrent()
        assertTrue(tasksStarted && projectsStarted); assertTrue(c.current().refreshing)
        assertFalse(c.current().tasksLoaded)
        taskGate.complete(source.rows); projectGate.complete(listOf(project.copy(name = "Z"), project.copy(id = "a", name = "A")))
        advanceUntilIdle()
        assertTrue(c.current().tasksLoaded && c.current().projectsLoaded)
        assertEquals(listOf("A", "Z"), c.current().projects.map { it.name })
        assertEquals(TaskSummary(1, BigDecimal("12.50"), 1), c.current().tasks.summary())
    }

    @Test fun emptyArraysAreLoadedButFailedFirstFetchIsNotAnEmptySuccess() = runTest {
        val source = Source().apply { rows = emptyList(); projectRows = emptyList(); taskError = IOException() }
        val c = TasksController(source, this); c.refresh(); advanceUntilIdle()
        assertFalse(c.current().tasksLoaded); assertTrue(c.current().projectsLoaded)
        assertEquals(TaskFailure.NETWORK, c.current().taskError!!.kind)
        source.taskError = null; c.refresh(); advanceUntilIdle()
        assertTrue(c.current().tasksLoaded); assertTrue(c.current().tasks.isEmpty()); assertNull(c.current().taskError)
    }

    @Test fun refreshFailureRetainsDataAndLabelsEachFailedDataset() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle()
        source.taskError = IOException(); source.projectError = http(500)
        c.refresh(); advanceUntilIdle()
        assertEquals(listOf(row), c.current().tasks); assertEquals(listOf(project), c.current().projects)
        assertEquals(TaskFailure.NETWORK, c.current().taskError!!.kind)
        assertEquals(TaskFailure.SERVER, c.current().projectError!!.kind)
        source.taskError = null; source.projectError = null; c.refresh(); advanceUntilIdle()
        assertNull(c.current().taskError); assertNull(c.current().projectError)
    }

    @Test fun obsoleteUncooperativeFetchCannotRestoreOlderData() = runTest {
        val source = Source(); val old = CompletableDeferred<List<Task>>()
        source.taskFetch = { withContext(NonCancellable) { old.await() } }
        val c = TasksController(source, this); c.refresh(); runCurrent()
        source.taskFetch = null; source.rows = emptyList(); c.refresh(); runCurrent()
        old.complete(listOf(row)); advanceUntilIdle()
        assertTrue(c.current().tasksLoaded); assertTrue(c.current().tasks.isEmpty())
    }

    @Test fun invalidFieldsAndMissingProjectsSendNoRequests() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle()
        c.edit(); c.draft(TaskDraft(name = " ", cost = "NaN", date = "2026-02-29", sentOn = "bad")); c.saveTask()
        assertEquals(setOf("projectId", "name", "cost", "date", "sentOn"), c.current().fields)
        assertEquals(0, source.writes); assertNotNull(c.current().draft)
        source.projectRows = emptyList(); c.refresh(); advanceUntilIdle(); c.validDraft(); c.saveTask()
        assertEquals(0, source.writes); assertTrue("projectId" in c.current().fields)
    }

    @Test fun projectCreateSelectsReturnedProjectWithoutLosingTaskInput() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.validDraft()
        val draft = c.current().draft!!; c.openProject(); c.projectInput(" New client ", " Description ")
        c.saveProject(); advanceUntilIdle()
        assertEquals(ProjectRequest("New client", "Description"), source.projectRequest)
        assertEquals(draft.copy(projectId = "44444444-4444-4444-8444-444444444444"), c.current().draft)
        assertFalse(c.current().projectForm); assertEquals(2, c.current().projects.size)
    }

    @Test fun duplicateProjectIsInlineAndDoesNotAddPhantomOrCloseForm() = runTest {
        val source = Source().apply { writeError = http(409, "Project already exists") }
        val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.openProject()
        c.projectInput("Client", "Keep this"); c.saveProject(); advanceUntilIdle()
        assertTrue(c.current().projectForm); assertEquals("Keep this", c.current().projectDescription)
        assertEquals(TaskFailure.DUPLICATE, c.current().projectProblem!!.kind)
        assertEquals(listOf(project), c.current().projects); assertFalse(c.current().uncertain)
    }

    @Test fun repeatedSaveIsSingleInFlightAndSentCreateUpdatesHistoryOnly() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }; val c = TasksController(source, this)
        c.refresh(); advanceUntilIdle(); c.validDraft(); c.draft(c.current().draft!!.copy(sentOn = "2026-11-01"))
        c.saveTask(); c.saveTask(); runCurrent(); assertEquals(1, source.writes); assertTrue(c.current().saving)
        c.closeForm(); assertNotNull(c.current().draft)
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(source.lastId); assertNull(c.current().draft)
        assertEquals(2, c.current().tasks.history().size); assertEquals(1, c.current().tasks.summary().pendingCount)
        assertEquals("New work", c.current().tasks.first { it.id != row.id }.name)
    }

    @Test fun editingUpdatesAllFieldsFromConfirmedResponse() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.edit(row)
        val updated = TaskDraft(project.id, " Updated ", "-2,50", "2099-12-31", "2000-01-01", " Details ")
        c.draft(updated); c.saveTask(); advanceUntilIdle()
        assertEquals(row.id, source.lastId); assertEquals(updated.request(), source.last)
        assertEquals("Updated", c.current().selected!!.name); assertEquals("Details", c.current().selected!!.description)
        assertEquals(BigDecimal.ZERO, c.current().tasks.summary().pendingTotal)
    }

    @Test fun statusReadsLatestRecordAndPreservesEveryOtherWritableField() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.select(row)
        val latest = row.copy(name = "Edited on web", cost = BigDecimal("-40.15"), date = "2027-01-01", description = "New description")
        source.rows = listOf(latest); c.status("2026-11-10"); advanceUntilIdle()
        assertEquals(1, source.details); assertEquals(latest.writable("2026-11-10"), source.last)
        assertEquals(0, c.current().tasks.summary().pendingCount)
        c.status(""); advanceUntilIdle()
        assertEquals(latest.writable(""), source.last); assertFalse(c.current().selected!!.isSent)
        assertEquals(BigDecimal("-40.15"), c.current().tasks.summary().pendingTotal)
    }

    @Test fun legacyUnassignedAndDeletedTaskNeverCauseShortcutRecreation() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.select(row)
        source.rows = listOf(row.copy(projectId = "")); c.status("2026-11-10"); advanceUntilIdle()
        assertEquals(0, source.writes); assertEquals(TaskFailure.PROJECT_MISSING, c.current().problem!!.kind)
        source.detailError = http(404); source.rows = emptyList(); c.status(""); advanceUntilIdle()
        assertEquals(0, source.writes); assertNull(c.current().selected)
        assertEquals(TaskFailure.MISSING, c.current().problem!!.kind)
    }

    @Test fun rejectedSaveRetainsInputWithoutReportingSuccess() = runTest {
        val source = Source().apply { writeError = http(400, "Name required") }
        val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.validDraft()
        val draft = c.current().draft; c.saveTask(); advanceUntilIdle()
        assertEquals(draft, c.current().draft); assertEquals(setOf("name"), c.current().fields)
        assertFalse(c.current().uncertain); assertEquals(listOf(row), c.current().tasks)
    }

    @Test fun uncertainWriteSurvivesNavigationAndRequiresSuccessfulReviewBeforeRetry() = runTest {
        val source = Source().apply { writeError = IOException() }; val c = TasksController(source, this)
        c.refresh(); advanceUntilIdle(); c.validDraft(); c.saveTask(); advanceUntilIdle()
        assertTrue(c.current().uncertain); assertNotNull(c.current().draft)
        c.closeForm(); c.validDraft(); c.openProject(); c.projectInput("New", ""); c.saveProject()
        c.closeProject(); c.saveTask(); advanceUntilIdle(); assertEquals(1, source.writes)
        source.taskError = IOException(); c.refresh(); advanceUntilIdle(); c.acknowledgeUncertain()
        assertTrue(c.current().uncertain)
        source.taskError = null; c.refresh(); advanceUntilIdle(); c.acknowledgeUncertain()
        assertFalse(c.current().uncertain); source.writeError = null; c.saveTask(); advanceUntilIdle()
        assertEquals(2, source.writes); assertNull(c.current().draft)
    }

    @Test fun timeoutAfterServerSaveReconcilesWithoutAutomaticPostReplay() = runTest {
        val source = Source(); val c = TasksController(source, this); c.refresh(); advanceUntilIdle(); c.validDraft()
        val draft = c.current().draft!!
        // Simulate the server accepting the create but its response being lost.
        source.rows = source.rows + row.copy(id = "33333333-3333-4333-8333-333333333333", name = draft.name.trim())
        source.writeError = IOException(); c.saveTask(); advanceUntilIdle()
        assertTrue(c.current().uncertain); assertEquals(2, c.current().tasks.size)
        c.saveTask(); c.refresh(); advanceUntilIdle(); assertEquals(1, source.writes)
    }

    @Test fun authenticationExpiryClearsRecordsDraftAndSessionWithoutRetryLoop() = runTest {
        val source = Source(); var expirations = 0; val c = TasksController(source, this) { expirations++ }
        c.refresh(); advanceUntilIdle(); c.validDraft(); source.writeError = http(401)
        c.saveTask(); advanceUntilIdle()
        assertEquals(1, expirations); assertTrue(c.current().authenticationRequired)
        assertTrue(c.current().tasks.isEmpty()); assertTrue(c.current().projects.isEmpty()); assertNull(c.current().draft)
        val reads = source.reads; c.refresh(); c.saveProject(); advanceUntilIdle(); assertEquals(reads, source.reads)
    }

    @Test fun eitherListReturning401ExpiresSessionAndForbiddenDoesNotLoop() = runTest {
        val source = Source().apply { projectError = http(401) }; var expirations = 0
        val c = TasksController(source, this) { expirations++ }; c.refresh(); advanceUntilIdle()
        assertTrue(c.current().authenticationRequired); assertEquals(1, expirations)
        val forbidden = Source().apply { taskError = http(403) }; val other = TasksController(forbidden, this)
        other.refresh(); advanceUntilIdle(); assertEquals(TaskFailure.FORBIDDEN, other.current().taskError!!.kind)
        assertFalse(other.current().authenticationRequired); assertEquals(1, forbidden.reads)
    }
}