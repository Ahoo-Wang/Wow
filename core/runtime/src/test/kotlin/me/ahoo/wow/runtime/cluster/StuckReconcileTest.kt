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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Finding C.1 F1 on virtual time: a node lost the lease of a partition whose subscriptions then never stopped (a Kafka
 * tail waiting for a consumer that did not stop), so its reconcile hung. It kept heartbeating, the other node kept
 * leaving the partition to it by HRW, and nobody ran the partition. A node whose reconcile is stuck leaves the members
 * instead, so the other takes the partition, and comes back once the reconcile ends.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the reconcile.
class StuckReconcileTest {
    @Test
    fun `a node stuck stopping a lost partition leaves the members, so the other node runs the partition`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val partition = Hrw.partitionsOf(STUCK, listOf(STUCK, OTHER), PARTITIONS).first()
            val leases = LosingLeases(store, partition)
            val log = StuckLog(InMemoryEventLog(PARTITIONS), partition)
            val runtime = SubscriptionRuntime(
                GROUP.name,
                log,
                InMemoryCheckpointStore(),
                InMemoryFailureLedger(),
                context = StandardTestDispatcher(testScheduler),
            )
            val stuck = ownership(STUCK, leases, runtime)
            val other = ownership(OTHER, store)
            try {
                stuck.start()
                other.start()
                delay(5.seconds)
                stuck.accepts(partition).assert().isTrue()

                // The lease is lost, and stopping the partition hangs.
                leases.losing = true
                log.stuck = true
                delay(30.seconds)
                stuck.accepts(partition).assert().isFalse()
                other.accepts(partition).assert().isFalse()
                store.members(GROUP.name).map { it.node }.assert().containsExactly(OTHER, STUCK)

                // Past the reconcile timeout: the stuck node is no member, and the other runs the partition.
                delay(ClusterOptions().reconcileTimeout)
                store.members(GROUP.name).map { it.node }.assert().containsExactly(OTHER)
                other.accepts(partition).assert().isTrue()
                stuck.owned.assert().doesNotContain(partition)

                // Once the stop ends, the node is a member again and takes its share back.
                leases.losing = false
                log.release.complete(Unit)
                delay(30.seconds)
                store.members(GROUP.name).map { it.node }.assert().containsExactly(OTHER, STUCK)
                stuck.accepts(partition).assert().isTrue()
                other.accepts(partition).assert().isFalse()
            } finally {
                log.release.complete(Unit)
                other.stop()
                stuck.stop()
            }
        }

    @Test
    fun `the reconcile timeout is positive`() {
        assertThrows<IllegalArgumentException> { ClusterOptions(reconcileTimeout = Duration.ZERO) }
    }

    private fun TestScope.ownership(node: String, leases: LeaseStore, runtime: SubscriptionRuntime? = null) =
        PartitionOwnership(
            GROUP,
            node,
            "$node.internal:7070",
            PARTITIONS,
            leases,
            subscriptions = runtime,
            context = StandardTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
        )

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** Renewing [partition] finds it lost while [losing], as after a store that stalled past the TTL. */
    private class LosingLeases(private val delegate: LeaseStore, private val partition: Int) : LeaseStore by delegate {
        @Volatile
        var losing = false

        override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration): List<Lease> =
            delegate.renew(group, if (losing) partitions - partition else partitions, owner, ttl)
    }

    /**
     * A log whose tail of [partition], once [stuck], does not stop when cancelled until [release]: as a Kafka tail
     * whose leave waited for a shared consumer that kept running.
     */
    private class StuckLog(private val delegate: EventLog, private val partition: Int) : EventLog by delegate {
        @Volatile
        var stuck = false
        val release = CompletableDeferred<Unit>()

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = if (partition != this.partition) {
            delegate.tail(partition, after)
        } else {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    if (stuck) {
                        withContext(NonCancellable) { release.await() }
                    }
                }
            }
        }
    }

    private companion object {
        const val PARTITIONS = 8
        const val STUCK = "stuck-node"
        const val OTHER = "other-node"
        val GROUP = LeaseGroup(SERVICE, "stuck")
    }
}
