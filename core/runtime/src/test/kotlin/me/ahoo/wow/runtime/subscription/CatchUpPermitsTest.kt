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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A node's catch-up permits ([SubscriptionOptions.maxCatchUps]): a partition behind the head of its log waits for one
 * while others catch up, standing at its checkpoints, then takes every entry; a partition gives its permit back once
 * its subscriptions have taken the entries up to the head it started behind, or have paused on a systemic failure.
 */
class CatchUpPermitsTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val handled = Handled()

    /** One permit for the node: a single partition reads behind its head at a time. */
    private val permits = Semaphore(1)

    private fun idsIn(partition: Int, count: Int): List<String> =
        generateSequence(0) { it + 1 }.map { "a$it" }.filter { Partitioner.partitionOf(cart(it), 2) == partition }
            .take(count).toList()

    private fun runtime(subscription: Subscription, options: SubscriptionOptions) = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(subscription),
        options,
        catchUps = permits,
    )

    @Test
    fun `a partition behind its head should wait for a permit another holds, then take everything`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, ENTRIES) to idsIn(1, ENTRIES)
            val slow = CompletableDeferred<Unit>()
            val runtime = runtime(
                Subscription("projector", SubscriptionFilter("shop")) {
                    if (it.ref.id in zero) slow.await()
                    handled.record(it)
                },
                // Partition 0 takes two entries, then waits for its handler: behind its head, it keeps the permit.
                testOptions(maxInFlight = 2),
            )
            (zero + one).forEach { appender.append(it) }
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { permits.availablePermits == 0 }
                runtime.startPartition(1)
                // Partition 1 stands at its checkpoint while partition 0 holds the permit, checked at once: behind its
                // head, it opens its reads only with a permit.
                handled.size.assert().isZero()
                runtime.positions().single { it.partition == 1 }.watermark.assert().isZero()

                slow.complete(Unit)
                eventually { handled.size == 2 * ENTRIES }
                handled.entries.map { it.first }.toSet().assert().isEqualTo((zero + one).toSet())
                eventually { permits.availablePermits == 1 }
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a partition whose subscription pauses on a systemic failure should give its permit back`(): Unit =
        runBlocking {
            val (zero, one) = idsIn(0, ENTRIES) to idsIn(1, ENTRIES)
            val runtime = runtime(
                Subscription("projector", SubscriptionFilter("shop")) {
                    if (it.ref.id in zero) throw StorageUnavailableException("The view store is unavailable.")
                    handled.record(it)
                },
                testOptions(maxInFlight = 3).copyWith(
                    systemic = SystemicFailureOptions(
                        failingAggregates = 2,
                        initialBackoff = 50.milliseconds,
                        maxBackoff = 100.milliseconds,
                    ),
                ),
            )
            (zero + one).forEach { appender.append(it) }
            runtime.start()
            try {
                runtime.startPartition(0)
                eventually { runtime.paused().isNotEmpty() }
                runtime.startPartition(1)
                // Paused, partition 0 gives its permit back: partition 1 catches up meanwhile.
                eventually { handled.size == ENTRIES }
                handled.entries.map { it.first }.toSet().assert().isEqualTo(one.toSet())
                runtime.paused().single().partition.assert().isZero()
            } finally {
                runtime.stop()
            }
        }

    private companion object {
        const val ENTRIES = 6
    }
}
