# End-to-end auth test (local, no Caddy, no Discord)

Proves the core chain: **Keycloak mints a JWT → the Ktor server accepts it on the protected
HTTP route and the WebSocket handshake → missing/bad tokens are rejected.**

Caddy/TLS can't run locally (needs real domains + Let's Encrypt) and Discord is skipped, so
this uses stock `keycloak:26.2` with a Discord-free test realm. See "What this does NOT cover".

## Quick run

```sh
./tests/e2e/test.sh
```

Brings up Postgres + Keycloak, runs the server, asserts every check, and tears it all down on
exit. Exit 0 = all passed. The sections below are the same steps by hand, with the why.

## Prerequisites

- Docker running (`docker info` works — on this machine, `colima start` first).
- `jq`, `python3`, and the Nix dev shell (for the Gradle server run).

## 1. Test realm

The test realm is `tests/e2e/test-realm.json` — a trimmed copy of the committed realm with no
Discord IdP (stock Keycloak can't import `providerId: discord`; that needs the SPI jar in the
custom image) plus a test user with direct-grant enabled.

> Colima only bind-mounts `$HOME`, **not** `/tmp`. Put the import dir under your home or the
> container sees an empty directory. Mount the **directory**, not the single file (a single-file
> bind mount gets created as an empty dir).

```sh
mkdir -p "$HOME/.cache/kc-import"
cp tests/e2e/test-realm.json "$HOME/.cache/kc-import/banalities-realm.json"
```

> In the realm file, `firstName`/`lastName`/`requiredActions: []` on the user matter — without
> them Keycloak's default user profile leaves the account "not fully set up" and the token
> grant fails with `invalid_grant`.

## 2. Start Postgres + Keycloak

```sh
# Postgres (compose vars are required even for `up db`; dummy values are fine here)
SERVER_DOMAIN=x KEYCLOAK_DOMAIN=y KEYCLOAK_ADMIN_PASSWORD=z POSTGRES_PASSWORD=banalities \
  docker compose up -d db

# Keycloak dev mode on :8081, importing the test realm
docker run -d --name kc-test -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "$HOME/.cache/kc-import":/opt/keycloak/data/import:ro \
  quay.io/keycloak/keycloak:26.2 start-dev --import-realm

# Wait for the realm (expect 200)
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/realms/banalities)" = 200 ]; do sleep 2; done
```

The `messages` table has no migration runner yet, so create it by hand (the protected
`/messages` route inserts into it):

```sh
docker exec -i banalities-db-1 psql -U banalities -d banalities \
  < banalities-server/src/main/resources/db/migration/V1__messages.sql
```

## 3. Start the server (pointed at local Keycloak)

```sh
KEYCLOAK_ISSUER=http://localhost:8081/realms/banalities KEYCLOAK_AUDIENCE=banalities-server \
  nix develop --command ./gradlew :banalities-server:run
# wait until `curl -s localhost:8080/` returns "Hello from world!"
```

## 4. Mint a token and assert

```sh
TOKEN=$(curl -s -X POST http://localhost:8081/realms/banalities/protocol/openid-connect/token \
  -d grant_type=password -d client_id=banalities-client -d username=tester -d password=test \
  | jq -r .access_token)

# Audience mapper check — aud must be banalities-server (this is the #1 401 cause if missing)
python3 -c "import base64,json,sys; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))['aud'])" "$TOKEN"

c(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
c http://localhost:8080/messages                                   # -> 401  (no token)
c -H "Authorization: Bearer bad.token" http://localhost:8080/messages  # -> 401
c -H "Authorization: Bearer $TOKEN" http://localhost:8080/messages     # -> 200
```

### WebSocket handshake

A plain `GET /play` returns 400 (no upgrade headers) **before** auth runs — it doesn't prove
anything. Send a real handshake:

```sh
WSH=(-H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==")
curl -s -i --max-time 3 "${WSH[@]}" http://localhost:8080/play | head -1                       # -> 401 Unauthorized
curl -s -i --max-time 3 "${WSH[@]}" -H "Authorization: Bearer $TOKEN" http://localhost:8080/play | head -1  # -> 101 Switching Protocols
```

### WS echo (optional, needs the `websockets` lib)

```sh
python3 -m venv /tmp/wsenv && /tmp/wsenv/bin/pip install -q websockets
TOKEN="$TOKEN" /tmp/wsenv/bin/python - <<'PY'
import asyncio, os, websockets
async def main():
    async with websockets.connect("ws://localhost:8080/play",
            additional_headers={"Authorization": f"Bearer {os.environ['TOKEN']}"}) as ws:
        await ws.send("ping"); print(await asyncio.wait_for(ws.recv(), 5))   # -> echo: ping
asyncio.run(main())
PY
```

## Expected results

| Check | Expected |
|---|---|
| Realm import | `Realm 'banalities' imported` |
| Token `aud` | `banalities-server` |
| `GET /messages` no / bad token | 401 |
| `GET /messages` valid token | 200 |
| WS `/play` handshake no token | 401 |
| WS `/play` handshake valid token | 101 |
| WS echo | `echo: ping` |

## Teardown

```sh
docker rm -f kc-test
SERVER_DOMAIN=x KEYCLOAK_DOMAIN=y KEYCLOAK_ADMIN_PASSWORD=z POSTGRES_PASSWORD=banalities \
  docker compose down -v
rm -rf "$HOME/.cache/kc-import" /tmp/wsenv
# stop the Gradle server with Ctrl-C (or kill the :run process)
```

## What this does NOT cover

- **Caddy / TLS** — needs real public domains + Let's Encrypt.
- **The custom Keycloak image + Discord SPI** — tested with stock Keycloak and a Discord-free
  realm. Run `docker compose build keycloak` separately to confirm the `keycloak-discord` jar
  URL in `keycloak/Dockerfile` resolves (it'll 404 at `ADD` if the version is wrong).
- **The committed realm file as-is** — it only imports on the custom image where `providerId:
  discord` resolves.
- **DB migrations** — no runner yet; `messages` table is created by hand above.
