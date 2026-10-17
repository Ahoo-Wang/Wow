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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class PostgresEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)

    /** A `\u0000` in a payload string: PostgreSQL's `jsonb` does not take it (finding A2-7). */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Named", 1, """{"name":"a\u0000b"}""")),
    )

    private fun table(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"

    @Test
    fun `a key too long for its index is rejected, not unknown`(): Unit = runBlocking {
        // Finding A3-10: SQLSTATE 54000 was answered as unknown, which the caller retries for ever.
        // Random, so that compressing the index row does not bring it under the limit (about 2.7 KB).
        val long = stream((1..LONG_ID_UUIDS).joinToString("") { UUID.randomUUID().toString() }, 1)
        val log = createLog(long.ref.type.context, 1)
        val outcome = log.append(long)
        (outcome as AppendOutcome.Rejected).cause.message.assert().contains("SQLSTATE 54000")
        log.head(0).assert().isNull()
    }

    @Test
    fun `creating the schema twice is harmless`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order"))
        PostgresFixture.log(4, context, listOf("order")).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context)
        PostgresFixture.bumpFormat(table(context, "partition"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context) }
    }

    @Test
    fun `an aggregate table recorded in another format version is refused`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order"))
        PostgresFixture.bumpFormat(table(context, "order"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context, listOf("order")) }
        val log = PostgresFixture.log(4, context)
        assertThrownBy<IllegalStateException> {
            runBlocking { log.read(AggregateType(context, "order").ref("tenant", "a")).toList() }
        }
    }

    @Test
    fun `a log created by a build without format records is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.createUnrecorded(table(context, "partition"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context) }
    }

    @Test
    fun `a different partition count is refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context)
        assertThrownBy<IllegalStateException> { PostgresFixture.log(8, context) }
    }

    @Test
    fun `an aggregate named like the partition counters is refused`() {
        val context = PostgresFixture.newContext()
        assertThrownBy<IllegalArgumentException> { PostgresFixture.log(4, context, listOf("Partition")) }
    }

    @Test
    fun `two aggregates that take one table name are refused`() {
        val context = PostgresFixture.newContext()
        PostgresFixture.log(4, context, listOf("order-item"))
        assertThrownBy<IllegalStateException> { PostgresFixture.log(4, context, listOf("order_item")) }
    }

    @Test
    fun `a long aggregate name is cut and hashed within the identifier limit`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val long = AggregateType(context, "a".repeat(80))
        val log = PostgresFixture.log(1, context, listOf(long.name))
        log.append(stream("a", 1, aggregate = long)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        log.append(stream("a", 1, aggregate = long)).assert().isEqualTo(AppendOutcome.Conflict(1))
        val name = PostgresIdentifiers.of("wow_log", context, long.name)
        name.length.assert().isEqualTo(PostgresIdentifiers.MAX_LENGTH)
        name.assert().isEqualTo(PostgresIdentifiers.limited(table(context, long.name)))
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
        reader.findByRequestId("tenant", entries[1].stream.requestId)?.ref.assert().isEqualTo(cart.ref("tenant", "b"))
    }

    @Test
    fun `a tail stops at a missing sequence rather than skip it`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = PostgresFixture.log(1, context)
        (1..3).forEach { log.append(stream("a", it, aggregate = AggregateType(context, "order"))) }
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DELETE FROM \"${table(context, "order")}\" WHERE sequence = 2") }
        }
        val read = withTimeoutOrNull(TAIL_STALL_MS) { log.tail(0).take(2).toList() }
        read.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    @Test
    fun `a commit of this log costs its idle tail one read`(): Unit = runBlocking {
        // B.D diagnostics: the poll a commit wakes finds the entry and keeps the backoff, without a run of short polls.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val log = PostgresEventLog(
            counted.dataSource,
            context,
            1,
            pollInterval = 1.milliseconds,
            maxPollInterval = 1.hours,
        ).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type))
        fun reads(mark: Int) = counted.since(mark).count { it.contains("AS head") }
        val start = counted.begin()
        val received = Channel<LogEntry>(Channel.UNLIMITED)
        val tail = launch(Dispatchers.Default) { log.tail(0).collect { received.send(it) } }
        try {
            withTimeout(TAIL_TIMEOUT_MS) { received.receive() }
            // Twelve empty polls back the tail off to seconds between polls.
            withTimeout(TAIL_TIMEOUT_MS) {
                while (reads(start) < IDLE_POLLS) {
                    delay(10)
                }
            }
            val mark = counted.begin()
            (2..COMMITS + 1).forEach { version ->
                log.append(stream("a", version, aggregate = type))
                withTimeout(TAIL_TIMEOUT_MS) { received.receive() }.stream.version.assert().isEqualTo(version)
            }
            delay(SETTLE_MS)
            reads(mark).assert().isEqualTo(COMMITS)
        } finally {
            tail.cancel()
        }
    }

    private companion object {
        const val TAIL_STALL_MS = 500L
        const val LONG_ID_UUIDS = 300
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
    }
}
