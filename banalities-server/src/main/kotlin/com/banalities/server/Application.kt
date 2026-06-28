package com.banalities.server

import com.auth0.jwk.JwkProviderBuilder
import com.banalities.core.greeting
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.auth.Authentication
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

// ponytail: example table — replace with your real schema. Here to show the DSL pattern.
object Messages : Table("messages") {
    val id = integer("id").autoIncrement()
    val body = text("body")
    override val primaryKey = PrimaryKey(id)
}

// ponytail: in-memory, single-instance registry of live sockets keyed by Keycloak subject.
// Lets the server drop a player mid-session (ban/force-logout) without waiting for token expiry.
// Ceiling: won't span replicas — move to Redis pub/sub (or sticky routing) when you scale out.
private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketServerSession>>()

/** Close every live socket for a user — call this when you ban or force-logout someone. */
suspend fun disconnectUser(subject: String, reason: String = "disconnected by server") {
    sessions[subject]?.toList()?.forEach {
        it.close(CloseReason(CloseReason.Codes.NORMAL, reason))
    }
}

fun Application.module() {
    val ds = dataSource()
    val flyway = Flyway.configure().dataSource(ds).locations("filesystem:db/migration").load()
    flyway.validate()  // throws if applied migrations have wrong checksums
    val pending = flyway.info().pending()
    check(pending.isEmpty()) { "Database not migrated. Pending: ${pending.map { it.version }}" }
    val db = Database.connect(ds)
    installAuth()
    install(WebSockets)
    routing {
        get("/") { call.respondText(greeting()) }
        get("/health/db") {
            val ok = runCatching {
                transaction(db) { exec("SELECT 1"); true }
            }.getOrDefault(false)
            if (ok) call.respondText("db ok")
            else call.respondText("db unreachable", status = HttpStatusCode.ServiceUnavailable)
        }
        // Protected: a valid Keycloak access token (Authorization: Bearer …) is required.
        // The same `authenticate("keycloak") { … }` wrapper authenticates the realtime
        // WebSocket on its upgrade handshake once that route exists.
        authenticate("keycloak") {
            get("/messages") {
                val rows = transaction(db) {
                    Messages.insert { it[body] = "hello from Exposed DSL" }
                    Messages.selectAll().map { it[Messages.body] }
                }
                call.respondText(rows.joinToString("\n"))
            }
            // Realtime game socket. Auth is enforced on the upgrade handshake by the wrapper —
            // a missing/invalid token is rejected before this block runs.
            webSocket("/play") {
                val subject = call.principal<JWTPrincipal>()?.subject ?: return@webSocket
                val mine = sessions.computeIfAbsent(subject) { ConcurrentHashMap.newKeySet() }
                mine.add(this)
                try {
                    // ponytail: echo stub — replace with the real game protocol.
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text("echo: ${frame.readText()}"))
                    }
                } finally {
                    mine.remove(this)
                    sessions.remove(subject, emptySet<WebSocketServerSession>())
                }
            }
        }
    }
}

// Validates Keycloak-issued JWTs against the realm's public keys (JWKS), fetched once and
// cached — no shared secret, keys rotate without redeploys. Issuer + audience are checked.
private fun Application.installAuth() {
    val issuer = env("KEYCLOAK_ISSUER", "http://localhost:8081/realms/banalities")
    val audience = env("KEYCLOAK_AUDIENCE", "banalities-server")
    val jwkProvider = JwkProviderBuilder(URI("$issuer/protocol/openid-connect/certs").toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
    install(Authentication) {
        jwt("keycloak") {
            verifier(jwkProvider, issuer) { acceptLeeway(5) }
            validate { cred ->
                if (cred.payload.audience?.contains(audience) == true) JWTPrincipal(cred.payload) else null
            }
        }
    }
}

// Hikari pool, handed to Exposed as the DataSource.
private fun dataSource(): DataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = env("JDBC_URL", "jdbc:postgresql://localhost:5432/banalities")
    username = env("DB_USER", "banalities")
    password = env("DB_PASSWORD", "banalities")
    maximumPoolSize = 5
    initializationFailTimeout = -1  // boot even if the DB is down; /health/db reports it
})

private fun env(name: String, default: String) = System.getenv(name) ?: default
