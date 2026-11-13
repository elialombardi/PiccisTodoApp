package com.elialombardi.piccistodo.data.remote

import com.elialombardi.piccistodo.data.model.*
import retrofit2.http.*

interface TasksApi {
    @Headers("Accept: application/json")
    @GET("/api/tasks") suspend fun tasks(): List<Task>
    @Headers("Accept: application/json")
    @GET("/api/projects") suspend fun projects(): List<Project>
    @Headers("Accept: application/json")
    @GET("/api/tasks/{id}") suspend fun task(@Path("id") id: String): Task
    @Headers("Accept: application/json")
    @POST("/api/tasks") suspend fun createTask(@Body request: TaskRequest): Task
    @Headers("Accept: application/json")
    @PUT("/api/tasks/{id}") suspend fun updateTask(@Path("id") id: String, @Body request: TaskRequest): Task
    @Headers("Accept: application/json")
    @POST("/api/projects") suspend fun createProject(@Body request: ProjectRequest): Project
}

