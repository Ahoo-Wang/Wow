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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class EventStreamTailTest : EventStreamImporterFixtures() {
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

    private companion object {
        const val MAX_TAIL_AGE = 60_000L
        const val WRITES = 40
        const val TICK = 100L
    }
}
