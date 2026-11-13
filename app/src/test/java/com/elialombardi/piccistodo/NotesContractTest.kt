package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.repository.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.ZoneId
import java.util.Locale

class NotesContractTest {
    private val itemJson = """{"id":42,"folderId":12,"position":2,"title":"Collect parcel","content":"Diary text","description":"Code","endDate":"2026-11-14","completed":true,"style":{"bold":true,"italic":false,"textColor":"#123456","backgroundColor":"#ffffff","fontSize":20},"createdAt":"2026-11-10T00:40:00.123456+02:00","updatedAt":"2026-11-10T00:40:00.123456+02:00"}"""
    private fun json(raw: String) = JsonParser.parseString(raw)
    private val item get() = NotesDecoder.item(json(itemJson))

    @Test fun completionAndExplicitFalseNeverOverwriteHiddenFields() {
        listOf(true, false).forEach { value ->
            val body = NotesWrites.completion(value)
            assertEquals(setOf("completed"), body.keySet())
            assertEquals(value, body["completed"].asBoolean)
        }
    }
    @Test fun movingAndRenamingOnlySendChangedFieldsAcrossAllTypes() {
        val original = NotesItemDraft.from(item)
        NotesType.entries.forEach { type ->
            val body = NotesWrites.item(original.copy(folderId = 15, title = "New title", position = "0"), original, type)
            assertEquals(setOf("folderId", "title", "position"), body.keySet())
            assertEquals(15, body["folderId"].asInt)
            assertEquals(0, body["position"].asInt)
            assertFalse(body.has("style")); assertFalse(body.has("content")); assertFalse(body.has("description"))
        }
    }
    @Test fun dateClearingAndStyleReplacementPreserveUntouchedProperties() {
        val original = NotesItemDraft.from(item)
        val cleared = NotesWrites.item(original.copy(endDate = "", description = "", completed = false), original, NotesType.TASKS)
        assertEquals("", cleared["endDate"].asString)
        assertFalse(cleared["endDate"].isJsonNull)
        assertEquals("", cleared["description"].asString)
        assertFalse(cleared["completed"].asBoolean)
        val style = NotesWrites.item(original.copy(style = original.style.copy(italic = true)), original, NotesType.DIARY)["style"].asJsonObject
        assertEquals(5, style.size()); assertTrue(style["bold"].asBoolean)
        assertTrue(style["italic"].asBoolean); assertEquals("#123456", style["textColor"].asString)
        assertEquals(20, style["fontSize"].asInt)
    }
    @Test fun typedCreateAndIndependentDiaryFolderDefaults() {
        val draft = NotesItemDraft(12, "Milk")
        assertEquals(setOf("folderId", "title", "position", "completed"), NotesWrites.item(draft, null, NotesType.BUY).keySet())
        assertEquals(setOf("folderId", "title", "position", "completed", "description"), NotesWrites.item(draft, null, NotesType.TASKS).keySet())
        assertEquals(setOf("folderId", "title", "position", "content", "style"), NotesWrites.item(draft, null, NotesType.DIARY).keySet())
        val child = NotesWrites.folder(NotesFolderDraft("Child"), null, 12)
        assertEquals("diary", child["type"].asString); assertEquals(12, child["parentId"].asInt)
        val root = NotesWrites.folder(NotesFolderDraft("Root"), null, null)
        assertFalse(root.has("parentId")); assertEquals(0, root["position"].asInt)
        val patch = NotesWrites.folder(NotesFolderDraft("Root", NotesType.BUY, "0"), NotesFolderDraft("Root", position = "2"), null)
        assertEquals(setOf("type", "position"), patch.keySet())
    }
    @Test fun calendarAndPositionValidationDoesNotInventDateOrdering() {
        assertTrue(notesDateValid("2024-02-29")); assertTrue(notesDateValid("2000-01-01")); assertTrue(notesDateValid(""))
        listOf("2026-02-29", "2026-11-31", "2026-1-01", "2026-11-14T00:00:00Z").forEach { assertFalse(notesDateValid(it)) }
        listOf("-1", "1.5", "1e2", "", "9999999999999").forEach { assertNull(notesPosition(it)) }
        assertEquals(0, notesPosition("0"))
        assertTrue(NotesFolderDraft("   ").errors().contains("name"))
        assertTrue(NotesFolderDraft("a".repeat(101)).errors().contains("name"))
        assertTrue(NotesItemDraft(12, "x".repeat(201)).errors(emptyList()).contains("title"))
    }
    @Test fun numericIdsDefaultsDirectChildrenAndServerOrderArePreserved() {
        val second = itemJson.replace("\"id\":42", "\"id\":7")
        val childNote = itemJson.replace("\"id\":42", "\"id\":99").replace("\"folderId\":12", "\"folderId\":13")
        val tree = NotesDecoder.tree(json("""[{"id":12,"name":"Legacy","position":0,"parentId":null,"notes":[$itemJson,$second],"children":[{"id":13,"name":"Child","type":"buy_list","position":0,"parentId":12,"notes":[$childNote],"children":[]}]}]"""))
        assertEquals(NotesType.DIARY, tree.first().type)
        assertEquals(listOf(42L, 7L), tree.first().notes.map { it.id })
        assertEquals(2, tree.first().notes.size); assertEquals(1, tree.first().children.first().notes.size)
        assertEquals(listOf(0, 1), tree.folderRows().map { it.depth })
        val defaults = NotesDecoder.item(json(itemJson).asJsonObject.apply { remove("style"); remove("description"); remove("completed"); remove("endDate") })
        assertEquals(NotesStyle(), defaults.style); assertFalse(defaults.completed); assertNull(defaults.endDate)
        assertThrows(NotesDataException::class.java) { NotesDecoder.item(json(itemJson.replace("\"id\":42", "\"id\":\"42\""))) }
        assertThrows(NotesDataException::class.java) { NotesDecoder.item(json(itemJson.replace("2026-11-14", "2026-02-29"))) }
        assertThrows(NotesDataException::class.java) { NotesDecoder.tree(json("""[{"id":12,"name":"X","type":"invalid"}]""")) }
    }
    @Test fun endDateNeverShiftsWhileTimestampsUseDeviceZone() {
        assertEquals("2026-11-14", NotesItemDraft.from(item).endDate)
        assertTrue(item.localUpdated(ZoneId.of("America/Los_Angeles"), Locale.US).contains("Nov 9, 2026"))
        assertTrue(item.localUpdated(ZoneId.of("Asia/Tokyo"), Locale.US).contains("Nov 10, 2026"))
        assertEquals(123456000, item.updatedAt.nano)
    }
    private suspend fun withServer(block: suspend (MockWebServer, NotesHttpDataSource) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(NotesApi::class.java)
            block(server, NotesHttpDataSource { api })
        }
    }
    @Test fun realHttpUsesNumericIdsPartialBodiesAndExactSuccessCodes() = runBlocking {
        withServer { server, source ->
            server.enqueue(MockResponse.Builder().code(201).body(itemJson).build())
            assertEquals(42L, source.saveItem(null, NotesWrites.item(NotesItemDraft(12, "New"), null, NotesType.BUY)).id)
            val create = server.takeRequest()
            assertEquals("/api/notes", create.url.encodedPath)
            val body = json(create.body!!.utf8()).asJsonObject
            assertFalse(body.has("id")); assertFalse(body.has("updatedAt")); assertFalse(body.has("style"))
            server.enqueue(MockResponse.Builder().code(200).body(itemJson).build())
            source.saveItem(42, NotesWrites.completion(false))
            val patch = server.takeRequest()
            assertEquals("PUT", patch.method); assertEquals("/api/notes/42", patch.url.encodedPath)
            assertEquals("{\"completed\":false}", patch.body!!.utf8())
            server.enqueue(MockResponse.Builder().code(200).body("{\"id\":12,\"children\":[],\"notes\":[]}").build())
            assertEquals(12L, source.saveFolder(12, NotesWrites.folder(NotesFolderDraft("New"), NotesFolderDraft("Old"), null)))
            server.takeRequest()
            server.enqueue(MockResponse.Builder().code(204).build()); source.deleteItem(42)
            assertEquals("/api/notes/42", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(204).build()); source.deleteFolder(12)
            assertEquals("/api/note-folders/12", server.takeRequest().url.encodedPath)
            server.enqueue(MockResponse.Builder().code(200).body("[]").build()); assertTrue(source.tree().isEmpty())
            assertEquals("/api/note-folders/tree", server.takeRequest().url.encodedPath)
        }
    }
    @Test fun jsonErrorsAndNonJsonFallbacksDoNotMisclassify404Or400() = runBlocking {
        withServer { server, source ->
            listOf(400 to NotesFailure.REJECTED, 422 to NotesFailure.REJECTED, 401 to NotesFailure.AUTH,
                404 to NotesFailure.DELETE_FAILED, 500 to NotesFailure.SERVER).forEach { (code, expected) ->
                val jsonError = code != 401
                server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", if (jsonError) "application/json" else "text/plain")
                    .body(if (jsonError) "{\"error\":\"Service rejected change\"}" else "not JSON").build())
                val error = try { source.deleteItem(42); error("Expected error") } catch (error: HttpException) { error }
                val problem = notesProblem(error, writing = true)
                assertEquals(expected, problem.kind)
                assertEquals(if (jsonError) "Service rejected change" else null, problem.message)
            }
        }
    }
    @Test fun malformedConfirmedCreateIsNotReportedAsAnUnconfirmedSave() = runBlocking {
        withServer { server, source ->
            server.enqueue(MockResponse.Builder().code(201).body("not-json").build())
            try {
                source.saveItem(null, NotesWrites.completion(false)); fail("Expected confirmed response error")
            } catch (_: NotesWriteConfirmedException) { /* known saved, reconcile instead of resubmit */ }
        }
    }
}