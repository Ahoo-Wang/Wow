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

import me.ahoo.wow.api.annotation.InternalWowApi
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long a store's calls to PostgreSQL may wait for a reply (JDBC's network timeout, the socket's read timeout in
 * pgjdbc) on PostgreSQL: without one, a reply lost on the way blocks its caller forever, pgjdbc's
 * socket reads having no timeout by default. Every connection a store borrows has [OPERATION] unless its store sets
 * another (`wow.postgres.operation-timeout`) or the application's pool sets a shorter one; the schema's statements (format records, tables, indexes) have [DDL],
 * as an index built over a large table may take minutes. A call that times out loses its connection (pgjdbc closes
 * it, the pool replaces it) and fails as an I/O error: a transient failure, a write's outcome unknown. The queries
 * keep their own budget (`statement_timeout`) on the connections of their own data source.
 *
 * Each transaction a store opens tells the server the same bound with `SET LOCAL` ([boundTransaction]):
 * its statements and lock waits stop a margin before the client gives up ([serverMillis]), and it may sit idle at most
 * twice as long, so a crashed node's open transaction is ended by the server. `SET LOCAL` ends with the transaction:
 * nothing stays on a connection the application's own pool lends again. An autocommitted statement carries no server
 * bound: a client-side query timeout would have pgjdbc open a connection to cancel it, which, against a database that
 * stopped answering, holds the caller past its own timeout.
 */
@InternalWowApi
object PostgresTimeouts {
    /** The default timeout of a store's calls besides the schema's. */
    val OPERATION: Duration = 30.seconds

    /** The timeout of the schema's statements. */
    val DDL: Duration = 5.minutes

    /**
     * [dataSource], each connection it lends bounded by [timeout]: one instance for one data source and timeout, so
     * stores of one database still find each other by their data source.
     */
    @JvmStatic
    fun timed(dataSource: DataSource, timeout: Duration = OPERATION): DataSource =
        timedSources.computeIfAbsent(dataSource to timeout) { TimedDataSource(dataSource, timeout) }

    private val timedSources = ConcurrentHashMap<Pair<DataSource, Duration>, DataSource>()

    /** Runs [block] with [connection] bounded by [DDL], then gives it back the timeout it had. */
    internal inline fun <T> ddl(connection: Connection, block: () -> T): T {
        val original = connection.networkTimeout
        connection.setNetworkTimeout(DIRECT, DDL.inWholeMilliseconds.toInt())
        try {
            return block()
        } finally {
            connection.setNetworkTimeout(DIRECT, original)
        }
    }

    /** pgjdbc applies a network timeout to its socket; the executor, for drivers that abort with one, runs inline. */
    internal val DIRECT = Executor { it.run() }

    /** How much earlier than its client the server gives up a statement, so that it cancels it first. */
    private const val MARGIN_MILLIS: Int = 1_000

    /**
     * The server's bound of a statement on a connection whose client waits [networkMillis]: [MARGIN_MILLIS] less, at
     * least a second; 0 (none) on a connection without a network timeout.
     */
    fun serverMillis(networkMillis: Int): Int =
        if (networkMillis <= 0) 0 else maxOf(networkMillis - MARGIN_MILLIS, MARGIN_MILLIS)

    /**
     * Bounds the transaction [connection] has just opened by its network timeout: its statements and its
     * lock waits by [serverMillis], its idle time, between two statements, by twice the network timeout. A query's
     * transaction gives its own budget instead ([statementMillis]) and is [readOnly]. One round trip,
     * before the transaction's first statement; nothing on a connection without a network timeout that is given
     * neither.
     */
    fun boundTransaction(connection: Connection, statementMillis: Long? = null, readOnly: Boolean = false) {
        val network = connection.networkTimeout
        val server = serverMillis(network)
        val settings = buildList {
            if (readOnly) add("SET TRANSACTION READ ONLY")
            (statementMillis ?: server.toLong().takeIf { it > 0 })?.let { add("SET LOCAL statement_timeout = $it") }
            if (server > 0) {
                add("SET LOCAL lock_timeout = $server")
                add("SET LOCAL idle_in_transaction_session_timeout = ${2L * network}")
            }
        }
        if (settings.isEmpty()) {
            return
        }
        connection.createStatement().use { it.execute(settings.joinToString("; ")) }
    }
}

/**
 * A [DataSource] whose connections are bounded by [timeout] when they are lent, unless the application's pool bounds
 * them tighter already (pgjdbc's `socketTimeout`): Wow's default never loosens a timeout the application chose. A
 * pool such as HikariCP gives a connection its own default back when it is returned, so the next borrower of the pool
 * sees it unchanged.
 */
private class TimedDataSource(private val delegate: DataSource, timeout: Duration) : DataSource by delegate {
    private val millis = timeout.inWholeMilliseconds.toInt()

    override fun getConnection(): Connection = delegate.connection.bounded()

    override fun getConnection(username: String?, password: String?): Connection =
        delegate.getConnection(username, password).bounded()

    /** Bounded by [millis], unless the pool's driver already bounds it tighter (its own `socketTimeout`). */
    private fun Connection.bounded(): Connection = also {
        val current = it.networkTimeout
        if (current == 0 || current > millis) it.setNetworkTimeout(PostgresTimeouts.DIRECT, millis)
    }

    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || delegate.isWrapperFor(iface)
}
