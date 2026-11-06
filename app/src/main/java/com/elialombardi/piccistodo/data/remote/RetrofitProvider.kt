package com.elialombardi.piccistodo.data.remote

import com.elialombardi.piccistodo.data.local.SettingsManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetrofitProvider @Inject constructor(
    private val settingsManager: SettingsManager
) {
    private var cachedApi: TodoApi? = null
    private var cachedUrl: String? = null

    fun getApi(): TodoApi {
        // Get the URL the user saved
        val url = settingsManager.getApiUrlSync() ?: throw IllegalStateException("API URL not configured")

        // If the URL changed, or we haven't built it yet, rebuild Retrofit
        if (cachedApi == null || cachedUrl != url) {

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val token = settingsManager.getTokenSync()
                    val request = chain.request().newBuilder()
                    if (token != null) {
                        request.header("Authorization", "Bearer $token")
                    }
                    chain.proceed(request.build())
                }
                .build()

            cachedApi = Retrofit.Builder()
                // Ensure the URL ends with a slash, otherwise Retrofit crashes
                .baseUrl(if (url.endsWith("/")) url else "$url/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TodoApi::class.java)

            cachedUrl = url
        }

        return cachedApi!!
    }
}