package com.elialombardi.piccistodo.auth

import android.util.Log
import com.elialombardi.piccistodo.BuildConfig
import com.elialombardi.piccistodo.data.local.SettingsManager
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository(
    api: AuthApi?,
    private val settings: SettingsManager? = null,
    private val statusLogger: (Int) -> Unit = { if (BuildConfig.DEBUG) Log.d("AuthHttp", it.toString()) }
) {
    @Inject constructor(settings: SettingsManager) : this(null, settings = settings)

    private var cachedApi: AuthApi? = api
    private var cachedUrl: String? = null

    private suspend fun api(): AuthApi {
        val url = settings?.getApiUrl() ?: return requireNotNull(cachedApi)
        return synchronized(this) {
            if (cachedApi == null || cachedUrl != url) {
                cachedApi = createApi(url)
                cachedUrl = url
            }
            requireNotNull(cachedApi)
        }
    }

    suspend fun passwordLogin(username: String, password: String): String = io {
        val body = checked(api().passwordLogin(PasswordRequest(username, password)), Operation.PASSWORD).body()
        body?.accessToken?.takeIf { it.isNotBlank() } ?: throw AuthError.InvalidResponse()
    }

    suspend fun registerDevice(bearerJwt: String, deviceId: String, publicKeyPem: String, deviceName: String) = io {
        checked(api().registerDevice("Bearer $bearerJwt", RegisterDeviceRequest(deviceId, publicKeyPem, deviceName)), Operation.REGISTER)
        Unit
    }

    suspend fun requestChallenge(deviceId: String): ChallengeResponse = io {
        checked(api().requestChallenge(ChallengeRequest(deviceId)), Operation.CHALLENGE).body()
            ?: throw AuthError.InvalidResponse()
    }

    suspend fun verifyChallenge(deviceId: String, signatureB64: String): TokenResponse = io {
        val body = checked(api().verifyChallenge(VerifyChallengeRequest(deviceId, signatureB64)), Operation.VERIFY).body()
            ?: throw AuthError.InvalidResponse()
        if (body.accessToken.isNullOrBlank() || body.expiresIn !in 1..31_536_000) throw AuthError.InvalidResponse()
        body
    }

    private enum class Operation { PASSWORD, REGISTER, CHALLENGE, VERIFY }

    private fun <T> checked(response: Response<T>, operation: Operation): Response<T> {
        // Never log headers, bodies, exception messages, URLs, JWTs or signatures.
        statusLogger(response.code())
        if (response.isSuccessful) return response
        val code = response.errorBody()?.use { body ->
            // Read only a bounded error envelope; never surface server text to UI/logs.
            val source = body.source()
            source.request(8193)
            if (source.buffer.size > 8192) null else runCatching {
                val value = errorMoshi.adapter(Map::class.java).fromJson(source.readUtf8())
                (value?.get("code") as? String)
                    ?: (value?.get("error") as? String)
                    ?: ((value?.get("error") as? Map<*, *>)?.get("code") as? String)
            }.getOrNull()
        }?.lowercase()
        throw when {
            response.code() == 429 || code == "rate_limited" -> AuthError.RateLimited()
            code == "device_not_found" -> AuthError.DeviceNotFound()
            code == "challenge_expired" -> AuthError.ChallengeExpired()
            code == "signature_invalid" -> AuthError.SignatureInvalid()
            response.code() >= 500 -> AuthError.Server(response.code())
            operation == Operation.CHALLENGE && response.code() == 404 -> AuthError.DeviceNotFound()
            operation == Operation.VERIFY && response.code() == 404 -> AuthError.DeviceNotFound()
            response.code() == 410 || response.code() == 408 -> AuthError.ChallengeExpired()
            operation == Operation.VERIFY && response.code() in listOf(401, 403, 422) -> AuthError.SignatureInvalid()
            response.code() == 401 || response.code() == 403 -> AuthError.CredentialsRejected()
            else -> AuthError.Server(response.code())
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (_: JsonDataException) { throw AuthError.InvalidResponse() }
        catch (_: IOException) { throw AuthError.Network() }
    }

    companion object {
        private val errorMoshi = Moshi.Builder().build()
        private fun createApi(baseUrl: String): AuthApi {
            val origin = baseUrl.toHttpUrl()
            require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty())
            val client = OkHttpClient.Builder()
                .retryOnConnectionFailure(false) // Never replay single-use challenge verification.
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .build() // No disk cache, cookie jar, analytics, or logging interceptor.
            return Retrofit.Builder().baseUrl(origin).client(client)
                .addConverterFactory(MoshiConverterFactory.create(
                    Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                )).build().create(AuthApi::class.java)
        }
    }
}



