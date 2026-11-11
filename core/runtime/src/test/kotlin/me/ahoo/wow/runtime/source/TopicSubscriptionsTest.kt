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
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.FlakyLeases
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The subscriptions of another context's published topic (decision D134) over [InMemoryTopics]: partitions leased by
 * HRW in the group `(service, "topic:<name>")`, offsets in the checkpoint store, copies dropped, and parking retried
 * by re-scanning the partition from the offset parked, unless retention passed it.
 */
class TopicSubscriptionsTest : TopicSubscriptionsFixtures() {
    @Test
    fun `a node that steps out of a topic's group should hand its partitions to the other and join it no more`(): Unit =
        runBlocking {
            // X-80: after a fatal error the node holds no partition of the topic, and never takes one again.
            val one = start("node-1")
            val two = start("node-2")
            eventually { listOf(one, two).all { it.ownership!!.owned.isNotEmpty() } }
            one.stepOut(10.seconds)
            eventually { two.ownership!!.owned == (0 until PARTITIONS).toSet() }
            one.ownership!!.owned.assert().isEmpty()
            one.stop()
            assertThrows<IllegalStateException> { runBlocking { one.start() } }.message.assert().contains("stepped out")
        }

    @Test
    fun `the partitions should be leased across the nodes by HRW and each record handled once`(): Unit = runBlocking {
        val one = start("node-1")
        val two = start("node-2")
        one.group.assert().isEqualTo(LeaseGroup(SERVICE, "topic:$ORDER_TOPIC"))
        eventually {
            val owned = listOf(one, two).map { it.ownership!!.owned }
            owned.all { it.isNotEmpty() } && owned.flatten().sorted() == (0 until PARTITIONS).toList()
        }
        one.ownership!!.owned.intersect(two.ownership!!.owned).assert().isEmpty()
        val ids = (0 until 20).map { "o-$it" }
        topics.publish(*ids.map { orderRecord(it, 1) }.toTypedArray())
        eventually { handled.size == ids.size }
        // A graceful leave hands the partitions over with their checkpoints: nothing is handled twice.
        two.stop()
        eventually { one.ownership!!.owned == (0 until PARTITIONS).toSet() }
        topics.publish(*ids.map { orderRecord(it, 2) }.toTypedArray())
        // A version 1 read again after the hand-off comes before its version 2, in its partition.
        eventually { ids.all { 2 in handled.versionsOf(it) } }
        handled.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(handled.size)
        ids.forEach { handled.versionsOf(it).assert().containsExactly(1, 2) }
    }

    @Test
    fun `the offset after the last record completed should be the checkpoint, and a restart go on from it`(): Unit =
        runBlocking {
            val partition = partitionOf("o-1")
            val group = LeaseGroup.topic(SERVICE, ORDER_TOPIC).name
            topics.publish(*(1..3).map { orderRecord("o-1", it) }.toTypedArray())
            val first = start("node-1")
            eventually { checkpoints.load(group, BOARD, partition) == 3L }
            first.stop()
            topics.publish(orderRecord("o-1", 4))
            start("node-1")
            eventually { handled.versionsOf("o-1").size == 4 }
            handled.versionsOf("o-1").assert().containsExactly(1, 2, 3, 4)
            eventually { checkpoints.load(group, BOARD, partition) == 4L }
            // The sequences handed over are the offsets plus one.
            handled.sequences().assert().containsExactly(1L, 2L, 3L, 4L)
        }

    @Test
    fun `a record published twice should be handled once`(): Unit = runBlocking {
        start("node-1")
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 1), orderRecord("o-1", 2), orderRecord("o-1", 1))
        eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf("o-1")) == 4L }
        handled.versionsOf("o-1").assert().containsExactly(1, 2)
    }

    @Test
    fun `a subscription without a checkpoint should start where it says`(): Unit = runBlocking {
        topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
        val end = Subscription("Tail.onOrder", SubscriptionFilter(ORDER.context), StartFrom.END) { handled.record(it) }
        start("node-1", end)
        topics.publish(orderRecord("o-1", 5))
        eventually { handled.size == 1 }
        handled.versionsOf("o-1").assert().containsExactly(5)
    }

    @Test
    fun `a start the host overrides with an instant should read from the first record committed at or after it`():
        Unit = runBlocking {
        topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
        start(
            "node-1",
            starts = mapOf(BOARD_ID to SubscriptionStart.At(Instant.ofEpochMilli(2_500)))
        )
        topics.publish(orderRecord("o-1", 5))
        eventually { handled.size == 3 }
        handled.versionsOf("o-1").assert().containsExactly(3, 4, 5)
    }

    @Test
    fun `a topic's subscriptions should be named apart`() {
        assertThrows<IllegalArgumentException> {
            TopicSubscriptions(SERVICE, ORDER_TOPIC, "n", { "a" }, topics, checkpoints, leases, ledger, emptyList())
        }
        val twice = listOf(board, board)
        assertThrows<IllegalArgumentException> {
            TopicSubscriptions(SERVICE, ORDER_TOPIC, "n", { "a" }, topics, checkpoints, leases, ledger, twice)
        }
    }

    @Test
    fun `a join that fails should be retried until the node reads the topic`(): Unit = runBlocking {
        // Review R2.1-8: the heartbeat fails twice, then the membership's read of the members once (finding R5.B-4).
        val flaky = FlakyLeases(leases).apply {
            heartbeats.set(2)
            memberReads.set(1)
        }
        val subscriptions = topicSubscriptions(flaky)
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.STOPPED)
        subscriptions.start()
        subscriptions.awaitStarted()
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.READING)
        subscriptions.joinFailures.assert().isZero()
        subscriptions.lastJoinFailure.assert().isNull()
        flaky.failures.get().assert().isEqualTo(3)
        eventually { subscriptions.ownership?.owned == (0 until PARTITIONS).toSet() }
        topics.publish(orderRecord("o-1", 1))
        eventually { handled.size == 1 }
        subscriptions.stop()
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.STOPPED)
    }

    @Test
    fun `a node that cannot join should say it is joining, and stop`(): Unit = runBlocking {
        val flaky = FlakyLeases(leases).apply { heartbeats.set(Int.MAX_VALUE) }
        val subscriptions = topicSubscriptions(flaky)
        subscriptions.start()
        eventually { subscriptions.joinFailures >= 3 }
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.JOINING)
        subscriptions.lastJoinFailure!!.message.assert().contains("heartbeat")
        subscriptions.runtime.assert().isNull()
        subscriptions.stop()
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.STOPPED)
        leases.members(SERVICE).assert().isEmpty()
        // Started again once the store is back, it joins.
        flaky.heartbeats.set(0)
        subscriptions.start()
        subscriptions.awaitStarted()
        subscriptions.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.READING)
    }

    @Test
    fun `a join over partitions added that fails should be retried until it reads them all`(): Unit = runBlocking {
        val topic = "wow.sales.flaky.state"
        topics.create(topic, 2)
        val flaky = FlakyLeases(leases)
        val growing = topicSubscriptions(flaky, topic, partitionRefresh = 20.milliseconds)
        growing.start()
        growing.awaitStarted()
        eventually { growing.ownership?.owned == setOf(0, 1) }
        flaky.heartbeats.set(3)
        topics.grow(topic, PARTITIONS)
        eventually { growing.ownership?.owned == (0 until PARTITIONS).toSet() }
        flaky.failures.get().assert().isEqualTo(3)
        growing.joinState.assert().isEqualTo(TopicSubscriptions.JoinState.READING)
        val record = EnvelopeCodec.message(orderRecord("o-3", 1))
        topics.send(topic, TopicMessage.of(record.key, record.value, record.headers, PARTITIONS - 1))
        eventually { handled.versionsOf("o-3") == listOf(1) }
    }

    private fun topicSubscriptions(
        store: LeaseStore,
        topic: String = ORDER_TOPIC,
        partitionRefresh: Duration = Duration.INFINITE,
    ): TopicSubscriptions = TopicSubscriptions(
        service = SERVICE,
        topic = topic,
        node = "node-1",
        addressOf = { "node-1.internal:9090" },
        source = topics,
        checkpoints = checkpoints,
        leases = store,
        ledger = ledger,
        subscriptions = listOf(board),
        options = testOptions(),
        cluster = fastOptions(),
        partitionRefresh = partitionRefresh,
    ).also { started += it }
}
