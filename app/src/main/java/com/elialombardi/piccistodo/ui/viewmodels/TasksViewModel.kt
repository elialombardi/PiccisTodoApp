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
import javax.inject.Inject

data class TasksState(
    val tasks: List<Task> = emptyList(), val projects: List<Project> = emptyList(),
    val tasksLoaded: Boolean = false, val projectsLoaded: Boolean = false,
    val refreshing: Boolean = false, val saving: Boolean = false,
    val taskError: TaskProblem? = null, val projectError: TaskProblem? = null,
    val problem: TaskProblem? = null, val authenticationRequired: Boolean = false,
    val selected: Task? = null, val draft: TaskDraft? = null, val original: TaskDraft? = null,
    val editId: String? = null, val fields: Set<String> = emptySet(),
    val projectForm: Boolean = false, val projectName: String = "", val projectDescription: String = "",
    val projectProblem: TaskProblem? = null, val projectNameInvalid: Boolean = false,
    val uncertain: Boolean = false
)

/** No disk task cache or offline writes. Each navigation entry owns a fresh controller. */
class TasksController(
    private val source: TaskDataSource,
    private val scope: CoroutineScope,
    private val expireSession: suspend () -> Unit = {}
) {
    private val mutable = MutableStateFlow(TasksState())
    val state = mutable.asStateFlow()
    private var fetch: Job? = null
    private var generation = 0
    private fun change(block: (TasksState) -> TasksState) { mutable.value = block(mutable.value) }
    private suspend fun authenticate(problem: TaskProblem): Boolean {
        if (problem.kind != TaskFailure.AUTH) return false
        generation++
        expireSession()
        mutable.value = TasksState(authenticationRequired = true)
        return true
    }
    fun refresh() {
        if (state.value.saving || state.value.authenticationRequired) return
        fetch?.cancel()
        val version = ++generation
        change { it.copy(refreshing = true) }
        fetch = scope.launch {
            supervisorScope {
                val tasks = async { runCatching { source.tasks() } }
                val projects = async { runCatching { source.projects() } }
                val taskResult = tasks.await()
                val projectResult = projects.await()
                if (version != generation) return@supervisorScope
                val taskError = taskResult.exceptionOrNull()?.let { taskProblem(it as Exception) }
                val projectError = projectResult.exceptionOrNull()?.let { taskProblem(it as Exception) }
                if (taskError?.let { authenticate(it) } == true || projectError?.let { authenticate(it) } == true) return@supervisorScope
                change { current -> current.copy(
                    tasks = taskResult.getOrNull() ?: current.tasks,
                    projects = projectResult.getOrNull()?.sortedWith(compareBy<Project> { it.name }.thenBy { it.id }) ?: current.projects,
                    tasksLoaded = current.tasksLoaded || taskResult.isSuccess,
                    projectsLoaded = current.projectsLoaded || projectResult.isSuccess,
                    taskError = taskError, projectError = projectError, refreshing = false,
                    selected = current.selected?.let { old -> taskResult.getOrNull()?.find { it.id == old.id } ?: if (taskResult.isSuccess) null else old }
                ) }
            }
        }
    }
    fun select(task: Task?) { if (!state.value.saving) change { it.copy(selected = task, problem = null) } }
    fun edit(task: Task? = null) {
        if (state.value.saving) return
        val draft = task?.let(TaskDraft::from) ?: TaskDraft()
        change { it.copy(draft = draft, original = draft, editId = task?.id, fields = emptySet(), problem = null) }
    }
    fun draft(value: TaskDraft) { if (!state.value.saving) change { it.copy(draft = value, fields = emptySet()) } }
    fun closeForm() { if (!state.value.saving) change { it.copy(draft = null, original = null, problem = null) } }
    fun openProject() { if (!state.value.saving) change { it.copy(projectForm = true, projectProblem = null, projectNameInvalid = false) } }
    fun projectInput(name: String, description: String) {
        if (!state.value.saving) change { it.copy(projectName = name, projectDescription = description, projectNameInvalid = false) }
    }
    fun closeProject() { if (!state.value.saving) change { it.copy(projectForm = false, projectName = "", projectDescription = "", projectProblem = null) } }
    // Explicit user decision only, after fetching current records. Never automatically replay writes.
    fun acknowledgeUncertain() {
        if (!state.value.refreshing && state.value.tasksLoaded && state.value.projectsLoaded &&
            state.value.taskError == null && state.value.projectError == null)
            change { it.copy(uncertain = false, problem = null, projectProblem = null) }
    }
    private fun mutate(action: suspend () -> Unit, project: Boolean = false) {
        if (state.value.saving || state.value.uncertain || state.value.authenticationRequired) return
        fetch?.cancel()
        generation++
        change { it.copy(saving = true, refreshing = false, problem = null, projectProblem = null) }
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                val problem = taskProblem(error, writing = true)
                if (!authenticate(problem)) change {
                    if (project) it.copy(projectProblem = problem, uncertain = problem.uncertain)
                    else it.copy(problem = problem, fields = setOfNotNull(problem.field), uncertain = problem.uncertain)
                }
            } finally {
                change { it.copy(saving = false) }
                refresh()
            }
        }
    }
    fun saveTask() {
        val current = state.value
        val draft = current.draft ?: return
        val errors = draft.errors(current.projects)
        if (errors.isNotEmpty()) { change { it.copy(fields = errors) }; return }
        if (!current.projectsLoaded || current.projectError != null) return
        mutate({
            val saved = source.saveTask(current.editId, draft.request())
            change { it.copy(tasks = it.tasks.filterNot { task -> task.id == saved.id } + saved,
                selected = if (current.editId != null) saved else null, draft = null, original = null, problem = null) }
        })
    }
    fun saveProject() {
        val current = state.value
        if (current.projectName.isBlank()) { change { it.copy(projectNameInvalid = true) }; return }
        mutate({
            val saved = source.createProject(ProjectRequest(current.projectName.trim(), current.projectDescription.trim()))
            change { it.copy(projects = (it.projects.filterNot { project -> project.id == saved.id } + saved)
                .sortedWith(compareBy<Project> { project -> project.name }.thenBy { project -> project.id }),
                draft = it.draft?.copy(projectId = saved.id), projectForm = false, projectName = "", projectDescription = "") }
        }, project = true)
    }
    fun status(sentOn: String) {
        val selected = state.value.selected ?: return
        if (sentOn.isNotEmpty() && !validDate(sentOn)) return
        mutate({
            val latest = source.task(selected.id)
            // Legacy unassigned records must be repaired in the full editor.
            if (!validProjectId(latest.projectId.orEmpty()) || state.value.projects.none { it.id == latest.projectId }) {
                change { it.copy(problem = TaskProblem(TaskFailure.PROJECT_MISSING, "projectId")) }
            } else {
                val saved = source.saveTask(latest.id, latest.writable(sentOn))
                change { it.copy(tasks = it.tasks.filterNot { task -> task.id == saved.id } + saved, selected = saved) }
            }
        })
    }
}

@HiltViewModel
class TasksViewModel @Inject constructor(repository: TasksRepository, settings: SettingsManager) : ViewModel() {
    val controller = TasksController(repository, viewModelScope) { settings.clearSession() }
}
