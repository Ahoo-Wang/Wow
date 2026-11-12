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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class EventStreamImporterTest : EventStreamImporterFixtures() {
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
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
                if (streams.first().stream.ref.id == "order-1") nextRead.await()
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
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
                val id = streams.first().stream.ref.id
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
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
                check(streams.first().stream.ref.id != "order-2") { "the store is gone" }
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
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
                check(streams.none { it.stream.ref.id == "order-14" }) { "the store is gone" }
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
        checkpoints.load().of(order)!!.ranges!!.map { it.range }.assert().isEqualTo(stopped.ranges.map { it.range })
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
            override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
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
}
