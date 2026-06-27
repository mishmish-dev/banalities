# banalities

Kotlin Multiplatform + Compose Multiplatform monorepo: one shared core, one shared
Compose UI, and per-platform clients (JVM server, Wasm web, Android, iOS).

## Modules

| Module | Type | Targets | Depends on |
|---|---|---|---|
| `banalities-core` | KMP library — shared logic, no Compose | jvm, android, iosArm64, iosSimulatorArm64, wasmJs | — |
| `banalities-ui` | Compose MP shared UI (`App()`) | android, iosArm64, iosSimulatorArm64, wasmJs | core |
| `banalities-server` | Ktor JVM app | jvm | core |
| `banalities-terminal` | Mosaic TUI console app (`main`) | jvm | core |
| `banalities-web` | Compose Wasm browser app | wasmJs | ui |
| `banalities-android` | Android app (`MainActivity`) | android | ui |
| `banalities-ios` | SwiftUI host, Xcode project generated from `project.yml` | iosArm64, iosSimulatorArm64 | ui (via BanalitiesUI framework) |

`banalities-ios` is **not** a Gradle module — see `banalities-ios/README.md`.
Versions are centralized in `gradle/libs.versions.toml`.

## Toolchain

All commands run inside the Nix dev shell, which provides the JDK, Gradle, Android SDK
(`ANDROID_HOME`), XcodeGen, and points `DEVELOPER_DIR` at the real Xcode:

```sh
nix develop --command <cmd>
```

Xcode (App Store) and Apple Silicon are required for iOS; everything else is from Nix.

## Build & test

```sh
# Server (JVM) — runs on :8080, GET / returns a greeting
nix develop --command ./gradlew :banalities-server:run

# Terminal (Mosaic TUI) — needs a real TTY, so :run only works from an interactive shell.
# From a terminal:
nix develop --command ./gradlew :banalities-terminal:run
# Non-interactive (CI, this harness): install the launcher and run it under a pty
nix develop --command ./gradlew :banalities-terminal:installDist
./banalities-terminal/build/install/banalities-terminal/bin/banalities-terminal

# Web (Wasm) — production bundle to banalities-web/build/dist/wasmJs/productionExecutable
nix develop --command ./gradlew :banalities-web:wasmJsBrowserDistribution
# Web dev server with hot reload
nix develop --command ./gradlew :banalities-web:wasmJsBrowserDevelopmentRun

# Android — debug APK to banalities-android/build/outputs/apk/debug/
nix develop --command ./gradlew :banalities-android:assembleDebug

# iOS — generate project (after editing project.yml) then build for the simulator
nix develop --command bash -c 'cd banalities-ios && xcodegen generate'
nix develop --command bash -c 'cd banalities-ios && \
  xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination "generic/platform=iOS Simulator" build'

# Everything Gradle-side at once (excludes iOS app, which is xcodebuild)
nix develop --command ./gradlew build
```

There are no tests yet; add them under each module's `src/<sourceSet>Test/` and run
`./gradlew test` (JVM/common) or `:module:check`.

## Database & Docker

Postgres + the server are defined in `compose.yaml`. The server connects via raw JDBC +
a Hikari pool, configured from env (`JDBC_URL`, `DB_USER`, `DB_PASSWORD`); defaults point
at `localhost:5432/banalities`. It boots even if the DB is down — `GET /health/db` reports
status (`db ok` / `503 db unreachable`). No ORM or migrations yet.

```sh
# Local dev: Postgres in Docker, server from Gradle (defaults already match)
docker compose up -d db
nix develop --command ./gradlew :banalities-server:run

# Full stack (VPS-style): build the server jar on the host, then compose builds its image
nix develop --command ./gradlew :banalities-server:buildFatJar
docker compose up --build
```

The server image is a prebuilt-jar `Dockerfile` (`banalities-server/Dockerfile`) — the jar
is built on the host so Docker doesn't need the KMP/Android toolchain. **Rebuild the jar
before `docker compose build`.** For a VPS, copy `.env.example` to `.env` and set a real
`POSTGRES_PASSWORD` and `SERVER_DOMAIN` (compose reads `.env`; `.env` is gitignored).

On the VPS, Caddy fronts both the server and Keycloak: it terminates TLS (automatic Let's
Encrypt for `SERVER_DOMAIN` and `KEYCLOAK_DOMAIN`, both of which must resolve to the host) and
reverse-proxies over the internal network — WebSockets included. Neither backend is
host-published; only Caddy's 80/443 are.

## Auth (Keycloak + Ktor)

Keycloak runs in compose against the same Postgres (its own `keycloak` database, created by
`db-migrations/` on first init) and is reachable at `KEYCLOAK_DOMAIN`. Set `KEYCLOAK_ADMIN_PASSWORD`
in `.env` before first boot.

The server validates Keycloak-issued JWTs itself — Ktor's `Authentication` plugin fetches the
realm's public keys (JWKS) and checks issuer + audience on every request, including the
realtime WebSocket's upgrade handshake (`authenticate("keycloak") { … }`). No shared secret;
Caddy does **not** do auth.

### Realm seed

The `banalities` realm is seeded automatically from `keycloak/import/banalities-realm.json`
on first boot (`start --import-realm`; skipped once the realm exists, so console edits stick).
It sets up:
- **self-registration** (`registrationAllowed`) + login/reset by email,
- client **`banalities-server`** — the bearer-only resource server the game validates against,
- client **`banalities-client`** — public + PKCE front-end, with an **audience mapper** that puts
  `banalities-server` in the token's `aud` (without this the server 401s every token),
- **Discord** as an identity provider (`providerId: discord`).

### Discord login (manual prerequisites)

Discord is **not** a built-in Keycloak provider — `keycloak/Dockerfile` adds the community
`keycloak-discord` SPI. Two things can't be automated:

1. **Create a Discord application** at <https://discord.com/developers/applications> → OAuth2.
   Add this redirect URI: `https://KEYCLOAK_DOMAIN/realms/banalities/broker/discord/endpoint`.
   Copy its Client ID + Client Secret.
2. In the Keycloak console → realm `banalities` → Identity providers → **discord**, paste the
   Client ID/Secret (the seed ships `SET_IN_CONSOLE` placeholders). Set once; they persist.

Also set `banalities-client`'s redirect URIs / web origins to your real front-end origin (the
seed ships `https://game.example.com/*`).

Clients then send `Authorization: Bearer <token>` (or the token on the WS connect URL).

## Gotchas

- Run Gradle **inside** `nix develop` — it needs `ANDROID_HOME` and `DEVELOPER_DIR`.
- iOS ships `iosArm64` + `iosSimulatorArm64` only; Compose MP dropped Intel `iosX64`.
- Compose deps in `banalities-ui`/`banalities-web` use the deprecated `compose.*` aliases
  on purpose — they resolve divergent per-artifact versions (e.g. material3 ≠ ui) and
  per-platform klibs that hand-pinned coordinates don't.
