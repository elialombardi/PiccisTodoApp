package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.remote.TasksApi
import com.elialombardi.piccistodo.data.repository.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

class TasksContractTest {
    private val projectId = "11111111-1111-4111-8111-111111111111"
    private val taskId = "22222222-2222-4222-8222-222222222222"
    private val taskJson = """{"id":"$taskId","projectId":"$projectId","projectName":"Client","name":"Work","cost":120.5,"date":"2026-11-09"}"""
    private val task get() = Gson().fromJson(taskJson, Task::class.java)

    @Test fun statusUsesOnlyNonemptySentDateAndToleratesLegacyOptionalFields() {
        listOf("", ",\"sentOn\":null", ",\"sentOn\":\"\"").forEach { suffix ->
            val row = Gson().fromJson(taskJson.dropLast(1) + suffix + "}", Task::class.java)
            assertFalse(row.isSent); assertEquals("", row.writable().sentOn)
            assertEquals("", row.writable().description)
        }
        assertTrue(task.copy(sentOn = "2026-11-10").isSent)
        val legacy = task.copy(projectId = "", projectName = "")
        assertEquals(BigDecimal("120.5"), listOf(legacy).summary().pendingTotal)
        assertTrue(TaskDraft.from(legacy).errors(emptyList()).contains("projectId"))
    }

    @Test fun summaryIsExactAndIncludesUnassignedAndNegativePendingCosts() {
        val rows = listOf(task.copy(cost = BigDecimal("0.10"), projectId = null),
            task.copy(cost = BigDecimal("0.20")), task.copy(cost = BigDecimal("-0.05")),
            task.copy(cost = BigDecimal("999"), sentOn = "2026-11-01"))
        assertEquals(TaskSummary(3, BigDecimal("0.25"), 4), rows.summary())
        assertEquals(TaskSummary(0, BigDecimal.ZERO, 0), emptyList<Task>().summary())
        assertEquals("€0.25", eur(rows.summary().pendingTotal, Locale.US))
    }

    @Test fun pendingMonthsAndHistoryUseStableUuidStringOrderWithBothStatuses() {
        val a = task.copy(id = "00000000-0000-4000-8000-000000000001", date = "2026-10-01")
        val b = a.copy(id = "ffffffff-ffff-4fff-8fff-ffffffffffff")
        val sent = task.copy(sentOn = "2026-11-10")
        val rows = listOf(sent, b, task, a)
        assertEquals(listOf("2026-10", "2026-11"), rows.pendingByMonth().keys.toList())
        assertEquals(listOf(a, b), rows.pendingByMonth()["2026-10"])
        assertEquals(listOf(sent, task, b, a), rows.history())
    }

    @Test fun decimalCommaAndFiniteNumericValidationDoNotInventNonnegativeRule() {
        assertEquals(BigDecimal("-12.50"), parseCost(" -12,50 "))
        assertEquals(BigDecimal("0.25"), parseCost(".25"))
        listOf("", "NaN", "Infinity", "1,2.3", "1 234,50", "--1", "1e9999", "9".repeat(400)).forEach {
            assertNull(it, parseCost(it))
        }
        val draft = TaskDraft(projectId, " Work ", "12,50", "2026-11-09", description = " Details ")
        val json = Gson().toJsonTree(draft.request()).asJsonObject
        assertEquals(setOf("projectId", "name", "cost", "date", "sentOn", "description"), json.keySet())
        assertTrue(json["cost"].asJsonPrimitive.isNumber)
        assertEquals(BigDecimal("12.50"), json["cost"].asBigDecimal)
        assertEquals("Work", json["name"].asString); assertEquals("Details", json["description"].asString)
        assertEquals("", json["sentOn"].asString)
    }

    @Test fun validationRequiresRealDatesAndProjectButNoDateOrderOrLengthLimits() {
        val project = Project(projectId, "Client")
        val invalid = TaskDraft("bad", "  ", "NaN", "2026-02-29", "2026-11-31")
        assertEquals(setOf("projectId", "name", "cost", "date", "sentOn"), invalid.errors(listOf(project)))
        listOf("2026-1-01", "2026-11-09T00:00:00Z", "", "2026-04-31").forEach { assertFalse(validDate(it)) }
        assertTrue(validDate("2024-02-29"))
        assertTrue(TaskDraft(projectId, "x".repeat(1000), "-1", "2099-12-31", "2000-01-01",
            "x".repeat(10000)).errors(listOf(project)).isEmpty())
        assertEquals("10", TaskDraft().cost); assertEquals("", TaskDraft().projectId)
    }

    @Test fun calendarDatesRemainUnchangedOnBothSidesOfUtc() {
        val original = TimeZone.getDefault()
        try {
            listOf("Pacific/Kiritimati", "America/Los_Angeles", "Asia/Tokyo").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(LocalDate.now().toString(), TaskDraft().date)
                assertEquals("2026-11-09", TaskDraft.from(task).request().date)
                assertEquals("2026-11-01", task.writable("2026-11-01").sentOn)
            }
        } finally { TimeZone.setDefault(original) }
    }

    private suspend fun withApi(block: suspend (MockWebServer, TasksApi) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val api = Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(TasksApi::class.java)
            block(server, api)
        }
    }

    @Test fun httpArraysCreatedResponsesFullPutAndExplicitClearsFollowExactContract() = runBlocking {
        withApi { server, api ->
            server.enqueue(MockResponse.Builder().code(200).body("[$taskJson]").build())
            assertEquals(taskId, api.tasks().single().id)
            val get = server.takeRequest(); assertEquals("/api/tasks", get.url.encodedPath)
            assertEquals("application/json", get.headers["Accept"])
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            assertTrue(api.projects().isEmpty()); assertEquals("/api/projects", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            assertTrue(api.tasks().isEmpty()); server.takeRequest()
            server.enqueue(MockResponse.Builder().code(201).body(taskJson).build())
            assertEquals(taskId, api.createTask(task.writable()).id)
            val post = server.takeRequest(); assertEquals("POST", post.method)
            assertEquals("/api/tasks", post.url.encodedPath)
            assertTrue(post.headers["Content-Type"]!!.startsWith("application/json"))
            val body = JsonParser.parseString(post.body!!.utf8()).asJsonObject
            assertEquals(setOf("projectId", "name", "cost", "date", "sentOn", "description"), body.keySet())
            assertTrue(body["cost"].asJsonPrimitive.isNumber)
            assertEquals("", body["sentOn"].asString); assertEquals("", body["description"].asString)
            server.enqueue(MockResponse.Builder().code(200).body(taskJson).build())
            api.task(taskId); assertEquals("/api/tasks/$taskId", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(200).body(taskJson).build())
            api.updateTask(taskId, task.writable("2026-11-10"))
            val put = server.takeRequest(); assertEquals("PUT", put.method)
            assertEquals("/api/tasks/$taskId", put.url.encodedPath)
            assertEquals("2026-11-10", JsonParser.parseString(put.body!!.utf8()).asJsonObject["sentOn"].asString)
            server.enqueue(MockResponse.Builder().code(201).body("""{"id":"$projectId","name":"Client"}""").build())
            assertEquals(projectId, api.createProject(ProjectRequest("Client", "")).id)
            assertEquals("/api/projects", server.takeRequest().url.encodedPath)
        }
    }

    @Test fun plainTextAndJsonHttpErrorsAreClassifiedWithoutExposingInternals() = runBlocking {
        withApi { server, api ->
            listOf(400 to TaskFailure.VALIDATION, 401 to TaskFailure.AUTH, 403 to TaskFailure.FORBIDDEN,
                404 to TaskFailure.MISSING, 409 to TaskFailure.DUPLICATE, 500 to TaskFailure.SERVER).forEach { (code, expected) ->
                server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "text/plain")
                    .body(if (code == 400) "Name required" else "private database internals").build())
                val error = try { api.createTask(task.writable()); error("Expected HTTP failure") } catch (e: HttpException) { e }
                val problem = taskProblem(error, writing = true)
                assertEquals(expected, problem.kind); assertEquals(code >= 500, problem.uncertain)
                assertEquals(if (code == 400) "name" else null, problem.field)
                server.takeRequest()
            }
            server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "application/json")
                .body("""{"message":"SentOn must be yyyy-MM-dd"}""").build())
            val error = try { api.updateTask(taskId, task.writable()); error("Expected failure") } catch (e: HttpException) { e }
            assertEquals("sentOn", taskProblem(error).field)
        }
    }

    @Test fun uncertainNetworkAndParsingFailuresCannotBeMistakenForSafeResubmission() {
        assertEquals(TaskProblem(TaskFailure.NETWORK, uncertain = true), taskProblem(IOException(), writing = true))
        assertFalse(taskProblem(IOException()).uncertain)
        assertTrue(taskProblem(IllegalStateException("Malformed success response"), writing = true).uncertain)
        assertTrue(taskProblem(IllegalArgumentException("Malformed decimal response"), writing = true).uncertain)
        assertEquals(TaskFailure.CONFIG, taskProblem(TasksConfigurationException(IllegalArgumentException())).kind)
        assertThrows(CancellationException::class.java) { taskProblem(CancellationException()) }
    }
}