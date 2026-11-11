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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.PartitionLineage
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** The reads of a partition: a run longer than one read takes, and the head of a partition. */
class PostgresTailReadsTest : PostgresLogFixtures() {
    @Test
    fun `a tail reads a run longer than its batch a batch at a time, without waiting to poll`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val type = AggregateType(context, "order")
        // A poll an hour apart: a tail that waited between its batches would not finish.
        val log = PostgresEventLog(
            PostgresFixture.dataSource,
            context,
            1,
            pollInterval = 1.hours,
            tailBatch = BATCH,
            maxPollInterval = 1.hours,
        ).also {
            it.createSchema()
            it.createTable(type.name)
        }
        repeat(ENTRIES) { log.append(stream(type.ref("tenant", "a$it")), null) }
        val entries = withTimeout(10.seconds) { log.tail(0, null).take(ENTRIES).toList() }
        entries.map { it.position.sequence }.assert().isEqualTo((1L..ENTRIES).toList())
    }

    @Test
    fun `head refuses a partition the log does not have, and one without its counter row, as lineage does`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(2, context)
            assertThrownBy<IllegalArgumentException> { runBlocking { log.head(-1) } }
                .hasMessage("No partition -1 among 2.")
            assertThrownBy<IllegalArgumentException> { runBlocking { log.head(2) } }
                .hasMessage("No partition 2 among 2.")
            val counters = PostgresEventLogTables(context).partitions
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DELETE FROM $counters WHERE partition = 1") }
            }
            // A damaged store: the partition is not empty, it has no counter, and both reads say so.
            val missing = "The log of $context has no counter of partition 1."
            assertThrownBy<IllegalStateException> { runBlocking { log.head(1) } }.hasMessage(missing)
            assertThrownBy<IllegalStateException> { runBlocking { log.lineage(1) } }.hasMessage(missing)
        }

    @Test
    fun `a partition's stored lineage reads back`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(2, context)
            val counters = PostgresEventLogTables(context).partitions
            // What an online split of 10.x writes: partition 1 split from partition 0 (decision D240).
            execute("UPDATE $counters SET parent = 0, epoch = 1 WHERE partition = 1")
            log.lineage(0).assert().isEqualTo(PartitionLineage.ROOT)
            log.lineage(1).assert().isEqualTo(PartitionLineage.of(0, 1))
            execute("DELETE FROM $counters WHERE partition = 1")
            runCatching { log.lineage(1) }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }

    @Test
    fun `a tail finds the aggregate tables another log created after it started`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val reader = PostgresFixture.log(1, context, listOf("order"))
        val writer = PostgresFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        writer.append(stream("a", 1, aggregate = order))
        reader.tail(0).first().stream.ref.assert().isEqualTo(order.ref("tenant", "a"))
        writer.append(stream("b", 1, aggregate = cart))
        writer.append(stream("a", 2, aggregate = order))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { reader.tail(0).take(3).toList() }
        entries.map { it.stream.ref.type }.assert().containsExactly(order, cart, order)
        reader.findByRequestId(cart, "tenant", entries[1].stream.requestId)?.ref
            .assert().isEqualTo(cart.ref("tenant", "b"))
    }

    @Test
    fun `a tail stops at a missing sequence rather than skip it`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = PostgresFixture.log(1, context)
        for (version in 1..3) {
            log.append(stream("a", version, aggregate = AggregateType(context, "order")))
        }
        PostgresFixture.dataSource.connection.use { connection ->
            val order = table(context, "order")
            connection.createStatement().use { it.execute("DELETE FROM \"$order\" WHERE sequence = 2") }
        }
        val read = withTimeoutOrNull(TAIL_STALL_MS) { log.tail(0).take(2).toList() }
        read.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    private fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    private companion object {
        const val BATCH = 2
        const val ENTRIES = 5
        const val TAIL_STALL_MS = 500L
    }
}
