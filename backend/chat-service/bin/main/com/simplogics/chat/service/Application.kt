package com.simplogics.chat.service

import com.simplogics.chat.common.models.Message
import com.simplogics.chat.common.result.Result
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

fun main() {
    embeddedServer(Netty, port = 8082, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    install(ContentNegotiation) {
        json(json)
    }

    install(StatusPages) {
        exception<ContentTransformationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                Result.error<String>(cause.message ?: "Invalid request body", status = HttpStatusCode.BadRequest.value),
            )
        }
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                Result.error<String>("Internal server error", status = HttpStatusCode.InternalServerError.value),
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, Result.error<String>("Not found", status = status.value))
        }
    }

    // Auth config (should share with auth-service or verify token only)
    val jwtSecret = "secret" // Env var
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
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    Result.error<String>("Unauthorized", status = HttpStatusCode.Unauthorized.value),
                )
            }
        }
    }

    val kafkaProps =
        Properties().apply {
            put("bootstrap.servers", "localhost:9092") // Env var
            put("key.serializer", StringSerializer::class.java.name)
            put("value.serializer", StringSerializer::class.java.name)
        }

    val producer = KafkaProducer<String, String>(kafkaProps)

    routing {
        authenticate("auth-jwt") {
            post("/messages/send") {
                val principal = call.principal<JWTPrincipal>()
                val senderEmail = principal!!.payload.getClaim("email").asString()
                // In real app, resolve email to userId. For now use email as senderId.

                try {
                    val message = call.receive<Message>()
                    // Validate message.senderId == senderEmail or resolve it

                    val messageJson = json.encodeToString(Message.serializer(), message)

                    // Partition key = channelId (conversationId)
                    val record = ProducerRecord("chat-message-events", message.channelId, messageJson)

                    producer.send(record) { metadata, exception ->
                        if (exception != null) {
                            // Log error
                            exception.printStackTrace()
                        } else {
                            // Log success
                        }
                    }

                    call.respond<Result<String>>(Result.success("Message queued"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        Result.error<String>(e.message ?: "Invalid request", status = HttpStatusCode.BadRequest.value),
                    )
                }
            }
        }
    }
}
