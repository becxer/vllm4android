#!/usr/bin/env bash
# End-to-end smoke test against a running vllm4android server.
#
# Prereqs:
#   - Server running on a device or emulator (Start in the app UI)
#   - adb forward tcp:8080 tcp:8080  (or set BASE_URL to the device's LAN IP)
#   - jq installed locally
#
# Usage:
#   scripts/smoke.sh
#   BASE_URL=http://192.168.1.10:8080 scripts/smoke.sh
#   MODEL=gemma-4-E4B-it scripts/smoke.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MODEL="${MODEL:-gemma-4-E2B-it}"

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }
pass()  { echo "$(green PASS) $*"; }
fail()  { echo "$(red FAIL) $*" >&2; exit 1; }

command -v jq >/dev/null || fail "jq is required (apt install jq / brew install jq)"

echo "Target: $BASE_URL  model=$MODEL"
echo

# ─── 1. /healthz ──────────────────────────────────────────────────────────
code=$(curl -fsS -o /dev/null -w "%{http_code}" "$BASE_URL/healthz" || true)
[[ "$code" == "200" ]] || fail "GET /healthz expected 200, got $code"
pass "GET /healthz → 200"

# ─── 2. /v1/models ────────────────────────────────────────────────────────
body=$(curl -fsS "$BASE_URL/v1/models")
echo "$body" | jq -e ".object == \"list\"" >/dev/null || fail "/v1/models: object != list"
echo "$body" | jq -e ".data | map(.id) | index(\"$MODEL\") != null" >/dev/null \
    || fail "/v1/models: $MODEL not in $(echo "$body" | jq -c '.data | map(.id)')"
pass "GET /v1/models → contains $MODEL"

# ─── 3. POST /v1/chat/completions (non-stream) ────────────────────────────
req=$(jq -nc \
    --arg m "$MODEL" \
    '{model:$m, messages:[{role:"user", content:"Reply with one short word."}]}')
body=$(curl -fsS "$BASE_URL/v1/chat/completions" \
    -H 'Content-Type: application/json' -d "$req")
id=$(echo "$body"      | jq -r '.id')
obj=$(echo "$body"     | jq -r '.object')
content=$(echo "$body" | jq -r '.choices[0].message.content')
role=$(echo "$body"    | jq -r '.choices[0].message.role')
finish=$(echo "$body"  | jq -r '.choices[0].finish_reason')
[[ "$id"     == chatcmpl-* ]]      || fail "non-stream: id $id"
[[ "$obj"    == "chat.completion" ]] || fail "non-stream: object $obj"
[[ "$role"   == "assistant" ]]     || fail "non-stream: role $role"
[[ -n "$content" ]]                || fail "non-stream: empty content; body=$body"
[[ "$finish" == "stop" ]]          || fail "non-stream: finish_reason=$finish"
pass "POST /v1/chat/completions (non-stream) → '$content'"

# ─── 4. POST /v1/chat/completions with empty messages → 400 ───────────────
code=$(curl -sS -o /dev/null -w "%{http_code}" \
    "$BASE_URL/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"$MODEL\",\"messages\":[]}")
[[ "$code" == "400" ]] || fail "empty messages: expected 400, got $code"
pass "POST /v1/chat/completions (empty messages) → 400"

# ─── 5. POST /v1/chat/completions stream=true ─────────────────────────────
sse=$(mktemp)
trap 'rm -f "$sse"' EXIT
req=$(jq -nc --arg m "$MODEL" \
    '{model:$m, messages:[{role:"user", content:"Say hi briefly."}], stream:true}')
curl -fsSN "$BASE_URL/v1/chat/completions" \
    -H 'Content-Type: application/json' -d "$req" >"$sse"

# Verify [DONE] terminator is the last data line.
tail -n5 "$sse" | grep -q '^data: \[DONE\]$' \
    || fail "stream: missing [DONE] terminator; tail:$'\n'$(tail -n5 "$sse")"

# Extract JSON payloads (drop the [DONE] line).
mapfile -t payloads < <(grep -E '^data: ' "$sse" \
    | sed 's/^data: //' \
    | grep -v '^\[DONE\]$')

[[ "${#payloads[@]}" -ge 3 ]] \
    || fail "stream: expected ≥3 chunks (role, ≥1 content, finish), got ${#payloads[@]}"

# First chunk: role=assistant.
echo "${payloads[0]}" | jq -e '.choices[0].delta.role == "assistant"' >/dev/null \
    || fail "stream: first chunk role!=assistant; got ${payloads[0]}"

# Last chunk: finish_reason=stop.
last="${payloads[${#payloads[@]}-1]}"
echo "$last" | jq -e '.choices[0].finish_reason == "stop"' >/dev/null \
    || fail "stream: last chunk finish_reason!=stop; got $last"

# All chunks share an id and use object=chat.completion.chunk.
ids=$(printf '%s\n' "${payloads[@]}" | jq -r '.id' | sort -u)
[[ "$(echo "$ids" | wc -l)" == "1" ]] \
    || fail "stream: chunks span multiple ids: $ids"
printf '%s\n' "${payloads[@]}" \
    | jq -e 'select(.object != "chat.completion.chunk") | empty' >/dev/null \
    || fail "stream: at least one chunk has wrong object"

text=$(printf '%s\n' "${payloads[@]}" \
    | jq -r '.choices[0].delta.content // ""' | tr -d '\n')
pass "POST /v1/chat/completions (stream) → ${#payloads[@]} chunks → '$text'"

echo
echo "$(green 'All smoke tests passed.')"
