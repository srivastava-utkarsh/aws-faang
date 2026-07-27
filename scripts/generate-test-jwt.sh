#!/bin/bash
# scripts/generate-test-jwt.sh
# Mints an HS256 JWT with plain openssl — no Cognito, no library needed.
# Only for testing the authorizer locally / via curl. Real clients would
# get this from a proper auth flow; this project has none in scope yet.
#
# Usage: JWT_SECRET=... ./scripts/generate-test-jwt.sh user-123
set -e

: "${JWT_SECRET:?Set JWT_SECRET (same value passed to api-gateway.yaml)}"
USER_ID=${1:-test-user}
EXP=$(( $(date +%s) + 3600 ))

b64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","exp":%d}' "$USER_ID" "$EXP" | b64url)
SIGNING_INPUT="${HEADER}.${PAYLOAD}"
SIGNATURE=$(printf '%s' "$SIGNING_INPUT" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)

echo "${SIGNING_INPUT}.${SIGNATURE}"
