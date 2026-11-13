package com.elialombardi.piccistodo

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.FuelDataSource
import com.elialombardi.piccistodo.ui.screens.FuelContent
import com.elialombardi.piccistodo.ui.viewmodels.FuelController
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.time.OffsetDateTime
import java.util.UUID

/** Fake data only: never logs in or mutates a real user's backend history. */
@RunWith(AndroidJUnit4::class)
class FuelScreenTest {
    @get:Rule val ui = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val controller = FuelController(source, scope)
    private class Source : FuelDataSource {
        val rows = mutableListOf<FuelRecord>()
        var creates = 0
        var deletes = 0
        var resets = 0
        fun seed(type: FuelType) {
            rows.add(0, FuelRecord(UUID.randomUUID().toString(), BigInteger.ONE, type,
                OffsetDateTime.parse("2026-11-10T00:40:00.123456+02:00").plusSeconds(creates.toLong() + rows.size)))
        }
        override suspend fun stats(): FuelStats {
            val remaining = (4 - rows.takeWhile { it.fillType == FuelType.GPL }.size).coerceAtLeast(0)
            return FuelStats(rows.size, rows.count { it.fillType == FuelType.GPL }, rows.count { it.fillType == FuelType.BENZINA },
                remaining, if (remaining == 0) FuelType.BENZINA else FuelType.GPL)
        }
        override suspend fun records() = rows.toList()
        override suspend fun create(type: FuelType) { creates++; seed(type) }
        override suspend fun delete(id: String) { deletes++; rows.removeAll { it.id == id } }
        override suspend fun reset() { resets++; rows.clear() }
    }
    @After fun close() { scope.cancel() }
    private fun show(role: String = "Reader", largeDark: Boolean = false) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeDark) 2f else density.fontScale)) {
                MaterialTheme(colorScheme = if (largeDark) darkColorScheme() else lightColorScheme()) {
                    FuelContent(controller)
                }
            }
        }
        ui.runOnIdle { controller.sessionChanged(role, true) }
        ui.waitUntil(5000) { controller.state.value.statsFresh && !controller.state.value.refreshing }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "fuel-qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun action(id: Int) = ui.onNodeWithText(text(id)).performScrollTo()

    @Test fun readerFullCycleAndNoDestructiveControls() {
        show()
        action(R.string.fuel_add_gpl).assertIsEnabled()
        action(R.string.fuel_add_benzina).assertIsNotEnabled()
        ui.onNodeWithContentDescription(text(R.string.fuel_more)).assertDoesNotExist()
        ui.onNodeWithContentDescription(text(R.string.fuel_delete)).assertDoesNotExist()
        screenshot("01-empty-reader")
        repeat(4) {
            action(R.string.fuel_add_gpl).performClick()
            ui.waitUntil(5000) { controller.state.value.statsFresh && controller.state.value.stats?.totalFills == it + 1 }
        }
        action(R.string.fuel_add_gpl).assertIsNotEnabled()
        action(R.string.fuel_add_benzina).assertIsEnabled()
        screenshot("02-benzina-due")
        action(R.string.fuel_add_benzina).performClick()
        ui.waitUntil(5000) { controller.state.value.statsFresh && controller.state.value.stats?.totalFills == 5 }
        action(R.string.fuel_add_gpl).assertIsEnabled()
        action(R.string.fuel_add_benzina).assertIsNotEnabled()
        assertEquals(5, source.creates)
        assertEquals(4, controller.state.value.stats!!.fillsUntilBenzina)
        screenshot("03-cycle-complete")
    }

    @Test fun adminDeleteAndResetRequireConfirmationAndCancelSendsNothing() {
        source.seed(FuelType.GPL)
        show("Admin")
        ui.onNodeWithContentDescription(text(R.string.fuel_delete)).performScrollTo().performClick()
        ui.onNodeWithText(text(R.string.fuel_delete_title)).assertIsDisplayed()
        screenshot("04-delete-confirmation")
        ui.onNodeWithText(text(R.string.fuel_cancel)).performClick()
        ui.runOnIdle { assertEquals(0, source.deletes) }
        ui.onNodeWithContentDescription(text(R.string.fuel_delete)).performClick()
        ui.onNodeWithText(text(R.string.fuel_confirm_delete)).performClick()
        ui.waitUntil(5000) { source.deletes == 1 && controller.state.value.statsFresh }
        ui.onNodeWithContentDescription(text(R.string.fuel_more)).performClick()
        ui.onNodeWithText(text(R.string.fuel_reset)).performClick()
        screenshot("05-reset-confirmation")
        ui.onNodeWithText(text(R.string.fuel_cancel)).performClick()
        assertEquals(0, source.resets)
        ui.onNodeWithContentDescription(text(R.string.fuel_more)).performClick()
        ui.onNodeWithText(text(R.string.fuel_reset)).performClick()
        ui.onNodeWithText(text(R.string.fuel_confirm_delete)).performClick()
        ui.waitUntil(5000) { source.resets == 1 && controller.state.value.statsFresh }
        assertEquals(0, controller.state.value.stats!!.totalFills)
    }

    @Test fun largeTextDarkModeKeepsGuidanceAndScrollableHistory() {
        repeat(60) { source.seed(FuelType.GPL) }
        show("Writer", largeDark = true)
        ui.onNodeWithText(text(R.string.fuel_due)).assertIsDisplayed()
        action(R.string.fuel_add_gpl).assertIsNotEnabled()
        action(R.string.fuel_add_benzina).assertIsEnabled()
        ui.onNodeWithText(text(R.string.fuel_history)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription(text(R.string.fuel_more)).assertDoesNotExist()
        screenshot("06-large-text-dark-history")
    }
}
