package com.simplogics.chat.common.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object CryptoManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    actual fun generateRSAKeyPair(alias: String): RSAKeyPair {
        if (!keyStore.containsAlias(alias)) {
            val kpg =
                KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    ANDROID_KEYSTORE,
                )
            val parameterSpec =
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .build()

            kpg.initialize(parameterSpec)
            kpg.generateKeyPair()
        }

        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        val publicKey = entry?.certificate?.publicKey ?: throw IllegalStateException("Key alias $alias not found")
        val pubKeyString = Base64.getEncoder().encodeToString(publicKey.encoded)

        return RSAKeyPair(pubKeyString, alias)
    }

    actual fun encryptRSA(
        data: ByteArray,
        publicKeyBase64: String,
    ): ByteArray {
        // Should implemented same as JVM mostly, but loading public key
        // Note: RSA encryption usually uses the recipient's public key, which comes from the server/friend.
        // It is NOT from the keystore usually.
        Log.d("CRYPTO_INPUT", publicKeyBase64)
        val publicBytes = decodeBase64Flexible(publicKeyBase64)

        val keySpec = java.security.spec.X509EncodedKeySpec(publicBytes)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    actual fun decryptRSA(
        data: ByteArray,
        privateKeyAlias: String,
    ): ByteArray {
        val entry =
            keyStore.getEntry(privateKeyAlias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("Key alias $privateKeyAlias not found")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, entry.privateKey)
        return cipher.doFinal(data)
    }

    actual fun generateAESKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey().encoded
    }

    actual fun encryptAES(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    actual fun decryptAES(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // IV is first 12 bytes
        val iv = data.copyOfRange(0, 12)
        val encrypted = data.copyOfRange(12, data.size)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encrypted)
    }
}

private fun decodeBase64Flexible(input: String): ByteArray {
    val normalized =
        input
            .replace('-', '+')
            .replace('_', '/')
            .let {
                it + "=".repeat((4 - it.length % 4) % 4)
            }

    return Base64.getDecoder().decode(normalized)
}
