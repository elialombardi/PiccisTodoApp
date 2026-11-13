package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.elialombardi.piccistodo.ui.viewmodels.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class FuelContractTest {
    private val empty = """{"totalFills":0,"gplCount":0,"benzinaCount":0,"fillsUntilBenzina":4,"nextFillType":"gpl"}"""
    private val record = """{"id":"44444444-4444-4444-8444-444444444444","userId":7,"fillType":"gpl","createdAt":"2026-11-10T00:40:00.123456+02:00"}"""
    private fun json(value: String) = JsonParser.parseString(value)

    @Test fun serializationContainsOnlyExactLowercaseFillType() {
        FuelType.entries.forEach { type ->
            val obj = json(Gson().toJson(FuelRequest(type.wire))).asJsonObject
            assertEquals(setOf("fillType"), obj.keySet())
            assertEquals(type.wire, obj.get("fillType").asString)
        }
        assertThrows(IllegalArgumentException::class.java) { FuelRequest("GPL") }
    }

    @Test fun numericUserIdOffsetFractionAndLocalDateAcrossUtc() {
        val parsed = FuelDecoder.record(json(record))
        assertEquals(7, parsed.userId.toInt())
        assertEquals(123456000, parsed.createdAt.nano)
        assertEquals(7200, parsed.createdAt.offset.totalSeconds)
        assertTrue(parsed.localDateTime(ZoneId.of("America/Los_Angeles"), Locale.US).contains("Nov 9, 2026"))
        assertTrue(parsed.localDateTime(ZoneId.of("Asia/Tokyo"), Locale.US).contains("Nov 10, 2026"))
        val newer = record.replace("44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555")
            .replace("2026-11-10T00:40:00.123456+02:00", "2026-11-10T08:40:00Z")
        assertEquals("55555555-5555-4555-8555-555555555555", FuelDecoder.records(json("[$record,$newer]")).first().id)
    }

    @Test fun rejectMissingInvalidAndInconsistentDataRatherThanSupplyingZeroes() {
        val invalidStats = listOf("{}", empty.replace("\"gpl\"", "\"GPL\""), empty.replace("\"fillsUntilBenzina\":4", "\"fillsUntilBenzina\":0"),
            empty.replace("\"totalFills\":0", "\"totalFills\":1"), empty.replace("\"totalFills\":0", "\"totalFills\":\"0\""),
            empty.replace("\"totalFills\":0", "\"totalFills\":0.0"))
        invalidStats.forEach { raw -> assertThrows(FuelDataException::class.java) { FuelDecoder.stats(json(raw)) } }
        listOf(record.replace("\"userId\":7", "\"userId\":\"7\""), record.replace("\"gpl\"", "\"petrol\""),
            record.replace("2026-11-10T00:40:00.123456+02:00", "2026-11-10"), record.replace("44444444-4444-4444-8444-444444444444", "1"))
            .forEach { raw -> assertThrows(FuelDataException::class.java) { FuelDecoder.record(json(raw)) } }
        assertEquals(FuelStats(0, 0, 0, 4, FuelType.GPL), FuelDecoder.stats(json(empty)))
    }

    private suspend fun withServer(block: suspend (MockWebServer, FuelHttpDataSource) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(FuelApi::class.java)
            block(server, FuelHttpDataSource { api })
        }
    }

    @Test fun http201And204UseCorrectPathsBodyAndNo204JsonDecoding() = runTest {
        withServer { server, source ->
            server.enqueue(MockResponse.Builder().code(201).body(record).build())
            source.create(FuelType.GPL)
            val post = server.takeRequest()
            assertEquals("POST", post.method); assertEquals("/api/fuel/records", post.url.encodedPath)
            assertEquals("gpl", json(post.body!!.utf8()).asJsonObject.get("fillType").asString)
            assertTrue(post.headers["Content-Type"]!!.startsWith("application/json"))
            server.enqueue(MockResponse.Builder().code(204).build())
            source.delete("44444444-4444-4444-8444-444444444444")
            assertEquals("/api/fuel/admin/records/44444444-4444-4444-8444-444444444444", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(204).build()); source.reset()
            assertEquals("/api/fuel/admin/records", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(200).body(empty).build())
            assertEquals(4, source.stats().fillsUntilBenzina)
            server.enqueue(MockResponse.Builder().code(200).body("[]").build())
            assertTrue(source.records().isEmpty())
        }
    }

    @Test fun plainTextAndJsonErrorsAreSafelyMappedForEveryRequiredStatus() = runTest {
        withServer { server, source ->
            val cases = listOf(
                Triple(400, "must fill with benzina before adding more GPL fills", FuelFailure.GPL_BLOCKED),
                Triple(400, "{\"error\":\"must wait 3 more GPL fills before benzina\"}", FuelFailure.BENZINA_BLOCKED),
                Triple(400, "{\"message\":\"invalid request body\"}", FuelFailure.VALIDATION),
                Triple(401, "unauthorized", FuelFailure.AUTH), Triple(403, "forbidden", FuelFailure.FORBIDDEN),
                Triple(404, "record not found", FuelFailure.MISSING), Triple(500, "private database internals", FuelFailure.SERVER))
            for ((code, body, expected) in cases) {
                server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", if (body.startsWith("{")) "application/json" else "text/plain").body(body).build())
                val error = try { source.create(FuelType.GPL); error("Expected error") } catch (error: HttpException) { error }
                val problem = fuelProblem(error, writing = true)
                assertEquals(expected, problem.kind)
                if (expected == FuelFailure.BENZINA_BLOCKED) assertEquals(3, problem.remaining)
                assertEquals(code >= 500, problem.uncertain)
            }
        }
    }

    @Test fun cycleRejectionRefreshesWithoutPhantomRecordAnd401ExpiresOnce() = runBlocking {
        withServer { server, source ->
            // Initial concurrent GETs return interchangeable path-specific values via a dispatcher.
            val dispatcher = object : mockwebserver3.Dispatcher() {
                @Volatile var expired = false
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    if (expired) return MockResponse.Builder().code(401).body("unauthorized").build()
                    if (request.method == "POST") return MockResponse.Builder().code(400)
                        .addHeader("Content-Type", "text/plain").body("must fill with benzina before adding more GPL fills").build()
                    return MockResponse.Builder().code(200).body(if (request.url.encodedPath.endsWith("stats")) empty else "[]").build()
                }
            }
            server.dispatcher = dispatcher
            var expirations = 0
            val controller = FuelController(source, this) { expirations++ }
            controller.refresh()
            withTimeout(5000) { controller.state.first { !it.refreshing } }
            controller.create(FuelType.GPL)
            withTimeout(5000) { controller.state.first { !it.saving && !it.refreshing } }
            assertEquals(FuelFailure.GPL_BLOCKED, controller.state.value.problem!!.kind)
            assertTrue(controller.state.value.records.isEmpty())
            assertEquals(0, expirations)
            dispatcher.expired = true
            controller.refresh()
            withTimeout(5000) { controller.state.first { it.authenticationRequired } }
            assertEquals(1, expirations)
            assertNull(controller.state.value.stats)
            assertTrue(controller.state.value.records.isEmpty())
            val requests = server.requestCount
            controller.refresh(); controller.create(FuelType.GPL)
            assertEquals(requests, server.requestCount)
        }
    }
}


