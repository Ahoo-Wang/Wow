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

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The partitions the first reconcile of a node's join starts, over a log holding a backlog: none reads its entries
 * until the reconcile has started all of them, or until the job the node passes completes, so the backlog the first
 * of them would read does not slow down the starts of the others. Then every entry is handled.
 */
class FirstReconcileHoldTest {
    private val log = InMemoryEventLog(PARTITIONS)
    private val appender = Appender(log)

    /** For each entry handled, how many partitions the runtime had started by then. */
    private val startedWhenHandled = CopyOnWriteArrayList<Int>()

    private val runtime: SubscriptionRuntime = SubscriptionRuntime(
        GROUP.name,
        log,
        InMemoryCheckpointStore(),
        SlowLedger(InMemoryFailureLedger()),
        listOf(Subscription("projector", SubscriptionFilter("shop")) { startedWhenHandled += runtime.partitions.size }),
        testOptions(),
    )

    /** One partition acquired at a time, each slow to start: without the hold, the first would read long before the last. */
    private val ownership = PartitionOwnership(
        GROUP,
        "node-1",
        { "node-1.internal:7070" },
        PARTITIONS,
        InMemoryLeaseStore(),
        options = fastOptions(leaseParallelism = 1),
        subscriptions = runtime,
    )

    @AfterEach
    fun stop(): Unit = runBlocking { ownership.stop() }

    private suspend fun backlog() = repeat(ENTRIES) { appender.append("a$it") }

    @Test
    fun `the partitions of the first reconcile should read nothing until it has started them all`(): Unit = runBlocking {
        backlog()
        appender.ends.keys.assert().hasSize(PARTITIONS)
        ownership.start()
        ownership.owned.assert().hasSize(PARTITIONS)
        eventually { startedWhenHandled.size == ENTRIES }
        startedWhenHandled.toSet().assert().containsExactly(PARTITIONS)
    }

    @Test
    fun `the partitions of the first reconcile should read nothing until the job the node passes completes`(): Unit =
        runBlocking {
            backlog()
            val reads = Job()
            ownership.start(reads)
            ownership.owned.assert().hasSize(PARTITIONS)
            delay(SETTLE_MILLIS)
            startedWhenHandled.assert().isEmpty()
            reads.complete()
            eventually { startedWhenHandled.size == ENTRIES }
        }

    /** A ledger slow to read a partition's parked aggregates, as a store under load is: each partition starts slowly. */
    private class SlowLedger(private val delegate: FailureLedger) : FailureLedger by delegate {
        override suspend fun due(
            subscription: String,
            partitions: Collection<Int>,
            now: Long,
            after: AggregateRef?,
            limit: Int,
        ): List<ParkedAggregate> {
            delay(START_MILLIS)
            return delegate.due(subscription, partitions, now, after, limit)
        }
    }

    private companion object {
        const val PARTITIONS = 8
        const val ENTRIES = 200
        const val START_MILLIS = 50L
        const val SETTLE_MILLIS = 300L
        val GROUP = LeaseGroup("shop-service", "shop")
    }
}
