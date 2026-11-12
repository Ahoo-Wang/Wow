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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * RT-14: the partitions one runtime stops drain together, outside its lifecycle mutex, on virtual time. Each partition
 * holds a first entry that completes and a second whose handler outlasts the stop timeout, so every stop drains for the
 * whole of it and saves the first entry's checkpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentStopsTest {
    private val log = InMemoryEventLog(partitions = PARTITIONS)
    private val appender = Appender(log)
    private val source = CountingSource(LogSource(log))
    private val checkpoints = RecordingStore()
    private val slow = ConcurrentHashMap.newKeySet<String>()
    private val handling = AtomicInteger()

    private fun subscription(name: String) = Subscription(name, SubscriptionFilter("shop")) {
        if (it.stream.ref.id in slow) {
            handling.incrementAndGet()
            awaitCancellation()
        }
    }

    private fun TestScope.runtime(vararg subscriptions: String) = SubscriptionRuntime(
        "group",
        source,
        checkpoints,
        InMemoryFailureLedger(),
        subscriptions.map(::subscription),
        testOptions(checkpointInterval = 1.hours, stopTimeout = STOP_TIMEOUT),
        context = StandardTestDispatcher(testScheduler),
    )

    /**
     * Runs [body] on virtual time with a runtime of the subscription `s`, stopped whatever [body] came to: a runtime
     * left running would keep the scheduler busy for good.
     */
    private fun stopsTest(body: suspend TestScope.(SubscriptionRuntime) -> Unit) = runTest {
        val runtime = runtime("s")
        try {
            body(runtime)
        } finally {
            runtime.stop()
        }
    }

    private fun idIn(partition: Int, skip: Int): String =
        generateSequence(0) { it + 1 }.map { "a$it" }
            .filter { Partitioner.partitionOf(cart(it), PARTITIONS) == partition }
            .drop(skip).first()

    /** Starts [partitions] with a fast first entry and a slow second one each, every slow handler running. */
    private suspend fun SubscriptionRuntime.startDraining(partitions: Iterable<Int>) {
        start()
        partitions.forEach { partition ->
            appender.append(idIn(partition, 0))
            slow += idIn(partition, 1)
            appender.append(idIn(partition, 1))
            startPartition(partition)
        }
        eventually { handling.get() == partitions.count() }
    }

    @Test
    fun `partitions stopped together drain together, so they stop within one stop timeout, not one a partition`(): Unit =
        stopsTest { runtime ->
            runtime.startDraining(0 until PARTITIONS)
            val begun = testScheduler.currentTime
            val stops = launch { (0 until PARTITIONS).forEach { launch { runtime.stopPartition(it) } } }
            runCurrent()
            // Every partition stopped reading before any drain ended.
            (0 until PARTITIONS).forEach { source.readers(it).assert().isZero() }
            runtime.partitions.assert().isEmpty()

            stops.join()
            (testScheduler.currentTime - begun).assert().isLessThan(STOP_TIMEOUT.inWholeMilliseconds * 2)
            (0 until PARTITIONS).forEach { checkpoints.savedOf("s", it).assert().isEqualTo(appender.ends[it]!! - 1) }
        }

    @Test
    fun `a start of a partition being stopped waits for the stop, so the partition is never read twice`(): Unit =
        stopsTest { runtime ->
            runtime.startDraining(listOf(0))
            val stopping = launch { runtime.stopPartition(0) }
            runCurrent()
            val starting = launch { runtime.startPartition(0) }
            advanceTimeBy(STOP_TIMEOUT / 2)
            starting.isCompleted.assert().isFalse()
            runtime.partitions.assert().isEmpty()

            stopping.join()
            starting.join()
            runtime.partitions.assert().containsExactly(0)
            source.most(0).assert().isEqualTo(1)
        }

    @Test
    fun `a stop of the runtime while partitions are stopping waits for them, and saves nothing after it`(): Unit =
        stopsTest { runtime ->
            runtime.startDraining(0 until PARTITIONS)
            val stops = launch { (0 until 2).forEach { launch { runtime.stopPartition(it) } } }
            runCurrent()
            runtime.stop()
            val stopped = checkpoints.saves.size
            runCurrent()
            stops.isCompleted.assert().isTrue()
            runtime.positions().assert().isEmpty()
            (0 until PARTITIONS).forEach { checkpoints.savedOf("s", it).assert().isEqualTo(appender.ends[it]!! - 1) }

            advanceTimeBy(STOP_TIMEOUT * 2)
            checkpoints.saves.size.assert().isEqualTo(stopped)
        }

    @Test
    fun `a subscription added while a partition stops is attached to the partitions still read, not to it`(): Unit =
        stopsTest { runtime ->
            runtime.startDraining(listOf(0, 1))
            val stopping = launch { runtime.stopPartition(0) }
            runCurrent()
            runtime.add(subscription("late"))
            stopping.join()

            runtime.positions().filter { it.subscription == "late" }.map { it.partition }.assert().containsExactly(1)
            checkpoints.savedOf("late", 0).assert().isNull()
        }

    /** The log's source, counting the tails of each partition read at once. */
    private class CountingSource(private val inner: EntrySource) : EntrySource by inner {
        private val reading = ConcurrentHashMap<Int, AtomicInteger>()
        private val mostReading = ConcurrentHashMap<Int, Int>()

        fun readers(partition: Int): Int = reading[partition]?.get() ?: 0

        fun most(partition: Int): Int = mostReading[partition] ?: 0

        override fun tail(partition: Int, after: Long?): Flow<SourcedEntry> = flow {
            val now = reading.computeIfAbsent(partition) { AtomicInteger() }.incrementAndGet()
            mostReading.merge(partition, now, ::maxOf)
            try {
                emitAll(inner.tail(partition, after))
            } finally {
                reading.getValue(partition).decrementAndGet()
            }
        }
    }

    /** Checkpoints in memory, each save recorded. */
    private class RecordingStore(private val inner: InMemoryCheckpointStore = InMemoryCheckpointStore()) :
        CheckpointStore by inner {
        val saves = CopyOnWriteArrayList<Checkpoint>()

        suspend fun savedOf(subscription: String, partition: Int): Long? = inner.load("group", subscription, partition)

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
            saves += Checkpoint.of(subscription, partition, position)
            inner.save(group, subscription, partition, position)
        }

        override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
            saves += checkpoints
            inner.saveAll(group, checkpoints)
        }
    }

    private companion object {
        const val PARTITIONS = 8
        val STOP_TIMEOUT = 10.seconds
    }
}
