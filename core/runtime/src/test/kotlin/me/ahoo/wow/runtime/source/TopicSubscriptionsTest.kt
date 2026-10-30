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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.FlakyLeases
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.Handled
import me.ahoo.wow.runtime.subscription.RecordingCheckpoints
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The subscriptions of another context's published topic (decision D134) over [InMemoryTopics]: partitions leased by
 * HRW in the group `(service, "topic:<name>")`, offsets in the checkpoint store, copies dropped, and parking retried
 * by re-scanning the partition from the offset parked, unless retention passed it.
 */
class TopicSubscriptionsTest {
    private val topics = InMemoryTopics(PARTITIONS).also { it.create(ORDER_TOPIC, PARTITIONS) }
    private val leases = InMemoryLeaseStore()
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()
    private val handled = Handled()
    private val started = CopyOnWriteArrayList<TopicSubscriptions>()

    /** The undecodable messages the observer heard of: offset and whether it was parked. */
    private val undecodable = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            when (event) {
                is ObservedEvent.Undecodable -> undecodable += event.offset to event.parked
                else -> Unit
            }
        }
    }

    /** While set, the handler fails for this aggregate at this version. */
    @Volatile
    private var failing: Pair<String, Int>? = null

    private val board = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { entry ->
        val failure = failing
        if (failure != null && entry.ref.id == failure.first && entry.version >= failure.second) {
            throw IllegalStateException("${entry.ref}@${entry.version} fails")
        }
        checkNotNull(entry.published) { "A record read from a topic carries it." }
        handled.record(entry)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        started.forEach { it.stop() }
    }

    private suspend fun start(
        node: String,
        subscription: Subscription = board,
        starts: Map<SubscriptionId, SubscriptionStart> = emptyMap(),
    ): TopicSubscriptions = TopicSubscriptions(
        service = SERVICE,
        topic = ORDER_TOPIC,
        node = node,
        addressOf = { "$node.internal:9090" },
        source = topics,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(subscription),
        options = testOptions().copyWith(starts = starts),
        cluster = fastOptions(),
        observer = observer,
    ).also {
        started += it
        it.start()
        it.awaitStarted()
    }

    private suspend fun partitionOf(id: String): Int = topics.partitionOf(ORDER_TOPIC, order(id).toString())

    /** The offset of [id]'s record at [version] in its partition. */
    private suspend fun offsetOf(id: String, version: Int): Long = topics.messages(ORDER_TOPIC).single {
        it.key == order(id).toString() && EnvelopeCodec.stateEventOf(it.value).version == version
    }.offset

    /** The ids of [count] orders of [partition]. */
    private suspend fun idsIn(partition: Int, count: Int): List<String> = buildList {
        var n = 0
        while (size < count) {
            val id = "o-${n++}"
            if (partitionOf(id) == partition) {
                add(id)
            }
        }
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
        eventually { handled.size == ids.size * 2 }
        delay(50.milliseconds)
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
    fun `a message without a key that holds no state event should be passed over`(): Unit = runBlocking {
        start("node-1")
        val partition = partitionOf("o-1")
        topics.send(ORDER_TOPIC, TopicMessage.of("not a ref", "not an envelope".toByteArray(), partition = partition))
        topics.publish(orderRecord("o-1", 1))
        eventually { handled.size == 1 }
        eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partition) == 2L }
        ledger.parked(BOARD, order("o-1")).assert().isNull()
        undecodable.assert().containsExactly(0L to false)
    }

    @Test
    fun `a message of an aggregate that holds no state event should park the aggregate there`(): Unit =
        runBlocking {
            // Review A3-14: failure is data; the operator sees it and decides.
            val subscriptions = start("node-1")
            topics.publish(orderRecord("o-1", 1))
            eventually { handled.versionsOf("o-1") == listOf(1) }
            topics.send(ORDER_TOPIC, TopicMessage.of(order("o-1").toString(), BAD.toByteArray()))
            topics.publish(orderRecord("o-1", 2))
            eventually { ledger.parked(BOARD, order("o-1")) != null }
            val parked = ledger.parked(BOARD, order("o-1"))!!
            parked.reason.assert().contains("cannot be decoded")
            undecodable.assert().containsExactly(parked.fromPosition!! to true)
            parked.fromPosition.assert().isEqualTo(
                topics.messages(ORDER_TOPIC).single { String(it.value) == BAD }.offset
            )
            // The aggregate's later record waits behind it; the checkpoint moves past both.
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf("o-1")) == 3L }
            handled.versionsOf("o-1").assert().containsExactly(1)
            // A retry meets it again; a skip resolves it.
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            ledger.parked(BOARD, order("o-1")).assert().isNull()
        }

    @Test
    fun `a failing aggregate should be parked at its offset and retried by re-scanning its partition`(): Unit =
        runBlocking {
            val (parkedId, otherId) = idsIn(partitionOf("o-1"), 2)
            val subscriptions = start("node-1")
            failing = parkedId to 2
            topics.publish(orderRecord(parkedId, 1), orderRecord(parkedId, 2), orderRecord(otherId, 1))
            topics.publish(orderRecord(parkedId, 3))
            eventually { ledger.parked(BOARD, order(parkedId)) != null }
            val parked = ledger.parked(BOARD, order(parkedId))!!
            parked.fromVersion.assert().isEqualTo(2)
            parked.fromPosition.assert().isEqualTo(offsetOf(parkedId, 2))
            parked.unrecoverable.assert().isFalse()
            // The other aggregate of the partition goes on; the parked one's later records wait.
            eventually { handled.versionsOf(otherId) == listOf(1) }
            handled.versionsOf(parkedId).assert().containsExactly(1)
            failing = null
            subscriptions.retry(BOARD, order(parkedId)).assert().isTrue()
            handled.versionsOf(parkedId).assert().containsExactly(1, 2, 3)
            ledger.parked(BOARD, order(parkedId)).assert().isNull()
            topics.publish(orderRecord(parkedId, 4))
            eventually { handled.versionsOf(parkedId) == listOf(1, 2, 3, 4) }
        }

    @Test
    fun `a retry that fails again should stay parked from where it failed, at that record's offset`(): Unit =
        runBlocking {
            val subscriptions = start("node-1")
            failing = "o-1" to 2
            topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
            eventually { ledger.parked(BOARD, order("o-1")) != null }
            failing = "o-1" to 3
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            val parked = ledger.parked(BOARD, order("o-1"))!!
            parked.fromVersion.assert().isEqualTo(3)
            parked.fromPosition.assert().isEqualTo(offsetOf("o-1", 3))
            failing = null
            subscriptions.retry(BOARD, order("o-1")).assert().isTrue()
            handled.versionsOf("o-1").assert().containsExactly(1, 2, 3, 4)
        }

    @Test
    fun `a parked aggregate whose offset retention passed should be unrecoverable and only skipped`(): Unit =
        runBlocking {
            val subscriptions = start("node-1")
            failing = "o-1" to 2
            topics.publish(*(1..3).map { orderRecord("o-1", it) }.toTypedArray())
            eventually { ledger.parked(BOARD, order("o-1")) != null }
            val position = ledger.parked(BOARD, order("o-1"))!!.fromPosition!!
            topics.retain(ORDER_TOPIC, partitionOf("o-1"), position + 1)
            failing = null
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            val parked = ledger.parked(BOARD, order("o-1"))!!
            parked.unrecoverable.assert().isTrue()
            parked.reason.assert().startsWith(ParkedAggregate.UNRECOVERABLE)
            parked.fromVersion.assert().isEqualTo(2)
            parked.fromPosition.assert().isEqualTo(position)
            // Marked once: a retry no longer tries.
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            ledger.parked(BOARD, order("o-1")).assert().isNull()
            topics.publish(orderRecord("o-1", 4))
            eventually { handled.versionsOf("o-1") == listOf(1, 4) }
            assertThrows<IllegalArgumentException> { runBlocking { subscriptions.skip("Other.onOrder", order("o-1")) } }
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
            starts = mapOf(SubscriptionId(ORDER.context, BOARD) to SubscriptionStart.At(Instant.ofEpochMilli(2_500)))
        )
        topics.publish(orderRecord("o-1", 5))
        eventually { handled.size == 3 }
        handled.versionsOf("o-1").assert().containsExactly(3, 4, 5)
    }

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
            delay(50.milliseconds)
            // Nothing read before the partitions were added was handled again.
            handled.size.assert().isEqualTo(3)
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

    private companion object {
        const val SERVICE = "board-service"
        const val BOARD = "Board.onOrder"
        const val PARTITIONS = 4
        const val BAD = "not an envelope"
    }
}
