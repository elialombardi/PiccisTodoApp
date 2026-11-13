package com.elialombardi.piccistodo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.elialombardi.piccistodo.R
import com.elialombardi.piccistodo.data.model.FuelType
import com.elialombardi.piccistodo.data.repository.FuelFailure
import com.elialombardi.piccistodo.data.repository.FuelProblem
import com.elialombardi.piccistodo.ui.viewmodels.*

@Composable
fun FuelScreen(onAuthenticationRequired: () -> Unit, viewModel: FuelViewModel = hiltViewModel()) {
    FuelContent(viewModel.controller, onAuthenticationRequired)
}

@Composable
private fun fuelLabel(type: FuelType) = stringResource(if (type == FuelType.GPL) R.string.fuel_gpl else R.string.fuel_benzina)

@Composable
private fun FuelError(problem: FuelProblem?) {
    if (problem == null) return
    val message = if (problem.kind == FuelFailure.BENZINA_BLOCKED) {
        val remaining = problem.remaining ?: 0
        pluralStringResource(R.plurals.fuel_error_benzina_blocked, remaining, remaining)
    } else stringResource(when (problem.kind) {
        FuelFailure.VALIDATION -> R.string.fuel_error_validation
        FuelFailure.GPL_BLOCKED -> R.string.fuel_error_gpl_blocked
        FuelFailure.AUTH -> R.string.fuel_error_auth
        FuelFailure.FORBIDDEN -> R.string.fuel_error_forbidden
        FuelFailure.MISSING -> R.string.fuel_error_missing
        FuelFailure.NETWORK -> R.string.fuel_error_network
        FuelFailure.SERVER -> R.string.fuel_error_server
        FuelFailure.DATA -> R.string.fuel_error_data
        FuelFailure.CONFIG -> R.string.fuel_error_config
        FuelFailure.BENZINA_BLOCKED -> R.string.fuel_error_validation
    })
    Text(message, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FuelContent(controller: FuelController, onAuthenticationRequired: () -> Unit = {}) {
    val state by controller.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    LaunchedEffect(state.authenticationRequired) { if (state.authenticationRequired) onAuthenticationRequired() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.fuel_title)) }, actions = {
            IconButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) {
                Icon(Icons.Default.Refresh, stringResource(R.string.fuel_refresh))
            }
            if (state.admin) {
                IconButton(onClick = { menu = true }, enabled = state.canDestroy) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.fuel_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fuel_reset), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; controller.requestReset() }, enabled = state.canDestroy)
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.saving || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            // Guidance stays visible above the virtualized history, including on long histories.
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val stats = state.stats
                    Text(when {
                        !state.statsFresh && state.refreshing -> stringResource(R.string.fuel_loading)
                        !state.statsFresh && stats != null -> stringResource(R.string.fuel_stale)
                        !state.statsFresh -> stringResource(R.string.fuel_stats_error)
                        stats?.fillsUntilBenzina == 0 -> stringResource(R.string.fuel_due)
                        else -> pluralStringResource(R.plurals.fuel_remaining, stats!!.fillsUntilBenzina, stats.fillsUntilBenzina)
                    })
                }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = controller::refresh, modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.saving) Text(stringResource(R.string.fuel_saving))
                            state.notice?.let { notice ->
                                Text(stringResource(when (notice) {
                                    FuelNotice.CREATED -> if (state.statsError != null || state.historyError != null) R.string.fuel_saved_refresh_failed else R.string.fuel_created
                                    FuelNotice.DELETED -> R.string.fuel_deleted
                                    FuelNotice.RESET -> R.string.fuel_reset_done
                                    FuelNotice.UNCERTAIN -> R.string.fuel_uncertain
                                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            FuelError(state.problem)
                            if (state.statsError != null) {
                                Text(stringResource(R.string.fuel_stats_error))
                                FuelError(state.statsError)
                            }
                            if (state.historyError != null || state.statsError != null || state.uncertain) {
                                OutlinedButton(onClick = controller::refresh, enabled = !state.saving && !state.refreshing) {
                                    Text(stringResource(R.string.fuel_retry))
                                }
                            }
                            if (state.uncertain) OutlinedButton(onClick = controller::acknowledgeUncertain, enabled = state.canAcknowledge) {
                                Text(stringResource(R.string.fuel_reviewed))
                            }
                        }
                    }
                    state.stats?.let { stats ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.fuel_total, stats.totalFills))
                                    Text(stringResource(R.string.fuel_gpl_count, stats.gplCount))
                                    Text(stringResource(R.string.fuel_benzina_count, stats.benzinaCount))
                                    Text(stringResource(R.string.fuel_remaining_count, stats.fillsUntilBenzina))
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.create(FuelType.GPL) }, enabled = state.canCreate(FuelType.GPL), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.fuel_add_gpl))
                            }
                            Button(onClick = { controller.create(FuelType.BENZINA) }, enabled = state.canCreate(FuelType.BENZINA), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.fuel_add_benzina))
                            }
                        }
                    }
                    item { Text(stringResource(R.string.fuel_history), style = MaterialTheme.typography.titleLarge) }
                    if (state.historyError != null) item {
                        Column {
                            Text(stringResource(R.string.fuel_history_error))
                            FuelError(state.historyError)
                        }
                    }
                    if (state.historyLoaded && state.records.isEmpty() && state.historyError == null) item {
                        Text(stringResource(if (state.statsFresh && state.stats?.totalFills == 0) R.string.fuel_empty_initial else R.string.fuel_empty))
                    }
                    items(state.records, key = { it.id }) { record ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(fuelLabel(record.fillType), Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                    }
                                    Text(record.localDateTime())
                                }
                                if (state.admin) IconButton(onClick = { controller.requestDelete(record) }, enabled = state.canDestroy) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.fuel_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.confirmation?.let { confirmation ->
        val record = (confirmation as? FuelConfirmation.Delete)?.record
        AlertDialog(onDismissRequest = controller::cancelConfirmation,
            title = { Text(stringResource(if (record == null) R.string.fuel_reset_title else R.string.fuel_delete_title)) },
            text = { Text(if (record == null) stringResource(R.string.fuel_reset_message)
                else stringResource(R.string.fuel_delete_message, fuelLabel(record.fillType), record.localDateTime())) },
            confirmButton = {
                TextButton(onClick = controller::confirm, enabled = state.canDestroy) {
                    Text(stringResource(R.string.fuel_confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = controller::cancelConfirmation) { Text(stringResource(R.string.fuel_cancel)) } })
    }
}

