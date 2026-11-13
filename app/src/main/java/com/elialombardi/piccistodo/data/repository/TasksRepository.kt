package com.elialombardi.piccistodo.data.repository

import com.elialombardi.piccistodo.data.local.SettingsManager
import com.elialombardi.piccistodo.data.model.*
import com.elialombardi.piccistodo.data.remote.TasksApi
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class TaskFailure { VALIDATION, AUTH, FORBIDDEN, MISSING, DUPLICATE, PROJECT_MISSING, NETWORK, SERVER, CONFIG }
data class TaskProblem(val kind: TaskFailure, val field: String? = null, val uncertain: Boolean = false)
internal class TasksConfigurationException(cause: Exception) : Exception(cause)

fun taskProblem(error: Exception, writing: Boolean = false): TaskProblem {
    if (error is CancellationException) throw error
    if (error is HttpException) {
        val response = error.response()?.errorBody()
        // Only recognized validation text is used. Never expose database/server bodies.
        val raw = runCatching { response?.string().orEmpty() }.getOrDefault("")
        val message = if (response?.contentType()?.subtype?.contains("json") == true) {
            runCatching { JsonParser.parseString(raw).let { json ->
                if (json.isJsonPrimitive) json.asString else json.asJsonObject.get("message")?.asString.orEmpty()
            } }.getOrDefault("")
        } else raw.trim()
        val field = when (message) {
            "ProjectId required", "Project does not exist" -> "projectId"
            "Name required" -> "name"
            "Date required", "Date must be yyyy-MM-dd" -> "date"
            "SentOn must be yyyy-MM-dd" -> "sentOn"
            else -> null
        }
        return TaskProblem(when (error.code()) {
            400 -> if (message == "Project does not exist") TaskFailure.PROJECT_MISSING else TaskFailure.VALIDATION
            401 -> TaskFailure.AUTH
            403 -> TaskFailure.FORBIDDEN
            404 -> TaskFailure.MISSING
            409 -> TaskFailure.DUPLICATE
            else -> TaskFailure.SERVER
        }, field, writing && error.code() >= 500)
    }
    return TaskProblem(when (error) {
        is IOException -> TaskFailure.NETWORK
        is TasksConfigurationException -> TaskFailure.CONFIG
        else -> TaskFailure.SERVER
    }, uncertain = writing && error !is TasksConfigurationException)
}

interface TaskDataSource {
    suspend fun tasks(): List<Task>
    suspend fun projects(): List<Project>
    suspend fun task(id: String): Task
    suspend fun saveTask(id: String?, request: TaskRequest): Task
    suspend fun createProject(request: ProjectRequest): Project
}

class TasksRepository @Inject constructor(private val settings: SettingsManager) : TaskDataSource {
    private val client by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header("Accept", "application/json")
                settings.getTokenForUrl(chain.request().url)?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
                chain.proceed(request.build())
            }.build()
    }
    private fun api(): TasksApi = try {
        val origin = requireNotNull(settings.getApiUrlSync()).toHttpUrl()
        require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty())
        Retrofit.Builder().baseUrl(origin.newBuilder().encodedPath("/").query(null).fragment(null).build())
            .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(TasksApi::class.java)
    } catch (error: IllegalArgumentException) {
        throw TasksConfigurationException(error)
    } catch (error: IllegalStateException) {
        throw TasksConfigurationException(error)
    }
    override suspend fun tasks() = api().tasks()
    override suspend fun projects() = api().projects()
    override suspend fun task(id: String) = api().task(id)
    override suspend fun saveTask(id: String?, request: TaskRequest) =
        if (id == null) api().createTask(request) else api().updateTask(id, request)
    override suspend fun createProject(request: ProjectRequest) = api().createProject(request)
}

