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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
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
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The partitions the first reconcile of a node's join starts, over a log holding a backlog: none reads its entries
 * until the reconcile has started all of them, or until the job the node passes completes, so the backlog the first
 * of them would read does not slow down the starts of the others. Then every entry is handled. A reconcile stuck
 * starting one partition holds the others back for its reconcile timeout at most.
 */
class FirstReconcileHoldTest {
    private val log = InMemoryEventLog(PARTITIONS)
    private val appender = Appender(log)

    /** For each entry handled, its id and how many partitions the runtime had started by then. */
    private val handled = CopyOnWriteArrayList<Pair<String, Int>>()

    private var ownership: PartitionOwnership? = null

    /** An ownership over [ledger], acquiring one partition at a time by default. */
    private fun ownership(
        ledger: FailureLedger,
        options: ClusterOptions = fastOptions(
            leaseParallelism = 1
        )
    ): PartitionOwnership {
        lateinit var runtime: SubscriptionRuntime
        runtime = SubscriptionRuntime(
            GROUP.name,
            log,
            InMemoryCheckpointStore(),
            ledger,
            listOf(Subscription("projector", SubscriptionFilter("shop")) { handled += it.ref.id to runtime.partitions.size }),
            testOptions(),
        )
        return PartitionOwnership(
            GROUP,
            "node-1",
            { "node-1.internal:7070" },
            PARTITIONS,
            InMemoryLeaseStore(),
            options = options,
            subscriptions = runtime,
        ).also { ownership = it }
    }

    @AfterEach
    fun stop(): Unit = runBlocking { ownership?.stop() ?: Unit }

    private suspend fun backlog() = repeat(ENTRIES) { appender.append("a$it") }

    @Test
    fun `the partitions of the first reconcile should read nothing until it has started them all`(): Unit = runBlocking {
        backlog()
        appender.ends.keys.assert().hasSize(PARTITIONS)
        val ownership = ownership(SlowLedger(InMemoryFailureLedger()))
        ownership.start()
        ownership.owned.assert().hasSize(PARTITIONS)
        eventually { handled.size == ENTRIES }
        handled.map { it.second }.toSet().assert().containsExactly(PARTITIONS)
    }

    @Test
    fun `the partitions of the first reconcile should read nothing until the job the node passes completes`(): Unit =
        runBlocking {
            backlog()
            val ownership = ownership(SlowLedger(InMemoryFailureLedger()))
            val reads = Job()
            ownership.start(reads)
            ownership.owned.assert().hasSize(PARTITIONS)
            delay(SETTLE_MILLIS)
            handled.assert().isEmpty()
            reads.complete()
            eventually { handled.size == ENTRIES }
        }

    @Test
    fun `a first reconcile stuck starting one partition should hold the others back for its reconcile timeout at most`(): Unit =
        runBlocking {
            backlog()
            val stuck = CompletableDeferred<Unit>()
            val options = fastOptions(drainTimeout = 200.milliseconds, reconcileTimeout = 1.seconds)
            val ownership = ownership(StuckLedger(InMemoryFailureLedger(), STUCK, stuck), options)
            val starting = launch { ownership.start() }
            try {
                eventually { ownership.owned.size == PARTITIONS - 1 }
                delay(SETTLE_MILLIS)
                handled.assert().isEmpty()

                // Past the reconcile timeout, the partitions started read their backlogs; the stuck one still starts.
                val others = (0 until ENTRIES).map { "a$it" }.filter { Partitioner.partitionOf(cart(it), PARTITIONS) != STUCK }
                eventually(timeout = 5.seconds) { handled.size == others.size }
                handled.map { it.first }.toSet().assert().isEqualTo(others.toSet())
                starting.isActive.assert().isTrue()

                stuck.complete(Unit)
                starting.join()
                eventually { handled.size == ENTRIES }
            } finally {
                stuck.complete(Unit)
            }
        }

    /** A ledger slow to read a partition's parked aggregates, as a store under load is: each partition starts slowly. */
    private class SlowLedger(private val delegate: FailureLedger) : FailureLedger by delegate {
        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> {
            delay(START_MILLIS)
            return delegate.due(subscription, partitions, now, after, limit)
        }
    }

    /** A ledger whose read of [partition]'s parked aggregates waits for [released]: that partition's start hangs. */
    private class StuckLedger(
        private val delegate: FailureLedger,
        private val partition: Int,
        private val released: CompletableDeferred<Unit>,
    ) : FailureLedger by delegate {
        override suspend fun due(
            subscription: SubscriptionId,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> {
            if (partition in partitions) {
                released.await()
            }
            return delegate.due(subscription, partitions, now, after, limit)
        }
    }

    private companion object {
        const val PARTITIONS = 8
        const val ENTRIES = 200
        const val STUCK = 5
        const val START_MILLIS = 50L
        const val SETTLE_MILLIS = 300L
        val GROUP = LeaseGroup("shop-service", "shop")
    }
}
