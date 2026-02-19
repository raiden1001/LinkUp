package com.simplogics.chat.service

import com.simplogics.chat.common.models.Message
import com.simplogics.chat.common.models.MessageType
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
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.Properties
import java.util.UUID

fun main() {
    embeddedServer(Netty, port = 8082, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/chat_platform"
    val dbUser = System.getenv("DB_USER") ?: "chat_user"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "chat_password"
    val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"

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

    val kafkaProps = Properties().apply {
        put("bootstrap.servers", kafkaBootstrap)
        put("key.serializer", StringSerializer::class.java.name)
        put("value.serializer", StringSerializer::class.java.name)
    }

    val producer = KafkaProducer<String, String>(kafkaProps)

    fun <T> withDb(block: (java.sql.Connection) -> T): T {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword).use(block)
    }

    fun parseUuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    fun resolveConversationUuid(channelId: String): UUID {
        return parseUuidOrNull(channelId) ?: UUID.nameUUIDFromBytes(channelId.toByteArray())
    }

    fun parseCurrentUserId(principal: JWTPrincipal): UUID? {
        val claim = principal.payload.getClaim("userId").asString()
        return claim?.let { parseUuidOrNull(it) }
    }

    fun currentUserRef(principal: JWTPrincipal): String {
        return principal.payload.getClaim("userId").asString() ?: principal.payload.getClaim("email").asString() ?: "unknown-user"
    }

    fun isMember(conn: java.sql.Connection, conversationId: UUID, userId: UUID): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM conversation_members WHERE conversation_id = ?::uuid AND user_id = ?::uuid LIMIT 1",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setObject(2, userId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    fun ensureConversationExists(conn: java.sql.Connection, conversationId: UUID, creatorId: UUID, requestedType: String, title: String?) {
        conn.prepareStatement("SELECT 1 FROM conversations WHERE id = ?::uuid").use { ps ->
            ps.setObject(1, conversationId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return
            }
        }
        conn.prepareStatement(
            "INSERT INTO conversations (id, type, title, created_by, created_at) VALUES (?::uuid, ?, ?, ?::uuid, ?)",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setString(2, requestedType)
            ps.setString(3, title)
            ps.setObject(4, creatorId)
            ps.setTimestamp(5, Timestamp(System.currentTimeMillis()))
            ps.executeUpdate()
        }
    }

    fun ensureMember(conn: java.sql.Connection, conversationId: UUID, userId: UUID, role: String = "MEMBER") {
        conn.prepareStatement(
            """
            INSERT INTO conversation_members (id, conversation_id, user_id, role, muted, joined_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, ?, false, ?)
            ON CONFLICT (conversation_id, user_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, conversationId)
            ps.setObject(3, userId)
            ps.setString(4, role)
            ps.setTimestamp(5, Timestamp(System.currentTimeMillis()))
            ps.executeUpdate()
        }
    }

    routing {
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")

        authenticate("auth-jwt") {
            post("/conversations") {
                val principal = call.principal<JWTPrincipal>()!!
                val creatorUuid =
                    parseCurrentUserId(principal)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<ConversationDetailsResponse>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val request = call.receive<CreateConversationRequest>()
                val conversationId = UUID.randomUUID()

                val response = withDb { conn ->
                    ensureConversationExists(conn, conversationId, creatorUuid, request.type, request.title)
                    ensureMember(conn, conversationId, creatorUuid, role = "ADMIN")
                    request.memberUserIds.orEmpty()
                        .mapNotNull { parseUuidOrNull(it) }
                        .filter { it != creatorUuid }
                        .distinct()
                        .forEach { ensureMember(conn, conversationId, it, role = "MEMBER") }

                    val conversation = loadConversation(conn, conversationId)
                    val members = loadConversationMembers(conn, conversationId)
                    ConversationDetailsResponse(conversation = conversation, members = members)
                }
                call.respond<Result<ConversationDetailsResponse>>(Result.success(response))
            }

            get("/conversations") {
                val principal = call.principal<JWTPrincipal>()!!
                val userUuid =
                    parseCurrentUserId(principal)
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<List<ConversationRecord>>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val visibleConversations = withDb { conn -> loadUserConversations(conn, userUuid) }
                call.respond<Result<List<ConversationRecord>>>(Result.success(visibleConversations))
            }

            get("/conversations/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userUuid =
                    parseCurrentUserId(principal)
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<ConversationDetailsResponse>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val conversationUuid =
                    parseUuidOrNull(call.parameters["id"].orEmpty())
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<ConversationDetailsResponse>("Invalid conversation id", status = HttpStatusCode.BadRequest.value),
                        )

                val response = withDb { conn ->
                    val conversation = loadConversationNullable(conn, conversationUuid)
                    if (conversation == null) return@withDb null
                    if (!isMember(conn, conversationUuid, userUuid) && conversation.createdBy != userUuid.toString()) {
                        return@withDb ConversationLookupResult.Forbidden
                    }
                    ConversationLookupResult.Found(
                        ConversationDetailsResponse(
                            conversation = conversation,
                            members = loadConversationMembers(conn, conversationUuid),
                        ),
                    )
                }
                when (response) {
                    null -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            Result.error<ConversationDetailsResponse>("Conversation not found", status = HttpStatusCode.NotFound.value),
                        )
                    }
                    ConversationLookupResult.Forbidden -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            Result.error<ConversationDetailsResponse>("Not a conversation member", status = HttpStatusCode.Forbidden.value),
                        )
                    }
                    is ConversationLookupResult.Found -> {
                        call.respond<Result<ConversationDetailsResponse>>(Result.success(response.data))
                    }
                }
            }

            post("/messages/send") {
                val principal = call.principal<JWTPrincipal>()
                val senderUuid =
                    principal?.let { parseCurrentUserId(it) }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<String>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )

                try {
                    val incomingMessage = call.receive<Message>()
                    val conversationUuid = resolveConversationUuid(incomingMessage.channelId)
                    val normalizedMessage = withDb { conn ->
                        ensureConversationExists(
                            conn = conn,
                            conversationId = conversationUuid,
                            creatorId = senderUuid,
                            requestedType = "DIRECT",
                            title = if (parseUuidOrNull(incomingMessage.channelId) == null) incomingMessage.channelId else null,
                        )
                        ensureMember(conn, conversationUuid, senderUuid, role = "ADMIN")
                        if (!isMember(conn, conversationUuid, senderUuid)) {
                            return@withDb null
                        }

                        val normalizedId = parseUuidOrNull(incomingMessage.id) ?: UUID.randomUUID()
                        val message =
                            incomingMessage.copy(
                                id = normalizedId.toString(),
                                senderId = senderUuid.toString(),
                                channelId = conversationUuid.toString(),
                                timestamp = if (incomingMessage.timestamp == 0L) System.currentTimeMillis() else incomingMessage.timestamp,
                            )
                        conn.prepareStatement(
                            """
                            INSERT INTO messages (id, conversation_id, sender_id, encrypted_payload, encrypted_data_key, sent_at, edited_at, expires_at)
                            VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setObject(1, UUID.fromString(message.id))
                            ps.setObject(2, conversationUuid)
                            ps.setObject(3, senderUuid)
                            ps.setString(4, message.encryptedPayload)
                            ps.setString(5, message.encryptedDataKey)
                            ps.setTimestamp(6, Timestamp(message.timestamp))
                            ps.setNull(7, java.sql.Types.TIMESTAMP)
                            ps.setNull(8, java.sql.Types.TIMESTAMP)
                            ps.executeUpdate()
                        }
                        message
                    }
                    if (normalizedMessage == null) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            Result.error<String>("Not a conversation member", status = HttpStatusCode.Forbidden.value),
                        )
                        return@post
                    }

                    val messageJson = json.encodeToString(Message.serializer(), normalizedMessage)
                    val record = ProducerRecord("chat-message-events", normalizedMessage.channelId, messageJson)
                    producer.send(record) { _, exception ->
                        if (exception != null) {
                            exception.printStackTrace()
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

            get("/conversations/{id}/messages") {
                val principal = call.principal<JWTPrincipal>()!!
                val userUuid =
                    parseCurrentUserId(principal)
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<List<StoredMessageRecord>>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val conversationUuid =
                    parseUuidOrNull(call.parameters["id"].orEmpty())
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<List<StoredMessageRecord>>("Invalid conversation id", status = HttpStatusCode.BadRequest.value),
                        )

                val result = withDb { conn ->
                    val conversation = loadConversationNullable(conn, conversationUuid) ?: return@withDb MessageListResult.NotFound
                    if (!isMember(conn, conversationUuid, userUuid) && conversation.createdBy != userUuid.toString()) {
                        return@withDb MessageListResult.Forbidden
                    }
                    MessageListResult.Found(loadMessages(conn, conversationUuid))
                }

                when (result) {
                    MessageListResult.NotFound -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            Result.error<List<StoredMessageRecord>>("Conversation not found", status = HttpStatusCode.NotFound.value),
                        )
                    }
                    MessageListResult.Forbidden -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            Result.error<List<StoredMessageRecord>>("Not a conversation member", status = HttpStatusCode.Forbidden.value),
                        )
                    }
                    is MessageListResult.Found -> {
                        call.respond<Result<List<StoredMessageRecord>>>(Result.success(result.messages))
                    }
                }
            }

            put("/messages/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userUuid =
                    parseCurrentUserId(principal)
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<StoredMessageRecord>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val messageUuid =
                    parseUuidOrNull(call.parameters["id"].orEmpty())
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<StoredMessageRecord>("Invalid message id", status = HttpStatusCode.BadRequest.value),
                        )
                val request = call.receive<UpdateMessageRequest>()

                val result = withDb { conn ->
                    val existing = loadMessageNullable(conn, messageUuid) ?: return@withDb UpdateResult.NotFound
                    if (existing.message.senderId != userUuid.toString()) return@withDb UpdateResult.Forbidden
                    conn.prepareStatement(
                        "UPDATE messages SET encrypted_payload = ?, encrypted_data_key = ?, edited_at = ? WHERE id = ?::uuid",
                    ).use { ps ->
                        ps.setString(1, request.encryptedPayload)
                        ps.setString(2, request.encryptedDataKey ?: existing.message.encryptedDataKey)
                        ps.setTimestamp(3, Timestamp(System.currentTimeMillis()))
                        ps.setObject(4, messageUuid)
                        ps.executeUpdate()
                    }
                    UpdateResult.Found(loadMessage(conn, messageUuid))
                }

                when (result) {
                    UpdateResult.NotFound -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            Result.error<StoredMessageRecord>("Message not found", status = HttpStatusCode.NotFound.value),
                        )
                    }
                    UpdateResult.Forbidden -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            Result.error<StoredMessageRecord>("Only sender can edit", status = HttpStatusCode.Forbidden.value),
                        )
                    }
                    is UpdateResult.Found -> {
                        call.respond<Result<StoredMessageRecord>>(Result.success(result.record))
                    }
                }
            }

            post("/messages/{id}/status") {
                val principal = call.principal<JWTPrincipal>()!!
                val userUuid =
                    parseCurrentUserId(principal)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<MessageStatusRecord>("JWT missing valid userId", status = HttpStatusCode.BadRequest.value),
                        )
                val messageUuid =
                    parseUuidOrNull(call.parameters["id"].orEmpty())
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            Result.error<MessageStatusRecord>("Invalid message id", status = HttpStatusCode.BadRequest.value),
                        )
                val request = call.receive<UpdateMessageStatusRequest>()

                val statusRecord = withDb { conn ->
                    val messageExists = loadMessageNullable(conn, messageUuid) != null
                    if (!messageExists) return@withDb null
                    val statusId = UUID.randomUUID()
                    conn.prepareStatement(
                        """
                        INSERT INTO message_status (id, message_id, user_id, status, updated_at)
                        VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?)
                        ON CONFLICT (message_id, user_id)
                        DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, statusId)
                        ps.setObject(2, messageUuid)
                        ps.setObject(3, userUuid)
                        ps.setString(4, request.status)
                        ps.setTimestamp(5, Timestamp(System.currentTimeMillis()))
                        ps.executeUpdate()
                    }
                    loadMessageStatus(conn, messageUuid, userUuid)
                }
                if (statusRecord == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        Result.error<MessageStatusRecord>("Message not found", status = HttpStatusCode.NotFound.value),
                    )
                    return@post
                }
                call.respond<Result<MessageStatusRecord>>(Result.success(statusRecord))
            }
        }
    }
}

private fun loadConversation(conn: java.sql.Connection, conversationId: UUID): ConversationRecord {
    return loadConversationNullable(conn, conversationId)
        ?: throw IllegalStateException("Conversation not found: $conversationId")
}

private fun loadConversationNullable(conn: java.sql.Connection, conversationId: UUID): ConversationRecord? {
    conn.prepareStatement(
        "SELECT id, type, title, created_by, created_at FROM conversations WHERE id = ?::uuid",
    ).use { ps ->
        ps.setObject(1, conversationId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            return ConversationRecord(
                id = rs.getObject("id").toString(),
                type = rs.getString("type"),
                title = rs.getString("title"),
                createdBy = rs.getObject("created_by")?.toString() ?: "",
                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            )
        }
    }
}

private fun loadConversationMembers(conn: java.sql.Connection, conversationId: UUID): List<ConversationMemberRecord> {
    conn.prepareStatement(
        """
        SELECT id, conversation_id, user_id, role, muted, joined_at
        FROM conversation_members
        WHERE conversation_id = ?::uuid
        ORDER BY joined_at ASC
        """.trimIndent(),
    ).use { ps ->
        ps.setObject(1, conversationId)
        ps.executeQuery().use { rs ->
            val list = mutableListOf<ConversationMemberRecord>()
            while (rs.next()) {
                list +=
                    ConversationMemberRecord(
                        id = rs.getObject("id").toString(),
                        conversationId = rs.getObject("conversation_id").toString(),
                        userId = rs.getObject("user_id").toString(),
                        role = rs.getString("role"),
                        muted = rs.getBoolean("muted"),
                        joinedAt = rs.getTimestamp("joined_at").toInstant().toString(),
                    )
            }
            return list
        }
    }
}

private fun loadUserConversations(conn: java.sql.Connection, userId: UUID): List<ConversationRecord> {
    conn.prepareStatement(
        """
        SELECT c.id, c.type, c.title, c.created_by, c.created_at
        FROM conversations c
        JOIN conversation_members cm ON cm.conversation_id = c.id
        WHERE cm.user_id = ?::uuid
        ORDER BY c.created_at DESC
        """.trimIndent(),
    ).use { ps ->
        ps.setObject(1, userId)
        ps.executeQuery().use { rs ->
            val list = mutableListOf<ConversationRecord>()
            while (rs.next()) {
                list +=
                    ConversationRecord(
                        id = rs.getObject("id").toString(),
                        type = rs.getString("type"),
                        title = rs.getString("title"),
                        createdBy = rs.getObject("created_by")?.toString() ?: "",
                        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                    )
            }
            return list
        }
    }
}

private fun loadMessages(conn: java.sql.Connection, conversationId: UUID): List<StoredMessageRecord> {
    conn.prepareStatement(
        """
        SELECT id, conversation_id, sender_id, encrypted_payload, encrypted_data_key, sent_at, edited_at, expires_at
        FROM messages
        WHERE conversation_id = ?::uuid
        ORDER BY sent_at ASC
        """.trimIndent(),
    ).use { ps ->
        ps.setObject(1, conversationId)
        ps.executeQuery().use { rs ->
            val list = mutableListOf<StoredMessageRecord>()
            while (rs.next()) {
                val sentAt = rs.getTimestamp("sent_at")
                list +=
                    StoredMessageRecord(
                        message =
                            Message(
                                id = rs.getObject("id").toString(),
                                senderId = rs.getObject("sender_id").toString(),
                                channelId = rs.getObject("conversation_id").toString(),
                                encryptedPayload = rs.getString("encrypted_payload"),
                                encryptedDataKey = rs.getString("encrypted_data_key"),
                                timestamp = sentAt.time,
                                type = MessageType.TEXT,
                            ),
                        editedAt = rs.getTimestamp("edited_at")?.toInstant()?.toString(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
                    )
            }
            return list
        }
    }
}

private fun loadMessage(conn: java.sql.Connection, messageId: UUID): StoredMessageRecord {
    return loadMessageNullable(conn, messageId)
        ?: throw IllegalStateException("Message not found: $messageId")
}

private fun loadMessageNullable(conn: java.sql.Connection, messageId: UUID): StoredMessageRecord? {
    conn.prepareStatement(
        """
        SELECT id, conversation_id, sender_id, encrypted_payload, encrypted_data_key, sent_at, edited_at, expires_at
        FROM messages
        WHERE id = ?::uuid
        """.trimIndent(),
    ).use { ps ->
        ps.setObject(1, messageId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            val sentAt = rs.getTimestamp("sent_at")
            return StoredMessageRecord(
                message =
                    Message(
                        id = rs.getObject("id").toString(),
                        senderId = rs.getObject("sender_id").toString(),
                        channelId = rs.getObject("conversation_id").toString(),
                        encryptedPayload = rs.getString("encrypted_payload"),
                        encryptedDataKey = rs.getString("encrypted_data_key"),
                        timestamp = sentAt.time,
                        type = MessageType.TEXT,
                    ),
                editedAt = rs.getTimestamp("edited_at")?.toInstant()?.toString(),
                expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
            )
        }
    }
}

private fun loadMessageStatus(conn: java.sql.Connection, messageId: UUID, userId: UUID): MessageStatusRecord {
    conn.prepareStatement(
        "SELECT id, message_id, user_id, status, updated_at FROM message_status WHERE message_id = ?::uuid AND user_id = ?::uuid",
    ).use { ps ->
        ps.setObject(1, messageId)
        ps.setObject(2, userId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) {
                throw IllegalStateException("Message status not found for message=$messageId user=$userId")
            }
            return MessageStatusRecord(
                id = rs.getObject("id").toString(),
                messageId = rs.getObject("message_id").toString(),
                userId = rs.getObject("user_id").toString(),
                status = rs.getString("status"),
                updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
            )
        }
    }
}

private sealed class ConversationLookupResult {
    data object Forbidden : ConversationLookupResult()

    data class Found(val data: ConversationDetailsResponse) : ConversationLookupResult()
}

private sealed class MessageListResult {
    data object NotFound : MessageListResult()

    data object Forbidden : MessageListResult()

    data class Found(val messages: List<StoredMessageRecord>) : MessageListResult()
}

private sealed class UpdateResult {
    data object NotFound : UpdateResult()

    data object Forbidden : UpdateResult()

    data class Found(val record: StoredMessageRecord) : UpdateResult()
}

@Serializable
data class CreateConversationRequest(
    val type: String,
    val title: String? = null,
    val memberUserIds: List<String>? = null,
)

@Serializable
data class ConversationRecord(
    val id: String,
    val type: String,
    val title: String?,
    val createdBy: String,
    val createdAt: String,
)

@Serializable
data class ConversationMemberRecord(
    val id: String,
    val conversationId: String,
    val userId: String,
    val role: String,
    val muted: Boolean,
    val joinedAt: String,
)

@Serializable
data class ConversationDetailsResponse(
    val conversation: ConversationRecord,
    val members: List<ConversationMemberRecord>,
)

@Serializable
data class StoredMessageRecord(
    val message: Message,
    val editedAt: String?,
    val expiresAt: String?,
)

@Serializable
data class UpdateMessageRequest(
    val encryptedPayload: String,
    val encryptedDataKey: String? = null,
)

@Serializable
data class UpdateMessageStatusRequest(
    val status: String,
)

@Serializable
data class MessageStatusRecord(
    val id: String,
    val messageId: String,
    val userId: String,
    val status: String,
    val updatedAt: String,
)
