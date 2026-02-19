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
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Date
import java.util.UUID

fun main() {
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/chat_platform"
    val dbUser = System.getenv("DB_USER") ?: "chat_user"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "chat_password"

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

    fun <T> withDb(block: (java.sql.Connection) -> T): T {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword).use(block)
    }

    fun findUserByEmail(conn: java.sql.Connection, email: String): UserRecord? {
        val sql = "SELECT id, email, public_key_base64, status, created_at, last_seen_at FROM users WHERE email = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return UserRecord(
                    id = rs.getObject("id").toString(),
                    email = rs.getString("email"),
                    publicKeyBase64 = rs.getString("public_key_base64"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                    lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant()?.toString(),
                )
            }
        }
    }

    fun findUserByRef(conn: java.sql.Connection, userRef: String): UserRecord? {
        val byIdSql = "SELECT id, email, public_key_base64, status, created_at, last_seen_at FROM users WHERE id = ?::uuid"
        runCatching {
            UUID.fromString(userRef)
        }.onSuccess { uuid ->
            conn.prepareStatement(byIdSql).use { ps ->
                ps.setObject(1, uuid)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return UserRecord(
                            id = rs.getObject("id").toString(),
                            email = rs.getString("email"),
                            publicKeyBase64 = rs.getString("public_key_base64"),
                            status = rs.getString("status"),
                            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                            lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant()?.toString(),
                        )
                    }
                }
            }
        }

        return findUserByEmail(conn, userRef)
    }

    fun upsertUserForLogin(conn: java.sql.Connection, email: String): UserRecord {
        val existing = findUserByEmail(conn, email)
        if (existing != null) {
            conn.prepareStatement("UPDATE users SET last_seen_at = ? WHERE id = ?::uuid").use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.now()))
                ps.setObject(2, UUID.fromString(existing.id))
                ps.executeUpdate()
            }
            return existing.copy(lastSeenAt = Instant.now().toString())
        }

        val userId = UUID.randomUUID()
        conn.prepareStatement(
            """
            INSERT INTO users (id, email, public_key_base64, status, created_at, last_seen_at)
            VALUES (?::uuid, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
        ).use { ps ->
            val now = Timestamp.from(Instant.now())
            ps.setObject(1, userId)
            ps.setString(2, email)
            ps.setString(3, "UNREGISTERED_KEY")
            ps.setTimestamp(4, now)
            ps.setTimestamp(5, now)
            ps.executeUpdate()
        }

        return UserRecord(
            id = userId.toString(),
            email = email,
            publicKeyBase64 = "UNREGISTERED_KEY",
            status = "ACTIVE",
            createdAt = Instant.now().toString(),
            lastSeenAt = Instant.now().toString(),
        )
    }

    fun writeLoginAudit(
        conn: java.sql.Connection,
        userId: String?,
        email: String?,
        success: Boolean,
        failureReason: String?,
        ip: String?,
        userAgent: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO login_audit_logs (id, user_id, email, success, failure_reason, ip_address, user_agent, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            if (userId != null) ps.setObject(2, UUID.fromString(userId)) else ps.setNull(2, java.sql.Types.OTHER)
            ps.setString(3, email)
            ps.setBoolean(4, success)
            ps.setString(5, failureReason)
            ps.setString(6, ip)
            ps.setString(7, userAgent)
            ps.setTimestamp(8, Timestamp.from(Instant.now()))
            ps.executeUpdate()
        }
    }

    fun writeAuthSession(
        conn: java.sql.Connection,
        userId: String,
        deviceId: String,
        tokenHash: String,
        ip: String?,
        userAgent: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO auth_sessions (id, user_id, device_id, refresh_token_hash, ip_address, user_agent, created_at, expires_at, revoked_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, UUID.fromString(userId))
            ps.setString(3, deviceId)
            ps.setString(4, tokenHash)
            ps.setString(5, ip)
            ps.setString(6, userAgent)
            ps.setTimestamp(7, Timestamp.from(Instant.now()))
            ps.setTimestamp(8, Timestamp.from(Instant.ofEpochMilli(System.currentTimeMillis() + 60000L * 60 * 24)))
            ps.setNull(9, java.sql.Types.TIMESTAMP)
            ps.executeUpdate()
        }
    }

    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")

        post("/login") {
            val loginRequest = call.receive<LoginRequest>()
            if (!loginRequest.email.endsWith("@simplogics.com")) {
                withDb { conn ->
                    writeLoginAudit(
                        conn = conn,
                        userId = null,
                        email = loginRequest.email,
                        success = false,
                        failureReason = "Only @simplogics.com emails allowed",
                        ip = call.request.local.remoteHost,
                        userAgent = call.request.headers["User-Agent"],
                    )
                }
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
            val userRecord = withDb { conn -> upsertUserForLogin(conn, loginRequest.email) }
            val userId = userRecord.id

            val token =
                JWT.create()
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .withClaim("email", loginRequest.email)
                    .withClaim("userId", userId)
                    .withExpiresAt(Date(System.currentTimeMillis() + 60000 * 60 * 24)) // 24 hours
                    .sign(Algorithm.HMAC256(jwtSecret))

            withDb { conn ->
                writeAuthSession(
                    conn = conn,
                    userId = userId,
                    deviceId = loginRequest.deviceId ?: "unknown-device",
                    tokenHash = "jwt-${token.take(16)}",
                    ip = call.request.local.remoteHost,
                    userAgent = call.request.headers["User-Agent"],
                )
                writeLoginAudit(
                    conn = conn,
                    userId = userId,
                    email = loginRequest.email,
                    success = true,
                    failureReason = null,
                    ip = call.request.local.remoteHost,
                    userAgent = call.request.headers["User-Agent"],
                )
            }

            call.respond<Result<LoginResponse>>(Result.success(LoginResponse(token, loginRequest.email, userId)))
        }

        authenticate("auth-jwt") {
            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val email = principal!!.payload.getClaim("email").asString()
                val userId =
                    principal.payload.getClaim("userId").asString()
                        ?: withDb { conn -> findUserByEmail(conn, email)?.id }
                call.respond<Result<Map<String, String>>>(Result.success(mapOf("email" to email, "userId" to (userId ?: ""))))
            }

            post("/keys/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val email = principal.payload.getClaim("email").asString()
                val resolvedUserId =
                    principal.payload.getClaim("userId").asString()
                        ?: withDb { conn -> findUserByEmail(conn, email)?.id }
                if (resolvedUserId == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<PublicKeyRecord>("User not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@post
                }

                val request = call.receive<UpsertPublicKeyRequest>()
                val keyRecord = withDb { conn ->
                    conn.prepareStatement(
                        "UPDATE users SET public_key_base64 = ?, last_seen_at = ? WHERE id = ?::uuid",
                    ).use { ps ->
                        ps.setString(1, request.publicKeyBase64)
                        ps.setTimestamp(2, Timestamp.from(Instant.now()))
                        ps.setObject(3, UUID.fromString(resolvedUserId))
                        if (ps.executeUpdate() == 0) {
                            return@withDb null
                        }
                    }
                    PublicKeyRecord(
                        userId = resolvedUserId,
                        publicKeyBase64 = request.publicKeyBase64,
                        keyId = request.keyId ?: "primary-$resolvedUserId",
                        algorithm = request.algorithm ?: "RSA",
                        updatedAt = Instant.now().toString(),
                    )
                }
                if (keyRecord == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<PublicKeyRecord>("User not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@post
                }
                call.respond<Result<PublicKeyRecord>>(Result.success(keyRecord))
            }

            get("/keys/{userId}") {
                val userRef = call.parameters["userId"].orEmpty()
                val keyRecord = withDb { conn ->
                    val user = findUserByRef(conn, userRef)
                    user?.let {
                        PublicKeyRecord(
                            userId = it.id,
                            publicKeyBase64 = it.publicKeyBase64,
                            keyId = "primary-${it.id}",
                            algorithm = "RSA",
                            updatedAt = it.lastSeenAt ?: it.createdAt,
                        )
                    }
                }
                if (keyRecord == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<PublicKeyRecord>("Not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@get
                }
                call.respond<Result<PublicKeyRecord>>(Result.success(keyRecord))
            }

            // Compatibility endpoint for existing mobile repository usage.
            get("/users/{userId}/keys/active") {
                val userRef = call.parameters["userId"].orEmpty()
                val keyRecord = withDb { conn ->
                    val user = findUserByRef(conn, userRef)
                    user?.let {
                        PublicKeyRecord(
                            userId = it.id,
                            publicKeyBase64 = it.publicKeyBase64,
                            keyId = "primary-${it.id}",
                            algorithm = "RSA",
                            updatedAt = it.lastSeenAt ?: it.createdAt,
                        )
                    }
                }
                if (keyRecord == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<PublicKeyRecord>("Not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@get
                }
                call.respond<Result<PublicKeyRecord>>(Result.success(keyRecord))
            }
        }
    }
}

@Serializable
data class LoginRequest(val email: String, val password: String? = null, val deviceId: String? = null)

@Serializable
data class LoginResponse(val token: String, val email: String, val userId: String)

@Serializable
data class UpsertPublicKeyRequest(
    val publicKeyBase64: String,
    val keyId: String? = null,
    val algorithm: String? = "RSA",
)

@Serializable
data class PublicKeyRecord(
    val userId: String,
    val publicKeyBase64: String,
    val keyId: String,
    val algorithm: String,
    val updatedAt: String,
)

@Serializable
data class UserRecord(
    val id: String,
    val email: String,
    val publicKeyBase64: String,
    val status: String,
    val createdAt: String,
    val lastSeenAt: String?,
)

@Serializable
data class AuthSessionRecord(
    val id: String,
    val userId: String,
    val deviceId: String,
    val refreshTokenHash: String,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: String,
    val expiresAt: String,
    val revokedAt: String?,
)

@Serializable
data class LoginAuditRecord(
    val id: String,
    val userId: String?,
    val email: String?,
    val success: Boolean,
    val failureReason: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: String,
)
