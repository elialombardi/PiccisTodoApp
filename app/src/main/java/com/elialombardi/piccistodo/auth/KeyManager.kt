package com.elialombardi.piccistodo.auth

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyManager @Inject constructor() {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun generateKeyPair(alias: String): KeyPair {
        check(!hasKey(alias)) { "Refusing to overwrite an enrolled key" }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 24–29 equivalent: a CryptoObject is required for every operation.
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec.build()) }.generateKeyPair()
    }

    fun getPublicKeyPem(alias: String): String {
        val publicKey = store().getCertificate(alias)?.publicKey ?: throw AuthError.KeyUnavailable()
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n${encoded.chunked(64).joinToString("\n")}\n-----END PUBLIC KEY-----"
    }

    fun prepareSignature(alias: String): Signature {
        val key = store().getKey(alias, null) as? PrivateKey ?: throw AuthError.KeyUnavailable()
        // initSign MUST precede BiometricPrompt: authentication binds to this operation.
        return Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }

    fun sign(alias: String, data: ByteArray, authenticatedSignature: Signature): ByteArray {
        if (!hasKey(alias)) throw AuthError.KeyUnavailable()
        // Do not re-initSign here: that would discard the biometric authorization.
        return authenticatedSignature.run { update(data); sign() } // ASN.1 DER ECDSA
    }

    fun deleteKey(alias: String) = store().deleteEntry(alias)
    fun hasKey(alias: String): Boolean = store().containsAlias(alias)
}
