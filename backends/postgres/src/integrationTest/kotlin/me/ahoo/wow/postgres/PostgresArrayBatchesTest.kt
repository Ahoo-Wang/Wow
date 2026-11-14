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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * PostgreSQL's batch statements bound as arrays above [PostgresBatches.ARRAYS_ABOVE] rows: one statement a call
 * whatever its size, and a statement that does not bind failed whole and counted, never written again a partition or a
 * snapshot at a time.
 */
class PostgresArrayBatchesTest : PostgresLogFixtures() {
    private val order = AggregateType(context, "order")

    private fun group(partitions: Int, from: Int, count: Int): List<Append> {
        val ids = generateSequence(from) { it + 1 }.map { "o$it" }
        // Several partitions in every group, so each is appended across partitions.
        val transport = byteArrayOf(1, 0, 92, 34)
        val group = ids.take(count).map { Append.of(stream(it, 1, aggregate = order), transport) }.toList()
        check(group.map { Partitioner.partitionOf(it.stream.ref, partitions) }.distinct().size > 1)
        return group
    }

    @Test
    fun `a group above the threshold is one array statement, call after call, and one at it the values form`(): Unit =
        runBlocking {
            val counted = CountedDataSource(PostgresFixture.dataSource)
            val log = PostgresEventLog(counted.dataSource, order.context, PARTITIONS).also {
                it.createSchema()
                it.createTable(order.name)
            }
            repeat(2) { call ->
                val mark = counted.begin()
                val above = group(PARTITIONS, call * 1_000, PostgresBatches.ARRAYS_ABOVE + 1)
                log.appendAll(above).assert().allMatch { it is AppendOutcome.Committed }
                counted.since(mark).assert().hasSize(1)
                counted.since(mark).single().assert().contains("unnest(")
            }
            val mark = counted.begin()
            log.appendAll(group(PARTITIONS, 5_000, PostgresBatches.ARRAYS_ABOVE))
                .assert().allMatch { it is AppendOutcome.Committed }
            counted.since(mark).assert().hasSize(1)
            counted.since(mark).single().assert().contains("VALUES").doesNotContain("unnest(")
        }

    @Test
    fun `an array append that does not bind is failed whole and counted, never appended a partition at a time`(): Unit =
        runBlocking {
            val unbound = UnboundArrays(PostgresFixture.dataSource)
            val log = PostgresEventLog(unbound.dataSource, order.context, PARTITIONS).also {
                it.createSchema()
                it.createTable(order.name)
            }
            val group = group(PARTITIONS, 0, PostgresBatches.ARRAYS_ABOVE + 1)
            val mark = unbound.prepared.size
            val outcomes = runCatching { log.appendAll(group) }
            outcomes.getOrNull()?.assert()?.noneMatch { it is AppendOutcome.Committed }
            log.bindFailures.assert().isEqualTo(1L)
            // The one statement that failed, no other: nothing was appended a partition at a time.
            unbound.prepared.drop(mark).assert().hasSize(1)
            val written = PostgresFixture.log(PARTITIONS, order.context)
            (0 until PARTITIONS).map { written.head(it) }.assert().containsOnlyNulls()
        }

    @Test
    fun `an array save that does not bind is failed whole and counted, never saved a snapshot at a time`(): Unit =
        runBlocking {
            val unbound = UnboundArrays(PostgresFixture.dataSource)
            val store = PostgresSnapshotStore(unbound.dataSource, order.context, arraysAbove = 0)
            store.createSchema()
            val snapshots = (1..3).map {
                val ref = order.ref("tenant", "s$it")
                SnapshotRecord(ref, 1, "fingerprint", "{}", firstEventTime = 1, lastEventTime = 1)
            }
            val mark = unbound.prepared.size
            assertThrows<IllegalStateException> { store.saveAll(snapshots) }
            store.bindFailures.assert().isEqualTo(1L)
            // The one save that failed, none a snapshot at a time.
            unbound.prepared.drop(mark).filter { "INSERT INTO \"wow_snapshot__" in it }.assert().hasSize(1)
        }

    /** A data source whose array statements fail to bind ([PostgresBatches.INVALID_PARAMETER_VALUE]). */
    private class UnboundArrays(target: DataSource) {
        val prepared = mutableListOf<String>()
        val dataSource: DataSource = proxy(DataSource::class.java, target)

        private fun <T : Any> proxy(type: Class<T>, delegate: T): T = type.cast(
            Proxy.newProxyInstance(
                type.classLoader,
                arrayOf(type),
                InvocationHandler { _, method, args ->
                    val sql = (args?.firstOrNull() as? String).takeIf { method.name == "prepareStatement" }
                    if (sql != null) prepared += sql
                    val result = try {
                        method.invoke(delegate, *(args ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                    when {
                        method.name == "getConnection" -> proxy(Connection::class.java, result as Connection)
                        sql != null && "unnest(" in sql -> unbound(result as PreparedStatement)
                        else -> result
                    }
                },
            ),
        )

        private fun unbound(statement: PreparedStatement): PreparedStatement = PreparedStatement::class.java.cast(
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
            ) { _, method, args ->
                if (method.name == "executeQuery") {
                    throw PSQLException("No value specified for a parameter.", PSQLState.INVALID_PARAMETER_VALUE)
                }
                try {
                    method.invoke(statement, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            },
        )
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
