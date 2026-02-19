package com.simplogics.chat.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Date

fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::module)
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

    val jwtSecret = "secret" // In production, verify this from env
    val jwtIssuer = "http://0.0.0.0:8081/"
    val jwtAudience = "chat-users"
    val jwtRealm = "Access to Chat"

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
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

    routing {
        post("/login") {
            val loginRequest = call.receive<LoginRequest>()
            if (!loginRequest.email.endsWith("@simplogics.com")) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    Result.error<LoginResponse>(
                        "Only @simplogics.com emails allowed",
                        status = HttpStatusCode.Forbidden.value,
                    ),
                )
                return@post
            }

            // In a real app, verify password/OTP here.
            // For this demo, we assume successful login if domain matches.

            val token =
                JWT.create()
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .withClaim("email", loginRequest.email)
                    .withExpiresAt(Date(System.currentTimeMillis() + 60000 * 60 * 24)) // 24 hours
                    .sign(Algorithm.HMAC256(jwtSecret))

            call.respond<Result<LoginResponse>>(Result.success(LoginResponse(token, loginRequest.email)))
        }

        authenticate("auth-jwt") {
            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val email = principal!!.payload.getClaim("email").asString()
                call.respond<Result<Map<String, String>>>(Result.success(mapOf("email" to email)))
            }
        }
    }
}

@Serializable
data class LoginRequest(val email: String, val password: String? = null)

@Serializable
data class LoginResponse(val token: String, val email: String)
