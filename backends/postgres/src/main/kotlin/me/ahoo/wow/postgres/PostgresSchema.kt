/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.postgres

import java.sql.Connection
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The schema's statements, run only for what the catalog lacks, each lock wait bounded.
 *
 * `CREATE INDEX IF NOT EXISTS` and `ALTER TABLE … ADD COLUMN IF NOT EXISTS` take their table's lock before they check:
 * behind a transaction that holds the table (a crashed node's, idle in transaction until its backend is reaped), they
 * queue, and every later reader of the table queues behind them. So [execute] reads the catalog first and runs a
 * statement only when its table, index or columns are missing: a current schema takes no table lock. What it does
 * run waits at most [LockWait.timeout] for a lock (`lock_timeout`), and [retrying] gives the lock back between
 * attempts, so a reader never waits behind it for longer, then fails naming the table and who holds it.
 *
 * Nodes starting together on a new database run one table's steps one at a time ([locked]): a node's
 * `ALTER TABLE` waits for the table's lock, which another node's `CREATE INDEX CONCURRENTLY` holds while it waits for
 * every open transaction, the first node's among them, and PostgreSQL ends one of them as a deadlock (`40P01`).
 */
internal object PostgresSchema {
    /** The SQLSTATE of a lock wait that ran past `lock_timeout`. */
    const val LOCK_NOT_AVAILABLE: String = "55P03"

    /** The SQLSTATE of a statement PostgreSQL ended to break a deadlock. */
    const val DEADLOCK_DETECTED: String = "40P01"

    /** The class of the advisory lock of one table's schema steps ([locked]); the table's name gives its object. */
    const val TABLE_LOCK_CLASS: Int = 0x776f7774

    /** How often [locked] asks for a table's lock again. */
    private val TABLE_LOCK_POLL = 50.milliseconds

    /** How long a schema statement waits for a lock, and how many times the step tries again. */
    data class LockWait(val timeout: Duration, val attempts: Int, val backoff: Duration) {
        init {
            require(timeout.isPositive()) { "The lock wait is positive." }
            require(attempts >= 1) { "A schema step is tried at least once." }
        }

        companion object {
            /** Five seconds a lock, five attempts with a backoff doubling from half a second: within a minute. */
            val DEFAULT: LockWait = LockWait(timeout = 5.seconds, attempts = 5, backoff = 500.milliseconds)
        }
    }

    /** What a schema statement makes: a relation (a table or an index), or columns of a table. */
    sealed interface Target {
        /** The table the statement locks, as the statement names it. */
        val table: String

        data class Relation(val name: String, override val table: String) : Target

        data class Columns(override val table: String, val columns: List<String>) : Target
    }

    private val CREATE_TABLE =
        Regex("""^\s*CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+("[^"]+"|[\w.]+)""", RegexOption.IGNORE_CASE)
    private const val NAME = """("[^"]+"|[\w.]+)"""
    private val CREATE_INDEX = Regex(
        """^\s*CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:CONCURRENTLY\s+)?IF\s+NOT\s+EXISTS\s+$NAME\s+ON\s+$NAME""",
        RegexOption.IGNORE_CASE,
    )
    private val ALTER_TABLE = Regex("""^\s*ALTER\s+TABLE\s+("[^"]+"|[\w.]+)\s""", RegexOption.IGNORE_CASE)
    private val ADD_COLUMN = Regex("""ADD\s+COLUMN\s+IF\s+NOT\s+EXISTS\s+("[^"]+"|\w+)""", RegexOption.IGNORE_CASE)

    /**
     * What [statement] makes. Only the idempotent forms the stores write are known: `CREATE TABLE IF NOT EXISTS`,
     * `CREATE [UNIQUE] INDEX [CONCURRENTLY] IF NOT EXISTS … ON`, and `ALTER TABLE … ADD COLUMN IF NOT EXISTS …`.
     */
    fun target(statement: String): Target {
        CREATE_TABLE.find(statement)?.let {
            val table = it.groupValues[1]
            return Target.Relation(table, table)
        }
        CREATE_INDEX.find(statement)?.let { return Target.Relation(it.groupValues[1], it.groupValues[2]) }
        ALTER_TABLE.find(statement)?.let { alter ->
            val columns = ADD_COLUMN.findAll(statement).map { unquoted(it.groupValues[1]) }.toList()
            require(columns.isNotEmpty()) { "Not a schema statement the catalog can check: $statement" }
            return Target.Columns(alter.groupValues[1], columns)
        }
        throw IllegalArgumentException("Not a schema statement the catalog can check: $statement")
    }

    /** Whether the catalog lacks what [statement] makes. */
    fun missing(connection: Connection, statement: String): Boolean = when (val target = target(statement)) {
        is Target.Relation -> !exists(connection, target.name)
        is Target.Columns -> !hasColumns(connection, target.table, target.columns)
    }

    /** Runs those of [statements] that make something the catalog lacks, in order, on [connection]. */
    fun execute(connection: Connection, statements: List<String>) {
        statements.forEach { statement ->
            if (missing(connection, statement)) {
                connection.createStatement().use { it.execute(statement) }
            }
        }
    }

    /** Whether [relation], a table or an index named as a statement names it, exists. */
    fun exists(connection: Connection, relation: String): Boolean =
        connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { query ->
            query.setString(1, relation)
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /** Whether [table], named as a statement names it, has every one of [columns]. */
    fun hasColumns(connection: Connection, table: String, columns: List<String>): Boolean =
        connection.prepareStatement(
            "SELECT count(*) FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ANY (?) " +
                "AND attnum > 0 AND NOT attisdropped",
        ).use { query ->
            query.setString(1, table)
            query.setArray(2, connection.createArrayOf("text", columns.toTypedArray()))
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1) == columns.size
            }
        }

    /** Bounds the lock waits of the transaction [connection] is in by [wait] (`SET LOCAL lock_timeout`). */
    fun boundLockWaits(connection: Connection, wait: LockWait) {
        val millis = wait.timeout.inWholeMilliseconds
        connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '${millis}ms'") }
    }

    /**
     * Runs [block] with the session's lock waits bounded by [wait] (`SET lock_timeout`), for a statement that cannot
     * run in a transaction (`CREATE INDEX CONCURRENTLY`), then gives the session its own bound back.
     */
    fun <T> boundSessionLockWaits(connection: Connection, wait: LockWait, block: () -> T): T {
        connection.createStatement().use { it.execute("SET lock_timeout = '${wait.timeout.inWholeMilliseconds}ms'") }
        try {
            return block()
        } finally {
            connection.createStatement().use { it.execute("RESET lock_timeout") }
        }
    }

    /**
     * Runs [block], a schema step of [table], holding the session-level advisory lock of the table's schema steps,
     * so that the nodes of a service that start together run them one at a time: a step's catalog check
     * and the statements it runs, in a transaction or not (`CREATE INDEX CONCURRENTLY`). [connection] is in autocommit
     * mode. The lock is asked for with `pg_try_advisory_lock` until it is free, for at most [wait]'s timeout, rather than
     * waited for: a session waiting in a statement holds a snapshot, which a `CREATE INDEX CONCURRENTLY` of the lock's
     * holder would wait for in turn. It fails as a lock wait that ran out (`55P03`), which [retrying] tries again.
     */
    fun <T> locked(connection: Connection, table: String, wait: LockWait, block: () -> T): T {
        val name = unquoted(table)
        val deadline = TimeSource.Monotonic.markNow() + wait.timeout
        while (!tableLock(connection, "pg_try_advisory_lock", name)) {
            if (deadline.hasPassedNow()) {
                throw SQLException(
                    "The schema lock of $name was not free within ${wait.timeout}.",
                    LOCK_NOT_AVAILABLE,
                )
            }
            Thread.sleep(TABLE_LOCK_POLL.inWholeMilliseconds)
        }
        val result = try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            runCatching { tableLock(connection, "pg_advisory_unlock", name) }.exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
        tableLock(connection, "pg_advisory_unlock", name)
        return result
    }

    private fun tableLock(connection: Connection, function: String, table: String): Boolean =
        connection.prepareStatement("SELECT $function(?, hashtext(?))").use { query ->
            query.setInt(1, TABLE_LOCK_CLASS)
            query.setString(2, table)
            query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /**
     * Runs [attempt], a schema step that gives back every lock it took when it fails (a transaction rolled back), up to
     * [LockWait.attempts] times while a lock wait runs out (`55P03`) or PostgreSQL ends it to break a deadlock
     * (`40P01`), backing off in between; then fails naming [table] and the sessions that hold it ([holders]).
     */
    fun <T> retrying(connection: Connection, table: String, wait: LockWait, attempt: () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        var backoff = wait.backoff
        var attempts = 0
        while (true) {
            attempts++
            val timedOut = try {
                return attempt()
            } catch (failure: SQLException) {
                if (failure.sqlState != LOCK_NOT_AVAILABLE && failure.sqlState != DEADLOCK_DETECTED) throw failure
                failure
            }
            check(attempts < wait.attempts) {
                "The schema step of $table could not take its lock in ${wait.attempts} attempts of " +
                    "${wait.timeout} over ${started.elapsedNow().inWholeSeconds}s: ${holders(connection, table)}. " +
                    "A session idle in transaction is often a crashed node's: it ends when its backend is reaped " +
                    "(idle_in_transaction_session_timeout, TCP keepalives), or terminate it with pg_terminate_backend. " +
                    "Last: ${timedOut.message}"
            }
            Thread.sleep(backoff.inWholeMilliseconds)
            backoff *= 2
        }
    }

    /**
     * The sessions holding a lock on [table], the schema's advisory lock or the table's own ([locked]), as a sentence
     * for the failure.
     */
    fun holders(connection: Connection, table: String): String = runCatching {
        connection.prepareStatement(
            "SELECT DISTINCT a.pid, a.state, a.client_addr, a.application_name, a.state_change FROM pg_locks l " +
                "JOIN pg_stat_activity a ON a.pid = l.pid WHERE l.granted AND l.pid <> pg_backend_pid() " +
                "AND (l.relation = to_regclass(?) OR (l.locktype = 'advisory' AND l.classid = ? AND l.objid = ?) " +
                "OR (l.locktype = 'advisory' AND l.classid = ? AND l.objid = hashtext(?)::oid))",
        ).use { query ->
            query.setString(1, table)
            query.setLong(2, PostgresFormat.CREATION_LOCK ushr Int.SIZE_BITS)
            query.setLong(3, PostgresFormat.CREATION_LOCK and 0xffffffffL)
            query.setLong(4, TABLE_LOCK_CLASS.toLong())
            query.setString(5, unquoted(table))
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            "pid ${rows.getInt(1)} (${rows.getString(2)} since ${rows.getTimestamp(5)}, " +
                                "from ${rows.getString(3)} ${rows.getString(4)})",
                        )
                    }
                }
            }
        }.ifEmpty { listOf("no session holds it now") }.joinToString(", ", prefix = "held by ")
    }.getOrElse { "its holders could not be read ($it)" }

    private fun unquoted(name: String): String = name.removeSurrounding("\"")
}
