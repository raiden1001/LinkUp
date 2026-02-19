#!/usr/bin/env bash
set -euo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:8081}"
CHAT_URL="${CHAT_URL:-http://localhost:8082}"
MEDIA_URL="${MEDIA_URL:-http://localhost:8084}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

EMAIL="api-test-$RANDOM@simplogics.com"
DEVICE_ID="device-$(date +%s)"
NOW_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

echo "==> Using test email: ${EMAIL}"

call_api() {
  local method="$1"
  local url="$2"
  local payload_file="${3:-}"
  local token="${4:-}"
  local out_file="$5"

  if [[ -n "${payload_file}" && -n "${token}" ]]; then
    curl -sS -X "${method}" "${url}" \
      -H "Authorization: Bearer ${token}" \
      -H "Content-Type: application/json" \
      --data @"${payload_file}" \
      > "${out_file}"
  elif [[ -n "${payload_file}" ]]; then
    curl -sS -X "${method}" "${url}" \
      -H "Content-Type: application/json" \
      --data @"${payload_file}" \
      > "${out_file}"
  elif [[ -n "${token}" ]]; then
    curl -sS -X "${method}" "${url}" \
      -H "Authorization: Bearer ${token}" \
      > "${out_file}"
  else
    curl -sS -X "${method}" "${url}" > "${out_file}"
  fi
}

assert_ok() {
  local file="$1"
  local label="$2"
  python3 - "$file" "$label" <<'PY'
import json, sys
path, label = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)
if data.get("ok") is not True:
    print(f"[FAIL] {label}: {json.dumps(data, indent=2)}")
    sys.exit(1)
print(f"[PASS] {label}")
PY
}

extract_json_field() {
  local file="$1"
  local expr="$2"
  python3 - "$file" "$expr" <<'PY'
import json, sys
path, expr = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)
value = data
for part in expr.split("."):
    if part == "":
        continue
    if isinstance(value, dict):
        value = value.get(part)
    elif isinstance(value, list):
        value = value[int(part)]
    else:
        value = None
        break
if value is None:
    sys.exit(2)
print(value)
PY
}

echo "==> 1) POST /login"
cat > "${TMP_DIR}/login.json" <<JSON
{"email":"${EMAIL}","password":"test","deviceId":"${DEVICE_ID}"}
JSON
call_api "POST" "${AUTH_URL}/login" "${TMP_DIR}/login.json" "" "${TMP_DIR}/login.out.json"
assert_ok "${TMP_DIR}/login.out.json" "auth login"
TOKEN="$(extract_json_field "${TMP_DIR}/login.out.json" "data.token")"
USER_ID="$(extract_json_field "${TMP_DIR}/login.out.json" "data.userId")"

echo "==> 2) GET /me"
call_api "GET" "${AUTH_URL}/me" "" "${TOKEN}" "${TMP_DIR}/me.out.json"
assert_ok "${TMP_DIR}/me.out.json" "auth me"

echo "==> 3) POST /keys/me"
cat > "${TMP_DIR}/keys_me.json" <<JSON
{"publicKeyBase64":"TEST_PUBLIC_KEY_${NOW_MS}","algorithm":"RSA"}
JSON
call_api "POST" "${AUTH_URL}/keys/me" "${TMP_DIR}/keys_me.json" "${TOKEN}" "${TMP_DIR}/keys_me.out.json"
assert_ok "${TMP_DIR}/keys_me.out.json" "auth keys me"

echo "==> 4) GET /keys/{userId}"
call_api "GET" "${AUTH_URL}/keys/${USER_ID}" "" "${TOKEN}" "${TMP_DIR}/keys_user.out.json"
assert_ok "${TMP_DIR}/keys_user.out.json" "auth keys by userId"

echo "==> 5) POST /conversations"
cat > "${TMP_DIR}/conversation_create.json" <<JSON
{"type":"DIRECT","title":"api-test-${NOW_MS}","memberUserIds":["${USER_ID}"]}
JSON
call_api "POST" "${CHAT_URL}/conversations" "${TMP_DIR}/conversation_create.json" "${TOKEN}" "${TMP_DIR}/conversation_create.out.json"
assert_ok "${TMP_DIR}/conversation_create.out.json" "chat create conversation"
CONVERSATION_ID="$(extract_json_field "${TMP_DIR}/conversation_create.out.json" "data.conversation.id")"

echo "==> 6) GET /conversations"
call_api "GET" "${CHAT_URL}/conversations" "" "${TOKEN}" "${TMP_DIR}/conversations_list.out.json"
assert_ok "${TMP_DIR}/conversations_list.out.json" "chat list conversations"

echo "==> 7) GET /conversations/{id}"
call_api "GET" "${CHAT_URL}/conversations/${CONVERSATION_ID}" "" "${TOKEN}" "${TMP_DIR}/conversation_get.out.json"
assert_ok "${TMP_DIR}/conversation_get.out.json" "chat get conversation"

echo "==> 8) POST /messages/send"
cat > "${TMP_DIR}/message_send.json" <<JSON
{
  "id":"",
  "senderId":"${USER_ID}",
  "channelId":"${CONVERSATION_ID}",
  "encryptedPayload":"ZW5jcnlwdGVkLXBheWxvYWQt${NOW_MS}",
  "encryptedDataKey":"ZW5jcnlwdGVkLWtleS0${NOW_MS}",
  "timestamp":${NOW_MS},
  "type":"TEXT"
}
JSON
call_api "POST" "${CHAT_URL}/messages/send" "${TMP_DIR}/message_send.json" "${TOKEN}" "${TMP_DIR}/message_send.out.json"
assert_ok "${TMP_DIR}/message_send.out.json" "chat send message"

echo "==> 9) GET /conversations/{id}/messages"
call_api "GET" "${CHAT_URL}/conversations/${CONVERSATION_ID}/messages" "" "${TOKEN}" "${TMP_DIR}/messages_list.out.json"
assert_ok "${TMP_DIR}/messages_list.out.json" "chat list messages"
MESSAGE_ID="$(extract_json_field "${TMP_DIR}/messages_list.out.json" "data.0.message.id")"

echo "==> 10) PUT /messages/{id}"
cat > "${TMP_DIR}/message_edit.json" <<JSON
{
  "encryptedPayload":"ZW5jcnlwdGVkLXBheWxvYWQtZWRpdGVkLQ==",
  "encryptedDataKey":"ZW5jcnlwdGVkLWtleS1lZGl0ZWQ="
}
JSON
call_api "PUT" "${CHAT_URL}/messages/${MESSAGE_ID}" "${TMP_DIR}/message_edit.json" "${TOKEN}" "${TMP_DIR}/message_edit.out.json"
assert_ok "${TMP_DIR}/message_edit.out.json" "chat edit message"

echo "==> 11) POST /messages/{id}/status"
cat > "${TMP_DIR}/message_status.json" <<JSON
{"status":"READ"}
JSON
call_api "POST" "${CHAT_URL}/messages/${MESSAGE_ID}/status" "${TMP_DIR}/message_status.json" "${TOKEN}" "${TMP_DIR}/message_status.out.json"
assert_ok "${TMP_DIR}/message_status.out.json" "chat message status"

echo "==> 12) POST /presign"
cat > "${TMP_DIR}/presign.json" <<JSON
{"extension":"png","contentType":"image/png"}
JSON
call_api "POST" "${MEDIA_URL}/presign" "${TMP_DIR}/presign.json" "${TOKEN}" "${TMP_DIR}/presign.out.json"
assert_ok "${TMP_DIR}/presign.out.json" "media presign"
OBJECT_NAME="$(extract_json_field "${TMP_DIR}/presign.out.json" "data.objectName")"

echo "==> 13) POST /media/confirm"
cat > "${TMP_DIR}/media_confirm.json" <<JSON
{
  "messageId":"${MESSAGE_ID}",
  "objectName":"${OBJECT_NAME}",
  "mimeType":"image/png",
  "sizeBytes":12345
}
JSON
call_api "POST" "${MEDIA_URL}/media/confirm" "${TMP_DIR}/media_confirm.json" "${TOKEN}" "${TMP_DIR}/media_confirm.out.json"
assert_ok "${TMP_DIR}/media_confirm.out.json" "media confirm"
MEDIA_ID="$(extract_json_field "${TMP_DIR}/media_confirm.out.json" "data.id")"

echo "==> 14) GET /media/{id}/download"
call_api "GET" "${MEDIA_URL}/media/${MEDIA_ID}/download" "" "${TOKEN}" "${TMP_DIR}/media_download.out.json"
assert_ok "${TMP_DIR}/media_download.out.json" "media download url"

echo "==> All API checks passed."
