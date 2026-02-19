package com.simplogics.chat.common.models

import kotlinx.serialization.Serializable

@Serializable
sealed class SocketFrame {
    @Serializable
    data class Subscribe(val channelId: String) : SocketFrame()

    @Serializable
    data class Unsubscribe(val channelId: String) : SocketFrame()

    @Serializable
    data class IncomingMessage(val message: Message) : SocketFrame()

    @Serializable
    data class Error(val reason: String) : SocketFrame()
}
