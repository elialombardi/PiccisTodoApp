package com.elialombardi.piccistodo.auth

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("DEPRECATION") // Required encrypted device-ID storage; never used for JWTs.
@Singleton
class DeviceIdProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "auth_device_id", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun getDeviceId(): String {
        // A device_id is a public identifier, NOT authorization. It can survive process
        // death; a bearer JWT cannot be persisted because possession grants access.
        // No JWT, password, signature or challenge is ever written to these preferences.
        preferences.getString("device_id", null)?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val uuid = UUID.randomUUID().toString()
        val id = MessageDigest.getInstance("SHA-256").digest("$androidId:$uuid".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(preferences.edit().putString("install_uuid", uuid).putString("device_id", id).commit())
        return id
    }
}
