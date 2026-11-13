package com.elialombardi.piccistodo.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    const val validationMessage = "Enter a valid HTTPS server address, without credentials, an API path, query parameters, or a fragment."

    fun normalize(value: String): String? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) return null
        return url.toString()
    }

    fun origin(url: HttpUrl): String = url.newBuilder()
        .encodedPath("/").query(null).fragment(null).build().toString()
}
