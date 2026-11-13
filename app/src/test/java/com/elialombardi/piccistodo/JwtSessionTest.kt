package com.elialombardi.piccistodo

import com.elialombardi.piccistodo.data.local.JwtSession
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class JwtSessionTest {
    private fun token(payload: String) = "e30.${payload.encodeUtf8().base64Url().trimEnd('=')}.signature"

    @Test fun expiredAndNearExpiryRequirePassword() {
        assertFalse(JwtSession.usable(token("""{"exp":999}"""), 1000))
        assertFalse(JwtSession.usable(token("""{"exp":1000}"""), 1000))
        assertFalse(JwtSession.usable(token("""{"exp":1030}"""), 1000))
        assertTrue(JwtSession.usable(token("""{"exp":1031}"""), 1000))
    }

    @Test fun missingOrMalformedSessionCannotUnlock() {
        listOf(null, "", "bad-token", "a.b.c", "a..b", token("{}"), token("null"), token("[]"),
            token("""{"exp":"2000"}"""), token("""{"exp":null}"""), token("""{"exp":{}}"""),
            token("""{"exp":99999999999999999999999999}"""))
            .forEach { assertFalse(JwtSession.usable(it, 1000)) }
    }

    @Test fun validSessionBecomesExpiredAsClockAdvances() {
        val jwt = token("""{"exp":2000,"sub":"test-user"}""")
        assertTrue(JwtSession.usable(jwt, 1000))
        assertFalse(JwtSession.usable(jwt, 2001))
    }
}
