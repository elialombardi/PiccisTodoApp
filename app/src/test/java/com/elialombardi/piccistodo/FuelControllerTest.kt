package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.time.OffsetDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FuelControllerTest {
    private class Source : FuelDataSource {
        val rows = mutableListOf<FuelRecord>()
        var calls = 0
        var deletes = 0
        var resets = 0
        var statsFail = false
        var historyFail = false
        var writeError: Exception? = null
        var afterWrite: () -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var fetchOverride: (suspend () -> FuelStats)? = null
        private var sequence = 0L
        fun add(type: FuelType) {
            rows.add(0, FuelRecord(UUID.randomUUID().toString(), BigInteger.ONE, type,
                OffsetDateTime.parse("2026-11-10T00:00:00Z").plusSeconds(++sequence)))
        }
        override suspend fun stats(): FuelStats {
            fetchOverride?.let { return it() }
            if (statsFail) throw IOException()
            val remaining = (4 - rows.takeWhile { it.fillType != FuelType.BENZINA }.size).coerceAtLeast(0)
            return FuelStats(rows.size, rows.count { it.fillType == FuelType.GPL }, rows.count { it.fillType == FuelType.BENZINA },
                remaining, if (remaining == 0) FuelType.BENZINA else FuelType.GPL)
        }
        override suspend fun records(): List<FuelRecord> {
            if (historyFail) throw IOException()
            return rows.toList()
        }
        override suspend fun create(type: FuelType) {
            calls++
            gate?.await()
            writeError?.let { throw it }
            add(type)
            afterWrite()
        }
        override suspend fun delete(id: String) { deletes++; writeError?.let { throw it }; rows.removeAll { it.id == id } }
        override suspend fun reset() { resets++; writeError?.let { throw it }; rows.clear() }
    }

    @Test fun fullRepeatedCycleAndButtonMapping() = runTest {
        val source = Source()
        val controller = FuelController(source, this)
        controller.refresh(); advanceUntilIdle()
        repeat(2) { cycle ->
            for (remaining in 4 downTo 1) {
                val state = controller.state.value
                assertEquals(remaining, state.stats!!.fillsUntilBenzina)
                assertTrue(state.canCreate(FuelType.GPL)); assertFalse(state.canCreate(FuelType.BENZINA))
                controller.create(FuelType.BENZINA)
                controller.create(FuelType.GPL); advanceUntilIdle()
            }
            assertFalse(controller.state.value.canCreate(FuelType.GPL))
            assertTrue(controller.state.value.canCreate(FuelType.BENZINA))
            controller.create(FuelType.BENZINA); advanceUntilIdle()
            assertEquals((cycle + 1) * 5, controller.state.value.stats!!.totalFills)
            assertEquals(4, controller.state.value.stats!!.fillsUntilBenzina)
        }
        assertEquals(10, source.calls)
    }

    @Test fun duplicateTapsAndSavedButRefreshFailed() = runTest {
        val source = Source()
        val controller = FuelController(source, this)
        controller.refresh(); advanceUntilIdle()
        source.gate = CompletableDeferred()
        source.afterWrite = { source.statsFail = true; source.historyFail = true }
        controller.create(FuelType.GPL); controller.create(FuelType.GPL); runCurrent()
        assertEquals(1, source.calls); assertTrue(controller.state.value.saving)
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(FuelNotice.CREATED, controller.state.value.notice)
        assertFalse(controller.state.value.canCreate(FuelType.GPL))
        controller.refresh(); advanceUntilIdle()
        assertEquals(1, source.calls)
        source.statsFail = false; source.historyFail = false
        controller.refresh(); advanceUntilIdle()
        assertEquals(1, controller.state.value.stats!!.totalFills)
    }

    @Test fun uncertainPostReconcilesAndRequiresUserDecisionWithoutReplay() = runTest {
        val source = Source()
        val controller = FuelController(source, this)
        controller.refresh(); advanceUntilIdle()
        source.writeError = IOException("timeout")
        controller.create(FuelType.GPL); advanceUntilIdle()
        assertTrue(controller.state.value.uncertain)
        assertTrue(controller.state.value.canAcknowledge)
        controller.create(FuelType.GPL); controller.refresh(); advanceUntilIdle()
        assertEquals(1, source.calls)
        controller.acknowledgeUncertain()
        assertTrue(controller.state.value.canCreate(FuelType.GPL))
    }

    @Test fun partialFailuresNeverBecomeEmptyStatsAndKeepUsableHistory() = runTest {
        val source = Source().apply { add(FuelType.GPL); statsFail = true }
        val controller = FuelController(source, this)
        controller.refresh(); advanceUntilIdle()
        assertNull(controller.state.value.stats)
        assertEquals(1, controller.state.value.records.size)
        assertFalse(controller.state.value.canCreate(FuelType.GPL))
        source.statsFail = false; source.historyFail = true
        controller.refresh(); advanceUntilIdle()
        assertTrue(controller.state.value.statsFresh)
        assertTrue(controller.state.value.canCreate(FuelType.GPL))
        assertNotNull(controller.state.value.historyError)
        assertEquals(1, controller.state.value.records.size)
    }

    @Test fun inconsistentSnapshotBlocksCreation() = runTest {
        val source = Source().apply { add(FuelType.GPL) }
        source.fetchOverride = { FuelStats(0, 0, 0, 4, FuelType.GPL) }
        val controller = FuelController(source, this)
        controller.refresh(); advanceUntilIdle()
        assertEquals(FuelFailure.DATA, controller.state.value.statsError!!.kind)
        assertFalse(controller.state.value.canCreate(FuelType.GPL))
    }

    @Test fun roleChecksConfirmCancelDeleteLatestBenzinaAndReset() = runTest {
        val source = Source().apply { repeat(4) { add(FuelType.GPL) }; add(FuelType.BENZINA) }
        val controller = FuelController(source, this)
        controller.sessionChanged("Reader", true); advanceUntilIdle()
        controller.requestDelete(source.rows.first()); controller.requestReset(); controller.confirm()
        assertNull(controller.state.value.confirmation); assertEquals(0, source.resets + source.deletes)
        controller.sessionChanged("admin", true); advanceUntilIdle()
        assertFalse(controller.state.value.admin)
        controller.sessionChanged("Admin", true); advanceUntilIdle()
        controller.requestDelete(source.rows.first()); controller.cancelConfirmation(); controller.confirm()
        controller.requestReset(); controller.cancelConfirmation(); controller.confirm()
        assertEquals(0, source.resets + source.deletes)
        val id = source.rows.first().id
        controller.requestDelete(source.rows.first()); controller.confirm(); controller.confirm(); advanceUntilIdle()
        assertEquals(1, source.deletes); assertTrue(source.rows.none { it.id == id })
        assertEquals(FuelType.BENZINA, controller.state.value.stats!!.nextFillType)
        controller.requestReset(); controller.confirm(); advanceUntilIdle()
        assertEquals(FuelStats(0, 0, 0, 4, FuelType.GPL), controller.state.value.stats)
        assertTrue(controller.state.value.records.isEmpty())
    }

    @Test fun failedDeleteRetainsRowsAndUncertainResetNeedsNewConfirmation() = runTest {
        val source = Source().apply { add(FuelType.GPL) }
        val controller = FuelController(source, this)
        controller.sessionChanged("Admin", true); advanceUntilIdle()
        source.writeError = IOException()
        controller.requestDelete(source.rows.first()); controller.confirm(); advanceUntilIdle()
        assertEquals(1, controller.state.value.records.size)
        controller.acknowledgeUncertain()
        controller.requestReset(); controller.confirm(); advanceUntilIdle()
        controller.confirm(); advanceUntilIdle()
        assertEquals(1, source.resets)
        assertTrue(controller.state.value.uncertain)
        controller.acknowledgeUncertain(); controller.confirm(); advanceUntilIdle()
        assertEquals(1, source.resets)
    }

    @Test fun obsoleteResponseAndAccountChangeCannotRestoreOldData() = runTest {
        val source = Source()
        val controller = FuelController(source, this)
        val old = CompletableDeferred<FuelStats>()
        source.fetchOverride = { withContext(NonCancellable) { old.await() } }
        controller.refresh(); runCurrent()
        source.fetchOverride = null
        controller.sessionChanged("Reader", true); advanceUntilIdle()
        old.complete(FuelStats(4, 4, 0, 0, FuelType.BENZINA)); advanceUntilIdle()
        assertEquals(0, controller.state.value.stats!!.totalFills)
        source.add(FuelType.GPL); controller.refresh(); advanceUntilIdle()
        controller.sessionChanged(null, false)
        assertNull(controller.state.value.stats); assertTrue(controller.state.value.records.isEmpty())
        assertTrue(controller.state.value.authenticationRequired)
    }
}
