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

import kotlinx.coroutines.runBlocking
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PostgresTimeoutsTest {
    @Test
    fun `a call that outlasts its timeout fails as transient instead of waiting`() {
        // A server that does not answer in time stands for a reply lost on the way.
        val timed = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        val failure = assertThrownBy<SQLException> {
            timed.connection.use { connection -> connection.createStatement().use { it.execute("SELECT pg_sleep(2)") } }
        }.actual()
        PostgresFailures.isTransient(failure).assert().isTrue()
        // The pool gives the next borrower a working connection.
        timed.connection.use { connection -> connection.createStatement().use { it.execute("SELECT 1") } }
    }

    @Test
    fun `the schema's statements have the longer timeout, the others the store's`(): Unit = runBlocking {
        val timed = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        timed.connection.use { connection ->
            connection.networkTimeout.assert().isEqualTo(SHORT.inWholeMilliseconds.toInt())
            PostgresTimeouts.ddl(connection) {
                connection.networkTimeout.assert().isEqualTo(PostgresTimeouts.DDL.inWholeMilliseconds.toInt())
                connection.createStatement().use { it.execute("SELECT pg_sleep(0.5)") }
            }
            connection.networkTimeout.assert().isEqualTo(SHORT.inWholeMilliseconds.toInt())
        }
        // A store on the timed source creates its schema and reads as it did.
        val store = PostgresCheckpointStore(timed).also { it.createSchema() }
        store.load("timeouts", "probe", 0).assert().isNull()
    }

    @Test
    fun `a pool that bounds its connections tighter keeps its own timeout`() {
        // An application's `socketTimeout` shorter than Wow's default is the application's choice.
        val container = WowContainers.postgres
        val config = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 1
            addDataSourceProperty("socketTimeout", "1")
        }
        HikariDataSource(config).use { tight ->
            PostgresTimeouts.timed(tight).connection.use { it.networkTimeout.assert().isEqualTo(TIGHT_MS) }
            // A tighter default than the pool's still applies.
            PostgresTimeouts.timed(tight, SHORT).connection.use {
                it.networkTimeout.assert().isEqualTo(SHORT.inWholeMilliseconds.toInt())
            }
        }
    }

    @Test
    fun `one data source and timeout give one wrapper, so stores of one database find each other`() {
        // Decision D268: the log reads a snapshot with its tail when both stores are on one data source.
        val first = PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT)
        PostgresTimeouts.timed(PostgresFixture.dataSource, SHORT).assert().isSameAs(first)
        PostgresTimeouts.timed(PostgresFixture.dataSource).assert().isNotSameAs(first)
        first.isWrapperFor(com.zaxxer.hikari.HikariDataSource::class.java).assert().isTrue()
    }

    @Test
    fun `a transaction's statement past the server bound should be cancelled by the server, keeping the connection`() {
        onePooled { pool ->
            val timed = PostgresTimeouts.timed(pool, 3.seconds)
            timed.connection.use { connection ->
                val started = TimeSource.Monotonic.markNow()
                val failure = assertThrownBy<SQLException> {
                    inTransaction(connection) { bound -> bound.createStatement().use { it.execute("SELECT pg_sleep(10)") } }
                }.actual()
                // The server's statement_timeout (2 s) stops it before the socket times out (3 s).
                failure.sqlState.assert().isEqualTo(QUERY_CANCELED)
                started.elapsedNow().assert().isLessThan(3.seconds)
                connection.createStatement().use { it.execute("SELECT 1") }
            }
        }
    }

    @Test
    fun `a Wow transaction's bounds should end with it, leaving the pooled connection at the server's defaults`() {
        onePooled { pool ->
            val defaults = pool.connection.use { settings(it) }
            PostgresTimeouts.timed(pool, 3.seconds).connection.use { connection ->
                inTransaction(connection) { bound ->
                    settings(bound).assert().isEqualTo(listOf("2s", "2s", "6s"))
                }
            }
            // The pool's one connection, borrowed by the application itself: nothing of Wow's stayed on it.
            pool.connection.use { settings(it) }.assert().isEqualTo(defaults)
        }
    }

    @Test
    fun `a transaction left idle, as a crashed node leaves it, should be ended by the server`() {
        onePooled { pool ->
            val timed = PostgresTimeouts.timed(pool, 2.seconds)
            val pid = AtomicInteger()
            assertThrownBy<SQLException> {
                timed.connection.use { connection ->
                    inTransaction(connection) { bound ->
                        pid.set(pid(bound))
                        // Idle past twice the network timeout (4 s): the server ends the session.
                        Thread.sleep(5_000)
                        bound.createStatement().use { it.execute("SELECT 1") }
                    }
                }
            }
            PostgresFixture.dataSource.connection.use { other ->
                other.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE pid = ?").use { query ->
                    query.setInt(1, pid.get())
                    query.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1).assert().isZero()
                    }
                }
            }
        }
    }

    /** Runs [block] on a pool of one connection, so every borrow is the same physical connection. */
    private fun onePooled(block: (HikariDataSource) -> Unit) {
        val container = WowContainers.postgres
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 1
            },
        ).use(block)
    }

    private fun settings(connection: Connection): List<String> = listOf(
        "statement_timeout",
        "lock_timeout",
        "idle_in_transaction_session_timeout",
    ).map { name -> connection.createStatement().use { it.executeQuery("SHOW $name").use { rows -> rows.next(); rows.getString(1) } } }

    private fun pid(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

    private companion object {
        val SHORT = 300.milliseconds
        const val TIGHT_MS = 1_000
        const val QUERY_CANCELED = "57014"
    }
}
