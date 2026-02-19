package com.simplogics.chat.gateway

import com.simplogics.chat.common.models.Message
import com.simplogics.chat.common.models.SocketFrame
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.routing.routing
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration
import java.util.Collections
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

fun main() {
    embeddedServer(Netty, port = 8083, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val jwtSecret = "secret"
    val jwtIssuer = "http://0.0.0.0:8081/"
    val jwtAudience = "chat-users"
    val jwtRealm = "Access to Chat"

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build(),
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    // In-memory subscription map: ChannelId -> Set<DefaultWebSocketServerSession>
    val subscriptions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    // Kafka Consumer Logic
    launch(Dispatchers.IO) {
        val props =
            Properties().apply {
                put("bootstrap.servers", "localhost:9092")
                put("group.id", "websocket-gateway-group")
                put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
                put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            }

        val consumer = KafkaConsumer<String, String>(props)
        consumer.subscribe(listOf("chat-message-events"))

        try {
            while (isActive) {
                val records = consumer.poll(Duration.ofMillis(100))
                for (record in records) {
                    val channelId = record.key() // Partition key is channelId
                    val messageJson = record.value()

                    try {
                        val message = Json.decodeFromString<Message>(messageJson)
                        val frame = SocketFrame.IncomingMessage(message)

                        val subscribers = subscriptions[channelId]
                        subscribers?.forEach { session ->
                            launch {
                                try {
                                    session.sendSerialized(frame)
                                } catch (e: Exception) {
                                    // Handle disconnection?
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } finally {
            consumer.close()
        }
    }

    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")

        authenticate("auth-jwt") {
            webSocket("/ws") {
                val principal = call.principal<JWTPrincipal>()
                val email =
                    principal?.payload?.getClaim("email")?.asString()
                        ?: return@webSocket close(
                            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No Email"),
                        )

                try {
                    for (frame in incoming) {
                        // We use receiveDeserialized in a loop but here we might receive raw frames if we don't assume strictly serialized input
                        // Use receiveDeserialized if client sends JSON frames.
                        // Or cleaner: iterate incoming and decode.

                        // Wait, `receiveDeserialized` is a suspending function that reads one frame.
                        // To allow reading multiple types, we might loop.
                        // But typical Ktor WebSocket loop:

                        // Actually, let's use receiveDeserialized<SocketFrame>() loop
                        // The client MUST send SocketFrame JSONs.

                        // Note: Ktor's receiveDeserialized might block or throw if format is wrong.
                        // Let's use `converter` manually or just read text.

                        when (val receivedFrame = receiveDeserialized<SocketFrame>()) {
                            is SocketFrame.Subscribe -> {
                                val set =
                                    subscriptions.getOrPut(receivedFrame.channelId) {
                                        Collections.newSetFromMap(ConcurrentHashMap())
                                    }
                                set.add(this)
                            }
                            is SocketFrame.Unsubscribe -> {
                                subscriptions[receivedFrame.channelId]?.remove(this)
                            }
                            else -> {
                                // Ignore other frames from client for now
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Cleanup subscriptions
                    subscriptions.values.forEach { it.remove(this) }
                }
            }
        }
    }
}
