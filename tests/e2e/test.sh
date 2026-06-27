#!/usr/bin/env bash
# End-to-end auth test: Keycloak issues a JWT, the Ktor server accepts it on the protected
# HTTP route and the WebSocket handshake, and rejects missing/bad tokens.
# No Caddy, no Discord (stock keycloak + tests/e2e/test-realm.json). See E2E-TESTING.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

KC=http://localhost:8081
SRV=http://localhost:8080
# Colima only bind-mounts $HOME, not /tmp — keep the import dir under home.
IMPORT_DIR="$(mktemp -d "$HOME/.kc-e2e.XXXXXX")"
SERVER_PID=""
pass=0; fail=0

cleanup() {
  echo "--- teardown ---"
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  pkill -f 'com.banalities.server' 2>/dev/null || true   # ponytail: kills the gradle-spawned JVM by main class
  docker rm -f kc-test >/dev/null 2>&1 || true
  SERVER_DOMAIN=x KEYCLOAK_DOMAIN=y KEYCLOAK_ADMIN_PASSWORD=z POSTGRES_PASSWORD=banalities \
    docker compose down -v >/dev/null 2>&1 || true
  rm -rf "$IMPORT_DIR"
}
trap cleanup EXIT

check() { # desc expected actual
  if [ "$2" = "$3" ]; then echo "PASS  $1 (got $3)"; pass=$((pass+1));
  else echo "FAIL  $1 (expected $2, got $3)"; fail=$((fail+1)); fi
}
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "=== 1. Postgres + Keycloak ==="
SERVER_DOMAIN=x KEYCLOAK_DOMAIN=y KEYCLOAK_ADMIN_PASSWORD=z POSTGRES_PASSWORD=banalities \
  docker compose up -d db >/dev/null

docker rm -f kc-test >/dev/null 2>&1 || true
cp tests/e2e/test-realm.json "$IMPORT_DIR/banalities-realm.json"
docker run -d --name kc-test -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "$IMPORT_DIR":/opt/keycloak/data/import:ro \
  quay.io/keycloak/keycloak:26.2 start-dev --import-realm >/dev/null

echo "waiting for keycloak realm..."
for _ in $(seq 1 60); do
  [ "$(code "$KC/realms/banalities")" = 200 ] && break; sleep 2
done
check "keycloak realm reachable" 200 "$(code "$KC/realms/banalities")"

echo "=== 2. messages table (no migration runner yet) ==="
docker exec -i banalities-db-1 psql -U banalities -d banalities \
  < banalities-server/src/main/resources/db/migration/V1__messages.sql >/dev/null

echo "=== 3. server (pointed at local keycloak) ==="
KEYCLOAK_ISSUER="$KC/realms/banalities" KEYCLOAK_AUDIENCE=banalities-server \
  nix develop --command ./gradlew :banalities-server:run --console=plain >/tmp/e2e-server.log 2>&1 &
SERVER_PID=$!
echo "waiting for server..."
for _ in $(seq 1 90); do
  [ "$(code "$SRV/")" = 200 ] && break; sleep 2
done
check "server up" 200 "$(code "$SRV/")"

echo "=== 4. token + assertions ==="
TOKEN=$(curl -s -X POST "$KC/realms/banalities/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=banalities-client -d username=tester -d password=test \
  | jq -r .access_token)
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || { echo "FAIL  could not mint token"; exit 1; }

AUD=$(python3 -c "import base64,json,sys; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p)).get('aud'))" "$TOKEN")
check "token aud == banalities-server" banalities-server "$AUD"

check "/messages no token   -> 401" 401 "$(code "$SRV/messages")"
check "/messages bad token  -> 401" 401 "$(code -H 'Authorization: Bearer bad.token' "$SRV/messages")"
check "/messages valid      -> 200" 200 "$(code -H "Authorization: Bearer $TOKEN" "$SRV/messages")"

WSH=(-H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")
ws_status() { curl -s -i --max-time 3 "${WSH[@]}" "$@" "$SRV/play" | head -1 | grep -oE '[0-9]{3}'; }
check "WS /play no token     -> 401" 401 "$(ws_status)"
check "WS /play valid token  -> 101" 101 "$(ws_status -H "Authorization: Bearer $TOKEN")"

# Optional WS echo — only if python websockets is installable.
if python3 -m venv /tmp/e2e-wsenv >/dev/null 2>&1 && /tmp/e2e-wsenv/bin/pip install -q websockets >/dev/null 2>&1; then
  ECHO=$(TOKEN="$TOKEN" /tmp/e2e-wsenv/bin/python - <<'PY'
import asyncio, os, websockets
async def main():
    async with websockets.connect("ws://localhost:8080/play",
            additional_headers={"Authorization": f"Bearer {os.environ['TOKEN']}"}) as ws:
        await ws.send("ping"); print(await asyncio.wait_for(ws.recv(), 5))
asyncio.run(main())
PY
)
  rm -rf /tmp/e2e-wsenv
  check "WS echo               -> echo: ping" "echo: ping" "$ECHO"
else
  echo "SKIP  WS echo (could not install python websockets)"
fi

echo "=================================="
echo "PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
