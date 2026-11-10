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

package me.ahoo.wow.migrate

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EventStreamImporterTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val log = InMemoryEventLog(4)
    private val checkpoints = InMemoryCheckpointRepository()
    private var now = 1_000L
    private val reports = mutableListOf<ImportProgress>()

    private fun importer(
        source: NineXEventSource,
        options: ImportOptions = ImportOptions(),
        target: EventLog = log,
        plan: MigrationPlan = NineXExample.plan,
    ) = EventStreamImporter(source, plan, { target }, checkpoints, options, { now }, { reports += it })

    private fun copyOf(stream: NineXStream, id: String, version: Int = stream.version, createTime: Long = stream.createTime) =
        stream.copy(
            id = "${stream.id}-$id",
            aggregateId = stream.aggregateId.copy(id = id),
            commandId = "${stream.commandId}-$id",
            requestId = "${stream.requestId}-$id",
            version = version,
            createTime = createTime,
        )

    @Test
    fun `the 9x example's data imports and rebuilds the 9x state`(): Unit = runTest {
        val streams = NineXExample.streams()
        importer(InMemoryNineXEventSource(streams)).also {
            it.importAll(order).imported.assert().isEqualTo(4)
            it.importAll(cart).imported.assert().isEqualTo(4)
        }
        val kernel = Kernel(log)
        val orderStream = streams.first { it.aggregate == order }
        val orderRef = NineXExample.orderModel.type.ref(orderStream.aggregateId.tenantId, orderStream.aggregateId.id)
        val orderState = kernel.stateAt(NineXExample.orderModel, orderRef, 4)!!
        NineXExample.sameJson(
            WowJson.mapper.valueToTree(orderState.state),
            WowJson.mapper.readTree(NineXExample.resource("order-state.json")),
        ).assert().isTrue()
        orderState.scope.ownerId.assert().isEqualTo("customer-1")
        val cartStream = streams.first { it.aggregate == cart }
        val cartRef = NineXExample.cartModel.type.ref(cartStream.aggregateId.tenantId, cartStream.aggregateId.id)
        NineXExample.sameJson(
            WowJson.mapper.valueToTree(kernel.stateAt(NineXExample.cartModel, cartRef, 4)!!.state),
            WowJson.mapper.readTree(NineXExample.resource("cart-state.json")),
        ).assert().isTrue()
        checkpoints.load().of(order)!!.completed.assert().isTrue()
        reports.last().done.assert().isTrue()
    }

    @Test
    fun `a rerun skips what the log holds and a completed import does nothing`(): Unit = runTest {
        val source = InMemoryNineXEventSource(NineXExample.streams())
        importer(source).importAll(order)
        importer(source, ImportOptions(restart = true)).importAll(order).also {
            it.imported.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(4)
        }
        importer(source).importAll(order).instances.assert().isEqualTo(0)
    }

    @Test
    fun `a stopped import resumes after its last page`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((1..5).map { copyOf(first, "order-$it") })
        val options = ImportOptions(pageSize = 2, concurrency = 2)
        checkpoints.save(
            ImportCheckpoints().with(
                order,
                AggregateCheckpoint(startedAt = 1, after = NineXAggregateId("order-2", "(0)"))
            ),
        )
        importer(source, options).importAll(order).also {
            it.instances.assert().isEqualTo(3)
            it.imported.assert().isEqualTo(3)
        }
        log.read(NineXExample.orderModel.type.ref("(0)", "order-1")).toList().assert().isEmpty()
        reports.count { !it.done }.assert().isEqualTo(2)
    }

    @Test
    fun `an instance its page read in part is read on from 9x, and one read whole is not read again`(): Unit = runTest {
        val inMemory = InMemoryNineXEventSource(NineXExample.streams())
        val reads = mutableListOf<Int>()
        val source = object : NineXEventSource by inMemory {
            override fun instances(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int, maxStreams: Int) =
                inMemory.instances(aggregate, after, limit, if (aggregate == order) 1 else maxStreams)

            override fun streams(aggregate: NineXAggregate, id: NineXAggregateId, fromVersion: Int, limit: Int) =
                inMemory.streams(aggregate, id, fromVersion, limit).also { reads += fromVersion }
        }
        importer(source, ImportOptions(streamBatch = 2, readParallelism = 1)).also {
            it.importAll(order).imported.assert().isEqualTo(4)
            it.importAll(cart).imported.assert().isEqualTo(4)
        }
        reads.assert().containsExactly(2, 4, 5)
        log.read(NineXExample.orderModel.type.ref("(0)", NineXExample.stream("order-stream-v1.json").aggregateId.id))
            .toList().map { it.version }.assert().containsExactly(1, 2, 3, 4)
        ImportOptions(pageSize = 3).pageStreams.assert().isEqualTo(30)
    }

    @Test
    fun `a dry run converts every stream and appends none`(): Unit = runTest {
        importer(InMemoryNineXEventSource(NineXExample.streams()), ImportOptions(dryRun = true)).importAll(order)
            .imported.assert().isEqualTo(4)
        // An instance its page read in part is read on from 9.x, as the import reads it.
        val inMemory = InMemoryNineXEventSource(NineXExample.streams())
        val partial = object : NineXEventSource by inMemory {
            override fun instances(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int, maxStreams: Int) =
                inMemory.instances(aggregate, after, limit, 1)
        }
        importer(partial, ImportOptions(dryRun = true)).importAll(order).imported.assert().isEqualTo(4)
        (0 until log.partitions).all { log.head(it) == null }.assert().isTrue()
        checkpoints.load().aggregates.assert().isEmpty()
    }

    @Test
    fun `the tail imports what 9x committed after the import, and new instances`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val source = InMemoryNineXEventSource(streams.filter { it.version <= 2 })
        now = streams.first().createTime
        importer(source).importAll(order)
        streams.filter { it.version > 2 }.forEach(source::add)
        val created = copyOf(streams.first(), "late", createTime = streams.last().createTime + 1)
        source.add(created)
        importer(source).tailOnce(order).also {
            it.imported.assert().isEqualTo(3)
        }
        checkpoints.load().of(order)!!.tailedTo.assert().isEqualTo(created.createTime)
        importer(source).tailOnce(order).imported.assert().isEqualTo(0)
    }

    @Test
    fun `an instance the map excludes is passed over by the import and the tail, and one it does not stops it`(): Unit =
        runTest {
            val first = NineXExample.stream("order-stream-v1.json")
            val source = InMemoryNineXEventSource(listOf(copyOf(first, "order-1"), copyOf(first, "")))
            val excluding = NineXExample.planExcluding(RefusedIds.EMPTY)
            // A page an instance: the first holds only the excluded one, and appends nothing.
            importer(source, ImportOptions(pageSize = 1), plan = excluding).importAll(order).also {
                it.instances.assert().isEqualTo(1)
                it.imported.assert().isEqualTo(1)
                it.excluded.assert().isEqualTo(1)
                it.render().assert().contains("1 excluded")
            }
            source.add(copyOf(first, "", version = 2, createTime = first.createTime + 1))
            importer(source, plan = excluding).tailOnce(order).also {
                it.imported.assert().isEqualTo(0)
                it.skipped.assert().isEqualTo(1)
            }
            // Without its entry, its first stream stops the import, naming the entry that excludes it.
            assertThrows<MigrationException> { importer(source, ImportOptions(restart = true)).importAll(order) }
                .message.assert().contains(
                    "its aggregate id is empty, which V10 refuses; exclude it with {\"aggregate\": " +
                        "\"example-service.order\", \"stream\": \"(empty)\", \"reason\": \"…\"}.",
                )
        }

    @Test
    fun `the tail skips with one log read an instance whose latest stream the log already holds`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val source = InMemoryNineXEventSource(streams)
        now = streams.last().createTime + 1
        importer(source).importAll(order)
        // A look-back over the whole history reads every stream again: each is already there.
        importer(source, ImportOptions(lookbackMillis = now)).tailOnce(order).also {
            it.imported.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(streams.size.toLong())
            it.instances.assert().isEqualTo(1)
        }
        // A page a stream: the instance spans the pages, each page after the first meets it reached already, and
        // each of its streams is skipped by its own read.
        importer(source, ImportOptions(pageSize = 1, lookbackMillis = now)).tailOnce(order).also {
            it.imported.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(streams.size.toLong())
        }
        // Another command at the latest version is not the 9.x stream: the instance is imported, which stops on it.
        source.add(streams.last().copy(id = "other", version = streams.size + 1, commandId = "c-x", requestId = "c-x"))
        importer(source, ImportOptions(lookbackMillis = now)).tailOnce(order).imported.assert().isEqualTo(1)
    }

    @Test
    fun `the tail brings an instance up to date when it meets a later stream first`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val source = InMemoryNineXEventSource(streams.take(1))
        now = streams.first().createTime + 1
        importer(source).importAll(order)
        // Versions 2 and 3 committed before the window the tail scans; version 4 inside it, so it is met first.
        streams.drop(1).dropLast(1).forEach { source.add(it.copy(createTime = 0)) }
        source.add(streams.last())
        importer(source, ImportOptions(lookbackMillis = 0)).tailOnce(order).imported.assert().isEqualTo(3)
        log.read(AggregateRef(NineXExample.orderModel.type, "(0)", streams.first().aggregateId.id)).toList()
            .map { it.version }.assert().containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `the tail imports an instance from its lowest version in the page, whatever the commit times say`(): Unit =
        runTest {
            val streams = NineXExample.streams().filter { it.aggregate == order }
            val source = InMemoryNineXEventSource(streams.take(2))
            now = streams.first().createTime + 1
            importer(source).importAll(order)
            // Versions 3 and 4 committed in the same millisecond, 4 read first: the page holds the instance out of order.
            val at = streams.last().createTime
            source.add(streams[2].copy(id = "b-3", createTime = at))
            source.add(streams[3].copy(id = "a-4", createTime = at))
            now = at + 1
            importer(source).tailOnce(order).imported.assert().isEqualTo(2)
            // A page a stream: version 4 is held, so skipped; version 3, in the next page, is behind what is reached.
            importer(source, ImportOptions(pageSize = 1, lookbackMillis = now)).tailOnce(order).also {
                it.imported.assert().isEqualTo(0)
                it.skipped.assert().isEqualTo(3)
            }
            log.read(AggregateRef(NineXExample.orderModel.type, "(0)", streams.first().aggregateId.id)).toList()
                .map { it.version }.assert().containsExactly(1, 2, 3, 4)
        }

    @Test
    fun `a stream visible after the tail's cursor passed it is read again, under steady writes`(): Unit =
        runTest {
            val first = NineXExample.stream("order-stream-v1.json")
            val start = first.createTime
            val written = (1..WRITES).map { copyOf(first, "written-$it", createTime = start + it * TICK) }
            // Committed between the fifth and the sixth, it becomes visible only once the tail's cursor is past it.
            val late = copyOf(first, "late", createTime = start + 5 * TICK + TICK / 2)
            val source = SteadyWrites(InMemoryNineXEventSource(listOf(first)), written, late)
            val options = ImportOptions(pageSize = 1, concurrency = 1, lookbackMillis = 10 * TICK)
            now = start
            importer(source, options).importAll(order)
            // The tail's passes as `tail` runs them, while 9.x commits a stream at each page the tail reads, four
            // look-backs long; then one more.
            var passes = 0
            while (source.writing) {
                importer(source, options).tailOnce(order)
                passes++
            }
            importer(source, options).tailOnce(order)
            // It became visible within the look-back of its commit time, which is what the look-back covers.
            source.lateDelay!!.assert().isLessThan(options.lookbackMillis)
            log.read(NineXExample.orderModel.type.ref("(0)", "late")).toList().map { it.version }.assert()
                .containsExactly(1)
            (written + first).forEach { stream ->
                log.read(NineXExample.orderModel.type.ref("(0)", stream.aggregateId.id)).toList().size.assert()
                    .isEqualTo(1)
            }
            // Each pass ends at the latest stream committed when it starts, so the tail saves its checkpoint, and
            // goes on to the next aggregate, while 9.x writes.
            passes.assert().isGreaterThan(1)
        }

    /**
     * A 9.x store under steady writes: each read by commit time first commits the next of [written]; [late], committed
     * early, becomes visible only at the first read whose cursor is past its commit time, [lateDelay] after it.
     */
    private class SteadyWrites(
        private val nineX: InMemoryNineXEventSource,
        written: List<NineXStream>,
        private val late: NineXStream,
    ) : NineXEventSource by nineX {
        private val pending = ArrayDeque(written)
        private var committedTo = 0L
        var lateDelay: Long? = null
            private set
        val writing: Boolean
            get() = pending.isNotEmpty()

        override fun committedSince(
            aggregate: NineXAggregate,
            from: Long,
            after: NineXPosition?,
            limit: Int,
            until: NineXPosition?,
        ): List<NineXStream> {
            pending.removeFirstOrNull()?.let {
                nineX.add(it)
                committedTo = it.createTime
            }
            if (lateDelay == null && after != null && after.createTime > late.createTime) {
                nineX.add(late)
                lateDelay = committedTo - late.createTime
            }
            return nineX.committedSince(aggregate, from, after, limit, until)
        }
    }

    @Test
    fun `the final pass is the tail only when the tail followed 9x until it stopped`(): Unit = runTest {
        val source = InMemoryNineXEventSource(NineXExample.streams().filter { it.aggregate == order })
        importer(
            source
        ).finalProblem(order, MAX_TAIL_AGE).assert().isEqualTo("the full import of $order has not completed")
        checkpoints.save(ImportCheckpoints().with(order, AggregateCheckpoint(startedAt = 1)))
        importer(source).finalProblem(order, MAX_TAIL_AGE).assert()
            .isEqualTo("the full import of $order has not completed")
        importer(source).importAll(order)
        importer(source).finalProblem(order, MAX_TAIL_AGE).assert().isEqualTo("the tail of $order never ran")
        importer(source).tailOnce(order)
        checkpoints.load().of(order)!!.tailedAt.assert().isEqualTo(now)
        importer(source).finalProblem(order, MAX_TAIL_AGE).assert().isNull()
        now += MAX_TAIL_AGE + 1
        importer(source).finalProblem(order, MAX_TAIL_AGE).assert()
            .isEqualTo(
                "the tail of $order last ended ${MAX_TAIL_AGE + 1} ms ago, more than $MAX_TAIL_AGE ms: it did not " +
                    "follow 9.x continuously",
            )
    }

    @Test
    fun `the tail needs a completed full import`(): Unit = runTest {
        assertThrows<MigrationException> { importer(InMemoryNineXEventSource()).tailOnce(order) }
        checkpoints.save(ImportCheckpoints().with(order, AggregateCheckpoint(startedAt = 1)))
        assertThrows<MigrationException> { importer(InMemoryNineXEventSource()).tailOnce(order) }
    }

    @Test
    fun `a request id another instance holds is imported under a stable new one`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val other = copyOf(first, "other").copy(requestId = first.requestId)
        val source = InMemoryNineXEventSource(listOf(first, other))
        importer(source).importAll(order).rewritten.assert().isEqualTo(1)
        // Instances are imported concurrently, so either may claim the request id first; the other is rewritten.
        val requestIds = listOf(first, other).associateWith { stream ->
            log.read(NineXExample.orderModel.type.ref("(0)", stream.aggregateId.id)).toList().single().requestId
        }
        requestIds.values.assert().containsExactlyInAnyOrder(
            first.requestId,
            requestIds.entries.single { it.value != first.requestId }.key.let { "${first.requestId}#${it.id}" },
        )
        importer(source, ImportOptions(restart = true)).importAll(order).also {
            it.imported.assert().isEqualTo(0)
            it.skipped.assert().isEqualTo(2)
        }
    }

    @Test
    fun `an instance V10 has written to stops the import`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val record = StreamConverter(NineXExample.plan).convert(first)
        log.append(record.copyWith(commandId = "v10-command", requestId = "v10-request"))
        val failure = assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(listOf(first))).importAll(order)
        }
        failure.message!!.assert().contains("V10 has been written to")
    }

    @Test
    fun `a log ahead of 9x stops the import`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val converter = StreamConverter(NineXExample.plan)
        log.append(converter.convert(first).copyWith(requestId = "other"))
        log.append(converter.convert(first).copyWith(version = 2, requestId = "next"))
        assertThrows<MigrationException> { importer(InMemoryNineXEventSource(listOf(first))).importAll(order) }
    }

    @Test
    fun `a log holding the instance up to a version resumes after it`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val converter = StreamConverter(NineXExample.plan)
        // Appended under other request ids, so only the version tells they are there, as PostgreSQL reports it.
        streams.take(2).forEach { log.append(converter.convert(it).copyWith(requestId = "r${it.version}")) }
        importer(InMemoryNineXEventSource(streams)).importAll(order).also {
            it.imported.assert().isEqualTo(2)
            it.skipped.assert().isEqualTo(2)
        }
    }

    @Test
    fun `a gap in the 9x history stops the import`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val failure = assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(streams.filter { it.version != 2 })).importAll(order)
        }
        failure.message!!.assert().contains("gap")
    }

    @Test
    fun `a gap a dry run meets one stream at a time stops it`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order && it.version != 2 }
        assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(streams), ImportOptions(dryRun = true)).importAll(order)
        }.message!!.assert().contains("is version 3 where 2 was expected: the 9.x history has a gap")
    }

    @Test
    fun `a request id an instance's earlier stream holds is imported under a stable new one`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val first = streams.single { it.version == 1 }
        // A 9.x client sent two commands to one instance under one request id.
        val reused = streams.map { if (it.version == 2) it.copy(requestId = first.requestId) else it }
        importer(InMemoryNineXEventSource(reused)).importAll(order).also {
            it.imported.assert().isEqualTo(4)
            it.rewritten.assert().isEqualTo(1)
        }
        val second = reused.single { it.version == 2 }
        log.read(NineXExample.orderModel.type.ref("(0)", first.aggregateId.id)).toList().map { it.requestId }.assert()
            .containsExactly(
                first.requestId,
                "${first.requestId}#${second.id}",
                streams[2].requestId,
                streams[3].requestId
            )
    }

    @Test
    fun `a log holding a version 9x lacks stops the import, whatever later version 9x holds`(): Unit = runTest {
        val streams = NineXExample.streams().filter { it.aggregate == order }
        val converter = StreamConverter(NineXExample.plan)
        // The log holds 9.x's v1, its v2 under another request id, and a v3 V10 wrote itself; 9.x has no v3, but a v4.
        log.append(converter.convert(streams[0]))
        log.append(converter.convert(streams[1]).copyWith(requestId = "r2"))
        log.append(converter.convert(streams[1]).copyWith(version = 3, requestId = "v10", commandId = "v10"))
        val inMemory = InMemoryNineXEventSource(streams.filter { it.version != 3 })
        val inPart = object : NineXEventSource by inMemory {
            override fun instances(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int, maxStreams: Int) =
                inMemory.instances(aggregate, after, limit, 2)
        }
        assertThrows<MigrationException> { importer(inPart).importAll(order) }.message!!.assert()
            .contains("v3 in the V10 log is not the 9.x stream there (none)")
    }

    @Test
    fun `an unknown outcome is tried again, then stops the import`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val position = LogPosition(0, 1)
        val flaky = mockk<EventLog>()
        coEvery { flaky.append(any(), any()) } returnsMany listOf(
            AppendOutcome.Unknown(IllegalStateException("timeout")),
            AppendOutcome.Conflict(0),
            AppendOutcome.Committed(position),
        )
        appendsOneByOne(flaky)
        importer(
            InMemoryNineXEventSource(listOf(first)),
            target = flaky
        ).importAll(order).imported.assert().isEqualTo(1)
        val broken = mockk<EventLog>()
        coEvery { broken.append(any(), any()) } returns AppendOutcome.Unknown(IllegalStateException("timeout"))
        appendsOneByOne(broken)
        checkpoints.save(ImportCheckpoints())
        val failure = assertThrows<MigrationException> {
            importer(
                InMemoryNineXEventSource(listOf(first)),
                ImportOptions(maxUnknownRetries = 1),
                broken
            ).importAll(order)
        }
        failure.message!!.assert().contains("unknown")
    }

    @Test
    fun `a stream the log refuses stops the import with the log's reason`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val refusing = mockk<EventLog>()
        val cause = IllegalArgumentException("unsupported Unicode escape sequence")
        coEvery { refusing.append(any(), any()) } returns AppendOutcome.Rejected(cause)
        appendsOneByOne(refusing)
        val failure = assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(listOf(first)), target = refusing).importAll(order)
        }
        failure.message!!.assert().contains("refuses").contains(cause.message)
    }

    @Test
    fun `a request id still taken after the rewrite stops the import`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val record = StreamConverter(NineXExample.plan).convert(first)
        val taken = mockk<EventLog>()
        coEvery { taken.append(any(), any()) } returns
            AppendOutcome.AlreadyCommitted(record.copyWith(ref = record.ref.copy(id = "someone-else")))
        appendsOneByOne(taken)
        assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(listOf(first)), target = taken).importAll(order)
        }
    }

    @Test
    fun `a page's streams are appended in groups of whole instances, one call of the log each`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val calls = mutableListOf<Int>()
        var single = 0
        val counting = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
                single++
                return log.append(stream, transport)
            }

            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                synchronized(calls) { calls += streams.size }
                return log.appendAll(streams)
            }
        }
        // Five instances, two of them holding a second stream: seven streams in two groups, by whole instances.
        val streams = (1..5).map { copyOf(first, "order-$it") } +
            (1..2).map { copyOf(NineXExample.stream("order-stream-v2.json"), "order-$it") }
        importer(
            InMemoryNineXEventSource(streams),
            ImportOptions(concurrency = 2, readParallelism = 1),
            counting
        ).importAll(order)
            .also {
                it.imported.assert().isEqualTo(7)
                it.instances.assert().isEqualTo(5)
            }
        calls.sorted().assert().containsExactly(3, 4)
        single.assert().isEqualTo(0)
        log.read(NineXExample.orderModel.type.ref("(0)", "order-1")).toList().map { it.version }.assert()
            .containsExactly(1, 2)
        // A rerun meets every stream in the log: each instance goes on alone from its first, and skips it.
        calls.clear()
        importer(
            InMemoryNineXEventSource(streams),
            ImportOptions(concurrency = 2, restart = true, readParallelism = 1),
            counting
        )
            .importAll(order).also {
                it.imported.assert().isEqualTo(0)
                it.skipped.assert().isEqualTo(7)
            }
        calls.sorted().assert().containsExactly(3, 4)
    }

    @Test
    fun `a gap in an instance's 9x versions stops the import before its group is appended`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val third = NineXExample.stream("order-stream-v3.json")
        val source = InMemoryNineXEventSource(listOf(first, third))
        assertThrows<MigrationException> { importer(source).importAll(order) }.message.assert()
            .isEqualTo(
                "9.x stream ${third.id} of example-service.order/${first.aggregateId} is version 3 where 2 was " +
                    "expected: the 9.x history has a gap.",
            )
        log.read(NineXExample.orderModel.type.ref("(0)", first.aggregateId.id)).toList().assert().isEmpty()
    }

    @Test
    fun `the next page is read while a page is appended`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val inMemory = InMemoryNineXEventSource((1..3).map { copyOf(first, "order-$it") })
        val nextRead = CompletableDeferred<Unit>()
        val source = object : NineXEventSource by inMemory {
            override fun instances(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int, maxStreams: Int) =
                inMemory.instances(
                    aggregate,
                    after,
                    limit,
                    maxStreams
                ).also { if (after != null) nextRead.complete(Unit) }
        }
        // The first page's append waits for the second page's read: with a barrier between pages, it never comes.
        val waiting = object : EventLog by log {
            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                if (streams.first().first.ref.id == "order-1") nextRead.await()
                return log.appendAll(streams)
            }
        }
        importer(
            source,
            ImportOptions(pageSize = 1, readParallelism = 1),
            waiting
        ).importAll(order).imported.assert().isEqualTo(3)
    }

    @Test
    fun `the checkpoint moves after a page once it and the pages before it are imported`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((1..3).map { copyOf(first, "order-$it") })
        val secondAppended = CompletableDeferred<Unit>()
        // The second page is appended before the first.
        val reordering = object : EventLog by log {
            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                val id = streams.first().first.ref.id
                if (id == "order-1") secondAppended.await()
                return log.appendAll(streams).also { if (id == "order-2") secondAppended.complete(Unit) }
            }
        }
        val saved = mutableListOf<String?>()
        val recording = object : CheckpointRepository by checkpoints {
            override fun update(change: (ImportCheckpoints) -> ImportCheckpoints): ImportCheckpoints =
                checkpoints.update(change).also { saved += it.of(order)?.ranges?.single()?.after?.id }
        }
        EventStreamImporter(source, NineXExample.plan, {
            reordering
        }, recording, ImportOptions(pageSize = 1, readParallelism = 1), { now })
            .importAll(order).imported.assert().isEqualTo(3)
        // The range's position after each page, then the range done, then the aggregate.
        saved.assert().containsExactly(null, "order-1", "order-2", "order-3", "order-3", "order-3")
    }

    @Test
    fun `a page that fails leaves the checkpoint no further than the pages imported in full`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((1..4).map { copyOf(first, "order-$it") })
        val failing = object : EventLog by log {
            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                check(streams.first().first.ref.id != "order-2") { "the store is gone" }
                return log.appendAll(streams)
            }
        }
        assertThrows<IllegalStateException> {
            importer(source, ImportOptions(pageSize = 1, readParallelism = 1), failing).importAll(order)
        }
        checkpoints.load().of(order)!!.ranges!!.single().after?.id.assert().isIn(null, "order-1")
        // The rerun goes on from there, and finds in the log what the stopped run appended after it.
        importer(source, ImportOptions(pageSize = 1)).importAll(order).also {
            (it.imported + it.skipped).assert().isGreaterThanOrEqualTo(3)
        }
        (1..4).forEach { n ->
            log.read(NineXExample.orderModel.type.ref("(0)", "order-$n")).toList().size.assert().isEqualTo(1)
        }
    }

    @Test
    fun `the walk is split into ranges of ids walked at once, each instance imported once`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val streams = (10..19).map { copyOf(first, "order-$it") }
        val walked = mutableListOf<IdRange>()
        val inMemory = InMemoryNineXEventSource(streams)
        val source = object : NineXEventSource by inMemory {
            override fun instances(
                aggregate: NineXAggregate,
                range: IdRange,
                after: NineXAggregateId?,
                limit: Int,
                maxStreams: Int,
            ): List<NineXInstance> {
                synchronized(walked) { walked += range }
                return inMemory.instances(aggregate, range, after, limit, maxStreams)
            }
        }
        importer(source, ImportOptions(pageSize = 2, readParallelism = 3)).importAll(order).also {
            it.imported.assert().isEqualTo(10)
            it.instances.assert().isEqualTo(10)
        }
        val ranges = checkpoints.load().of(order)!!.ranges!!
        ranges.map { it.range }.assert()
            .containsExactly(IdRange(null, "order-13"), IdRange("order-13", "order-16"), IdRange("order-16", null))
        ranges.all { it.completed }.assert().isTrue()
        ranges.map { it.after?.id }.assert().containsExactly("order-12", "order-15", "order-19")
        walked.distinct().size.assert().isEqualTo(3)
        streams.forEach { stream ->
            log.read(NineXExample.orderModel.type.ref("(0)", stream.aggregateId.id)).toList().size.assert().isEqualTo(1)
        }
        // A restart samples the ranges again, and finds every stream in the log.
        importer(source, ImportOptions(restart = true, readParallelism = 2)).importAll(order).also {
            it.skipped.assert().isEqualTo(10)
        }
        checkpoints.load().of(order)!!.ranges!!.size.assert().isEqualTo(2)
    }

    @Test
    fun `an earlier version's single position resumes as one range from there`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((1..4).map { copyOf(first, "order-$it") })
        checkpoints.save(
            ImportCheckpoints().with(
                order,
                AggregateCheckpoint(startedAt = 1, after = NineXAggregateId("order-2", "(0)"))
            ),
        )
        importer(source, ImportOptions(readParallelism = 4)).importAll(order).imported.assert().isEqualTo(2)
        checkpoints.load().of(order)!!.also {
            it.completed.assert().isTrue()
            it.startedAt.assert().isEqualTo(1)
            it.after.assert().isNull()
            it.ranges!!.single().also { range ->
                range.range.assert().isEqualTo(IdRange.ALL)
                range.after!!.id.assert().isEqualTo("order-4")
            }
        }
        log.read(NineXExample.orderModel.type.ref("(0)", "order-1")).toList().assert().isEmpty()
    }

    @Test
    fun `a stopped import resumes each range where it stopped`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val source = InMemoryNineXEventSource((10..15).map { copyOf(first, "order-$it") })
        val failing = object : EventLog by log {
            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                check(streams.none { it.first.ref.id == "order-14" }) { "the store is gone" }
                return log.appendAll(streams)
            }
        }
        val options = ImportOptions(pageSize = 1, concurrency = 1, readParallelism = 2)
        assertThrows<IllegalStateException> { importer(source, options, failing).importAll(order) }
        val stopped = checkpoints.load().of(order)!!
        stopped.completed.assert().isFalse()
        stopped.ranges!!.map { it.range }.assert().containsExactly(IdRange(null, "order-13"), IdRange("order-13", null))
        importer(
            source,
            options
        ).importAll(order).also { (it.imported + it.skipped).assert().isGreaterThanOrEqualTo(2) }
        checkpoints.load().of(order)!!.ranges!!.map { it.range }.assert().isEqualTo(stopped.ranges!!.map { it.range })
        (10..15).forEach { n ->
            log.read(NineXExample.orderModel.type.ref("(0)", "order-$n")).toList().size.assert().isEqualTo(1)
        }
    }

    @Test
    fun `ranges split the ids and hold their own`() {
        IdRange.split(emptyList()).assert().containsExactly(IdRange.ALL)
        IdRange.split(
            listOf("b", "d")
        ).assert().containsExactly(IdRange(null, "b"), IdRange("b", "d"), IdRange("d", null))
        IdRange("b", "d").holds("b").assert().isTrue()
        IdRange("b", "d").holds("c").assert().isTrue()
        IdRange("b", "d").holds("d").assert().isFalse()
        IdRange(null, "b").holds("a").assert().isTrue()
        assertThrows<IllegalArgumentException> { ImportOptions(readParallelism = 0) }
    }

    /** [log] answering a group commit as the log's own default does: one append after another. */
    private fun appendsOneByOne(log: EventLog) {
        coEvery { log.appendAll(any()) } coAnswers {
            firstArg<List<Pair<EventStreamRecord, ByteArray?>>>().map { (stream, transport) -> log.append(stream, transport) }
        }
    }

    @Test
    fun `progress reports its throughput`() {
        val progress = ImportProgress(order, 2, 30, 10, 40, 1, 2_000, done = true)
        progress.streamsPerSecond.assert().isEqualTo(20.0)
        progress.render().assert().contains("example-service.order done").contains("20.0 streams/s")
        progress.copy(elapsedMillis = 0, done = false).render().assert().doesNotContain("done")
    }

    @Test
    fun `a finished import reports its time and where its workers spent it`() {
        val phases = ImportPhases(readMillis = 1_200, convertMillis = 300, appendMillis = 4_500, checkpointMillis = 40)
        val done = ImportProgress(order, 2, 30, 10, 40, 1, 2_000, done = true, phases = phases)
        done.render().assert().endsWith(
            "20.0 streams/s in 2.0 s; worker time: read 9.x 1.2 s, convert 0.3 s, append 4.5 s, checkpoints 0.0 s",
        )
        done.copy(done = false).render().assert().endsWith("20.0 streams/s")
    }

    @Test
    fun `the import times every phase it runs`() = runTest {
        val inMemory = InMemoryEventLog(4)
        val slowLog = object : EventLog by inMemory {
            override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
                Thread.sleep(2L * streams.size)
                return inMemory.appendAll(streams)
            }
        }
        val done = importer(InMemoryNineXEventSource(NineXExample.streams()), target = slowLog)
            .importAll(order)
        done.phases.appendMillis.assert().isGreaterThanOrEqualTo(2L * done.imported)
        reports.last().assert().isEqualTo(done)
    }

    @Test
    fun `options refuse impossible values`() {
        assertThrows<IllegalArgumentException> { ImportOptions(pageSize = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(streamBatch = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(concurrency = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(lookbackMillis = -1) }
        assertThrows<IllegalArgumentException> { ImportOptions(maxUnknownRetries = -1) }
    }

    private companion object {
        const val MAX_TAIL_AGE = 60_000L
        const val WRITES = 40
        const val TICK = 100L
    }
}
