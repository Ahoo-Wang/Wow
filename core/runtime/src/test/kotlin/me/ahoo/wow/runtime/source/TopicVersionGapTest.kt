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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.LogSource
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RunHandled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionWorker
import me.ahoo.wow.runtime.subscription.VersionGapException
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * A state topic's records come at every version of an aggregate (the transport sink publishes every commit, D491): a
 * record above the version after the last one a subscription saw parks the aggregate at the version missing, and its
 * later records wait behind the park. A late record resolves it in order at the next automatic retry; an operator's
 * retry accepts the gap and goes on from the next version present.
 */
class TopicVersionGapTest : TopicSubscriptionsFixtures() {
    /** A node of [board] whose parked entries are retried automatically every 10 ms, at most [retries] times. */
    private suspend fun startRetrying(
        retries: Int = 50,
        source: PartitionedSource = topics,
        subscription: Subscription = board,
    ) = TopicSubscriptions(
        service = SERVICE,
        topic = ORDER_TOPIC,
        node = "node-1",
        addressOf = { "node-1.internal:9090" },
        source = source,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(subscription),
        options = testOptions().copyWith(
            parked = ParkedRetryOptions(
                spec = ParkedRetrySpec(
                    maxRetries = retries,
                    initialBackoff = 10.milliseconds,
                    claimTtl = 10.milliseconds,
                ),
                scanInterval = 10.milliseconds,
            ),
        ),
        cluster = fastOptions(),
        observer = observer,
    ).also {
        started += it
        it.start()
        it.awaitStarted()
    }

    /** The offset of [id]'s record at [version] in its partition. */
    private suspend fun offsetOf(id: String, version: Int): Long = topics.messages(ORDER_TOPIC).single {
        it.key == order(id).toString() && EnvelopeCodec.stateEventOf(it.value).version == version
    }.offset

    private suspend fun checkpoint(id: String): Long? =
        checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf(id))

    @Test
    fun `a version the topic did not deliver should park the aggregate there, the records after it held`(): Unit =
        runBlocking {
            start("node-1")
            topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 3))
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            val parked = ledger.parked(BOARD_ID, order("o-1"))!!
            parked.fromVersion.assert().isEqualTo(2)
            parked.fromPosition.assert().isEqualTo(offsetOf("o-1", 3))
            parked.reason.assert().contains("'wow.subscription.version-gap'")
                .contains("wait and a late record resolves it in order")
                .contains("or retry by hand to accept the gap and go on from the next version")
                .contains("or skip to drop what it holds")
            topics.publish(orderRecord("o-1", 4))
            eventually { checkpoint("o-1") == offsetOf("o-1", 4) + 1 }
            handled.versionsOf("o-1").assert().containsExactly(1)
        }

    @Test
    fun `a late record should resolve the park in version order at the next automatic retry`(): Unit = runBlocking {
        startRetrying()
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 3))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        topics.publish(orderRecord("o-1", 2), orderRecord("o-1", 4))
        eventually { handled.versionsOf("o-1") == listOf(1, 2, 3, 4) }
        eventually { ledger.parked(BOARD_ID, order("o-1")) == null }
    }

    @Test
    fun `a record that never arrives should keep the aggregate parked at it, an automatic retry parking it again`():
        Unit = runBlocking {
        startRetrying(retries = 1)
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 3))
        // The retry ran (its claim counted) and parked the aggregate again from its start, at the version still
        // missing: the claim comes the initial backoff after the first park, so only the second is at or after it.
        eventually {
            ledger.parked(BOARD_ID, order("o-1"))?.let { it.retries == 1 && it.failedAt >= it.lastRetryAt!! } == true
        }
        val parked = ledger.parked(BOARD_ID, order("o-1"))!!
        parked.fromVersion.assert().isEqualTo(2)
        parked.reason.assert().contains("'wow.subscription.version-gap'")
        handled.versionsOf("o-1").assert().containsExactly(1)
    }

    @Test
    fun `an operator's retry should accept the gap and go on from the next version present`(): Unit = runBlocking {
        val subscriptions = start("node-1")
        val retired = orderRecord("o-1", 4).copyWith(events = emptyList())
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 3), retired, orderRecord("o-1", 5))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        subscriptions.retry(BOARD, order("o-1")).assert().isTrue()
        // Version 4, a record of no event, is passed over by the retry as by a live read.
        handled.versionsOf("o-1").assert().containsExactly(1, 3, 5)
        ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
    }

    @Test
    fun `an operator's retry asked through the ledger should accept the gap as well`(): Unit = runBlocking {
        // No automatic retry: the owner's scan takes the operator's request, as from another node's ops service.
        startRetrying(retries = 0)
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 3), orderRecord("o-1", 4))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        ledger.amend(BOARD_ID, order("o-1"), ParkedAmendment.Request(LedgerRequest.RETRY))
        eventually { handled.versionsOf("o-1") == listOf(1, 3, 4) }
        eventually { ledger.parked(BOARD_ID, order("o-1")) == null }
    }

    @Test
    fun `a record of an event the subscription does not take should count as a version, so the next is no gap`():
        Unit = runBlocking {
        val placed = Subscription(
            BOARD,
            SubscriptionFilter(ORDER.context, setOf(ORDER.name), setOf("sales.order.Placed")),
        ) { handled.record(it) }
        start("node-1", subscription = placed)
        val paid = StateEventPayload("sales.order.Paid", 1, WowJson.mapper.readTree("{}"))
        topics.publish(
            orderRecord("o-1", 1),
            orderRecord("o-1", 2).copyWith(events = listOf(paid)),
            orderRecord("o-1", 3),
        )
        eventually { handled.versionsOf("o-1") == listOf(1, 3) }
        ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
    }

    @Test
    fun `a record of no event and one of only schedules should count as versions, so the next is no gap`(): Unit =
        runBlocking {
            start("node-1")
            val scheduled = StateEventPayload("wow.schedule.ScheduleSet", 1, WowJson.mapper.readTree("{}"))
            topics.publish(
                orderRecord("o-1", 1),
                orderRecord("o-1", 2).copyWith(events = emptyList()),
                orderRecord("o-1", 3).copyWith(events = listOf(scheduled)),
                orderRecord("o-1", 4),
            )
            eventually { handled.versionsOf("o-1") == listOf(1, 4) }
            eventually { checkpoint("o-1") == offsetOf("o-1", 4) + 1 }
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
        }

    @Test
    fun `a gap in a lane that runs its entries together should park alone, no run handed it`(): Unit = runBlocking {
        val runs = CopyOnWriteArrayList<List<Int>>()
        val batching = Subscription.batching(
            BOARD,
            SubscriptionFilter(ORDER.context, setOf(ORDER.name)),
            maxRun = 16,
            runHandler = { entries ->
                runs += entries.map { it.entry.version }
                entries.forEach { handled.record(it.entry) }
                RunHandled(entries.size, null)
            },
        ) { handled.record(it) }
        start("node-1", subscription = batching)
        topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 2), orderRecord("o-1", 4), orderRecord("o-1", 5))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        ledger.parked(BOARD_ID, order("o-1"))!!.fromVersion.assert().isEqualTo(3)
        handled.versionsOf("o-1").assert().containsExactly(1, 2)
        runs.flatten().assert().doesNotContain(4, 5)
    }

    @Test
    fun `a park at an undecodable record should be no gap park once a version reads the record, automatically`():
        Unit = runBlocking {
        val source = UpgradedReader(topics)
        startRetrying(source = source)
        val parked = readableAfterPark(source)
        // The record reads now: the automatic retry handles it and the version after it, and resolves the park.
        eventually { ledger.parked(BOARD_ID, order("o-1")) == null }
        parked.reason.assert().doesNotContain(VersionGapException.CODE)
        handled.versionsOf("o-1").assert().contains(5, 6)
    }

    @Test
    fun `a park at an undecodable record should be no gap park once a version reads the record, by hand`(): Unit =
        runBlocking {
            val source = UpgradedReader(topics)
            val subscriptions = startRetrying(retries = 0, source = source)
            val logged = workerLines {
                readableAfterPark(source)
                subscriptions.retry(BOARD, order("o-1")).assert().isTrue()
            }
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
            handled.versionsOf("o-1").assert().contains(5, 6)
            logged.assert().noneMatch { "did not deliver" in it }
        }

    /**
     * Versions 1 to 4 of o-1 handled, version 5 a record this node cannot decode, which parks o-1 there, at the
     * placeholder version 1, and version 6 after it; then [source] reads the record, as a node upgraded does. The
     * parked entry, as the park left it.
     */
    private suspend fun readableAfterPark(source: UpgradedReader): ParkedAggregate {
        topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
        eventually { handled.versionsOf("o-1") == listOf(1, 2, 3, 4) }
        source.hidden = partitionOf("o-1") to offsetOf("o-1", 4) + 1
        topics.publish(orderRecord("o-1", 5), orderRecord("o-1", 6))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        val parked = ledger.parked(BOARD_ID, order("o-1"))!!
        parked.fromVersion.assert().isEqualTo(1)
        source.readable = true
        return parked
    }

    /** The lines the worker logs while [block] runs. */
    private suspend fun workerLines(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SubscriptionWorker::class.java.name) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
            return appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    /**
     * [delegate], whose record at [hidden] (partition and offset) reads as an envelope of a later format, which this
     * node cannot decode, until it is [readable]: as a node upgraded reads it.
     */
    private class UpgradedReader(private val delegate: PartitionedSource) : PartitionedSource by delegate {
        @Volatile
        var hidden: Pair<Int, Long>? = null

        @Volatile
        var readable = false

        override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> =
            delegate.read(topic, partition, from).map { hide(partition, it) }

        override fun read(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> =
            delegate.read(topic, partition, from, until).map { hide(partition, it) }

        private fun hide(partition: Int, message: SourceMessage): SourceMessage =
            if (!readable && hidden == partition to message.offset) {
                SourceMessage.of(message.offset, message.key, message.headers, BAD.toByteArray())
            } else {
                message
            }
    }

    @Test
    fun `a retry that fails at a late record should park again from the lowest offset it did not handle`(): Unit =
        runBlocking {
            // Offsets: version 6, then 5 (late), then 7. The gap park at 5 records the offset of 6; its retry walks 5, 6,
            // 7 and fails at 5, so the park again records the offset of 6, the lowest it did not handle, not that of 5,
            // past which 6 would never be read again; the next retry walks them in version order again.
            val failing = AtomicBoolean(false)
            val versions = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { entry ->
                check(!(failing.get() && entry.version == 5)) { "o-1@5 fails" }
                handled.record(entry)
            }
            startRetrying(subscription = versions)
            topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray(), orderRecord("o-1", 6))
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            failing.set(true)
            topics.publish(orderRecord("o-1", 5), orderRecord("o-1", 7))
            eventually { ledger.parked(BOARD_ID, order("o-1"))?.reason?.contains("IllegalStateException") == true }
            ledger.parked(BOARD_ID, order("o-1"))!!.fromPosition.assert().isEqualTo(offsetOf("o-1", 6))
            failing.set(false)
            eventually { ledger.parked(BOARD_ID, order("o-1")) == null }
            handled.versionsOf("o-1").assert().containsExactly(1, 2, 3, 4, 5, 6, 7)
        }

    @Test
    fun `an aggregate first seen above its first version should not park`(): Unit = runBlocking {
        start("node-1")
        topics.publish(orderRecord("o-1", 2), orderRecord("o-1", 3))
        eventually { handled.versionsOf("o-1") == listOf(2, 3) }
        ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
    }

    @Test
    fun `only a state topic is read for gaps, never the context's own log`() {
        TopicEntries(ORDER_TOPIC, topics, PARTITIONS).contiguous.assert().isTrue()
        LogSource(InMemoryEventLog(1)).contiguous.assert().isFalse()
    }
}
