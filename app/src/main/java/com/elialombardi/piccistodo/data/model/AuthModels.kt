package com.elialombardi.piccistodo.data.model

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    @SerializedName("access_token") val token: String,
    val role: String? = null
)
