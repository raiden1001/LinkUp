package com.simplogics.chat.common.models

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String? = null,
    /** RSA Public Key (Base64) */
    val publicKey: String,
)
