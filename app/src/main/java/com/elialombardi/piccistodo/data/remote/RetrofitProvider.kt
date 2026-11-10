package com.elialombardi.piccistodo.data.remote

import com.elialombardi.piccistodo.data.local.SettingsManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetrofitProvider @Inject constructor(
    private val settingsManager: SettingsManager
) {
    private var cachedRetrofit: Retrofit? = null
    private var cachedUrl: String? = null

    fun getApi(): TodoApi = getService(TodoApi::class.java)

    @Synchronized
    fun <T> getService(service: Class<T>): T {
        // Get the URL the user saved
        val url = settingsManager.getApiUrlSync() ?: throw IllegalStateException("API URL not configured")

        // If the URL changed, or we haven't built it yet, rebuild Retrofit
        if (cachedRetrofit == null || cachedUrl != url) {
            val origin = url.toHttpUrl()
            require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty())

            val okHttpClient = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val isLogin = chain.request().url.encodedPath.endsWith("/auth/login")
                    val token = if (isLogin) null else settingsManager.getTokenSync()
                    val request = chain.request().newBuilder().header("Accept", "application/json")
                    if (token != null) {
                        request.header("Authorization", "Bearer $token")
                    }
                    val response = chain.proceed(request.build())
                    if (response.code == 401 && token != null) {
                        runBlocking { settingsManager.clearRejectedToken(token) }
                    }
                    response
                }
                .build()

            cachedRetrofit = Retrofit.Builder()
                .baseUrl(origin.newBuilder().encodedPath("/").query(null).fragment(null).build())
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            cachedUrl = url
        }

        return cachedRetrofit!!.create(service)
    }
}