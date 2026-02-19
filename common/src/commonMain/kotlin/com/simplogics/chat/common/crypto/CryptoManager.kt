package com.simplogics.chat.common.crypto

expect object CryptoManager {
    fun generateRSAKeyPair(alias: String): RSAKeyPair

    fun encryptRSA(
        data: ByteArray,
        publicKeyBase64: String,
    ): ByteArray

    fun decryptRSA(
        data: ByteArray,
        privateKeyAlias: String,
    ): ByteArray

    fun generateAESKey(): ByteArray

    fun encryptAES(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray

    fun decryptAES(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray
}

data class RSAKeyPair(val publicKeyBase64: String, val privateKeyAlias: String)
