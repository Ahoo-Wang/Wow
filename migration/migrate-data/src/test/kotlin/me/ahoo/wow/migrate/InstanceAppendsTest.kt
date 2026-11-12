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
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class InstanceAppendsTest : EventStreamImporterFixtures() {
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

            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
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

    /** [log] answering a group commit as the log's own default does: one append after another. */
    private fun appendsOneByOne(log: EventLog) {
        coEvery { log.appendAll(any()) } coAnswers {
            firstArg<List<Append>>().map { log.append(it.stream, it.transport) }
        }
    }
}
