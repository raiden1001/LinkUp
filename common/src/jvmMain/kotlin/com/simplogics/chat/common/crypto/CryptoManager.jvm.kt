package com.simplogics.chat.common.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

actual object CryptoManager {
    private val keyStore: KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
        }

    actual fun generateRSAKeyPair(alias: String): RSAKeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        // In a real server scenario, we'd store the private key securely.
        // For this demo/impl, we store in the in-memory KeyStore.
        keyStore.setKeyEntry(
            alias,
            kp.private,
            null,
            arrayOf(
                // Self-signed cert would be needed for JKS usually, but for raw private key storage we might need adjustments
                // Simplified: Just keep a map for JVM testing if KeyStore is complex without certs.
                // But let's try standard KeyStore approach or just a static map for the "simulation".
            ),
        )

        // Actually, let's use a static map for JVM simulation of a secure container (since we don't have a real HSM on standard JVM without config)
        JvmKeyStore.keys[alias] = kp.private

        val pubKeyString = Base64.getEncoder().encodeToString(kp.public.encoded)
        return RSAKeyPair(pubKeyString, alias)
    }

    actual fun encryptRSA(
        data: ByteArray,
        publicKeyBase64: String,
    ): ByteArray {
        println("CRYPTO_INPUT$publicKeyBase64")
        val publicBytes = decodeBase64Flexible(publicKeyBase64)

        val keySpec = X509EncodedKeySpec(publicBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    actual fun decryptRSA(
        data: ByteArray,
        privateKeyAlias: String,
    ): ByteArray {
        val privateKey =
            JvmKeyStore.keys[privateKeyAlias]
                ?: throw IllegalStateException("Key alias $privateKeyAlias not found")

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    actual fun generateAESKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()
        return secretKey.encoded
    }

    actual fun encryptAES(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding") // Or AES/CBC/PKCS5Padding
        // GCM needs IV. For simplicity here, let's use AES/ECB/PKCS5Padding or manage IV.
        // Security requirement: "AES used for message payload".
        // GCM is better. I need to handle IV.
        // Let's prepend IV.
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
        val iv = data.copyOfRange(0, 12) // GCM IV is usually 12 bytes
        val encrypted = data.copyOfRange(12, data.size)
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encrypted)
    }
}

object JvmKeyStore {
    val keys = mutableMapOf<String, PrivateKey>()
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
