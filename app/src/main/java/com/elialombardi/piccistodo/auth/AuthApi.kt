package com.elialombardi.piccistodo.auth

import androidx.annotation.Keep
import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Deliberately not data classes: generated toString() must not expose credentials.
@Keep class PasswordRequest(val username: String, val password: String)
@Keep class PasswordResponse(@Json(name = "access_token") val accessToken: String?)
@Keep class RegisterDeviceRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "public_key_pem") val publicKeyPem: String,
    @Json(name = "device_name") val deviceName: String
)
@Keep class ChallengeRequest(@Json(name = "device_id") val deviceId: String)
@Keep class ChallengeResponse(val challenge: String?, @Json(name = "expires_at") val expiresAt: String?)
@Keep class VerifyChallengeRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "signature_b64") val signatureB64: String
)
@Keep class TokenResponse(
    @Json(name = "access_token") val accessToken: String?,
    @Json(name = "expires_in") val expiresIn: Long,
    val role: String? = null
)

interface AuthApi {
    // Existing application's username/password endpoint; adjust if the backend moves it.
    @POST("/api/auth/login")
    suspend fun passwordLogin(@Body body: PasswordRequest): Response<PasswordResponse>

    @POST("/api/v1/auth/register-device")
    suspend fun registerDevice(@Header("Authorization") bearer: String, @Body body: RegisterDeviceRequest): Response<Unit>

    @POST("/api/v1/auth/challenge")
    suspend fun requestChallenge(@Body body: ChallengeRequest): Response<ChallengeResponse>

    @POST("/api/v1/auth/verify-challenge")
    suspend fun verifyChallenge(@Body body: VerifyChallengeRequest): Response<TokenResponse>
}

sealed class AuthError(message: String) : Exception(message) {
    class ServerConfiguration : AuthError(ServerUrl.validationMessage)
    class NoHardware : AuthError("Strong biometrics are not supported on this device.")
    class NoEnrollment : AuthError("No strong biometrics enrolled. Enroll a fingerprint in Android settings.")
    class BiometricUnavailable : AuthError("Biometric hardware is temporarily unavailable.")
    class Lockout : AuthError("Biometrics are locked. Unlock your device and retry later.")
    class Canceled : AuthError("Biometric sign-in canceled. Tap sign in to retry.")
    class BiometricFailed : AuthError("Biometric authentication failed. Please retry.")
    class DeviceNotFound : AuthError("Device is not registered. Enroll using your username and password.")
    class ChallengeExpired : AuthError("Challenge expired. Tap sign in to request a new one.")
    class SignatureInvalid : AuthError("The signature was rejected. Re-enroll this device.")
    class RateLimited : AuthError("Too many attempts. Please wait before retrying.")
    class KeyUnavailable : AuthError("Biometric key is missing or invalidated. Re-enroll this device.")
    class CredentialsRejected : AuthError("Username, password, or registration authorization was rejected.")
    class Network : AuthError("Cannot connect securely. Check your connection and server configuration.")
    class InvalidResponse : AuthError("The server returned an invalid authentication response.")
    class Server(val status: Int) : AuthError("Authentication service error ($status). Please retry.")
}



