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

package me.ahoo.wow.runtime.cluster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.cart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** RT-14: the hand-offs of one ownership's partitions, which run together, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOffBoundTest {
    private val gates = ConcurrentHashMap<Int, PartitionGate>()

    @Test
    fun `a partition whose checkpoints fail to save is logged, and every partition is released all the same`(): Unit =
        runTest {
            var down = false
            val failing = object : CheckpointStore by InMemoryCheckpointStore() {
                override suspend fun save(group: String, subscription: String, partition: Int, position: Long) {
                    check(!down || partition != 1) { "The checkpoint store is down." }
                }

                override suspend fun saveAll(group: String, checkpoints: List<Checkpoint>) {
                    check(!down || checkpoints.none { it.partition == 1 }) { "The checkpoint store is down." }
                }
            }
            val log = InMemoryEventLog(partitions = 4)
            val appender = Appender(log)
            val handled = AtomicInteger()
            val runtime = SubscriptionRuntime(
                GROUP.name,
                log,
                failing,
                InMemoryFailureLedger(),
                listOf(Subscription("s", SubscriptionFilter("shop")) { handled.incrementAndGet() }),
                testOptions(checkpointInterval = 1.hours),
                context = StandardTestDispatcher(testScheduler),
            )
            val leases = InMemoryLeaseStore()
            val runs = runs(leases, ClusterOptions(), listOf(runtime))
            val logger = LoggerFactory.getLogger(PartitionOwnership::class.java) as Logger
            val logged = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(logged)
            try {
                runtime.start()
                (0 until 4).forEach { runs.acquire(it, null) }
                runtime.partitions.assert().containsExactlyInAnyOrder(0, 1, 2, 3)
                // Every partition has a checkpoint to save on its stop: past its first, which the start saved.
                (0 until 4).forEach { partition ->
                    appender.append(
                        generateSequence(0) { it + 1 }.map { "a$it" }.first { Partitioner.partitionOf(cart(it), 4) == partition }
                    )
                }
                eventually { handled.get() == 4 }
                down = true

                runs.handOff(listOf(0, 1, 2, 3))
                gates.assert().isEmpty()
                leases.owners(GROUP.name).assert().isEmpty()
                logged.list.filter { it.level == Level.WARN }.map { it.formattedMessage }.assert()
                    .contains("$NODE failed to stop the subscriptions of partition 1 in $GROUP; the partition's other parts are stopped all the same.")
            } finally {
                logger.detachAppender(logged)
                runtime.stop()
            }
        }

    @Test
    fun `the releases of a hand-off and the acquisitions running beside it share one bound on lease calls`(): Unit =
        runTest {
            val inFlight = AtomicInteger()
            val most = AtomicInteger()
            val counted = object : LeaseStore by InMemoryLeaseStore() {
                val inner = InMemoryLeaseStore()

                private suspend fun <T> call(block: suspend () -> T): T {
                    most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        delay(100.milliseconds)
                        return block()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }

                override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? =
                    call { inner.acquire(group, partition, owner, ttl) }

                override suspend fun release(group: String, partition: Int, owner: String): Boolean =
                    call { inner.release(group, partition, owner) }
            }
            val runs = runs(counted, ClusterOptions(leaseParallelism = 2), emptyList())
            (0 until 4).forEach { runs.acquire(it, null) }
            most.set(0)

            val handing = launch { runs.handOff(listOf(0, 1, 2, 3)) }
            val acquiring = (4 until 8).map { launch { runs.acquire(it, null) } }
            handing.join()
            acquiring.forEach { it.join() }

            gates.keys.assert().containsExactlyInAnyOrder(4, 5, 6, 7)
            most.get().assert().isEqualTo(2)
        }

    private fun TestScope.runs(
        leases: LeaseStore,
        options: ClusterOptions,
        runtimes: List<SubscriptionRuntime>,
    ) = PartitionRuns(
        GROUP,
        NODE,
        leases,
        options,
        testScheduler.timeSource,
        options.leaseTtl - options.renewInterval,
        gates,
        runtimes,
        null,
        emptyList(),
        partitionChanged = {},
        leaseCalls = LeaseCalls(options.leaseParallelism),
    )

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("handed-service", "handed")
    }
}
