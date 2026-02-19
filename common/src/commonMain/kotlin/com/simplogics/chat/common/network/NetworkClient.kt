package com.simplogics.chat.common.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object NetworkClient {
    private var token: String? = null

    private val json =
        Json {
            classDiscriminator = "type" // Must match backend discriminator for polymorphic types (e.g. SocketFrame)
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    val client =
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }

            install(WebSockets) {
                contentConverter =
                    KotlinxWebsocketSerializationConverter(json)
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        token?.let { BearerTokens(it, "") }
                    }
                    refreshTokens {
                        token?.let { BearerTokens(it, "") }
                    }
                }
            }
        }

    fun setAuthToken(newToken: String) {
        token = newToken
    }
}
