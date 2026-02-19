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
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.BucketExistsArgs
import io.minio.http.Method
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.TimeUnit

fun main() {
    embeddedServer(Netty, port = 8084, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/chat_platform"
    val dbUser = System.getenv("DB_USER") ?: "chat_user"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "chat_password"
    val minioEndpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
    val minioUser = System.getenv("MINIO_ROOT_USER") ?: "minioadmin"
    val minioPassword = System.getenv("MINIO_ROOT_PASSWORD") ?: "minioadmin"

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
            .endpoint(minioEndpoint)
            .credentials(minioUser, minioPassword)
            .build()

    val bucketName = "chat-images"

    fun <T> withDb(block: (java.sql.Connection) -> T): T {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword).use(block)
    }

    try {
        val exists =
            minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build(),
            )
        if (!exists) {
            minioClient.makeBucket(
                MakeBucketArgs.builder().bucket(bucketName).build(),
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")

        authenticate("auth-jwt") {
            post("/presign") {
                val request = call.receive<PresignRequest>()
                val objectName = "uploads/${java.util.UUID.randomUUID()}.${request.extension}"

                try {
                    val bucketExists =
                        minioClient.bucketExists(
                            BucketExistsArgs.builder().bucket(bucketName).build(),
                        )
                    if (!bucketExists) {
                        minioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucketName).build(),
                        )
                    }

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
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        Result.error<PresignResponse>(
                            "Failed to generate presigned URL",
                            status = HttpStatusCode.InternalServerError.value,
                        ),
                    )
                }
            }

            post("/media/confirm") {
                val principal = call.principal<JWTPrincipal>()!!
                val userIdRaw = principal.payload.getClaim("userId").asString()
                val userId =
                    userIdRaw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<MediaRecord>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val request = call.receive<ConfirmMediaRequest>()
                val messageId =
                    runCatching { UUID.fromString(request.messageId) }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<MediaRecord>("Invalid messageId", status = HttpStatusCode.BadRequest.value),
                        )
                val mediaId = UUID.randomUUID()

                val record =
                    try {
                        withDb { conn ->
                            conn.prepareStatement(
                                """
                                INSERT INTO media (id, message_id, uploader_id, object_name, mime_type, size_bytes, created_at)
                                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?)
                                """.trimIndent(),
                            ).use { ps ->
                                ps.setObject(1, mediaId)
                                ps.setObject(2, messageId)
                                ps.setObject(3, userId)
                                ps.setString(4, request.objectName)
                                ps.setString(5, request.mimeType)
                                if (request.sizeBytes != null) ps.setLong(6, request.sizeBytes) else ps.setNull(6, java.sql.Types.BIGINT)
                                ps.setTimestamp(7, Timestamp.from(Instant.now()))
                                ps.executeUpdate()
                            }
                        }
                        MediaRecord(
                            id = mediaId.toString(),
                            messageId = messageId.toString(),
                            uploaderId = userId.toString(),
                            objectName = request.objectName,
                            mimeType = request.mimeType,
                            sizeBytes = request.sizeBytes,
                            createdAt = Instant.now().toString(),
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<MediaRecord>("Failed to confirm media: ${e.message}", status = HttpStatusCode.BadRequest.value),
                        )
                        return@post
                    }
                call.respond<Result<MediaRecord>>(Result.success(record))
            }

            get("/media/{id}/download") {
                val principal = call.principal<JWTPrincipal>()!!
                val requester =
                    principal.payload.getClaim("userId").asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<DownloadMediaResponse>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val mediaId = call.parameters["id"].orEmpty()
                val mediaUuid =
                    runCatching { UUID.fromString(mediaId) }.getOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<DownloadMediaResponse>("Invalid media id", status = HttpStatusCode.BadRequest.value),
                        )
                val record = withDb { conn -> loadMedia(conn, mediaUuid) }
                if (record == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<DownloadMediaResponse>("Media not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@get
                }

                // Simple authz guard for now: uploader can always access.
                // In production, enforce conversation membership policy.
                if (record.uploaderId != requester.toString()) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        Result.error<DownloadMediaResponse>("Not authorized for this media", status = HttpStatusCode.Forbidden.value),
                    )
                    return@get
                }

                try {
                    val downloadUrl =
                        minioClient.getPresignedObjectUrl(
                            GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .`object`(record.objectName)
                                .expiry(15 * 60, TimeUnit.SECONDS)
                                .build(),
                        )
                    call.respond<Result<DownloadMediaResponse>>(
                        Result.success(
                            DownloadMediaResponse(
                                mediaId = mediaId,
                                downloadUrl = downloadUrl,
                                objectName = record.objectName,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        Result.error<DownloadMediaResponse>("Failed to generate download URL", status = HttpStatusCode.InternalServerError.value),
                    )
                }
            }
        }
    }
}

private fun loadMedia(conn: java.sql.Connection, mediaId: UUID): MediaRecord? {
    conn.prepareStatement(
        "SELECT id, message_id, uploader_id, object_name, mime_type, size_bytes, created_at FROM media WHERE id = ?::uuid",
    ).use { ps ->
        ps.setObject(1, mediaId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            return MediaRecord(
                id = rs.getObject("id").toString(),
                messageId = rs.getObject("message_id").toString(),
                uploaderId = rs.getObject("uploader_id").toString(),
                objectName = rs.getString("object_name"),
                mimeType = rs.getString("mime_type"),
                sizeBytes = rs.getLong("size_bytes").let { if (rs.wasNull()) null else it },
                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            )
        }
    }
}

@Serializable
data class PresignRequest(val extension: String, val contentType: String)

@Serializable
data class PresignResponse(val uploadUrl: String, val objectName: String)

@Serializable
data class ConfirmMediaRequest(
    val messageId: String,
    val objectName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class MediaRecord(
    val id: String,
    val messageId: String,
    val uploaderId: String,
    val objectName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val createdAt: String,
)

@Serializable
data class DownloadMediaResponse(
    val mediaId: String,
    val downloadUrl: String,
    val objectName: String,
)
