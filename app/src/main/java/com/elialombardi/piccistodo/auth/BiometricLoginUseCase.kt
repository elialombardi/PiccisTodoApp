package com.elialombardi.piccistodo.auth

/*
 * Security model: the P-256 private key never leaves Android Keystore and each
 * signing operation requires BIOMETRIC_STRONG authorization via CryptoObject.
 * Password JWTs are used only for registration; access JWTs live only in RAM.
 * The server must issue single-use, device-bound challenges expiring in 60s and
 * consume them atomically on verification. Process death discards pending work;
 * retry starts a new operation. Local checks do not replace server enforcement.
 */

import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.elialombardi.piccistodo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricLoginUseCase @Inject constructor(
    private val keys: KeyManager,
    private val biometrics: BiometricAuthenticator,
    private val repository: AuthRepository,
    private val deviceIds: DeviceIdProvider
) {
    private val operation = Mutex()

    suspend fun hasKey(): Boolean = withContext(Dispatchers.IO) { keys.hasKey(KEY_ALIAS) }

    suspend fun enrollDevice(passwordJwt: String) = operation.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (keys.hasKey(KEY_ALIAS)) {
                    try { keys.prepareSignature(KEY_ALIAS) }
                    catch (_: KeyPermanentlyInvalidatedException) { keys.deleteKey(KEY_ALIAS) }
                }
                if (!keys.hasKey(KEY_ALIAS)) keys.generateKeyPair(KEY_ALIAS)
                repository.registerDevice(
                    passwordJwt, deviceIds.getDeviceId(), keys.getPublicKeyPem(KEY_ALIAS),
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                )
            } catch (_: GeneralSecurityException) { throw AuthError.KeyUnavailable() }
            // No field/coroutine state retains passwordJwt after return. JVM Strings
            // cannot be reliably zeroized; the caller must also drop its reference.
        }
    }

    suspend fun login(activity: FragmentActivity): String = operation.withLock {
        val signature = withContext(Dispatchers.IO) {
            try { keys.prepareSignature(KEY_ALIAS) }
            catch (_: GeneralSecurityException) { throw AuthError.KeyUnavailable() }
        }
        // Authorize this exact operation BEFORE network so failed/canceled biometrics
        // make zero HTTP requests. initSign AFTER the prompt would lose authorization.
        val authenticated = biometrics.authenticate(activity, biometrics.promptInfo(), signature)
        currentCoroutineContext().ensureActive()
        val deviceId = withContext(Dispatchers.IO) { deviceIds.getDeviceId() }
        val requestedAt = SystemClock.elapsedRealtime()
        val challenge = repository.requestChallenge(deviceId)
        val bytes = validateChallenge(challenge, requestedAt)
        try {
            val encoded = withContext(Dispatchers.IO) {
                try { Base64.encodeToString(keys.sign(KEY_ALIAS, bytes, authenticated), Base64.NO_WRAP) }
                catch (_: GeneralSecurityException) { throw AuthError.KeyUnavailable() }
            }
            currentCoroutineContext().ensureActive()
            validateChallenge(challenge, requestedAt)
            val response = repository.verifyChallenge(deviceId, encoded)
            currentCoroutineContext().ensureActive()
            val token = response.accessToken ?: throw AuthError.InvalidResponse()
            TokenHolder.set(token, response.expiresIn, response.role)
            if (BuildConfig.DEBUG) {
                val role = TokenHolder.session.value?.role
                    ?.replace('\r', ' ')?.replace('\n', ' ')?.take(64) ?: "unknown"
                Log.d("BiometricLogin", "Login successful; user role: $role")
            }
            token
        } finally { bytes.fill(0) }
    }

    private fun validateChallenge(response: ChallengeResponse, requestedAt: Long): ByteArray {
        val challenge = response.challenge?.takeIf { it.isNotBlank() && it.length <= 8192 }
            ?: throw AuthError.InvalidResponse()
        val value = response.expiresAt ?: throw AuthError.InvalidResponse()
        val expiry = runCatching { Instant.parse(value) }.getOrElse {
            value.toLongOrNull()?.let(Instant::ofEpochSecond) ?: throw AuthError.InvalidResponse()
        }
        if (!expiry.isAfter(Instant.now()) || SystemClock.elapsedRealtime() - requestedAt >= 60_000) {
            throw AuthError.ChallengeExpired()
        }
        val decoded = try {
            Base64.decode(
                challenge,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        } catch (_: IllegalArgumentException) {
            throw AuthError.InvalidResponse()
        }
        if (decoded.size != 32) throw AuthError.InvalidResponse()
        return decoded
    }

    companion object { const val KEY_ALIAS = "piccis_biometric_signing_v1" }
}

