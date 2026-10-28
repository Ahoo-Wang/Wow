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
import java.util.concurrent.atomic.AtomicReferenceArray
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
     * neither. A store's own transaction sends them with its first statement instead ([inTransaction]).
     */
    fun boundTransaction(connection: Connection, statementMillis: Long? = null, readOnly: Boolean = false) {
        val bound = transactionBound(connection, statementMillis, readOnly) ?: return
        connection.createStatement().use { it.execute(bound.alone) }
    }

    /**
     * The bound of a transaction of [connection], as [boundTransaction] sends it; none to send. One instance for each
     * network timeout, statement budget and read-only flag seen lately: a store's transactions take theirs without
     * building it again.
     */
    internal fun transactionBound(
        connection: Connection,
        statementMillis: Long?,
        readOnly: Boolean,
    ): TransactionBound? {
        val network = connection.networkTimeout
        val millis = statementMillis ?: NO_BUDGET
        val slot = (31 * (31 * network + millis.hashCode()) + readOnly.hashCode()).mod(BOUNDS)
        val known = bounds.get(slot)
        val bound = if (known != null && known.isFor(network, millis, readOnly)) {
            known
        } else {
            TransactionBound(network, millis, readOnly).also { bounds.set(slot, it) }
        }
        return bound.takeIf { it.count > 0 }
    }

    /** No statement budget of its own: the transaction's statements are bounded by the network timeout. */
    private const val NO_BUDGET = -1L

    /** How many bounds [transactionBound] keeps: a few data sources' stores and their queries' budgets. */
    private const val BOUNDS = 16

    private val bounds = AtomicReferenceArray<TransactionBound>(BOUNDS)

    /**
     * The statements that bound a transaction on a connection whose client waits [network] milliseconds: its
     * statements by [statementMillis] (or [NO_BUDGET]: the server's bound) and [readOnly]; [count] of them, as a
     * statement's [prefix] or [alone].
     */
    internal class TransactionBound(
        private val network: Int,
        private val statementMillis: Long,
        private val readOnly: Boolean,
    ) {
        private val statements: List<String> = buildList {
            val server = serverMillis(network)
            if (readOnly) add("SET TRANSACTION READ ONLY")
            val budget = if (statementMillis == NO_BUDGET) server.toLong() else statementMillis
            if (budget > 0) add("SET LOCAL statement_timeout = $budget")
            if (server > 0) {
                add("SET LOCAL lock_timeout = $server")
                add("SET LOCAL idle_in_transaction_session_timeout = ${2L * network}")
            }
        }

        /** How many statements, each answering one result before the statement they prefix. */
        val count: Int = statements.size

        /** The statements on their own. */
        val alone: String = statements.joinToString("; ")

        /** The statements as the start of another statement's text. */
        val prefix: String = statements.joinToString("") { "$it; " }

        fun isFor(network: Int, statementMillis: Long, readOnly: Boolean): Boolean =
            this.network == network && this.statementMillis == statementMillis && this.readOnly == readOnly
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
