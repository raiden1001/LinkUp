package com.simplogics.chat.common.repository

import com.simplogics.chat.common.crypto.CryptoManager
import com.simplogics.chat.common.models.Message
import com.simplogics.chat.common.models.SocketFrame
import com.simplogics.chat.common.network.NetworkClient
import com.simplogics.chat.common.result.Result
import com.simplogics.chat.common.result.toResult
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.encodeBase64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class ChatRepository {
    private val client = NetworkClient.client
    private val wsUrl = "ws://192.168.29.143:8083/ws"
    private val restUrl = "http://192.168.29.143:8082"

    suspend fun sendMessage(
        channelId: String,
        text: String,
        senderId: String,
        recipientPublicKey: String,
    ): Result<String> {
        try {
            // Encryption Logic
            val aesKey = CryptoManager.generateAESKey()
            val encryptedPayload = CryptoManager.encryptAES(text.toByteArray(), aesKey)
            val payloadBase64 = encryptedPayload.encodeBase64()

            val encryptedKeyBytes = CryptoManager.encryptRSA(aesKey, recipientPublicKey)
            val encryptedKeyBase64 = encryptedKeyBytes.encodeBase64()

            val message =
                Message(
                    id = "${System.currentTimeMillis()}",
                    senderId = senderId,
                    channelId = channelId,
                    encryptedPayload = payloadBase64,
                    encryptedDataKey = encryptedKeyBase64,
                    timestamp = System.currentTimeMillis(),
                )

            val response =
                client.post("$restUrl/messages/send") {
                    contentType(ContentType.Application.Json)
                    setBody(message)
                }

            val result = response.toResult<String>()
            if (result.ok) {
                println("ChatRepository.sendMessage: SUCCESS channel=$channelId sender=$senderId")
            } else {
                println("ChatRepository.sendMessage: FAILED channel=$channelId sender=$senderId reason=${result.error?.message ?: "Unknown error"}")
            }
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            println("ChatRepository.sendMessage: EXCEPTION channel=$channelId sender=$senderId reason=${e.message}")
            return Result.error<String>(e.message ?: "Unknown error")
        }
    }

    fun observeMessages(channelId: String): Flow<SocketFrame> =
        flow {
            try {
                client.webSocket(wsUrl) {
                    // Send Subscribe frame
                    sendSerialized(SocketFrame.Subscribe(channelId))

                    while (isActive) {
                        val frame = receiveDeserialized<SocketFrame>()
                        emit(frame)
                    }
                }
            } catch (e: Exception) {
                emit(SocketFrame.Error(e.message ?: "Connection error"))
            }
        }
}
