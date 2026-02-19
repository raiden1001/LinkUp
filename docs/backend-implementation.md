# Backend Implementation Document

## Overview

This backend is organized as multiple Kotlin/Ktor microservices. Services communicate through HTTP, WebSocket, and Kafka event streaming.

Current service modules with implementation:

- `backend/auth-service` (port `8081`)
- `backend/chat-service` (port `8082`)
- `backend/websocket-gateway` (port `8083`)
- `backend/media-service` (port `8084`)
- `backend/kafka-workers` (consumer/background worker)

Infrastructure currently containerized in `infra/docker/docker-compose.yml`:

- Zookeeper (`2181`)
- Kafka (`9092`)
- MinIO (`9000`, console `9001`)
- PostgreSQL (`5433` host -> `5432` container)

---

## Common API Contract

All HTTP services use a shared response envelope:

```json
{
  "ok": true,
  "data": {},
  "error": null
}
```

Error shape:

```json
{
  "ok": false,
  "data": null,
  "error": {
    "message": "Not found",
    "code": null,
    "status": 404
  }
}
```

This comes from `common/src/commonMain/kotlin/com/simplogics/chat/common/result/Result.kt`.

---

## Authentication and JWT

All protected routes across services use JWT validation with these current values:

- Secret: `secret`
- Issuer: `http://0.0.0.0:8081/`
- Audience: `chat-users`
- Realm: `Access to Chat`

Token contains claim:

- `email` (string)

Important: these values are hardcoded today and should move to environment/config for production.

---

## Service: Auth Service (`backend/auth-service`, port `8081`)

### Purpose

- User login (domain-restricted demo login)
- JWT issuance
- Identity introspection (`/me`)
- Key directory APIs for E2EE public-key discovery

### Base URL

- `http://localhost:8081`

### Endpoints

#### `POST /login`

Authenticates a user by email domain and returns JWT.

- Auth required: No
- Request body:

```json
{
  "email": "user@simplogics.com",
  "password": "optional"
}
```

- Validation:
  - `email` must end with `@simplogics.com`
- Success response (`200`):

```json
{
  "ok": true,
  "data": {
    "token": "<jwt>",
    "email": "user@simplogics.com"
  },
  "error": null
}
```

- Failure responses:
  - `403` if non-`@simplogics.com` domain
  - `400` invalid body
  - `500` unhandled internal error

#### `GET /me`

Returns email from JWT principal.

- Auth required: Yes (`Authorization: Bearer <token>`)
- Success response (`200`):

```json
{
  "ok": true,
  "data": {
    "email": "user@simplogics.com"
  },
  "error": null
}
```

- Failure responses:
  - `401` unauthorized

#### `POST /keys/me`

Upserts authenticated user's public key in key directory.

- Auth required: Yes
- Request body:

```json
{
  "publicKeyBase64": "BASE64_PUBLIC_KEY",
  "keyId": "optional-key-id",
  "algorithm": "RSA"
}
```

- Success response (`200`): `Result<PublicKeyRecord>`

#### `GET /keys/{userId}`

Returns active key for given `userId` (or email alias fallback).

- Auth required: Yes
- Success response (`200`): `Result<PublicKeyRecord>`
- Failure responses: `404` not found

#### `GET /users/{userId}/keys/active`

Compatibility endpoint used by current mobile key-fetch flow.

- Auth required: Yes
- Success response (`200`): `Result<PublicKeyRecord>`

### Global error handling

- `404` -> `Result.error("Not found", status=404)`
- `400` for content transformation failures
- `500` for uncaught exceptions

---

## Service: Chat Service (`backend/chat-service`, port `8082`)

### Purpose

- Accept encrypted message payload from client
- Publish message event to Kafka topic `chat-message-events`

### Base URL

- `http://localhost:8082`

### Endpoints

#### `POST /conversations`

Creates a conversation and membership entries.

- Auth required: Yes
- Request:

```json
{
  "type": "DIRECT",
  "title": "optional",
  "memberUserIds": ["user-id-2"]
}
```

#### `GET /conversations`

Returns conversations visible to authenticated user.

- Auth required: Yes

#### `GET /conversations/{id}`

Returns conversation details + members.

- Auth required: Yes
- Requires membership

#### `POST /messages/send`

Queues a message to Kafka.

- Auth required: Yes
- Request model: `Message` from `common/models/Message.kt`

```json
{
  "id": "string",
  "senderId": "string",
  "channelId": "string",
  "encryptedPayload": "base64",
  "encryptedDataKey": "base64",
  "timestamp": 1739800000000,
  "type": "TEXT"
}
```

- Behavior:
  - Reads JWT principal email.
  - Receives message JSON.
  - Serializes message.
  - Publishes `ProducerRecord("chat-message-events", message.channelId, messageJson)`.
  - Kafka key = `channelId` (used for partition affinity).

- Success response (`200`):

```json
{
  "ok": true,
  "data": "Message queued",
  "error": null
}
```

- Failure responses:
  - `401` unauthorized
  - `400` malformed request/message body
  - `500` uncaught internal errors

#### `GET /conversations/{id}/messages`

Lists stored messages for a conversation.

- Auth required: Yes
- Requires membership

#### `PUT /messages/{id}`

Edits encrypted payload of message (sender only).

- Auth required: Yes

#### `POST /messages/{id}/status`

Stores per-user message status (`SENT`, `DELIVERED`, `READ`).

- Auth required: Yes

### Kafka configuration (current)

- Bootstrap servers: `localhost:9092`
- Key serializer: `StringSerializer`
- Value serializer: `StringSerializer`

---

## Service: WebSocket Gateway (`backend/websocket-gateway`, port `8083`)

### Purpose

- Maintains live WebSocket connections
- Manages channel subscriptions in memory
- Consumes Kafka `chat-message-events`
- Pushes message events to subscribed clients

### WebSocket Endpoint

#### `GET ws://localhost:8083/ws`

- Auth required: Yes (JWT during WebSocket auth handshake)
- On successful auth, connection remains active.
- If missing email claim, server closes with policy violation.

### WebSocket frame protocol

Client -> server supported frames (`SocketFrame`):

- `Subscribe(channelId)`
- `Unsubscribe(channelId)`

Server -> client frames:

- `IncomingMessage(message)`
- `Error(reason)` (mostly from client side path in current project; gateway currently primarily emits incoming messages)

### Subscription model

- In-memory map:
  - `channelId -> Set<WebSocketSession>`
- No persistent subscription store
- On disconnect, session is removed from all channel sets

### Kafka consumer behavior

- Topic: `chat-message-events`
- Group: `websocket-gateway-group`
- Poll interval: 100 ms
- For each record:
  - `key` = channel id
  - `value` = serialized `Message`
  - Deserialize and forward to all sessions subscribed to that channel

### Operational notes

- Subscription state is lost on gateway restart.
- Scaling multiple gateway instances requires shared subscription/state strategy (not implemented yet).

---

## Service: Media Service (`backend/media-service`, port `8084`)

### Purpose

- Generates MinIO presigned upload URLs for client-side direct upload

### Base URL

- `http://localhost:8084`

### Endpoints

#### `POST /presign`

Returns a presigned `PUT` URL and generated object name.

- Auth required: Yes
- Request body:

```json
{
  "extension": "png",
  "contentType": "image/png"
}
```

- Behavior:
  - Generates object key: `uploads/<uuid>.<extension>`
  - Creates MinIO presigned PUT URL
  - Expiry: 1 hour

- Success response (`200`):

```json
{
  "ok": true,
  "data": {
    "uploadUrl": "http://...",
    "objectName": "uploads/<uuid>.png"
  },
  "error": null
}
```

- Failure responses:
  - `401` unauthorized
  - `400` invalid request
  - `500` URL generation failure/internal error

#### `POST /media/confirm`

Persists uploaded media metadata.

- Auth required: Yes
- Request:

```json
{
  "messageId": "message-id",
  "objectName": "uploads/<uuid>.png",
  "mimeType": "image/png",
  "sizeBytes": 12345
}
```

#### `GET /media/{id}/download`

Returns presigned GET URL for media object.

- Auth required: Yes
- Current authz: uploader-only guard in service

### MinIO config (current)

- Endpoint: `http://localhost:9000`
- Credentials: `minioadmin` / `minioadmin`
- Bucket: `chat-images`

---

## Service: Kafka Workers (`backend/kafka-workers`)

### Purpose

- Background consumer for `chat-message-events`
- Simulates asynchronous notification processing

### Behavior

- Kafka group: `chat-workers-group`
- Poll interval: 1000 ms
- For each event:
  - Logs received key/value
  - Launches coroutine simulating processing (`delay(500)`)
  - Logs success/failure

### Public API

- No HTTP or WebSocket endpoints
- Internal worker process only

---

## Message and Realtime Data Models

### `Message`

- `id: String`
- `senderId: String`
- `channelId: String`
- `encryptedPayload: String` (Base64)
- `encryptedDataKey: String` (Base64)
- `timestamp: Long`
- `type: MessageType` (`TEXT`, `IMAGE_URL`, `SYSTEM`)

### `SocketFrame`

Sealed type for websocket communication:

- `Subscribe(channelId: String)`
- `Unsubscribe(channelId: String)`
- `IncomingMessage(message: Message)`
- `Error(reason: String)`

---

## Database and Migrations

Schema migration added:

- `infra/db/migrations/V1__initial_schema.sql`

This includes tables for:

- users
- auth sessions
- login audit logs
- conversations
- conversation members
- messages
- media
- message statuses
- reactions
- conversation last message pointer

PostgreSQL container:

- Host: `localhost`
- Port: `5433`
- DB: `chat_platform`
- User: `chat_user`
- Password: `chat_password`

---

## Verification Script

Runnable API verification script (live generated test data):

- `scripts/api-tests/run_all.sh`

It validates:

- Auth: `/login`, `/me`, `/keys/me`, `/keys/{userId}`
- Chat: `/conversations`, `/conversations/{id}`, `/messages/send`, `/conversations/{id}/messages`, `/messages/{id}`, `/messages/{id}/status`
- Media: `/presign`, `/media/confirm`, `/media/{id}/download`

## Current Gaps and Important Notes

- Hardcoded secrets and service URLs
- PostgreSQL-backed repositories are implemented in auth/chat/media services; websocket subscriptions remain in-memory by design
- No API versioning
- No OpenAPI/Swagger spec
- Minimal authorization rules beyond JWT audience/issuer

---

## Suggested Next Backend Additions

1. Implement key directory endpoints in auth or dedicated key service.
2. Add transaction management and connection pooling (HikariCP) for production load.
3. Externalize all configs (`JWT_SECRET`, Kafka, MinIO, DB URLs, ports).
4. Add health endpoints (`/health`, `/ready`) per service.
5. Add OpenAPI documentation generation for HTTP services.

---

## 8-12 Week Scalability Roadmap (Target: 2L+ Concurrent Users)

This roadmap is implementation-focused and split by week, with service-by-service tasks and measurable acceptance criteria.

### Assumed Target SLOs

- API p95 latency: `< 300ms` (non-media control APIs)
- Message enqueue p95 (`/messages/send`): `< 200ms`
- WebSocket delivery p95 (broker to client): `< 1.5s`
- Error rate: `< 1%` under peak test
- Reconnect success after transient restart: `> 99%` within 60s

### Week 1-2: Foundation and Runtime Hardening

#### Cross-Service (All Services)

- **Tasks**
  - Introduce shared DB utility module (config + lifecycle + query helper wrappers).
  - Add HikariCP connection pooling to auth/chat/media services.
  - Externalize service configs to env vars (JWT, DB, Kafka, MinIO, ports).
  - Add `/health` and `/ready` endpoints in each HTTP service.
  - Add structured logging and correlation id propagation.
- **Acceptance criteria**
  - All services boot with only env-driven config (no localhost/hardcoded secrets in runtime path).
  - `/ready` fails when dependencies are unavailable (DB/Kafka/MinIO).
  - DB connections are pooled and visible in logs/metrics.

#### Auth Service

- **Tasks**
  - Wrap login/session/audit writes in DB transactions.
  - Add unique and index validations in migration follow-up scripts as needed.
- **Acceptance criteria**
  - No partial writes for login flow under forced fault injection.

#### Chat Service

- **Tasks**
  - Wrap conversation creation and message status transitions in transactions.
  - Add explicit DB error mapping (constraint violations -> 4xx where appropriate).
- **Acceptance criteria**
  - No orphan `conversation_members` or `message_status` rows in fault tests.

#### Media Service

- **Tasks**
  - Harden media confirm flow with transactional metadata persistence.
  - Add explicit uploader/message existence validation and response mapping.
- **Acceptance criteria**
  - Invalid `messageId` and authz cases produce deterministic API error responses.

---

### Week 3-4: WebSocket and Messaging Scale Core

#### WebSocket Gateway

- **Tasks**
  - Move subscription/presence state from in-memory map to Redis.
  - Add multi-instance-safe subscription fanout model.
  - Add graceful reconnect handling and stale session cleanup worker.
- **Acceptance criteria**
  - With 2+ gateway instances, subscriptions/delivery work across instances.
  - Gateway restart preserves system correctness (clients reconnect + receive new events).

#### Chat Service + Kafka

- **Tasks**
  - Tune Kafka producer settings for reliability/throughput (`acks`, retries, batching).
  - Standardize event schema version field in message events.
- **Acceptance criteria**
  - No message loss in controlled broker restart tests.
  - Consumer lag remains within threshold during 10x baseline traffic replay.

#### Infrastructure

- **Tasks**
  - Move from single broker assumptions to 3-broker Kafka deployment plan.
  - Add topic partition strategy document (`chat-message-events` partitioning key and count).
- **Acceptance criteria**
  - Topic replication and broker failover tested successfully in staging.

---

### Week 5-6: Data Layer and Read Scalability

#### PostgreSQL

- **Tasks**
  - Add/verify indexes for high-frequency query paths:
    - `messages(conversation_id, sent_at)`
    - `conversation_members(user_id, conversation_id)`
    - `message_status(message_id, user_id)`
  - Introduce read replica strategy for read-heavy endpoints.
  - Prepare partition strategy for `messages` growth.
- **Acceptance criteria**
  - Query plans show index usage for top-10 hot queries.
  - Read endpoints sustain target RPS with p95 under SLO in staging.

#### Auth Service

- **Tasks**
  - Add key-directory caching (short TTL) for frequent key lookups.
- **Acceptance criteria**
  - Key lookup p95 improved and DB query count reduced measurably.

#### Chat Service

- **Tasks**
  - Add pagination and cursor-based reads for message history endpoints.
- **Acceptance criteria**
  - Message listing remains within SLO for large conversations (100k+ messages).

---

### Week 7-8: Security, Abuse Controls, and Operational Safety

#### Cross-Service Security

- **Tasks**
  - Rotate JWT secrets via secret manager (not static config).
  - Add rate limiting per user/IP for sensitive endpoints.
  - Add authz checks review for media download and conversation access.
- **Acceptance criteria**
  - Rate-limit rules trigger correctly under abuse simulation.
  - Secret rotation runbook tested in staging without downtime.

#### Auth + Key Directory

- **Tasks**
  - Add key versioning metadata and revocation semantics.
  - Add audit trail endpoint/logging for key changes.
- **Acceptance criteria**
  - Key update and revocation workflows are traceable and recoverable.

---

### Week 9-10: Observability, SLOs, and Resilience

#### Observability (All Services)

- **Tasks**
  - Add metrics: latency percentiles, error rates, DB pool saturation, Kafka lag, WS active connections.
  - Add distributed tracing across auth -> chat -> gateway path.
  - Add dashboards + alert policies tied to SLOs.
- **Acceptance criteria**
  - On-call dashboard can identify latency source within 5 minutes.
  - Alerts fire for synthetic incidents and route correctly.

#### Reliability

- **Tasks**
  - Add graceful shutdown hooks (drain websocket sessions, flush producer).
  - Add retry/backoff policies with idempotency for critical writes.
- **Acceptance criteria**
  - Rolling restart causes no sustained error spike beyond agreed error budget.

---

### Week 11-12: Full-Scale Validation and Launch Readiness

#### Performance Testing

- **Tasks**
  - Run staged concurrency tests:
    - 20k -> 50k -> 1L -> 2L concurrent
  - Mixed workload profile: login bursts, conversation reads, message sends, websocket fanout, media flows.
  - Run chaos scenarios: broker loss, DB failover, gateway node kill.
- **Acceptance criteria**
  - Meets defined SLOs at target stage before progressing.
  - No data integrity regressions in post-test audits.
  - Recovery time objectives met for injected failures.

#### Go-Live Readiness

- **Tasks**
  - Final runbooks: incident response, rollback, scaling, key compromise procedure.
  - Capacity and cost envelope signed off.
- **Acceptance criteria**
  - Production readiness review signed by backend + SRE + security stakeholders.

---

## Service-by-Service Deliverables Checklist

### Auth Service

- Transactional login/session/audit writes
- Key directory lifecycle (versioning/revocation)
- Rate limiting and audit observability
- Readiness endpoint dependency checks

### Chat Service

- Transactional conversation/message/status writes
- Kafka producer hardening and event versioning
- Message read pagination and query optimization
- Idempotency and failure-safe retry strategy

### WebSocket Gateway

- Redis-backed subscription/presence state
- Multi-instance fanout correctness
- Reconnect/resubscribe robustness
- Connection and delivery metrics

### Media Service

- Metadata persistence with strict authz checks
- Presign/download lifecycle validation
- Bucket/object health monitoring
- Abuse protections for media endpoints

### Kafka Workers

- Consumer lag monitoring and autoscaling policy
- Dead-letter/retry strategy for failed processing
- Delivery processing idempotency

### Platform/Infra

- Kafka multi-broker, replicated topics
- PostgreSQL high availability + replicas
- Secrets manager integration
- CI/CD gates with load and smoke criteria
