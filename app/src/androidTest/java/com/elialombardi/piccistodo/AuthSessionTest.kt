package com.elialombardi.piccistodo

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elialombardi.piccistodo.auth.TokenHolder
import com.elialombardi.piccistodo.auth.DeviceIdProvider
import com.elialombardi.piccistodo.data.local.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Fake bearer values only. No network or biometric prompts. */
@RunWith(AndroidJUnit4::class)
class AuthSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var settings: SettingsManager
    private lateinit var originalUrl: String
    @Before fun prepare() = runBlocking {
        settings = SettingsManager(instrumentation.targetContext)
        originalUrl = settings.getApiUrl()
        settings.clearSession()
    }
    @After fun cleanup() = runBlocking {
        settings.clearSession()
        settings.saveApiUrl(originalUrl)
    }

    @Test fun coldStartHasNoSessionEvenWithEnrollmentPreference() = runBlocking {
        settings.setBiometricEnabled(true)
        val session = settings.loginSessionFlow.first()
        assertFalse(session.hasToken)
        assertFalse(session.usable)
        assertNull(settings.getTokenSync())
    }

    @Test fun verifiedTokenExistsOnlyInRam() = runBlocking {
        val fakeToken = "fake-memory-only-access-value"
        TokenHolder.set(fakeToken, 60)
        assertEquals(fakeToken, settings.getTokenSync())
        assertTrue(settings.loginSessionFlow.first().usable)
        settings.setBiometricEnabled(true) // Flush a non-secret preference edit.
        val file = File(instrumentation.targetContext.filesDir, "datastore/user_prefs.preferences_pb")
        assertFalse(file.readText().contains(fakeToken))
        assertFalse(file.readText().contains("jwt_token"))
    }

    @Test fun expiryClearsTokenAndEmitsSignedOut() = runBlocking {
        TokenHolder.set("fake-expiring-token", 1, "Writer")
        withTimeout(5000) { TokenHolder.signedIn.first { !it } }
        assertNull(TokenHolder.accessToken)
        assertNull(TokenHolder.session.value)
        assertNull(settings.sessionFlow.first().third)
        assertEquals(0L, TokenHolder.expiresAt)
    }

    @Test fun responseRoleReachesRoleAwareScreens() = runBlocking {
        for (role in listOf("Writer", "Admin", "Reader")) {
            TokenHolder.set("fake-$role-token", 60, role)
            val session = settings.sessionFlow.first()
            assertEquals("fake-$role-token", session.first)
            assertEquals(role, session.third)
        }
    }

    @Test fun jwtRoleIsUsedWhenVerificationResponseOmitsRole() = runBlocking {
        for (role in listOf("Writer", "Admin", "Reader")) {
            val payload = Base64.encodeToString("{\"role\":\"$role\"}".toByteArray(),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            TokenHolder.set("fake-header.$payload.fake-signature", 60)
            assertEquals(role, settings.sessionFlow.first().third)
        }
    }

    @Test fun replacementSessionEmitsEvenWhenAlreadySignedIn() = runBlocking {
        TokenHolder.set("fake-writer-token", 60, "Writer")
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) { settings.sessionFlow.first { it.first == "fake-reader-token" } }
        }
        TokenHolder.set("fake-reader-token", 60, "Reader")
        assertEquals("Reader", next.await().third)
    }

    @Test fun unknownRoleDoesNotInheritPreviousWritePermission() = runBlocking {
        TokenHolder.set("fake-writer-token", 60, "Writer")
        for (token in listOf("not-a-jwt", "header.invalid.signature")) {
            TokenHolder.set(token, 60)
            assertNull(settings.sessionFlow.first().third)
        }
        TokenHolder.set("fake-admin-token", 60, "Admin")
        settings.clearSession()
        assertNull(TokenHolder.session.value)
        assertNull(settings.sessionFlow.first().third)
    }

    @Test fun stale401CannotClearNewSession() = runBlocking {
        TokenHolder.set("old-fake-token", 60)
        TokenHolder.set("new-fake-token", 60)
        settings.clearRejectedToken("old-fake-token")
        assertEquals("new-fake-token", settings.getTokenSync())
        settings.clearRejectedToken("new-fake-token")
        assertNull(settings.getTokenSync())
    }

    @Test fun logoutClearsMemoryWithoutDisablingRegisteredDevice() = runBlocking {
        TokenHolder.set("fake-token", 60)
        settings.setBiometricEnabled(true)
        settings.clearSession()
        assertNull(TokenHolder.accessToken)
        assertFalse(TokenHolder.signedIn.value)
        assertTrue(settings.biometricEnabledFlow.first())
    }

    @Test fun deviceIdentitySurvivesProviderRecreation() = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        val id = DeviceIdProvider(context).getDeviceId()
        assertEquals(64, id.length)
        assertEquals(id, DeviceIdProvider(context).getDeviceId())
    }

    @Test fun selectedServerPersistsAndClearsPreviousSession() = runBlocking {
        settings.saveApiUrl("https://first.example")
        TokenHolder.set("fake-first-server-token", 60)
        settings.saveApiUrl(" https://SECOND.example:8443 ")
        assertNull(TokenHolder.accessToken)
        assertEquals("https://second.example:8443/", settings.getApiUrlSync())
        val recreated = SettingsManager(instrumentation.targetContext)
        assertEquals("https://second.example:8443/", recreated.getApiUrl())
        assertEquals(recreated.getApiUrl(), recreated.apiUrlFlow.first())
    }

    @Test fun tokensAreRestrictedToTheSelectedOrigin() = runBlocking {
        settings.saveApiUrl("https://selected.example:8443")
        TokenHolder.set("fake-selected-token", 60)
        assertEquals("fake-selected-token", settings.getTokenForUrl("https://selected.example:8443/api/tasks".toHttpUrl()))
        assertNull(settings.getTokenForUrl("https://other.example/api/tasks".toHttpUrl()))
        assertNull(settings.getTokenForUrl("https://selected.example/api/tasks".toHttpUrl()))
        settings.saveApiUrl("https://SELECTED.example:8443/")
        assertEquals("fake-selected-token", TokenHolder.accessToken)
    }

    @Test fun invalidServerDoesNotReplaceConfigurationOrSession() = runBlocking {
        settings.saveApiUrl("https://selected.example/")
        TokenHolder.set("fake-selected-token", 60)
        try { settings.saveApiUrl("http://insecure.example/"); fail("HTTPS must be required") }
        catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals("https://selected.example/", settings.getApiUrl())
        assertEquals("fake-selected-token", TokenHolder.accessToken)
    }
}
