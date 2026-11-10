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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.notAppendedAfter
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** How the log's appends answer the failures of their statements, and how a large group is cut into statements. */
class PostgresLogAppenderTest : PostgresLogFixtures() {
    @Test
    fun `an append whose statement fails once it may have left is unknown, a lost connection too`(): Unit =
        runBlocking {
            // The driver's own failure, and one of a pool's wrapper, which is not the driver's exception.
            val failures = listOf(
                PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE),
                SQLException("This connection has been closed.", "08003"),
            )
            failures.forEach { failure ->
                val context = PostgresFixture.newContext()
                val log = log(context) { sql -> failure.takeIf { sql.startsWith(SINGLE_STATEMENT) } }
                val outcome = log.append(stream(AggregateType(context, "order").ref("tenant", "a"), 1), null)
                (outcome as AppendOutcome.Unknown).cause.assert().isSameAs(failure)
                log.head(0).assert().isNull()
            }
        }

    @Test
    fun `an append that fails before its statement leaves is thrown, whatever the failure, never unknown`(): Unit =
        runBlocking {
            // Nothing was written: the command is answered with the failure, translated as the log translates it.
            val context = PostgresFixture.newContext()
            val refused = SQLException("permission denied for table", "42501")
            PostgresFixture.log(1, context)
            val log = PostgresEventLog(unreachable(refused), context, 1)
            assertThrownBy<SQLException> {
                runBlocking { log.append(stream(AggregateType(context, "order").ref("tenant", "a"), 1), null) }
            }.isSameAs(refused)
        }

    @Test
    fun `a group whose later chunk gets no connection keeps the first chunk's commits, the rest unknown`(): Unit =
        runBlocking {
            // The call commits a chunk a time: once one was sent, a later one that fails unsent cannot be thrown,
            // or every command of the call would be answered as committing nothing.
            val context = PostgresFixture.newContext()
            val lost = SQLTransientConnectionException("The pool timed out.")
            PostgresFixture.log(1, context, listOf("order"))
            val lent = AtomicInteger()
            val log = PostgresEventLog(connections(PostgresFixture.dataSource, lent, failing = 2, lost), context, 1)
            val order = AggregateType(context, "order")
            // Three chunks: the first commits, the second gets no connection, the third is not tried.
            val streams = (0..2 * STATEMENT_STREAMS).map { stream(order.ref("tenant", "a$it"), 1) to null }
            val outcomes = log.appendAll(streams)
            outcomes.take(STATEMENT_STREAMS).assert().isEqualTo((1L..STATEMENT_STREAMS).map { committed(it) })
            outcomes.drop(STATEMENT_STREAMS).forEach { (it as AppendOutcome.Unknown).cause.assert().isSameAs(lost) }
            lent.get().assert().isEqualTo(2)
            PostgresFixture.log(1, context).head(0).assert().isEqualTo(STATEMENT_STREAMS.toLong())
        }

    @Test
    fun `a group PostgreSQL ends a deadlock with is appended one by one, and commits`(): Unit = runBlocking {
        val context = PostgresFixture.newContext()
        val log = log(context) { sql -> deadlock().takeIf { sql.startsWith(GROUP_STATEMENT) } }
        val order = AggregateType(context, "order")
        val streams = listOf(stream(order.ref("tenant", "a"), 1), stream(order.ref("tenant", "b"), 1))
        val outcomes = log.appendAll(streams.map { it to null })
        outcomes.assert().isEqualTo(listOf(committed(1), committed(2)))
    }

    @Test
    fun `a group whose connection is lost while it runs is unknown for every stream, never appended one by one`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val lost =
                PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE)
            val log = log(context) { sql -> lost.takeIf { sql.startsWith(GROUP_STATEMENT) } }
            val order = AggregateType(context, "order")
            val outcomes = log.appendAll((1..3).map { stream(order.ref("tenant", "a$it"), 1) to null })
            outcomes.forEach { (it as AppendOutcome.Unknown).cause.assert().isSameAs(lost) }
            log.head(0).assert().isNull()
        }

    @Test
    fun `a stream appended alone after its group aborted is unknown when its statement fails, and its later ones are not appended`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val closed = SQLException("This connection has been closed.", "08003")
            val log = log(context) { sql ->
                when {
                    sql.startsWith(GROUP_STATEMENT) -> deadlock()
                    sql.startsWith(SINGLE_STATEMENT) -> closed
                    else -> null
                }
            }
            val order = AggregateType(context, "order")
            val first = stream(order.ref("tenant", "a"), 1)
            val outcomes = log.appendAll(listOf(first to null, stream(first.ref, 2) to null))
            outcomes[0].assert().isEqualTo(AppendOutcome.Unknown(closed))
            outcomes[1].assert().isEqualTo(notAppendedAfter(first, outcomes[0]))
            log.head(0).assert().isNull()
        }

    @Test
    fun `a group of more streams than a statement takes is committed a statement at a time, an aggregate's streams in one`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val prepared = ConcurrentLinkedQueue<String>()
            val log = log(context) { sql ->
                prepared += sql
                null
            }
            val order = AggregateType(context, "order")
            // The last aggregate of the first statement has its second stream after it: it stays in that statement.
            val firsts = (0 until STATEMENT_STREAMS).map { stream(order.ref("tenant", "a$it"), 1) }
            val second = stream(firsts.last().ref, 2)
            val next = stream(order.ref("tenant", "b"), 1)
            prepared.clear()
            val outcomes = log.appendAll((firsts + second + next).map { it to null })
            outcomes.assert().isEqualTo((1L..STATEMENT_STREAMS + 2L).map { committed(it) })
            // Each stream of a group statement binds one `?::boolean`: the statements hold 1,001 streams, then 1.
            val groups = prepared.filter { it.startsWith(GROUP_STATEMENT) }.map { sql -> sql.split("?::boolean").size - 1 }
            groups.assert().isEqualTo(listOf(STATEMENT_STREAMS + 1, 1))
            log.read(second.ref).toList().map { it.version }.assert().isEqualTo(listOf(1, 2))
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

    @Test
    fun `a group of several partitions is one statement, and a duplicate in one retries each partition alone`(): Unit =
        runBlocking {
            // Roadmap §6 item 15: the append queue's mergers hand several partitions' groups to one call.
            val counted = CountedDataSource(PostgresFixture.dataSource)
            val context = PostgresFixture.newContext()
            val log = PostgresEventLog(counted.dataSource, context, ACROSS).also { it.createSchema() }
            val order = AggregateType(context, "order")
            log.appendsAcrossPartitions.assert().isTrue()
            log.append(stream("warm", 1, aggregate = order))
            val ids = idsByPartition(order, ACROSS)
            val mark = counted.begin()
            val group = (0 until ACROSS).flatMap { partition -> ids[partition].take(2) }
                .map { stream(it, 1, aggregate = order) to null }
            val outcomes = log.appendAll(group)
            counted.since(mark).size.assert().isEqualTo(1)
            outcomes.assert().allMatch { it is AppendOutcome.Committed }
            outcomes.map { (it as AppendOutcome.Committed).position.partition }.assert()
                .isEqualTo(group.map { (stream) -> Partitioner.partitionOf(stream.ref, ACROSS) })
            // A request committed in partition 0 repeated there: that partition's group answers it, the others commit.
            val repeated = stream(ids[0][2], 1, requestId = group.first().first.requestId, aggregate = order)
            val again = log.appendAll(
                listOf(repeated to null) + (1 until ACROSS).map { stream(ids[it][2], 1, aggregate = order) to null },
            )
            again.first().assert().isInstanceOf(AppendOutcome.AlreadyCommitted::class.java)
            again.drop(1).assert().allMatch { it is AppendOutcome.Committed }
        }

    @Test
    fun `a value PostgreSQL refuses in a group of several partitions is rejected alone, the others commit`(): Unit =
        runBlocking {
            // The statement aborts as a whole; each partition is then appended as its own group (decision D280).
            val context = PostgresFixture.newContext()
            val order = AggregateType(context, "order")
            val log = PostgresFixture.log(ACROSS, context, listOf(order.name))
            val ids = idsByPartition(order, ACROSS)
            val group = (0 until ACROSS).map { partition ->
                // A NUL PostgreSQL refuses in a text column, in partition 1's stream.
                val commandType = if (partition == 1) "$context.order.Act\u0000" else "$context.order.Act"
                stream(ids[partition][0], 1, commandType = commandType, aggregate = order) to null
            }
            val outcomes = log.appendAll(group)
            outcomes[1].assert().isInstanceOf(AppendOutcome.Rejected::class.java)
            outcomes.filterIndexed { index, _ -> index != 1 }.assert().allMatch { it is AppendOutcome.Committed }
            (0 until ACROSS).filter { it != 1 }.forEach { partition -> log.head(partition).assert().isEqualTo(1L) }
        }

    @Test
    fun `a stream that does not fit in a group of several partitions conflicts alone, and another context is refused`(): Unit =
        runBlocking {
            val context = PostgresFixture.newContext()
            val order = AggregateType(context, "order")
            val log = PostgresFixture.log(ACROSS, context, listOf(order.name))
            val ids = idsByPartition(order, ACROSS)
            // Partition 2's aggregate has no stream yet: its version 2 conflicts, the other partitions commit.
            val group = (0 until ACROSS).map { partition ->
                stream(ids[partition][0], if (partition == 2) 2 else 1, aggregate = order) to null
            }
            val outcomes = log.appendAll(group)
            outcomes[2].assert().isEqualTo(AppendOutcome.Conflict(0))
            outcomes.filterIndexed { index, _ -> index != 2 }.assert().allMatch { it is AppendOutcome.Committed }
            log.head(2).assert().isNull()
            // A group holds the streams of this log's context alone.
            val elsewhere = stream("x", 1, aggregate = AggregateType(PostgresFixture.newContext(), "order"))
            assertThrownBy<IllegalArgumentException> { runBlocking { log.appendAll(group.take(1) + (elsewhere to null)) } }
        }

    @Test
    fun `groups across overlapping partitions in any order commit at once, with no deadlock and no gap`(): Unit =
        runBlocking {
            // Each group locks its partitions' counters in partition order, whatever order its streams come in.
            val context = PostgresFixture.newContext()
            val order = AggregateType(context, "order")
            val log = PostgresFixture.log(ACROSS, context, listOf(order.name))
            val ids = idsByPartition(order, ACROSS)
            val next = IntArray(ACROSS)
            val groups = (0 until GROUPS).map { round ->
                (0 until ACROSS).shuffled(java.util.Random(round.toLong())).take(ACROSS - 1).map { partition ->
                    stream(ids[partition][next[partition]++], 1, aggregate = order) to null
                }
            }
            val outcomes = coroutineScope {
                groups.map { group -> async(Dispatchers.IO) { log.appendAll(group) } }.awaitAll().flatten()
            }
            outcomes.assert().allMatch { it is AppendOutcome.Committed }
            outcomes.map { (it as AppendOutcome.Committed).position }.groupBy { it.partition }.forEach { (partition, at) ->
                at.map { it.sequence }.sorted().assert().isEqualTo((1L..next[partition]).toList())
            }
        }

    @Test
    fun `groups of two partitions repeating each other's request ids in opposite orders do not deadlock`(): Unit =
        runBlocking {
            // Two groups wait on each other's request keys unless both take them in one order.
            val context = PostgresFixture.newContext()
            val types = listOf(AggregateType(context, "order"), AggregateType(context, "cart"))
            val log = PostgresFixture.log(2, context, types.map { it.name })
            val ids = generateSequence(1) { it + 1 }.map { "r$it" }
            val inPartition = (0..1).map { partition ->
                types.associateWith { type ->
                    ids.filter { Partitioner.partitionOf(type.ref("tenant", it), 2) == partition }
                        .take(ROUNDS * REQUESTS).toList()
                }
            }
            val outcomes = ConcurrentLinkedQueue<AppendOutcome>()
            (0 until ROUNDS).forEach { round ->
                // Both groups of a round start together.
                coroutineScope {
                    (0..1).forEach { partition ->
                        val requests = (0 until REQUESTS).map { "round-$round-request-$it" }
                        val ordered = if (partition == 0) requests else requests.reversed()
                        val group = types.let { if (partition == 0) it else it.reversed() }.flatMap { type ->
                            ordered.mapIndexed { index, request ->
                                val id = inPartition[partition].getValue(type)[round * REQUESTS + index]
                                stream(id, 1, requestId = request, aggregate = type) to null
                            }
                        }
                        launch(Dispatchers.IO) { outcomes += log.appendAll(group) }
                    }
                }
            }
            outcomes.filter { it is AppendOutcome.Unknown || it is AppendOutcome.Rejected }.assert().isEmpty()
            outcomes.count { it is AppendOutcome.Committed }.assert().isEqualTo(ROUNDS * REQUESTS * types.size)
        }

    @Test
    fun `an append that cannot get a connection is the retryable storage error, not an unknown outcome`(): Unit =
        runBlocking {
            // Decision D261: nothing reached the store, so the command is answered as unavailable (finding A2-6).
            val context = PostgresFixture.newContext()
            val healthy = PostgresEventLog(PostgresFixture.dataSource, context, PARTITIONS).also { it.createSchema() }
            val type = AggregateType(context, "order")
            healthy.append(stream("a", 1, aggregate = type))
            val loader = DataSource::class.java.classLoader
            val unreachable = Proxy.newProxyInstance(loader, arrayOf(DataSource::class.java)) { _, method, args ->
                if (method.name == "getConnection") throw SQLTransientConnectionException("The pool timed out.")
                try {
                    method.invoke(PostgresFixture.dataSource, *(args ?: emptyArray()))
                } catch (thrown: InvocationTargetException) {
                    throw thrown.targetException
                }
            } as DataSource
            val log = PostgresEventLog(unreachable, context, PARTITIONS)
            runCatching { log.append(stream("a", 2, aggregate = type)) }.exceptionOrNull()
                .assert().isInstanceOf(StorageUnavailableException::class.java)
            val group = listOf(stream("b", 1, aggregate = type) to null, stream("c", 1, aggregate = type) to null)
            runCatching { log.appendAll(group) }.exceptionOrNull()
                .assert().isInstanceOf(StorageUnavailableException::class.java)
            healthy.read(type.ref("tenant", "a")).toList().size.assert().isEqualTo(1)
        }

    private fun committed(sequence: Long) = AppendOutcome.Committed(LogPosition(0, sequence))

    private fun deadlock() = PSQLException(ServerErrorMessage("SERROR\u0000C40P01\u0000Mdeadlock detected\u0000"))

    /**
     * A log of [context] with one partition, its table of `order` made sure of, over connections whose statements
     * fail with what [failureOf] gives for their SQL when they run, and run when it gives null.
     */
    private fun log(context: String, failureOf: (String) -> SQLException?): PostgresEventLog {
        PostgresFixture.log(1, context, listOf("order"))
        return PostgresEventLog(failing(PostgresFixture.dataSource, failureOf), context, 1)
    }

    private fun failing(target: DataSource, failureOf: (String) -> SQLException?): DataSource =
        proxy(DataSource::class.java, target) { name, _ ->
            if (name != "getConnection") return@proxy null
            val connection = target.connection
            proxy(Connection::class.java, connection) { method, arguments ->
                val sql = (arguments.firstOrNull() as? String)?.trimStart()
                if (method != "prepareStatement" || sql == null) return@proxy null
                val failure = failureOf(sql)
                proxy(PreparedStatement::class.java, connection.prepareStatement(sql)) { call, _ ->
                    if (call == "executeQuery" && failure != null) throw failure else null
                }
            }
        }

    /** A data source whose every connection fails with [failure]. */
    /**
     * A data source whose [failing]th connection, counted from one in [lent], fails with [failure]; the others are
     * lent.
     */
    private fun connections(target: DataSource, lent: AtomicInteger, failing: Int, failure: SQLException): DataSource =
        proxy(DataSource::class.java, target) { name, _ ->
            if (name == "getConnection" && lent.incrementAndGet() == failing) throw failure else null
        }

    private fun unreachable(failure: SQLException): DataSource =
        proxy(DataSource::class.java, PostgresFixture.dataSource) { name, _ ->
            if (name == "getConnection") throw failure else null
        }

    private fun <T : Any> proxy(type: Class<T>, delegate: T, intercept: (String, Array<Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            val arguments = args ?: emptyArray()
            intercept(method.name, arguments) ?: try {
                method.invoke(delegate, *arguments)
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        },
    )

    /** Aggregate ids of [type] by the partition of [partitions] each falls in, plenty of each. */
    private fun idsByPartition(type: AggregateType, partitions: Int): List<List<String>> {
        val ids = generateSequence(1) { it + 1 }.map { "x$it" }.take(partitions * PER_PARTITION * 4).toList()
        return (0 until partitions).map { partition ->
            ids.filter { Partitioner.partitionOf(type.ref("tenant", it), partitions) == partition }
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
        /** The single append's statement, and a group's ([PostgresEventLogTables.appendGroup]). */
        const val SINGLE_STATEMENT = "WITH current"
        const val GROUP_STATEMENT = "WITH input"

        /** The most streams one group statement takes. */
        const val STATEMENT_STREAMS = 1000
        const val LONG_ID_UUIDS = 300
        const val POLL_MS = 10L
        const val AGGREGATES = 16
        const val VERSIONS = 10
        const val ACROSS = 4
        const val GROUPS = 24
        const val PER_PARTITION = GROUPS
        const val ROUNDS = 100
        const val REQUESTS = 32
    }
}
