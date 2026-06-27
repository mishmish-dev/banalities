package com.banalities.server

import com.banalities.core.greeting
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import javax.sql.DataSource
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

fun Application.module() {
    val db = Database.connect(dataSource())
    routing {
        get("/") { call.respondText(greeting()) }
        get("/health/db") {
            val ok = runCatching {
                transaction(db) { exec("SELECT 1"); true }
            }.getOrDefault(false)
            if (ok) call.respondText("db ok")
            else call.respondText("db unreachable", status = HttpStatusCode.ServiceUnavailable)
        }
        get("/messages") {
            val rows = transaction(db) {
                Messages.insert { it[body] = "hello from Exposed DSL" }
                Messages.selectAll().map { it[Messages.body] }
            }
            call.respondText(rows.joinToString("\n"))
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
