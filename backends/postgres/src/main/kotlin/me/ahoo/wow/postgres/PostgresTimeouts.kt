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
 * pgjdbc), decision D279 on PostgreSQL: without one, a reply lost on the way blocks its caller forever, pgjdbc's
 * socket reads having no timeout by default. Every connection a store borrows has [OPERATION] unless its store sets
 * another (`wow.postgres.operation-timeout`) or the application's pool sets a shorter one; the schema's statements (format records, tables, indexes) have [DDL],
 * as an index built over a large table may take minutes. A call that times out loses its connection (pgjdbc closes
 * it, the pool replaces it) and fails as an I/O error: a transient failure, a write's outcome unknown. The queries
 * keep their own budget (`statement_timeout`, decision D150) on the connections of their own data source.
 */
@InternalWowApi
object PostgresTimeouts {
    /** The default timeout of a store's calls besides the schema's. */
    val OPERATION: Duration = 30.seconds

    /** The timeout of the schema's statements. */
    val DDL: Duration = 5.minutes

    /**
     * [dataSource], each connection it lends bounded by [timeout]: one instance for one data source and timeout, so
     * stores of one database still find each other by their data source (decision D268).
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
