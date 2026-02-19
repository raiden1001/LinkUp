package com.simplogics.chat.media

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
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MinioClient
import io.minio.http.Method
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit

fun main() {
    embeddedServer(Netty, port = 8084, host = "0.0.0.0", module = Application::module)
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
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    Result.error<String>("Unauthorized", status = HttpStatusCode.Unauthorized.value),
                )
            }
        }
    }

    // MinIO Client
    // In production, configure with env variables
    val minioClient =
        MinioClient.builder()
            .endpoint("http://localhost:9000")
            .credentials("minioadmin", "minioadmin")
            .build()

    val bucketName = "chat-images"

    routing {
        authenticate("auth-jwt") {
            post("/presign") {
                val principal = call.principal<JWTPrincipal>()
                // In real app, check quotas etc.

                val request = call.receive<PresignRequest>()
                val objectName = "uploads/${java.util.UUID.randomUUID()}.${request.extension}"

                try {
                    val uploadUrl =
                        minioClient.getPresignedObjectUrl(
                            io.minio.GetPresignedObjectUrlArgs.builder()
                                .method(io.minio.http.Method.PUT)
                                .bucket(bucketName)
                                .`object`(objectName)
                                .expiry(60 * 60, TimeUnit.SECONDS)
                                .build(),
                        )

                    call.respond<Result<PresignResponse>>(Result.success(PresignResponse(uploadUrl, objectName)))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        Result.error<PresignResponse>(
                            "Failed to generate presigned URL",
                            status = HttpStatusCode.InternalServerError.value,
                        ),
                    )
                }
            }
        }
    }
}

@Serializable
data class PresignRequest(val extension: String, val contentType: String)

@Serializable
data class PresignResponse(val uploadUrl: String, val objectName: String)
