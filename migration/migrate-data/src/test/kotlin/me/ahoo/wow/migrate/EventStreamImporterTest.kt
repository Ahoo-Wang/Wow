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
    ) = EventStreamImporter(source, NineXExample.plan, { target }, checkpoints, options, { now }, { reports += it })

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
    fun `a dry run converts every stream and appends none`(): Unit = runTest {
        importer(InMemoryNineXEventSource(NineXExample.streams()), ImportOptions(dryRun = true)).importAll(order)
            .imported.assert().isEqualTo(4)
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
    fun `the final pass is the tail only when the tail followed 9x until it stopped`(): Unit = runTest {
        val source = InMemoryNineXEventSource(NineXExample.streams().filter { it.aggregate == order })
        importer(
            source
        ).finalProblem(order, MAX_TAIL_AGE).assert().isEqualTo("the full import of $order has not completed")
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
    fun `an unknown outcome is tried again, then stops the import`(): Unit = runTest {
        val first = NineXExample.stream("order-stream-v1.json")
        val position = LogPosition(0, 1)
        val flaky = mockk<EventLog>()
        coEvery { flaky.append(any(), any()) } returnsMany listOf(
            AppendOutcome.Unknown(IllegalStateException("timeout")),
            AppendOutcome.Conflict(0),
            AppendOutcome.Committed(position),
        )
        importer(
            InMemoryNineXEventSource(listOf(first)),
            target = flaky
        ).importAll(order).imported.assert().isEqualTo(1)
        val broken = mockk<EventLog>()
        coEvery { broken.append(any(), any()) } returns AppendOutcome.Unknown(IllegalStateException("timeout"))
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
        assertThrows<MigrationException> {
            importer(InMemoryNineXEventSource(listOf(first)), target = taken).importAll(order)
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
    fun `options refuse impossible values`() {
        assertThrows<IllegalArgumentException> { ImportOptions(pageSize = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(streamBatch = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(concurrency = 0) }
        assertThrows<IllegalArgumentException> { ImportOptions(lookbackMillis = -1) }
        assertThrows<IllegalArgumentException> { ImportOptions(maxUnknownRetries = -1) }
    }

    private companion object {
        const val MAX_TAIL_AGE = 60_000L
    }
}
