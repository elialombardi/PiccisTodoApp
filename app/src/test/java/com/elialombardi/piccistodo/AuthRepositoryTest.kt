package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.auth.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AuthRepositoryTest {
    private class FakeApi : AuthApi {
        var status = 200
        var error = "{}"
        var bearer: String? = null
        var callerThread: Thread? = null
        var failure: Exception? = null
        var token = TokenResponse("fake-access-token", 60)
        private fun <T> response(value: T): Response<T> {
            callerThread = Thread.currentThread()
            failure?.let { throw it }
            return if (status in 200..299) Response.success(value)
            else Response.error(status, error.toResponseBody("application/json".toMediaType()))
        }
        override suspend fun passwordLogin(body: PasswordRequest) = response(PasswordResponse("fake-registration-token"))
        override suspend fun registerDevice(bearer: String, body: RegisterDeviceRequest): Response<Unit> {
            this.bearer = bearer
            return response(Unit)
        }
        override suspend fun requestChallenge(body: ChallengeRequest) = response(ChallengeResponse("challenge", "2099-01-01T00:00:00Z"))
        override suspend fun verifyChallenge(body: VerifyChallengeRequest) = response(token)
    }

    private suspend inline fun <reified E : AuthError> expectError(crossinline action: suspend () -> Unit) {
        try { action(); fail("Expected ${E::class.java.simpleName}") }
        catch (error: AuthError) { assertTrue(error.javaClass.name, error is E) }
    }

    @Test fun mapsTypedStatusErrors() = runBlocking {
        val api = FakeApi()
        val repository = AuthRepository(api) {}
        api.status = 404
        expectError<AuthError.DeviceNotFound> { repository.requestChallenge("device") }
        api.status = 410
        expectError<AuthError.ChallengeExpired> { repository.verifyChallenge("device", "fake-signature") }
        api.status = 401
        expectError<AuthError.SignatureInvalid> { repository.verifyChallenge("device", "fake-signature") }
        expectError<AuthError.CredentialsRejected> { repository.passwordLogin("user", "fake-password") }
        api.status = 429
        expectError<AuthError.RateLimited> { repository.requestChallenge("device") }
    }

    @Test fun mapsStructuredErrorCodesWithoutExposingServerMessages() = runBlocking {
        val api = FakeApi().apply { status = 400; error = """{"error":{"code":"challenge_expired","message":"untrusted server text"}}""" }
        val repository = AuthRepository(api) {}
        expectError<AuthError.ChallengeExpired> { repository.verifyChallenge("device", "fake-signature") }
        api.error = """{"code":"signature_invalid"}"""
        expectError<AuthError.SignatureInvalid> { repository.verifyChallenge("device", "fake-signature") }
    }

    @Test fun registrationIsTheOnlyBearerCallAndRunsOffCallerThread() = runBlocking {
        val api = FakeApi()
        val statuses = mutableListOf<Int>()
        val repository = AuthRepository(api) { statuses.add(it) }
        val caller = Thread.currentThread()
        val jwt = repository.passwordLogin("user", "fake-password")
        repository.registerDevice(jwt, "device", "public-key", "test-device")
        assertEquals("Bearer fake-registration-token", api.bearer)
        assertNotSame(caller, api.callerThread)
        assertEquals(listOf(200, 200), statuses)
        assertEquals("fake-access-token", repository.verifyChallenge("device", "fake-signature").accessToken)
    }

    @Test fun rejectsEmptyTokenOrInvalidLifetime() = runBlocking {
        val api = FakeApi().apply { token = TokenResponse("", 60) }
        val repository = AuthRepository(api) {}
        expectError<AuthError.InvalidResponse> { repository.verifyChallenge("device", "fake-signature") }
        api.token = TokenResponse("fake-token", 0)
        expectError<AuthError.InvalidResponse> { repository.verifyChallenge("device", "fake-signature") }
    }

    @Test fun networkFailuresAreSafeAndCancellationPropagates() = runBlocking {
        val api = FakeApi().apply { failure = IOException("must not surface this detail") }
        val repository = AuthRepository(api) {}
        expectError<AuthError.Network> { repository.requestChallenge("device") }
        api.failure = CancellationException("cancel")
        try { repository.requestChallenge("device"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { /* expected */ }
    }

    @Test fun wireFieldsAndToStringDoNotLeakCredentials() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val request = VerifyChallengeRequest("device", "fake-signature")
        val json = moshi.adapter(VerifyChallengeRequest::class.java).toJson(request)
        assertTrue(json.contains("\"device_id\""))
        assertTrue(json.contains("\"signature_b64\""))
        assertFalse(request.toString().contains("fake-signature"))
        assertFalse(TokenResponse("fake-secret-token", 60).toString().contains("fake-secret-token"))
        assertFalse(PasswordRequest("user", "fake-password").toString().contains("fake-password"))
    }
}
