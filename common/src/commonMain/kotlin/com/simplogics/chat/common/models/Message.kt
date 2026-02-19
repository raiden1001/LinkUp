package com.simplogics.chat.common.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val channelId: String,
    /** AES encrypted content (Base64) */
    val encryptedPayload: String,
    /** AES key encrypted with recipient's public key (RSA) - for 1:1 or group key logic */
    val encryptedDataKey: String,
    val timestamp: Long,
    val type: MessageType = MessageType.TEXT,
)

@Serializable
enum class MessageType {
    TEXT,
    IMAGE_URL,
    SYSTEM,
}
