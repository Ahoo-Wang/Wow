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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import kotlin.time.Duration.Companion.seconds

/**
 * A store's transaction sends its bound with its first statement, whatever kind of statement that is: the caller sees
 * plain JDBC, and the transaction takes one round trip fewer. A first statement that batches keeps its update counts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundConnectionTest {
    private val timed = PostgresTimeouts.timed(PostgresFixture.dataSource, 3.seconds)
    private val table = "bound_connection_${System.nanoTime()}"

    @BeforeAll
    fun createTable() {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE $table (id int PRIMARY KEY, v int NOT NULL)") }
        }
    }

    /**
     * Runs [block] in a store's transaction on a connection counting its round trips, checks the transaction was
     * bounded, and gives what [block] answered with the round trips it took.
     */
    private fun <T> transaction(readOnly: Boolean = false, block: (Connection) -> T): Pair<T, Int> =
        timed.connection.use { pooled ->
            val counted = RoundTrips(pooled)
            var trips = 0
            val result = inTransaction(counted.connection, readOnly = readOnly) { bound ->
                block(bound).also {
                    trips = counted.executions
                    bounds(bound).assert().isEqualTo(listOf("2s", "2s", "6s"))
                }
            }
            result to trips
        }

    @Test
    fun `a first query carries the bound in its own round trip and answers its rows`() {
        val (value, trips) = transaction { bound ->
            bound.prepareStatement("SELECT ?::int + 1").use { query ->
                query.setInt(1, 6)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
        value.assert().isEqualTo(7)
        trips.assert().isEqualTo(1)
    }

    @Test
    fun `a first update carries the bound and answers its own update count`() {
        val (count, trips) = transaction { bound ->
            bound.prepareStatement("INSERT INTO $table (id, v) VALUES (?, 1), (?, 1) ON CONFLICT (id) DO NOTHING").use {
                it.setInt(1, 101)
                it.setInt(2, 102)
                it.executeUpdate()
            }
        }
        count.assert().isEqualTo(2)
        trips.assert().isEqualTo(1)
    }

    @Test
    fun `a first execute carries the bound and tells a result set from an update count`() {
        val (answers, trips) = transaction { bound ->
            val query = bound.prepareStatement("SELECT 1").use { it.execute() to it.resultSet.use { rows -> rows.next() } }
            val update = bound.createStatement().use { it.execute("UPDATE $table SET v = v WHERE id < 0") to it.updateCount }
            listOf(query, update)
        }
        answers.assert().containsExactly(true to true, false to 0)
        trips.assert().isEqualTo(2)
    }

    @Test
    fun `a first plain statement carries the bound too`() {
        val (value, trips) = transaction { bound ->
            bound.createStatement().use { statement ->
                statement.executeQuery("SELECT 41 + 1").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
        value.assert().isEqualTo(42)
        trips.assert().isEqualTo(1)
    }

    @Test
    fun `a first prepared statement refuses to batch, as its text carries the bound`() {
        val upsert = "INSERT INTO $table (id, v) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET v = excluded.v"
        listOf<(PreparedStatement) -> Any>({ it.addBatch() }, { it.executeBatch() }, { it.executeLargeBatch() })
            .forEach { batch ->
                assertThrownBy<IllegalStateException> {
                    transaction { bound ->
                        bound.prepareStatement(upsert).use { insert ->
                            insert.setInt(1, 201)
                            insert.setInt(2, 1)
                            batch(insert)
                        }
                    }
                }.actual().message.assert().startsWith("A store transaction's first statement cannot be a batch")
            }
        // Nothing of a refused batch ran.
        sum(201..201).assert().isZero()
        // A statement first carries the bound; the batch after it is the connection's own.
        val (counts, trips) = transaction { bound ->
            bound.prepareStatement("SELECT 1").use { it.executeQuery().close() }
            bound.prepareStatement(upsert).use { insert ->
                listOf(202, 203).forEach { id ->
                    insert.setInt(1, id)
                    insert.setInt(2, id)
                    insert.addBatch()
                }
                insert.executeBatch().toList()
            }
        }
        counts.assert().containsExactly(1, 1)
        trips.assert().isEqualTo(2)
        sum(202..203).assert().isEqualTo(405)
    }

    @Test
    fun `a first plain batch sends the bound on its own first`() {
        val (counts, trips) = transaction { bound ->
            bound.createStatement().use { statement ->
                statement.addBatch("INSERT INTO $table (id, v) VALUES (301, 1) ON CONFLICT (id) DO NOTHING")
                statement.addBatch("INSERT INTO $table (id, v) VALUES (302, 1) ON CONFLICT (id) DO NOTHING")
                statement.executeBatch().toList()
            }
        }
        counts.assert().containsExactly(1, 1)
        trips.assert().isEqualTo(2)
    }

    @Test
    fun `a read-only transaction refuses a write, with the bound carried by the write itself`() {
        val failure = assertThrownBy<SQLException> {
            transaction(readOnly = true) { bound ->
                bound.prepareStatement("INSERT INTO $table (id, v) VALUES (401, 1)").use { it.executeUpdate() }
            }
        }.actual()
        failure.sqlState.assert().isEqualTo(READ_ONLY_TRANSACTION)
    }

    @Test
    fun `a query whose statement returns no rows fails as plain JDBC does`() {
        assertThrownBy<SQLException> {
            transaction { bound -> bound.prepareStatement("UPDATE $table SET v = v WHERE id < 0").use { it.executeQuery() } }
        }.actual().message.assert().isEqualTo("No results were returned by the query.")
    }

    @Test
    fun `every other way of making a statement sends the bound on its own first`() {
        val select = "SELECT 1"
        val makers: List<(Connection) -> Statement> = listOf(
            { it.prepareStatement(select, Statement.NO_GENERATED_KEYS) },
            { it.prepareStatement(select, arrayOf("id")) },
            { it.prepareStatement(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY) },
            { it.prepareStatement(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, HOLD) },
            { it.prepareCall(select) },
            { it.prepareCall(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY) },
            { it.prepareCall(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, HOLD) },
        )
        makers.forEach { make ->
            transaction { bound ->
                make(bound).use { (it as PreparedStatement).execute() }
            }.second.assert().isEqualTo(2)
        }
        val plain: List<(Connection) -> Statement> = listOf(
            { it.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY) },
            { it.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, HOLD) },
        )
        plain.forEach { make ->
            transaction { bound -> make(bound).use { it.execute(select) } }.second.assert().isEqualTo(2)
        }
    }

    @Test
    fun `a plain execution with options sends the bound on its own first`() {
        val (isResultSet, trips) = transaction { bound ->
            bound.createStatement().use { it.execute("SELECT 1", Statement.NO_GENERATED_KEYS) }
        }
        isResultSet.assert().isTrue()
        trips.assert().isEqualTo(2)
    }

    @Test
    fun `large updates carry the bound and answer their own counts`() {
        transaction { bound ->
            bound.prepareStatement("INSERT INTO $table (id, v) VALUES (?, 1) ON CONFLICT (id) DO NOTHING").use {
                it.setInt(1, 501)
                it.executeLargeUpdate()
            }
        }.assert().isEqualTo(1L to 1)
        transaction { bound ->
            bound.createStatement().use {
                it.executeLargeUpdate("INSERT INTO $table (id, v) VALUES (502, 1) ON CONFLICT (id) DO NOTHING")
            }
        }.assert().isEqualTo(1L to 1)
        transaction { bound ->
            bound.createStatement().use {
                it.executeUpdate("INSERT INTO $table (id, v) VALUES (503, 1) ON CONFLICT (id) DO NOTHING")
            }
        }.assert().isEqualTo(1 to 1)
    }

    @Test
    fun `an update that returns rows, or a plain query that returns none, fails as plain JDBC does`() {
        listOf<(Connection) -> Any?>(
            { bound -> bound.prepareStatement("SELECT 1").use { it.executeUpdate() } },
            { bound -> bound.createStatement().use { it.executeUpdate("SELECT 1") } },
            { bound -> bound.createStatement().use { it.executeQuery("UPDATE $table SET v = v WHERE id < 0") } },
        ).forEach { run ->
            assertThrownBy<SQLException> { transaction { bound -> run(bound) } }
        }
    }

    @Test
    fun `a first statement used again keeps carrying the bound`() {
        val upsert = "INSERT INTO $table (id, v) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET v = excluded.v"
        transaction { bound ->
            bound.prepareStatement(upsert).use { insert ->
                listOf(601 to 1, 602 to 2).map { (id, v) ->
                    insert.clearParameters()
                    insert.setInt(1, id)
                    insert.setInt(2, v)
                    insert.executeUpdate()
                }
            }
        }.assert().isEqualTo(listOf(1, 1) to 2)
        sum(601..602).assert().isEqualTo(3)
    }

    @Test
    fun `the bound connection unwraps to itself or to what it wraps`() {
        transaction { bound ->
            bound.isWrapperFor(BoundConnection::class.java).assert().isTrue()
            bound.unwrap(BoundConnection::class.java).assert().isSameAs(bound)
            bound.isWrapperFor(org.postgresql.PGConnection::class.java).assert().isTrue()
            bound.unwrap(org.postgresql.PGConnection::class.java).assert().isNotNull()
            bound.prepareStatement("SELECT 1").use { it.executeQuery().close() }
        }
    }

    @Test
    fun `a connection without a bound is given to the block as it is`() {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.setNetworkTimeout(PostgresTimeouts.DIRECT, 0)
            inTransaction(connection) { it.assert().isSameAs(connection) }
        }
    }

    private fun sum(ids: IntRange): Int = PostgresFixture.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT coalesce(sum(v), 0) FROM $table WHERE id BETWEEN ? AND ?").use { query ->
            query.setInt(1, ids.first)
            query.setInt(2, ids.last)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun bounds(connection: Connection): List<String> = listOf(
        "statement_timeout",
        "lock_timeout",
        "idle_in_transaction_session_timeout",
    ).map { name ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW $name").use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

    /** [target], counting the executions (one round trip each) of the statements made on it. */
    private class RoundTrips(target: Connection) {
        var executions = 0
            private set

        val connection: Connection = proxy(Connection::class.java, target)

        private fun <T : Any> proxy(type: Class<T>, delegate: T): T = type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, args ->
                    if (delegate is Statement && method.name.startsWith("execute")) executions++
                    val result = try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                    when (method.name) {
                        "prepareStatement" -> proxy(PreparedStatement::class.java, result as PreparedStatement)
                        "createStatement" -> proxy(Statement::class.java, result as Statement)
                        "prepareCall" -> proxy(CallableStatement::class.java, result as CallableStatement)
                        else -> result
                    }
                },
            ),
        )
    }

    private companion object {
        const val READ_ONLY_TRANSACTION = "25006"
        const val HOLD = ResultSet.HOLD_CURSORS_OVER_COMMIT
    }
}
