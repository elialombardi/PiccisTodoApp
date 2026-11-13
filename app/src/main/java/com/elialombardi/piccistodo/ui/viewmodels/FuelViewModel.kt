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

sealed interface FuelConfirmation {
    data class Delete(val record: FuelRecord) : FuelConfirmation
    data object Reset : FuelConfirmation
}
enum class FuelNotice { CREATED, DELETED, RESET, UNCERTAIN }
data class FuelState(
    val stats: FuelStats? = null, val records: List<FuelRecord> = emptyList(),
    val historyLoaded: Boolean = false, val statsFresh: Boolean = false,
    val refreshing: Boolean = false, val saving: Boolean = false, val admin: Boolean = false,
    val statsError: FuelProblem? = null, val historyError: FuelProblem? = null,
    val problem: FuelProblem? = null, val notice: FuelNotice? = null,
    val uncertain: Boolean = false, val authenticationRequired: Boolean = false,
    val confirmation: FuelConfirmation? = null
) {
    fun canCreate(type: FuelType) = statsFresh && !refreshing && !saving && !uncertain &&
        !authenticationRequired && stats?.nextFillType == type
    val canDestroy get() = admin && historyLoaded && !saving && !refreshing && !uncertain && !authenticationRequired
    val canAcknowledge get() = uncertain && statsFresh && historyLoaded && historyError == null && !refreshing && !saving
}

/** Navigation-local memory only. Generation guards also protect against non-cooperative obsolete responses. */
class FuelController(private val source: FuelDataSource, private val scope: CoroutineScope,
                     private val expireSession: suspend () -> Unit = {}) {
    private val mutable = MutableStateFlow(FuelState())
    val state = mutable.asStateFlow()
    private var fetch: Job? = null
    private var mutation: Job? = null
    private var generation = 0
    private fun change(block: (FuelState) -> FuelState) { mutable.value = block(mutable.value) }

    fun sessionChanged(role: String?, authenticated: Boolean) {
        generation++
        fetch?.cancel()
        mutation?.cancel()
        mutable.value = FuelState(admin = role == "Admin", authenticationRequired = !authenticated)
        if (authenticated) refresh()
    }
    private suspend fun authenticate(problem: FuelProblem?): Boolean {
        if (problem?.kind != FuelFailure.AUTH) return false
        generation++
        mutable.value = FuelState(authenticationRequired = true)
        expireSession()
        return true
    }
    fun refresh() {
        if (state.value.saving || state.value.authenticationRequired) return
        fetch?.cancel()
        val version = ++generation
        change { it.copy(refreshing = true, statsFresh = false) }
        fetch = scope.launch {
            supervisorScope {
                val stats = async { runCatching { source.stats() } }
                val history = async { runCatching { source.records() } }
                val s = stats.await()
                val h = history.await()
                if (version != generation) return@supervisorScope
                var se = s.exceptionOrNull()?.let { fuelProblem(it as Exception) }
                var he = h.exceptionOrNull()?.let { fuelProblem(it as Exception) }
                if (authenticate(se) || authenticate(he)) return@supervisorScope
                if (s.isSuccess && h.isSuccess && !FuelDecoder.consistent(s.getOrThrow(), h.getOrThrow())) {
                    se = FuelProblem(FuelFailure.DATA)
                    he = FuelProblem(FuelFailure.DATA)
                }
                change { it.copy(stats = s.getOrNull() ?: it.stats, records = h.getOrNull() ?: it.records,
                    historyLoaded = it.historyLoaded || h.isSuccess, statsFresh = se == null && s.isSuccess,
                    statsError = se, historyError = he, refreshing = false) }
            }
        }
    }
    fun requestDelete(record: FuelRecord) {
        if (state.value.canDestroy && state.value.records.any { it.id == record.id })
            change { it.copy(confirmation = FuelConfirmation.Delete(record)) }
    }
    fun requestReset() { if (state.value.canDestroy) change { it.copy(confirmation = FuelConfirmation.Reset) } }
    fun cancelConfirmation() { if (!state.value.saving) change { it.copy(confirmation = null) } }
    fun confirm() {
        val current = state.value
        if (!current.canDestroy) return
        when (val target = current.confirmation) {
            is FuelConfirmation.Delete -> if (current.records.any { it.id == target.record.id })
                mutate(FuelNotice.DELETED) { source.delete(target.record.id) }
            FuelConfirmation.Reset -> mutate(FuelNotice.RESET) { source.reset() }
            null -> Unit
        }
    }
    fun create(type: FuelType) {
        if (state.value.canCreate(type)) mutate(FuelNotice.CREATED) { source.create(type) }
    }
    fun acknowledgeUncertain() {
        if (state.value.canAcknowledge) change { it.copy(uncertain = false, notice = null, problem = null) }
    }
    private fun mutate(success: FuelNotice, action: suspend () -> Unit) {
        if (state.value.saving || state.value.uncertain || state.value.authenticationRequired) return
        fetch?.cancel()
        val version = ++generation
        change { it.copy(saving = true, statsFresh = false, refreshing = false, confirmation = null, problem = null, notice = null) }
        mutation = scope.launch {
            try {
                action()
                if (version == generation) change { it.copy(notice = success) }
            } catch (error: Exception) {
                if (version != generation) return@launch
                val problem = fuelProblem(error, writing = true)
                if (authenticate(problem)) return@launch
                change { it.copy(problem = problem, uncertain = problem.uncertain,
                    notice = if (problem.uncertain) FuelNotice.UNCERTAIN else null,
                    admin = it.admin && problem.kind != FuelFailure.FORBIDDEN) }
            } finally {
                if (version == generation) {
                    change { it.copy(saving = false) }
                    refresh()
                }
            }
        }
    }
}

@HiltViewModel
class FuelViewModel @Inject constructor(repository: FuelRepository, settings: SettingsManager) : ViewModel() {
    val controller = FuelController(repository, viewModelScope) { settings.clearSession() }
    init {
        viewModelScope.launch {
            settings.sessionFlow.distinctUntilChanged().collect { (token, _, role) ->
                controller.sessionChanged(role, token != null)
            }
        }
    }
}
