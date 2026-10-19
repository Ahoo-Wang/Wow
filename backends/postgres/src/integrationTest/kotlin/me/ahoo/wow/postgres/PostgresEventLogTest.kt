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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
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
    fun `a partition's stored lineage reads back, and a counter table created without it gets it`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(2, context)
            val counters = PostgresEventLogTables(context).partitions
            // What an online split of 10.x writes: partition 1 split from partition 0 (decision D240).
            execute("UPDATE $counters SET parent = 0, epoch = 1 WHERE partition = 1")
            log.lineage(0).assert().isEqualTo(PartitionLineage.ROOT)
            log.lineage(1).assert().isEqualTo(PartitionLineage.of(0, 1))
            // A counter table of format 1 created before the lineage columns reads as the partitions it was created with.
            execute("ALTER TABLE $counters DROP COLUMN parent, DROP COLUMN epoch")
            log.lineage(1).assert().isEqualTo(PartitionLineage.ROOT)
            log.createSchema()
            execute("UPDATE $counters SET parent = 0, epoch = 1 WHERE partition = 1")
            log.lineage(1).assert().isEqualTo(PartitionLineage.of(0, 1))
            execute("DELETE FROM $counters WHERE partition = 1")
            runCatching { log.lineage(1) }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }

    private fun execute(sql: String) {
        PostgresFixture.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

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

    @Test
    fun `a group commit is one statement, and each conflict in it one lookup more`(): Unit = runBlocking {
        // B.G-1: a group of two aggregate types costs the round trip of one append.
        val counted = CountedDataSource(PostgresFixture.dataSource)
        val context = PostgresFixture.newContext()
        val log = PostgresEventLog(counted.dataSource, context, 1).also { it.createSchema() }
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        log.append(stream("warm", 1, aggregate = order))
        log.append(stream("warm", 1, aggregate = cart))
        val mark = counted.begin()
        val group = (1..GROUP).flatMap {
            listOf(stream("o$it", 1, aggregate = order) to null, stream("c$it", 1, aggregate = cart) to null)
        }
        log.appendAll(group).map { (it as AppendOutcome.Committed).position.sequence }
            .assert().isEqualTo((3L..GROUP * 2L + 2).toList())
        counted.since(mark).size.assert().isEqualTo(1)
        val withConflict = counted.begin()
        val outcomes = log.appendAll(
            listOf(stream("o1", 2, aggregate = order) to null, stream("o2", 3, aggregate = order) to null),
        )
        outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(1))
        counted.since(withConflict).size.assert().isEqualTo(2)
    }

    @Test
    fun `a group whose commit is unknown is unknown for every stream, never appended one by one`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val lost = SQLException("An I/O error occurred while sending to the backend.", "08006")
            val log = PostgresEventLog(failingGroups(PostgresFixture.dataSource, lost), context, 1)
                .also { it.createSchema() }
            val order = AggregateType(context, "order")
            val outcomes = log.appendAll((1..3).map { stream("a$it", 1, aggregate = order) to null })
            outcomes.forEach { (it as AppendOutcome.Unknown).cause.assert().isSameAs(lost) }
            log.head(0).assert().isNull()
        }

    @Test
    fun `a version another node commits while the group waits on the counter fails that stream alone`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val log = PostgresFixture.log(1, context, listOf("order"))
            val order = AggregateType(context, "order")
            val counters = PostgresEventLogTables(context).partitions
            PostgresFixture.dataSource.connection.use { rival ->
                // Another node's append of "taken", holding the counter row until it commits.
                rival.autoCommit = false
                rival.createStatement().use {
                    it.execute("UPDATE $counters SET sequence = sequence + 1 WHERE partition = 0")
                    it.execute(
                        "INSERT INTO \"${table(context, "order")}\" (partition, sequence, tenant_id, aggregate_id, " +
                            "version, command_id, request_id, command_type, commit_time, events, headers) " +
                            "VALUES (0, 1, 'tenant', 'taken', 1, 'rival', 'rival', 'Act', 0, " +
                            "'[{\"name\": \"Happened\", \"revision\": 1, \"payload\": {}}]', '{}')",
                    )
                }
                val group = async(Dispatchers.IO) {
                    log.appendAll(listOf("first", "taken", "last").map { stream(it, 1, aggregate = order) to null })
                }
                // The group read the versions before the rival committed, and queues on the counter row.
                withTimeout(TAIL_TIMEOUT_MS) {
                    while (!groupWaitsOnLock()) {
                        delay(POLL_MS)
                    }
                }
                rival.commit()
                val outcomes = group.await()
                outcomes[0].assert().isEqualTo(AppendOutcome.Committed(LogPosition(0, 2)))
                outcomes[1].assert().isEqualTo(AppendOutcome.Conflict(1))
                outcomes[2].assert().isEqualTo(AppendOutcome.Committed(LogPosition(0, 3)))
            }
            log.head(0).assert().isEqualTo(3L)
            withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).take(3).toList() }.map { it.stream.ref.id }
                .assert().isEqualTo(listOf("taken", "first", "last"))
        }

    @Test
    fun `nodes committing groups and single appends to one partition at once leave no gap`(): Unit = runBlocking {
        // D10, D71: the partition's owner commits groups while other nodes append in Local mode; a tail started
        // before them reads one contiguous run.
        val context = PostgresFixture.newContext()
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        val refs = (1..AGGREGATES).map { (if (it % 2 == 0) order else cart).ref("tenant", "a$it") }
        val total = AGGREGATES * VERSIONS
        val reader = PostgresFixture.log(1, context)
        val tailed = async(Dispatchers.IO) { withTimeout(TAIL_TIMEOUT_MS) { reader.tail(0).take(total).toList() } }
        val committed = ConcurrentLinkedQueue<LogPosition>()
        coroutineScope {
            listOf(true, true, false).forEach { grouped ->
                val log = PostgresFixture.log(1, context)
                launch(Dispatchers.IO) {
                    val known = refs.associateWith { 0 }.toMutableMap()
                    while (known.values.any { it < VERSIONS }) {
                        val due = refs.filter { known.getValue(it) < VERSIONS }.shuffled()
                        val appends = due.map { stream(it.id, known.getValue(it) + 1, aggregate = it.type) to null }
                        val outcomes = if (grouped) {
                            log.appendAll(appends)
                        } else {
                            appends.take(1).map { (stream) -> log.append(stream) }
                        }
                        due.zip(outcomes).forEach { (ref, outcome) ->
                            known[ref] = when (outcome) {
                                is AppendOutcome.Committed -> {
                                    committed += outcome.position
                                    known.getValue(ref) + 1
                                }

                                is AppendOutcome.Conflict -> outcome.currentVersion
                                else -> throw AssertionError("An append of $ref ended $outcome.")
                            }
                        }
                    }
                }
            }
        }
        committed.map { it.sequence }.sorted().assert().isEqualTo((1L..total).toList())
        reader.head(0).assert().isEqualTo(total.toLong())
        val entries = tailed.await()
        entries.map { it.position.sequence }.assert().isEqualTo((1L..total).toList())
        refs.forEach { ref ->
            entries.filter { it.stream.ref == ref }.map { it.stream.version }.assert().isEqualTo((1..VERSIONS).toList())
        }
    }

    private fun groupWaitsOnLock(): Boolean = PostgresFixture.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'WITH input%'",
            ).use { rows -> rows.next() && rows.getInt(1) > 0 }
        }
    }

    /** [target], whose group commit statements fail with [failure] instead of running: their outcome is unknown. */
    private fun failingGroups(target: DataSource, failure: SQLException): DataSource {
        fun <T : Any> proxy(type: Class<T>, delegate: T, intercept: (String, Array<Any?>) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val arguments = args ?: emptyArray()
                intercept(method.name, arguments) ?: try {
                    method.invoke(delegate, *arguments)
                } catch (thrown: InvocationTargetException) {
                    throw thrown.targetException
                }
            },
        )
        return proxy(DataSource::class.java, target) { name, _ ->
            if (name != "getConnection") return@proxy null
            val connection = target.connection
            proxy(Connection::class.java, connection) { method, arguments ->
                val sql = arguments.firstOrNull() as? String
                if (method != "prepareStatement" || sql?.startsWith("WITH input") != true) return@proxy null
                proxy(PreparedStatement::class.java, connection.prepareStatement(sql)) { call, _ ->
                    if (call == "executeQuery") throw failure else null
                }
            }
        }
    }

    private companion object {
        const val TAIL_STALL_MS = 500L
        const val LONG_ID_UUIDS = 300
        const val IDLE_POLLS = 13
        const val COMMITS = 5
        const val SETTLE_MS = 100L
        const val GROUP = 10
        const val POLL_MS = 10L
        const val AGGREGATES = 16
        const val VERSIONS = 10
    }
}
