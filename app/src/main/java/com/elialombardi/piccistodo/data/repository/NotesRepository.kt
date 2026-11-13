package com.elialombardi.piccistodo.data.repository

import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.remote.RetrofitProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.*
import java.io.IOException
import javax.inject.Inject

interface NotesApi {
    @GET("api/note-folders/tree") suspend fun tree(): Response<JsonElement>
    @Streaming @POST("api/note-folders") suspend fun createFolder(@Body body: JsonObject): Response<ResponseBody>
    @Streaming @PUT("api/note-folders/{id}") suspend fun updateFolder(@Path("id") id: Long, @Body body: JsonObject): Response<ResponseBody>
    @DELETE("api/note-folders/{id}") suspend fun deleteFolder(@Path("id") id: Long): Response<Unit>
    @Streaming @POST("api/notes") suspend fun createItem(@Body body: JsonObject): Response<ResponseBody>
    @Streaming @PUT("api/notes/{id}") suspend fun updateItem(@Path("id") id: Long, @Body body: JsonObject): Response<ResponseBody>
    @DELETE("api/notes/{id}") suspend fun deleteItem(@Path("id") id: Long): Response<Unit>
}
enum class NotesFailure { AUTH, FORBIDDEN, REJECTED, DELETE_FAILED, NETWORK, SERVER, DATA, CONFIG, FOLDER_NOT_EMPTY }
class NotesWriteConfirmedException : Exception()
data class NotesProblem(val kind: NotesFailure, val message: String? = null, val uncertain: Boolean = false)
fun notesProblem(error: Exception, writing: Boolean = false): NotesProblem {
    if (error is CancellationException) throw error
    if (error is HttpException) {
        val body = error.response()?.errorBody()
        val raw = runCatching { body?.string().orEmpty() }.getOrDefault("")
        val message = if (body?.contentType()?.subtype?.contains("json") == true) runCatching {
            JsonParser.parseString(raw).asJsonObject.get("error")?.asString
                ?.filterNot { it.isISOControl() }?.take(240)?.takeIf { it.isNotBlank() }
        }.getOrNull() else null
        return NotesProblem(when (error.code()) {
            401 -> NotesFailure.AUTH
            403 -> NotesFailure.FORBIDDEN
            400, 422 -> NotesFailure.REJECTED
            404 -> NotesFailure.DELETE_FAILED
            else -> NotesFailure.SERVER
        }, message, writing && error.code() >= 500)
    }
    return NotesProblem(when (error) {
        is NotesDataException -> NotesFailure.DATA
        is IOException -> NotesFailure.NETWORK
        is IllegalStateException, is IllegalArgumentException -> NotesFailure.CONFIG
        else -> NotesFailure.SERVER
    }, uncertain = writing)
}
interface NotesDataSource {
    suspend fun tree(): List<NotesFolder>
    suspend fun saveFolder(id: Long?, body: JsonObject): Long
    suspend fun saveItem(id: Long?, body: JsonObject): NotesItem
    suspend fun deleteFolder(id: Long)
    suspend fun deleteItem(id: Long)
}
class NotesRepository @Inject constructor(provider: RetrofitProvider) : NotesDataSource by
    NotesHttpDataSource({ provider.getService(NotesApi::class.java) })

class NotesHttpDataSource(private val api: () -> NotesApi) : NotesDataSource {
    private fun <T> Response<ResponseBody>.decodeConfirmed(decode: (JsonElement) -> T): T = try {
        body()?.use { decode(JsonParser.parseString(it.string())) } ?: throw NotesDataException()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw NotesWriteConfirmedException()
    }
    private fun <T> Response<T>.expect(code: Int): Response<T> {
        if (!isSuccessful) throw HttpException(this)
        if (code() != code) throw NotesDataException()
        return this
    }
    override suspend fun tree() = NotesDecoder.tree(api().tree().expect(200).body() ?: throw NotesDataException())
    override suspend fun saveFolder(id: Long?, body: JsonObject): Long {
        val response = if (id == null) api().createFolder(body).expect(201) else api().updateFolder(id, body).expect(200)
        // Mutation responses contain empty children/notes. Never replace a cached subtree with them.
        return response.decodeConfirmed(NotesDecoder::folderId)
    }
    override suspend fun saveItem(id: Long?, body: JsonObject): NotesItem {
        val response = if (id == null) api().createItem(body).expect(201) else api().updateItem(id, body).expect(200)
        return response.decodeConfirmed(NotesDecoder::item)
    }
    override suspend fun deleteFolder(id: Long) { api().deleteFolder(id).expect(204) }
    override suspend fun deleteItem(id: Long) { api().deleteItem(id).expect(204) }
}
