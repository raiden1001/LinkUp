package com.simplogics.chat.common.repository

import com.simplogics.chat.common.network.NetworkClient
import com.simplogics.chat.common.result.Result
import com.simplogics.chat.common.result.toResult
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

class KeyDirectoryRepository {
    private val client = NetworkClient.client
    private val baseUrl = "http://192.168.29.143:8081"

    suspend fun getActiveEncryptionKey(userId: String): Result<RecipientKeyBundle> {
        return try {
            val response = client.get("$baseUrl/users/$userId/keys/active")
            response.toResult<RecipientKeyBundle>()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.error("Failed to fetch recipient key: ${e.message}")
        }
    }
}

@Serializable
data class RecipientKeyBundle(
    val userId: String,
    val publicKey: String,
    val keyId: String,
    val algorithm: String,
)
