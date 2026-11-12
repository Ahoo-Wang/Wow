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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
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
                store.members(
                    GROUP.service
                ).filter { GROUP.name in it.groups }.map { it.node }.assert().containsExactly(OTHER, STUCK)

                // Past the reconcile timeout: the stuck node is no member, and the other runs the partition.
                delay(ClusterOptions().reconcileTimeout)
                store.members(
                    GROUP.service
                ).filter { GROUP.name in it.groups }.map { it.node }.assert().containsExactly(OTHER)
                other.accepts(partition).assert().isTrue()
                stuck.owned.assert().doesNotContain(partition)

                // Once the stop ends, the node is a member again and takes its share back.
                leases.losing = false
                log.release.complete(Unit)
                delay(30.seconds)
                store.members(
                    GROUP.service
                ).filter { GROUP.name in it.groups }.map { it.node }.assert().containsExactly(OTHER, STUCK)
                stuck.accepts(partition).assert().isTrue()
                other.accepts(partition).assert().isFalse()
            } finally {
                log.release.complete(Unit)
                other.stop()
                stuck.stop()
            }
        }

    @Test
    fun `a node stuck handing off one partition gives up every lease, so one node alone runs each partition`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val handed = Hrw.partitionsOf(OTHER, listOf(STUCK, OTHER), PARTITIONS).first()
            val kept = Hrw.partitionsOf(STUCK, listOf(STUCK, OTHER), PARTITIONS)
            val log = StuckLog(InMemoryEventLog(PARTITIONS), handed)
            val stuck = ownership(STUCK, store, stuckRuntime(log))
            val other = ownership(OTHER, store)
            try {
                // Alone, the node holds every partition; then its hand-off of one to the other hangs.
                stuck.start()
                delay(5.seconds)
                stuck.owned.assert().hasSize(PARTITIONS)
                log.stuck = true
                other.start()

                // Never two holders of a partition at once, sampled every second through the fence and the hand-over.
                val options = ClusterOptions()
                val handOver = options.reconcileTimeout + options.heartbeatInterval + options.drainTimeout +
                    options.leaseTtl + options.reconcileInterval
                repeat((handOver + 10.seconds).inWholeSeconds.toInt()) {
                    delay(1.seconds)
                    (0 until PARTITIONS).filter { stuck.accepts(it) && other.accepts(it) }.assert().isEmpty()
                }
                // The stuck node holds and accepts nothing; the other node runs every partition, its own share kept.
                store.members(GROUP.service).filter { GROUP.name in it.groups }.map { it.node }.assert()
                    .containsExactly(OTHER)
                kept.forEach { stuck.accepts(it).assert().describedAs("partition $it on the stuck node").isFalse() }
                (0 until PARTITIONS).forEach { other.accepts(it).assert().describedAs("partition $it").isTrue() }
                store.owners(GROUP.name).map { it.owner }.toSet().assert().containsExactly(OTHER)
                // A command the stuck node is still given goes to the owner now.
                kept.forEach { stuck.forwardTarget(it)?.node.assert().isEqualTo(OTHER) }
                // The fence is observed once, after a minute without a step.
                val fenced = events.map { it.second }.filterIsInstance<ObservedEvent.OwnershipFenced>().single()
                fenced.group.assert().isEqualTo(GROUP.name)
                (fenced.stalledMillis > options.reconcileTimeout.inWholeMilliseconds).assert().isTrue()

                // Once the hand-off ends, the node is a member again and takes its share back.
                log.release.complete(Unit)
                delay(30.seconds)
                kept.forEach { stuck.accepts(it).assert().describedAs("partition $it back").isTrue() }
                other.accepts(handed).assert().isTrue()
                events.map { it.second }.filterIsInstance<ObservedEvent.OwnershipRecovered>().single().group.assert()
                    .isEqualTo(GROUP.name)
            } finally {
                log.release.complete(Unit)
                other.stop()
                stuck.stop()
            }
        }

    @Test
    fun `a command in flight on a fenced partition keeps its lease until it ends, then the other node takes it`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val handed = Hrw.partitionsOf(OTHER, listOf(STUCK, OTHER), PARTITIONS).first()
            val kept = Hrw.partitionsOf(STUCK, listOf(STUCK, OTHER), PARTITIONS).first()
            val log = StuckLog(InMemoryEventLog(PARTITIONS), handed)
            val stuck = ownership(STUCK, store, stuckRuntime(log))
            val other = ownership(OTHER, store)
            val command = CompletableDeferred<Unit>()
            try {
                stuck.start()
                delay(5.seconds)
                val running = backgroundScope.launch { stuck.runIfAccepting(kept) { command.await() } }
                delay(1.seconds)
                log.stuck = true
                other.start()
                while (events.none { it.second is ObservedEvent.OwnershipFenced }) {
                    delay(1.seconds)
                }

                // Fenced: the partition takes no command, but its lease is kept while the command runs.
                stuck.accepts(kept).assert().isFalse()
                delay(ClusterOptions().drainTimeout - 2.seconds)
                store.owners(GROUP.name).single { it.partition == kept }.owner.assert().isEqualTo(STUCK)
                other.accepts(kept).assert().isFalse()

                // The command ends: the lease is left to expire, and the other node takes the partition.
                command.complete(Unit)
                running.join()
                delay(ClusterOptions().leaseTtl + 5.seconds)
                other.accepts(kept).assert().isTrue()
                stuck.accepts(kept).assert().isFalse()
            } finally {
                command.complete(Unit)
                log.release.complete(Unit)
                other.stop()
                stuck.stop()
            }
        }

    @Test
    fun `a reconcile that keeps finishing steps for longer than the reconcile timeout fences nothing`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val slow = SlowStarts(InMemoryFailureLedger(), 20.seconds)
            val options = ClusterOptions(leaseParallelism = 1)
            val runtime = SubscriptionRuntime(
                GROUP.name,
                InMemoryEventLog(PARTITIONS),
                InMemoryCheckpointStore(),
                slow,
                listOf(Subscription("projector", SubscriptionFilter(GROUP.context)) {}),
                context = StandardTestDispatcher(testScheduler),
            )
            val node = ownership(STUCK, store, runtime, options)
            val leaving = ownership(OTHER, store, options = options)
            try {
                node.start()
                leaving.start()
                delay(10.seconds)
                val share = (0 until PARTITIONS).filter { leaving.accepts(it) }
                share.assert().isNotEmpty()

                // The other node leaves: this node takes its share one partition at a time, each start 20 s.
                slow.slow = true
                leaving.stop()
                var longest = Duration.ZERO
                repeat((20.seconds * share.size + 30.seconds).inWholeSeconds.toInt()) {
                    delay(1.seconds)
                    node.reconcilingFor?.let { longest = maxOf(longest, it) }
                    store.members(GROUP.service).filter { GROUP.name in it.groups }.map { it.node }.assert()
                        .containsExactly(STUCK)
                }
                (longest > options.reconcileTimeout).assert().describedAs("the reconcile ran $longest").isTrue()
                events.map { it.second }.filterIsInstance<ObservedEvent.OwnershipFenced>().assert().isEmpty()
                node.owned.assert().hasSize(PARTITIONS)
            } finally {
                slow.slow = false
                leaving.stop()
                node.stop()
            }
        }

    @Test
    fun `the reconcile timeout is positive`() {
        assertThrows<IllegalArgumentException> { ClusterOptions(reconcileTimeout = Duration.ZERO) }
    }

    private fun TestScope.ownership(
        node: String,
        leases: LeaseStore,
        runtime: SubscriptionRuntime? = null,
        options: ClusterOptions = ClusterOptions(),
    ) = PartitionOwnership(
        GROUP,
        node,
        { "$node.internal:7070" },
        PARTITIONS,
        leases,
        options,
        subscriptions = runtime,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        observer = object : Observer {
            override fun observed(event: ObservedEvent) {
                events += testScheduler.currentTime to event
            }
        },
    )

    /** What the ownerships observed, each with the virtual time it was observed at. */
    private val events = CopyOnWriteArrayList<Pair<Long, ObservedEvent>>()

    private fun TestScope.stuckRuntime(log: EventLog) = SubscriptionRuntime(
        GROUP.name,
        log,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        context = StandardTestDispatcher(testScheduler),
    )

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /**
     * A partition's start reads its parked aggregates, which take [pause] while [slow], as on a store under load: a
     * reconcile that is long but finishes each step.
     */
    private class SlowStarts(
        private val delegate: FailureLedger,
        private val pause: Duration,
    ) : FailureLedger by delegate {
        @Volatile
        var slow = false

        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> {
            if (slow) {
                delay(pause)
            }
            return delegate.due(subscription, partitions, now, after, limit)
        }
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
