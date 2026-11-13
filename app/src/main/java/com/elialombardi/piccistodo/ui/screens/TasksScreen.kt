package com.elialombardi.piccistodo.ui.screens

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import com.elialombardi.piccistodo.ui.components.WorkspaceHero
import java.time.LocalDate

@Composable
private fun Problem(problem: TaskProblem?) {
    if (problem == null) return
    val message = when (problem.kind) {
        TaskFailure.VALIDATION -> R.string.tasks_error_validation
        TaskFailure.AUTH -> R.string.tasks_error_auth
        TaskFailure.FORBIDDEN -> R.string.tasks_error_permission
        TaskFailure.MISSING -> R.string.tasks_error_missing
        TaskFailure.DUPLICATE -> R.string.tasks_error_duplicate
        TaskFailure.PROJECT_MISSING -> R.string.tasks_error_project
        TaskFailure.NETWORK -> R.string.tasks_error_network
        TaskFailure.SERVER -> R.string.tasks_error_server
        TaskFailure.CONFIG -> R.string.tasks_error_config
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
}

@Composable
fun TasksScreen(onBack: () -> Unit, onAuthenticationRequired: () -> Unit, viewModel: TasksViewModel = hiltViewModel(),
                isTopLevel: Boolean = false, onRootChanged: (Boolean) -> Unit = {}) {
    TasksContent(viewModel.controller, onBack, onAuthenticationRequired, isTopLevel, onRootChanged)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksContent(controller: TasksController, onBack: () -> Unit, onAuthenticationRequired: () -> Unit,
                          isTopLevel: Boolean = false, onRootChanged: (Boolean) -> Unit = {}) {
    val state by controller.state.collectAsState()
    val root = state.draft == null && !state.projectForm && state.selected == null
    LaunchedEffect(root) { onRootChanged(root) }
    var history by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var statusDialog by rememberSaveable { mutableStateOf(false) }
    var sentDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var review by rememberSaveable { mutableStateOf(false) }
    val back = {
        if (!state.saving) {
            when {
                state.projectForm -> {
                    if (state.projectName.isNotEmpty() || state.projectDescription.isNotEmpty()) discard = { controller.closeProject() }
                    else controller.closeProject()
                }
                state.draft != null -> {
                    if (state.draft != state.original) discard = { controller.closeForm() }
                    else controller.closeForm()
                }
                state.selected != null -> controller.select(null)
                else -> onBack()
            }
        }
    }
    BackHandler { back() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    LaunchedEffect(state.authenticationRequired) { if (state.authenticationRequired) onAuthenticationRequired() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(when {
                state.projectForm -> R.string.tasks_add_project
                state.draft != null -> if (state.editId == null) R.string.tasks_add else R.string.tasks_edit
                else -> R.string.tasks_title
            })) }, navigationIcon = {
                if (!isTopLevel || !root) IconButton(onClick = back, enabled = !state.saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tasks_back))
                }
            })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.tasks_saving), Modifier.padding(horizontal = 16.dp))
            }
            when {
                state.projectForm -> ProjectForm(state, controller) { LoadNotices(state, controller) { review = true } }
                state.draft != null -> TaskForm(state, controller) { LoadNotices(state, controller) { review = true } }
                state.selected != null -> {
                    val task = requireNotNull(state.selected)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadNotices(state, controller) { review = true }
                        TaskContents(task, state.projects)
                        Text(task.description?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.tasks_no_description))
                        Problem(state.problem)
                        Text(stringResource(R.string.tasks_status_explanation))
                        Text(stringResource(R.string.tasks_concurrency), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { controller.edit(task) }, enabled = !state.saving) { Text(stringResource(R.string.tasks_edit)) }
                        Button(onClick = { sentDate = LocalDate.now().toString(); statusDialog = true }, enabled = !state.saving && !state.uncertain) {
                            Text(stringResource(if (task.isSent) R.string.tasks_mark_not_sent else R.string.tasks_mark_sent))
                        }
                    }
                }
                else -> {
                    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = controller::refresh, modifier = Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item(key = "notices") { Column { LoadNotices(state, controller) { review = true }; Problem(state.problem) } }
                            item(key = "actions") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { controller.edit() }, enabled = !state.saving) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tasks_add)) }
                                    TextButton(onClick = controller::openProject, enabled = !state.saving) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tasks_add_project)) }
                                    TextButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tasks_refresh)) }
                                }
                            }
                            if (state.tasksLoaded) item(key = "summary") {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val summary = state.tasks.summary()
                                    WorkspaceHero(eur(summary.pendingTotal), stringResource(R.string.ui_pending_total), Icons.Default.WorkOutline)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SuggestionChip(onClick = { history = false }, label = { Text(stringResource(R.string.ui_pending_count, summary.pendingCount)) })
                                        SuggestionChip(onClick = { history = true }, label = { Text(stringResource(R.string.ui_total_tasks, summary.totalCount)) })
                                    }
                                    if (state.taskError != null || state.projectError != null) Text(stringResource(R.string.tasks_stale))
                                }
                            }
                            item(key = "shared") { Text(stringResource(R.string.tasks_shared), style = MaterialTheme.typography.bodySmall) }
                            if (state.projectsLoaded && state.projects.isEmpty()) item(key = "no-projects") { Text(stringResource(R.string.tasks_no_projects)) }
                            item(key = "tabs") {
                                PrimaryTabRow(selectedTabIndex = if (history) 1 else 0) {
                                    Tab(selected = !history, onClick = { history = false }, text = { Text(stringResource(R.string.tasks_not_sent)) })
                                    Tab(selected = history, onClick = { history = true }, text = { Text(stringResource(R.string.tasks_history)) })
                                }
                            }
                            if (state.refreshing) item(key = "loading") { Text(stringResource(if (state.tasksLoaded) R.string.tasks_refreshing else R.string.tasks_loading)) }
                            if (state.tasksLoaded && state.taskError == null && state.tasks.isEmpty()) item { Text(stringResource(R.string.tasks_empty)) }
                            else if (history) items(state.tasks.history(), key = { it.id }) { task ->
                                TaskCard(task, state.projects) { controller.select(task) }
                            } else {
                                val groups = state.tasks.pendingByMonth()
                                if (state.tasksLoaded && state.taskError == null && groups.isEmpty()) item { Text(stringResource(R.string.tasks_none_pending)) }
                                groups.forEach { (month, tasks) ->
                                    item(key = "month:$month") { Text(month, style = MaterialTheme.typography.titleMedium) }
                                    items(tasks, key = { it.id }) { task -> TaskCard(task, state.projects) { controller.select(task) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (discard != null) AlertDialog(onDismissRequest = { discard = null },
        title = { Text(stringResource(R.string.tasks_discard_title)) }, text = { Text(stringResource(R.string.tasks_discard_text)) },
        confirmButton = { TextButton(onClick = { discard?.invoke(); discard = null }) { Text(stringResource(R.string.tasks_discard)) } },
        dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.tasks_keep_editing)) } })

    if (statusDialog && state.selected != null) {
        val isSent = requireNotNull(state.selected).isSent
        AlertDialog(onDismissRequest = { statusDialog = false }, title = { Text(stringResource(if (isSent) R.string.tasks_mark_not_sent else R.string.tasks_mark_sent)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tasks_status_explanation))
                if (isSent) Text(stringResource(R.string.tasks_clear_confirm))
                else DateField(stringResource(R.string.tasks_sent_on), sentDate, false, false) { sentDate = it }
            } },
            confirmButton = { TextButton(onClick = { statusDialog = false; controller.status(if (isSent) "" else sentDate) }, enabled = isSent || validDate(sentDate)) { Text(stringResource(R.string.tasks_confirm)) } },
            dismissButton = { TextButton(onClick = { statusDialog = false }) { Text(stringResource(R.string.tasks_cancel)) } })
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text(stringResource(R.string.tasks_review_title)) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.tasks_uncertain)) }
            items(state.projects, key = { "project:${it.id}" }) { Text(it.name) }
            items(state.tasks.history(), key = { it.id }) { task ->
                Column { TaskContents(task, state.projects); Text(task.description.orEmpty()) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { controller.acknowledgeUncertain(); review = false }, enabled = !state.refreshing && state.tasksLoaded && state.projectsLoaded && state.taskError == null && state.projectError == null) {
            Text(stringResource(R.string.tasks_review_confirm))
        }
    }, dismissButton = { TextButton(onClick = controller::refresh, enabled = !state.refreshing) { Text(stringResource(R.string.tasks_refresh)) } })
}

@Composable
private fun LoadNotices(state: TasksState, controller: TasksController, onReview: () -> Unit) {
    if (state.taskError != null || state.projectError != null) {
        Text(stringResource(R.string.tasks_stale))
        if (state.taskError != null) { Text(stringResource(R.string.tasks_tasks_failed)); Problem(state.taskError) }
        if (state.projectError != null) { Text(stringResource(R.string.tasks_projects_failed)); Problem(state.projectError) }
        TextButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) { Text(stringResource(R.string.tasks_retry)) }
    }
    if (state.uncertain) {
        Text(stringResource(R.string.tasks_uncertain), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onReview) { Text(stringResource(R.string.tasks_review)) }
    }
}

@Composable
private fun TaskContents(task: Task, projects: List<Project>) {
    Text(task.name, style = MaterialTheme.typography.titleMedium)
    Text(projects.find { it.id == task.projectId }?.name ?: task.projectName?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.tasks_unknown_project))
    Text(stringResource(R.string.tasks_date_value, task.date))
    Text(eur(task.cost), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Surface(shape = MaterialTheme.shapes.small,
        color = if (task.isSent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (task.isSent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(stringResource(if (task.isSent) R.string.tasks_sent else R.string.tasks_not_sent), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
    if (task.isSent) Text(stringResource(R.string.tasks_sent_value, task.sentOn.orEmpty()))
}

@Composable
private fun TaskCard(task: Task, projects: List<Project>, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { TaskContents(task, projects) }
    }
}

@Composable
private fun TaskForm(state: TasksState, controller: TasksController, notices: @Composable () -> Unit) {
    val draft = requireNotNull(state.draft)
    var picker by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.saving
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notices()
        Problem(state.problem)
        Text(stringResource(R.string.tasks_concurrency), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker = true }, enabled = enabled && state.projectsLoaded && state.projectError == null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tasks_project) + ": " + (state.projects.find { it.id == draft.projectId }?.name ?: stringResource(R.string.tasks_required)))
        }
        if ("projectId" in state.fields) Text(stringResource(R.string.tasks_valid_project), color = MaterialTheme.colorScheme.error)
        if (state.projectsLoaded && state.projects.isEmpty()) Text(stringResource(R.string.tasks_no_projects))
        TextButton(onClick = controller::openProject, enabled = enabled) { Text(stringResource(R.string.tasks_add_project)) }
        DraftText(draft.name, { controller.draft(draft.copy(name = it)) }, R.string.tasks_name, "name" in state.fields, R.string.tasks_required, enabled)
        DraftText(draft.cost, { controller.draft(draft.copy(cost = it)) }, R.string.tasks_cost, "cost" in state.fields, R.string.tasks_valid_cost, enabled, decimal = true)
        DateField(stringResource(R.string.tasks_date), draft.date, "date" in state.fields, false, enabled) { controller.draft(draft.copy(date = it)) }
        DateField(stringResource(R.string.tasks_sent_on), draft.sentOn, "sentOn" in state.fields, true, enabled) { controller.draft(draft.copy(sentOn = it)) }
        DraftText(draft.description, { controller.draft(draft.copy(description = it)) }, R.string.tasks_description, false, R.string.tasks_required, enabled, multiline = true)
        Button(onClick = controller::saveTask, enabled = enabled && !state.uncertain && state.projectsLoaded && state.projects.isNotEmpty() && state.projectError == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tasks_save)) }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text(stringResource(R.string.tasks_project)) }, text = {
        LazyColumn { items(state.projects, key = { it.id }) { project ->
            TextButton(onClick = { controller.draft(draft.copy(projectId = project.id)); picker = false }, modifier = Modifier.fillMaxWidth()) { Text(project.name) }
        } }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.tasks_cancel)) } })
}

@Composable
private fun ProjectForm(state: TasksState, controller: TasksController, notices: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notices()
        Problem(state.projectProblem)
        DraftText(state.projectName, { controller.projectInput(it, state.projectDescription) }, R.string.tasks_project_name,
            state.projectNameInvalid || state.projectProblem?.kind == TaskFailure.DUPLICATE || state.projectProblem?.field == "name", R.string.tasks_required, !state.saving)
        DraftText(state.projectDescription, { controller.projectInput(state.projectName, it) }, R.string.tasks_description, false, R.string.tasks_required, !state.saving, multiline = true)
        Button(onClick = controller::saveProject, enabled = !state.saving && !state.uncertain) { Text(stringResource(R.string.tasks_save)) }
    }
}

@Composable
private fun DraftText(value: String, onChange: (String) -> Unit, label: Int, error: Boolean, help: Int, enabled: Boolean, multiline: Boolean = false, decimal: Boolean = false) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        enabled = enabled, isError = error, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = if (error) ({ Text(stringResource(help)) }) else null)
}

/** Native date picker returns calendar components directly; never convert through UTC instants. */
@Composable
private fun DateField(label: String, value: String, error: Boolean, clearable: Boolean, enabled: Boolean = true, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var choosing by rememberSaveable { mutableStateOf(false) }
    var pickerDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val update by rememberUpdatedState(onChange)
    Column {
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(label) }, isError = error, singleLine = true,
            placeholder = { Text("YYYY-MM-DD") },
            trailingIcon = { IconButton(enabled = enabled, onClick = {
                pickerDate = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }.toString()
                choosing = true
            }) { Icon(Icons.Default.DateRange, stringResource(R.string.tasks_choose_date) + ": " + label) } },
            supportingText = if (error) ({ Text(stringResource(R.string.tasks_valid_date)) }) else null)
        if (clearable && value.isNotEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text(stringResource(R.string.tasks_clear_date)) }
    }
    if (choosing) DisposableEffect(context) {
        val initial = LocalDate.parse(pickerDate)
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            update(LocalDate.of(year, month + 1, day).toString())
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.datePicker.init(initial.year, initial.monthValue - 1, initial.dayOfMonth) { _, year, month, day ->
            pickerDate = LocalDate.of(year, month + 1, day).toString()
        }
        dialog.setOnDismissListener { choosing = false }
        dialog.show()
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

