package com.elialombardi.piccistodo.data.local

import com.google.gson.JsonParser
import okio.ByteString.Companion.decodeBase64
import java.time.Instant

object JwtSession {
    /** Only decides whether local unlock is worth offering; this does not verify a signature. */
    fun usable(token: String?, nowSeconds: Long = Instant.now().epochSecond): Boolean {
        if (token.isNullOrBlank()) return false
        return runCatching {
            val parts = token.split('.')
            require(parts.size == 3 && parts.all { it.isNotBlank() })
            val payload = parts[1].decodeBase64()?.utf8() ?: return false
            val expiry = JsonParser.parseString(payload).asJsonObject.get("exp")?.asJsonPrimitive ?: return false
            require(expiry.isNumber)
            val seconds = expiry.asBigDecimal.longValueExact()
            // Avoid unlocking a session that will expire during the first request.
            seconds > nowSeconds && seconds - nowSeconds > 30
        }.getOrDefault(false)
    }
}
