package com.rinha.providers

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object SQLiteManager {

    private val connections = ConcurrentHashMap<String, Connection>()
    private val dbLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val initLock = ReentrantLock()
    private val logger = LoggerFactory.getLogger(SQLiteManager::class.java)

    private fun getConnection(dbPath: String): Connection {
        return connections.getOrPut(dbPath) {
            initLock.withLock {
                DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
                    if (!isClosed) {
                        setPragmas(this)
                    }
                }
            }
        }
    }

    private fun setPragmas(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous = NORMAL")
            stmt.execute("PRAGMA locking_mode=EXCLUSIVE")
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA cache_size=-10000")
            stmt.execute("PRAGMA temp_store=MEMORY")
            stmt.execute("PRAGMA mmap_size=33554432")
            stmt.execute("PRAGMA wal_autocheckpoint=100")
        }
    }

    fun <T> executeWithLock(dbPath: String, block: (Connection) -> T): T {
        val dbLock = dbLocks.computeIfAbsent(dbPath) { ReentrantLock() }

        return dbLock.withLock {
            val conn = getConnection(dbPath)
            block(conn)
        }
    }

    fun upPaymentsTable(dbPath: String) {
        executeWithLock(dbPath) { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                    correlation_id PRIMARY KEY,
                    amount TEXT NOT NULL,
                    processed BOOLEAN NOT NULL,
                    processor TEXT NOT NULL,
                    requested_at TEXT NOT NULL
                )
            """.trimIndent())

                stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_payments_processor_requested_at 
                ON payments(processor, requested_at)
            """.trimIndent())

            }
        }
    }

    fun closeAll() {
        connections.values.forEach { safeClose(it) }
        connections.clear()
        dbLocks.clear()
    }

    private fun safeClose(conn: Connection) {
        try {
            if (!conn.isClosed) conn.close()
        } catch (e: Exception) {
            logger.error("error closing SQLite connection: ${e.message}", e)
        }
    }
}