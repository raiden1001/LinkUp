# API Test Scripts

This folder contains runnable scripts to validate backend APIs using live generated test data.

## Script

- `run_all.sh`: End-to-end verification for:
  - Auth: `/login`, `/me`, `/keys/me`, `/keys/{userId}`
  - Chat: `/conversations`, `/conversations/{id}`, `/messages/send`, `/messages/{id}`, `/messages/{id}/status`
  - Media: `/presign`, `/media/confirm`, `/media/{id}/download`

## Usage

From repository root:

```bash
chmod +x scripts/api-tests/run_all.sh
scripts/api-tests/run_all.sh
```

Optional custom base URLs:

```bash
AUTH_URL=http://localhost:8081 \
CHAT_URL=http://localhost:8082 \
MEDIA_URL=http://localhost:8084 \
scripts/api-tests/run_all.sh
```

## Prerequisites

- Services running:
  - auth-service on `8081`
  - chat-service on `8082`
  - media-service on `8084`
- Docker infra running for dependencies:
  - Kafka
  - MinIO
