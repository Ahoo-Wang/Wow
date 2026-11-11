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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SystemicFailureOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * A topic that gains partitions: read once it has some, partitions added while reading, and an aggregate whose key moved
 * to an added partition, held, skipped and answered by the owner of the partition it maps to now.
 */
class TopicGrowthTest : TopicSubscriptionsFixtures() {
    @Test
    fun `a topic without partitions yet should be read once it has some`(): Unit = runBlocking {
        val empty = object : me.ahoo.wow.spi.transport.PartitionedSource by topics {
            @Volatile
            var ready = false

            override suspend fun partitions(topic: String): List<Int> =
                if (ready) topics.partitions(topic) else emptyList()
        }
        val subscriptions = TopicSubscriptions(
            SERVICE,
            ORDER_TOPIC,
            "node-1",
            { "node-1.internal:9090" },
            empty,
            checkpoints,
            leases,
            ledger,
            listOf(board),
            testOptions(),
            fastOptions(),
        )
        started += subscriptions
        subscriptions.start()
        subscriptions.runtime.assert().isNull()
        assertThrows<IllegalStateException> { runBlocking { subscriptions.retry(BOARD, order("o-1")) } }
        // Skipping needs no partition: the ledger entry is resolved.
        subscriptions.skip(BOARD, order("o-1")).assert().isFalse()
        empty.ready = true
        subscriptions.awaitStarted()
        topics.publish(orderRecord("o-1", 1))
        eventually { handled.size == 1 }
        subscriptions.toString().assert().contains(ORDER_TOPIC)
    }

    @Test
    fun `partitions added while reading should be read too, the others going on from their checkpoints`(): Unit =
        runBlocking {
            val topic = "wow.sales.growing.state"
            topics.create(topic, 2)
            val growing = TopicSubscriptions(
                service = SERVICE,
                topic = topic,
                node = "node-1",
                addressOf = { "node-1.internal:9090" },
                source = topics,
                checkpoints = checkpoints,
                leases = leases,
                ledger = ledger,
                subscriptions = listOf(board),
                options = testOptions(),
                cluster = fastOptions(),
                partitionRefresh = 20.milliseconds,
            ).also {
                started += it
                it.start()
                it.awaitStarted()
            }
            topics.publish(orderRecord("o-1", 1), orderRecord("o-2", 1), topic = topic)
            eventually { handled.size == 2 }
            topics.grow(topic, PARTITIONS)
            eventually { growing.ownership?.owned == (0 until PARTITIONS).toSet() }
            val record = EnvelopeCodec.message(orderRecord("o-3", 1))
            topics.send(topic, TopicMessage.of(record.key, record.value, record.headers, PARTITIONS - 1))
            eventually { handled.versionsOf("o-3") == listOf(1) }
            // A record of each first partition, read after o-1 and o-2 would be if they were read again.
            val sentinels = (0 until 2).map { partition ->
                generateSequence(0) { it + 1 }.map { "s-$it" }
                    .first { topics.partitionOf(topic, order(it).toString()) == partition }
            }
            topics.publish(*sentinels.map { orderRecord(it, 1) }.toTypedArray(), topic = topic)
            eventually { sentinels.all { handled.versionsOf(it) == listOf(1) } }
            // Nothing read before the partitions were added was handled again.
            listOf("o-1", "o-2").forEach { handled.versionsOf(it).assert().containsExactly(1) }
            handled.size.assert().isEqualTo(5)
        }

    /** Reads [topic] on [node] with [subscription], looking for new partitions every 20 ms. */
    private suspend fun growing(
        node: String,
        topic: String,
        subscription: Subscription = board,
        ledger: FailureLedger = this.ledger,
        options: SubscriptionOptions = testOptions(),
    ): TopicSubscriptions = TopicSubscriptions(
        service = SERVICE,
        topic = topic,
        node = node,
        addressOf = { "$node.internal:9090" },
        source = topics,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(subscription),
        options = options.copyWith(parked = ParkedRetryOptions(scanInterval = 10.milliseconds)),
        cluster = fastOptions(),
        partitionRefresh = 20.milliseconds,
    ).also {
        started += it
        it.start()
        it.awaitStarted()
    }

    @Test
    fun `an aggregate parked before its key moved to an added partition is re-homed, held and only skipped`(): Unit =
        runBlocking {
            // #5481: version 2 lands at the parked offset of the new partition; it must not stand in for version 1.
            val topic = "wow.sales.moving.state"
            topics.create(topic, 1)
            val id = generateSequence(0) { it + 1 }.map { "o-$it" }
                .first { Math.floorMod(order(it).toString().hashCode(), 2) == 1 }
            val listings = AtomicInteger()
            val counting = object : FailureLedger by ledger {
                override suspend fun list(
                    subscription: SubscriptionId,
                    after: AggregateRef?,
                    limit: Int,
                    tenantId: String?,
                ): List<ParkedAggregate> = ledger.list(subscription, after, limit, tenantId).also {
                    listings.incrementAndGet()
                }
            }
            val growing = growing("node-1", topic, ledger = counting)
            failing = id to 1
            topics.publish(orderRecord(id, 1), topic = topic)
            eventually { ledger.parked(BOARD_ID, order(id)) != null }
            val parked = ledger.parked(BOARD_ID, order(id))!!
            topics.grow(topic, 2)
            eventually {
                growing.ownership?.owned == setOf(0, 1) && ledger.parked(BOARD_ID, order(id))?.partition == 1
            }
            val rehomed = ledger.parked(BOARD_ID, order(id))!!
            rehomed.unrecoverable.assert().isTrue()
            rehomed.reason.assert().contains(
                "$topic gained partitions",
                "partition 0 from offset ${parked.fromPosition}"
            )
            rehomed.fromPosition.assert().isNull()
            rehomed.fromVersion.assert().isEqualTo(1)
            failing = null
            topics.publish(orderRecord(id, 2), topic = topic)
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, topic).name, BOARD, 1) == 1L }
            handled.versionsOf(id).assert().isEmpty()
            growing.retry(BOARD, order(id)).assert().isFalse()
            growing.skip(BOARD, order(id)).assert().isTrue()
            topics.publish(orderRecord(id, 3), topic = topic)
            eventually { handled.versionsOf(id) == listOf(3) }
            // One listing of the ledger a run: the first join's, and the join again over two partitions.
            listings.get().assert().isEqualTo(2)
        }

    @Test
    fun `a moved aggregate is held, skipped and answered by the owner of the partition its key maps to now`(): Unit =
        runBlocking {
            // #5481, two owners: the entry parked on partition 0's owner; its key maps to a partition the other owns.
            val topic = "wow.sales.split.state"
            topics.create(topic, 1)
            val broken = AtomicBoolean(true)
            val split = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { entry ->
                check(!(broken.get() && entry.version == 1)) { "${entry.ref}@1 fails" }
                handled.record(entry)
            }
            // Many aggregates fail at once here, which is no systemic failure.
            val options = testOptions().copyWith(systemic = SystemicFailureOptions(failingAggregates = Int.MAX_VALUE))
            val nodes = listOf("node-1", "node-2").map { growing(it, topic, split, options = options) }
            val ids = (0 until 16).map { "s-$it" }
            topics.publish(*ids.map { orderRecord(it, 1) }.toTypedArray(), topic = topic)
            eventually { ids.all { ledger.parked(BOARD_ID, order(it)) != null } }
            topics.grow(topic, PARTITIONS)
            eventually {
                val owned = nodes.map { it.ownership?.owned.orEmpty() }
                owned.all { it.isNotEmpty() } && owned.flatten().sorted() == (0 until PARTITIONS).toList()
            }
            val (old, new) = nodes.partition { 0 in it.ownership!!.owned }.let { it.first.single() to it.second.single() }
            val id = ids.first { topics.partitionOf(topic, order(it).toString()) in new.ownership!!.owned }
            val now = topics.partitionOf(topic, order(id).toString())
            eventually { ledger.parked(BOARD_ID, order(id))?.partition == now }
            ledger.parked(BOARD_ID, order(id))!!.unrecoverable.assert().isTrue()
            broken.set(false)
            // Its later version waits on the new owner.
            topics.publish(orderRecord(id, 2), topic = topic)
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, topic).name, BOARD, now) == 1L }
            handled.versionsOf(id).assert().isEmpty()
            // An operator's retry, left as a request by a node that does not run the partition, is taken by the new
            // owner and answered: the entry cannot be retried, and stays parked.
            ledger.amend(BOARD_ID, order(id), ParkedAmendment.Request(LedgerRequest.RETRY))
            eventually { ledger.parked(BOARD_ID, order(id))?.request == null }
            ledger.parked(BOARD_ID, order(id))!!.partition.assert().isEqualTo(now)
            handled.versionsOf(id).assert().isEmpty()
            // The old owner runs no worker of it; the new owner's skip resolves it.
            old.ownership!!.owned.assert().doesNotContain(now)
            new.skip(BOARD, order(id)).assert().isTrue()
            ledger.parked(BOARD_ID, order(id)).assert().isNull()
            topics.publish(orderRecord(id, 3), topic = topic)
            eventually { handled.versionsOf(id) == listOf(3) }
        }
}
