package com.elialombardi.piccistodo.auth

import android.os.CancellationSignal
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class BiometricAuthenticator @Inject constructor() {
    fun checkAvailability(activity: FragmentActivity) {
        when (BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> throw AuthError.NoHardware()
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> throw AuthError.NoEnrollment()
            else -> throw AuthError.BiometricUnavailable()
        }
    }

    fun promptInfo(): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Sign in with fingerprint")
        .setSubtitle("Authorize this device's signing key")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Cancel")
        .setConfirmationRequired(true)
        .build()

    // Non-crypto overload is suitable only for a UI gate, never for signing.
    suspend fun authenticate(activity: FragmentActivity, promptInfo: BiometricPrompt.PromptInfo): Boolean {
        authenticateInternal(activity, promptInfo, null)
        return true
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        signature: Signature
    ): Signature = authenticateInternal(activity, promptInfo, signature) ?: throw AuthError.BiometricFailed()

    private suspend fun authenticateInternal(
        activity: FragmentActivity,
        info: BiometricPrompt.PromptInfo,
        signature: Signature?
    ): Signature? = withContext(Dispatchers.Main.immediate) {
        checkAvailability(activity)
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val cancellation = CancellationSignal()
            lateinit var prompt: BiometricPrompt
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    val authenticated = result.cryptoObject?.signature
                    if (signature != null && authenticated !== signature) {
                        continuation.resumeWithException(AuthError.BiometricFailed())
                    } else continuation.resume(authenticated)
                }

                override fun onAuthenticationFailed() {
                    // Fail closed on a mismatch. A retry is an explicit new operation.
                    if (continuation.isActive) continuation.resumeWithException(AuthError.BiometricFailed())
                    cancellation.cancel()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.isActive) return
                    val error = when (errorCode) {
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> AuthError.NoHardware()
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> AuthError.NoEnrollment()
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthError.Lockout()
                        BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> AuthError.Canceled()
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> AuthError.BiometricUnavailable()
                        else -> AuthError.BiometricFailed()
                    }
                    continuation.resumeWithException(error)
                }
            }
            prompt = BiometricPrompt(activity, executor, callback)
            // AndroidX owns its internal cancellation signal; bridge ours to cancelAuthentication.
            cancellation.setOnCancelListener { executor.execute { prompt.cancelAuthentication() } }
            continuation.invokeOnCancellation { cancellation.cancel() }
            if (continuation.isActive) {
                try {
                    if (signature == null) prompt.authenticate(info)
                    else prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resumeWithException(AuthError.BiometricUnavailable())
                }
            }
        }
    }
}
