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

package me.ahoo.wow.benchmark.transfer.raw

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class WowShapeTest {
    /** A store that records its calls: each append's partitions and size, each save, and when each was appended. */
    private class Store : AutoCloseable {
        override fun close() = Unit
    }

    private class Calls {
        val appends = Collections.synchronizedList(ArrayList<List<Int>>())
        val saves = Collections.synchronizedList(ArrayList<Int>())
        val appended = ConcurrentHashMap.newKeySet<Int>()
        val savedBeforeAppend = AtomicInteger()
        val inFlight = ConcurrentHashMap<Int, AtomicInteger>()
        val overlapping = AtomicInteger()
    }

    private val partitions = 8
    private fun partitionOf(command: Aggregates.Command) = command.number % partitions

    private fun shape(calls: Calls, options: WowShape.Options, refuse: (Aggregates.Command) -> Boolean = { false }) =
        WowShape(
            partitions,
            ::partitionOf,
            options,
            ::Store,
            { _, commands ->
                val parts = commands.map(::partitionOf)
                parts.distinct().forEach { part ->
                    if (calls.inFlight.computeIfAbsent(part) { AtomicInteger() }.incrementAndGet() > 1) {
                        calls.overlapping.incrementAndGet()
                    }
                }
                calls.appends += parts
                Thread.sleep(2)
                parts.distinct().forEach { calls.inFlight.getValue(it).decrementAndGet() }
                BooleanArray(commands.size) { !refuse(commands[it]) }.also { written ->
                    commands.forEachIndexed { at, command -> if (written[at]) calls.appended += command.number }
                }
            },
            { _, commands ->
                calls.saves += commands.size
                commands.forEach { if (it.number !in calls.appended) calls.savedBeforeAppend.incrementAndGet() }
                BooleanArray(commands.size) { true }
            },
        )

    @Test
    fun `calls merge the lanes' handed batches whole while under the call's maximum, one batch a lane at a time`() {
        val calls = Calls()
        val options = WowShape.Options(mergeMax = 12, maxBatch = 4, writerMax = 5)
        val pool = Executors.newFixedThreadPool(64)
        val results = shape(calls, options).use { shape ->
            (0 until 400).map { number -> pool.submit<Boolean> { shape.submit(Aggregates.Command(number, 2)) } }
                .map { it.get() }
        }
        pool.shutdown()
        results.all { it }.assert().isTrue()
        calls.appends.sumOf { it.size }.assert().isEqualTo(400)
        // A batch is taken whole while the call holds fewer than its maximum: up to a lane's batch past it.
        calls.appends.all { it.size <= options.mergeMax + options.maxBatch - 1 }.assert().isTrue()
        calls.appends.all { parts -> parts.groupingBy { it }.eachCount().values.all { it <= options.maxBatch } }
            .assert().isTrue()
        calls.appends.any { it.size > options.maxBatch }.assert().isTrue()
        calls.overlapping.get().assert().isZero()
        calls.saves.sum().assert().isEqualTo(400)
        calls.saves.all { it <= options.writerMax }.assert().isTrue()
        calls.savedBeforeAppend.get().assert().isZero()
    }

    @Test
    fun `a filled call waits for its size and takes whole batches past it, its saves of theirs`() {
        val calls = Calls()
        val options = WowShape.Options(answer = WowShape.Answer.COMMITTED, maxBatch = 4, appendFill = 6, saveFill = 5)
        val pool = Executors.newFixedThreadPool(64)
        val results = shape(calls, options).use { shape ->
            (0 until 400).map { number -> pool.submit<Boolean> { shape.submit(Aggregates.Command(number, 2)) } }
                .map { it.get() }
        }
        pool.shutdown()
        results.all { it }.assert().isTrue()
        calls.appends.sumOf { it.size }.assert().isEqualTo(400)
        // A call takes no more than a lane's batch past its size, a save none past its own.
        calls.appends.all { it.size <= 6 + 4 - 1 }.assert().isTrue()
        calls.saves.sum().assert().isEqualTo(400)
        calls.saves.all { it <= 5 }.assert().isTrue()
        // Certain enough to assert: 64 callers keep far more than six commands queued, and a call waits up to 20 ms for
        // its size, ten times the store call's 2 ms, so calls and saves reach their sizes; how many do is timing's.
        calls.appends.any { it.size >= 6 }.assert().isTrue()
        calls.saves.any { it == 5 }.assert().isTrue()
        WowShape.Options(appendFill = 6).wows.assert().isFalse()
    }

    @Test
    fun `a command the store refused is not saved, and one a failed call carried fails its caller`() {
        val calls = Calls()
        shape(calls, WowShape.Options(), refuse = { it.number == 3 }).use { shape ->
            shape.submit(Aggregates.Command(3, 2)).assert().isFalse()
            shape.submit(Aggregates.Command(4, 2)).assert().isTrue()
        }
        calls.saves.sum().assert().isEqualTo(1)
        val failing =
            WowShape(partitions, ::partitionOf, WowShape.Options(), ::Store, { _, _ -> error("down") }) { _, c ->
                BooleanArray(c.size) { true }
            }
        failing.use { shape ->
            assertThrows<IllegalStateException> {
                shape.submit(
                    Aggregates.Command(1, 2)
                )
            }.message.assert().isEqualTo("down")
        }
    }

    @Test
    fun `a committed caller is answered at its append, its snapshot saved behind`() {
        val saving = java.util.concurrent.CountDownLatch(1)
        val saved = AtomicInteger()
        val committed = WowShape.Options(answer = WowShape.Answer.COMMITTED)
        val append = { _: Store, c: List<Aggregates.Command> -> BooleanArray(c.size) { true } }
        val save = { _: Store, c: List<Aggregates.Command> ->
            saving.await()
            saved.addAndGet(c.size)
            BooleanArray(c.size) { true }
        }
        val shape = WowShape(partitions, ::partitionOf, committed, ::Store, append, save)
        shape.use {
            shape.submit(Aggregates.Command(1, 2)).assert().isTrue()
            saved.get().assert().isZero()
            shape.counters()[WowShape.SHAPE_APPENDED].assert().isEqualTo(1.0)
            shape.counters()[WowShape.SHAPE_APPEND_CALLS].assert().isEqualTo(1.0)
            shape.counters()[WowShape.SHAPE_APPEND_PARTITIONS].assert().isEqualTo(1.0)
            saving.countDown()
        }
        saved.get().assert().isEqualTo(1)
        shape.counters()[WowShape.SHAPE_SAVE_CALLS].assert().isEqualTo(1.0)
        WowShape.ratios(mapOf(WowShape.SHAPE_APPENDED to 100.0, WowShape.SHAPE_SAVED to 96.0))
            .assert().isEqualTo(mapOf(WowShape.SAVES_PER_APPEND to 0.96))
        WowShape.ratios(mapOf(WowShape.SHAPE_APPEND_CALLS to 4.0, WowShape.SHAPE_APPEND_PARTITIONS to 10.0))
            .assert().isEqualTo(mapOf(WowShape.PARTITIONS_PER_APPEND to 2.5))
    }

    @Test
    @OptIn(InternalWowApi::class)
    fun `the shape's constants are the runtime's own, for which it has no option`() {
        WowShape.MERGE_MAX.assert().isEqualTo(AppendQueueOptions.DEFAULT_MERGE_MAX)
        WowShape.APPENDERS.assert().isEqualTo(AppendQueueOptions.DEFAULT_MERGERS)
        WowShape.MAX_BATCH.assert().isEqualTo(AppendQueueOptions.DEFAULT_MAX_BATCH)
        WowShape.WRITERS.assert().isEqualTo(SnapshotStore.DEFAULT_WRITES_IN_FLIGHT)
        WowShape.WRITER_MAX.assert().isEqualTo(SnapshotOptions.DEFAULT_WRITE_BATCH)
        WowShape.Options().wows.assert().isTrue()
        WowShape.Options(mergeMax = 256).wows.assert().isFalse()
    }

    @Test
    fun `only wow's own shape has a ceiling, committed and snapshot, and the what-ifs have none`() {
        val cases = WowShape.cases().map { (case, options) -> Triple(case.name, case.ceiling, options.wows) }
        cases.assert().containsExactly(
            Triple("wow-shape-committed", RawCase.COMMITTED, true),
            Triple("wow-shape-snapshot", RawCase.SNAPSHOT, true),
            Triple("what-if-merge-64", null, false),
            Triple("what-if-merge-256", null, false),
            Triple("what-if-4-appenders-4-writers", null, false),
        )
        WowShape.cases().last().second.connections.assert().isEqualTo(8)
        // The fills are the calibration's alone (RawStoreBench.equalShape): no ceiling's step or what-if waits for a size.
        WowShape.cases().all { (_, options) -> options.appendFill == null && options.saveFill == null }.assert().isTrue()
    }

    @Test
    fun `wow's defaults are two appenders of up to 512 streams and two writers of up to 256 rows`() {
        WowShape.Options().apply {
            appenders.assert().isEqualTo(2)
            mergeMax.assert().isEqualTo(512)
            maxBatch.assert().isEqualTo(64)
            writers.assert().isEqualTo(2)
            writerMax.assert().isEqualTo(256)
            connections.assert().isEqualTo(4)
            answer.assert().isEqualTo(WowShape.Answer.SNAPSHOT)
        }
    }
}
