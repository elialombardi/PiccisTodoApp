package com.elialombardi.piccistodo.data.repository

import com.elialombardi.piccistodo.data.local.SettingsManager
import com.elialombardi.piccistodo.data.model.*
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface FuelApi {
    @GET("api/fuel/stats") suspend fun stats(): Response<JsonElement>
    @GET("api/fuel/records") suspend fun records(): Response<JsonElement>
    @Streaming
    @POST("api/fuel/records") suspend fun create(@Body request: FuelRequest): Response<okhttp3.ResponseBody>
    @DELETE("api/fuel/admin/records/{id}") suspend fun delete(@Path("id") id: String): Response<Unit>
    @DELETE("api/fuel/admin/records") suspend fun reset(): Response<Unit>
}

enum class FuelFailure { VALIDATION, GPL_BLOCKED, BENZINA_BLOCKED, AUTH, FORBIDDEN, MISSING, NETWORK, SERVER, DATA, CONFIG }
data class FuelProblem(val kind: FuelFailure, val remaining: Int? = null, val uncertain: Boolean = false)

fun fuelProblem(error: Exception, writing: Boolean = false): FuelProblem {
    if (error is CancellationException) throw error
    if (error is HttpException) {
        val body = error.response()?.errorBody()
        val raw = runCatching { body?.string().orEmpty() }.getOrDefault("")
        val message = if (body?.contentType()?.subtype?.contains("json") == true) {
            runCatching { JsonParser.parseString(raw).asJsonObject.let { (it.get("error") ?: it.get("message"))?.asString.orEmpty() } }.getOrDefault("")
        } else raw.trim()
        val remaining = Regex("must wait ([0-4]) more GPL fills before benzina").matchEntire(message)?.groupValues?.get(1)?.toInt()
        return FuelProblem(when (error.code()) {
            400 -> when {
                message == "must fill with benzina before adding more GPL fills" -> FuelFailure.GPL_BLOCKED
                remaining != null -> FuelFailure.BENZINA_BLOCKED
                else -> FuelFailure.VALIDATION
            }
            401 -> FuelFailure.AUTH
            403 -> FuelFailure.FORBIDDEN
            404 -> FuelFailure.MISSING
            else -> FuelFailure.SERVER
        }, remaining, writing && error.code() >= 500)
    }
    return FuelProblem(when (error) {
        is FuelDataException -> FuelFailure.DATA
        is IOException -> FuelFailure.NETWORK
        is IllegalArgumentException, is IllegalStateException -> FuelFailure.CONFIG
        else -> FuelFailure.DATA
    }, uncertain = writing)
}

interface FuelDataSource {
    suspend fun stats(): FuelStats
    suspend fun records(): List<FuelRecord>
    suspend fun create(type: FuelType)
    suspend fun delete(id: String)
    suspend fun reset()
}

class FuelRepository @Inject constructor(private val settings: SettingsManager) : FuelDataSource {
    private val remote = FuelHttpDataSource(::api)
    override suspend fun stats() = remote.stats()
    override suspend fun records() = remote.records()
    override suspend fun create(type: FuelType) = remote.create(type)
    override suspend fun delete(id: String) = remote.delete(id)
    override suspend fun reset() = remote.reset()

    private fun api(): FuelApi {
        val origin = requireNotNull(settings.getApiUrlSync()).toHttpUrl()
        require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty())
        // Snapshot the session for this request: never replay a request with another account's token.
        val token = settings.getTokenForUrl(origin)
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request().newBuilder().header("Accept", "application/json")
                token?.takeIf { it.isNotBlank() && it == settings.getTokenForUrl(chain.request().url) }
                    ?.let { request.header("Authorization", "Bearer $it") }
                chain.proceed(request.build())
            }.build()
        return Retrofit.Builder().baseUrl(origin.newBuilder().encodedPath("/").query(null).fragment(null).build())
            .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(FuelApi::class.java)
    }
}

class FuelHttpDataSource(private val api: () -> FuelApi) : FuelDataSource {
    private fun <T> Response<T>.expect(code: Int): Response<T> {
        if (!isSuccessful) throw HttpException(this)
        if (code() != code) throw FuelDataException()
        return this
    }
    override suspend fun stats() = FuelDecoder.stats(api().stats().expect(200).body() ?: throw FuelDataException())
    override suspend fun records() = FuelDecoder.records(api().records().expect(200).body() ?: throw FuelDataException())
    override suspend fun create(type: FuelType) {
        val response = api().create(FuelRequest(type.wire)).expect(201)
        // A confirmed 201 is a successful write even if its body is malformed; refresh is authoritative.
        response.body()?.close()
    }
    override suspend fun delete(id: String) { api().delete(id).expect(204) }
    override suspend fun reset() { api().reset().expect(204) }
}



