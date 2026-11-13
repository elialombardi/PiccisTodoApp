package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.auth.ServerUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerUrlTest {
    @Test fun normalizesHttpsOrigins() {
        assertEquals("https://backend.example/", ServerUrl.normalize("  https://BACKEND.example  "))
        assertEquals("https://backend.example/", ServerUrl.normalize("https://backend.example:443/"))
        assertEquals("https://192.168.1.10:8443/", ServerUrl.normalize("https://192.168.1.10:8443"))
    }

    @Test fun rejectsPlaintextAndMalformedAddresses() {
        listOf("", "backend.example", "http://backend.example", "https://", "ftp://backend.example")
            .forEach { assertNull(ServerUrl.normalize(it)) }
    }

    @Test fun rejectsCredentialsAndNonOriginComponents() {
        listOf("https://user:password@backend.example", "https://backend.example/api",
            "https://backend.example/?key=value", "https://backend.example/#fragment")
            .forEach { assertNull(ServerUrl.normalize(it)) }
    }

    @Test fun requestOriginIncludesSchemeHostAndPortButNotPath() {
        assertEquals("https://backend.example:8443/",
            ServerUrl.origin("https://backend.example:8443/api/tasks?filter=active".toHttpUrl()))
        assertNotEquals(ServerUrl.origin("http://backend.example/".toHttpUrl()),
            ServerUrl.origin("https://backend.example/".toHttpUrl()))
    }
}
