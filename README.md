# LinkUp

A real-time, secure internal chat application for Simplogics, built with Kotlin Multiplatform, mainting strict architectural standards.

## Architecture

- **Monorepo**: All code in this repository.
- **Languages**: 100% Kotlin.
- **Backend**: Ktor Services + Kafka + MinIO.
- **Mobile**: Android (Compose Multiplatform).
- **Security**: End-to-End Encryption (AES + RSA). User keys stored in Android Keystore.
- **Linting**: Strict `ktlint` enforcement via `build-logic` convention plugins.

## Prerequisites

- JDK 17+
- Docker & Docker Compose
- Android Studio Iguana+ (for Mobile)

## Getting Started

### 1. Infrastructure
Start the required services (Kafka, Zookeeper, MinIO):
```bash
cd infra/docker
docker-compose up -d
```

### 2. Backend Services
Run the services independently or via Gradle:
```bash
./gradlew :backend:auth-service:run
./gradlew :backend:chat-service:run
./gradlew :backend:websocket-gateway:run
./gradlew :backend:media-service:run
```
*Note: Ensure you have a valid JDK environment.*

### 3. Mobile App
Open the project in Android Studio and run the `androidApp` configuration on an emulator or device.
- Login with any email ending in `@simplogics.com`.
- Requires backend services to be running (configure `NetworkClient` IP if not `localhost`).

## Module Structure

- `common`: Shared KMP code (Models, Crypto, Networking).
- `backend`:
  - `auth-service`: JWT Authentication.
  - `chat-service`: REST API + Kafka Producer.
  - `websocket-gateway`: Real-time delivery via WebSockets + Kafka Consumer.
  - `media-service`: MinIO Presigned URLs.
- `mobile`:
  - `shared-ui`: Compose Multiplatform UI.
  - `androidApp`: Android entry point.
- `build-logic`: Custom Gradle convention plugins.

## Verification
Run clean build to verify linting and compilation:
```bash
./gradlew clean build
```
