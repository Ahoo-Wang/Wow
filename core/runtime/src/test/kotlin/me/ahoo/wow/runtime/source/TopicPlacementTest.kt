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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.sink.TopicSink
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.EnvelopeHeaders
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A state topic whose records the publisher placed by its log's partition (`wow-partitions`, #6783): a subscriber
 * routes a parked aggregate by where it read it, which no growth of the topic moves, and warns once per partition of
 * a record placed otherwise.
 */
class TopicPlacementTest : TopicSubscriptionsFixtures() {
    private val logger = LoggerFactory.getLogger(TopicEntries::class.java) as Logger
    private val warnings = ListAppender<ILoggingEvent>().apply { start() }.also { logger.addAppender(it) }

    @AfterEach
    fun detach() {
        logger.detachAppender(warnings)
    }

    private fun placementWarnings(topic: String): List<String> =
        warnings.list.map { it.formattedMessage }.filter { "of $topic holds a record" in it }

    private suspend fun reading(topic: String, spec: ParkedRetrySpec = ParkedRetrySpec()) = TopicSubscriptions(
        service = SERVICE,
        topic = topic,
        node = "node-1",
        addressOf = { "node-1.internal:9090" },
        source = topics,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(board),
        options = testOptions().copyWith(parked = ParkedRetryOptions(spec, scanInterval = 10.milliseconds)),
        cluster = fastOptions(),
        partitionRefresh = 20.milliseconds,
    ).also {
        started += it
        it.start()
        it.awaitStarted()
    }

    /**
     * An order parked at v1 in its log's partition (among two) of [topic], which then grows to eight, among which its
     * slot is another partition; v2 is written after the growth. Once [retried] (its failure cleared [clearedBefore]
     * the growth or after it), v3 is written. Each is handled in turn only when the retry ran in the partition the
     * entry was read from, whose worker holds the aggregate back: a retry another partition's worker ran would handle
     * v1 and v2 there too, re-reading the right partition, but leave v3 held.
     */
    private suspend fun retriedAcrossGrowth(
        topic: String,
        spec: ParkedRetrySpec,
        clearedBefore: Boolean,
        retried: suspend (TopicSubscriptions, String) -> Unit,
    ) {
        topics.create(topic, LOG)
        val sink = TopicSink(topics, topic, LOG)
        val id = generateSequence(0) { it + 1 }.map { "p-$it" }
            .first { Partitioner.partitionOf(order(it), LOG) != Partitioner.partitionOf(order(it), GROWN) }
        val subscriptions = reading(topic, spec)
        failing = id to 1
        sink.write(listOf(orderRecord(id, 1)))
        eventually { ledger.parked(BOARD_ID, order(id)) != null }
        if (clearedBefore) {
            failing = null
        }
        topics.grow(topic, GROWN)
        eventually { subscriptions.ownership?.owned == (0 until GROWN).toSet() }
        sink.write(listOf(orderRecord(id, 2)))
        // The added partitions started without re-homing it: its records never moved.
        val parked = checkNotNull(ledger.parked(BOARD_ID, order(id)))
        parked.partition.assert().isEqualTo(Partitioner.partitionOf(order(id), LOG))
        parked.skipOnly.assert().isFalse()
        failing = null
        retried(subscriptions, id)
        eventually { handled.versionsOf(id) == listOf(1, 2) }
        // The retry resolves its entry after its handler returned: the scan's retry has no caller to wait for it.
        eventually { ledger.parked(BOARD_ID, order(id)) == null }
        sink.write(listOf(orderRecord(id, 3)))
        eventually { handled.versionsOf(id) == listOf(1, 2, 3) }
        placementWarnings(topic).assert().isEmpty()
    }

    @Test
    fun `an operator's retry after the topic grew should run in the partition the entry was read from`(): Unit =
        runBlocking {
            // No automatic retry: the operator's is the only one.
            retriedAcrossGrowth("wow.sales.placed.state", ParkedRetrySpec(maxRetries = 0), false) { subscriptions, id ->
                subscriptions.retry(BOARD, order(id)).assert().isTrue()
            }
        }

    @Test
    fun `the retry scan after the topic grew should run the retry in the partition the entry was read from`(): Unit =
        runBlocking {
            // The scan's retry only, due a second after the failure: after the growth unless that takes longer, when
            // the retry runs before it and the test, passing, holds nothing of the scan's routing.
            val spec = ParkedRetrySpec(maxRetries = 1, initialBackoff = 1.seconds)
            retriedAcrossGrowth("wow.sales.scanned.state", spec, true) { _, _ -> }
        }

    @Test
    fun `a record placed by its key's hash should be warned of once per partition, not once per record`(): Unit =
        runBlocking {
            val topic = "wow.sales.hashed.state"
            topics.create(topic, PARTITIONS)
            reading(topic)
            // Five records of one aggregate, placed by the key's hash, as an earlier publisher placed them.
            topics.publish(*(1..5).map { orderRecord("h-1", it) }.toTypedArray(), topic = topic)
            eventually { handled.versionsOf("h-1") == (1..5).toList() }
            val hashed = topics.partitionOf(topic, order("h-1").toString())
            placementWarnings(topic).assert().hasSize(1)
            placementWarnings(topic).single().assert().contains("Partition $hashed of $topic")
                .contains("names no log partition (${EnvelopeHeaders.PARTITIONS})")
            // A record whose header names a count it is not placed by is warned of too, once for its partition.
            val misplaced = generateSequence(0) { it + 1 }.map { "m-$it" }.first {
                Partitioner.partitionOf(order(it), LOG) != hashed
            }
            val wrong = (Partitioner.partitionOf(order(misplaced), LOG) + 1) % LOG
            repeat(2) { version ->
                val record = EnvelopeCodec.message(orderRecord(misplaced, version + 1))
                val headers = record.headers + (EnvelopeHeaders.PARTITIONS to "$LOG")
                topics.send(topic, TopicMessage.of(record.key, record.value, headers, wrong))
            }
            eventually { handled.versionsOf(misplaced) == listOf(1, 2) }
            val warned = placementWarnings(topic)
            if (wrong == hashed) {
                warned.assert().hasSize(1)
            } else {
                warned.assert().hasSize(2)
                warned.last().assert().contains("Partition $wrong of $topic")
                    .contains("is not in partition ${Partitioner.partitionOf(order(misplaced), LOG)} of the $LOG")
            }
        }

    @Test
    fun `a subscriber caught up before the publisher's upgrade should read an aggregate in order across it`(): Unit =
        runBlocking {
            val topic = "wow.sales.upgraded.state"
            topics.create(topic, PARTITIONS)
            reading(topic)
            // An order the earlier publisher placed by its key's hash, in another partition than its log's.
            val id = generateSequence(0) { it + 1 }.map { "u-$it" }.first {
                topics.partitionOf(topic, order(it).toString()) != Partitioner.partitionOf(order(it), PARTITIONS)
            }
            topics.publish(orderRecord(id, 1), topic = topic)
            // The procedure: the subscriber is caught up (v1 handled) before the publisher is upgraded.
            eventually { handled.versionsOf(id) == listOf(1) }
            TopicSink(topics, topic, PARTITIONS).write(listOf(orderRecord(id, 2), orderRecord(id, 3)))
            eventually { handled.versionsOf(id).size == 3 }
            handled.versionsOf(id).assert().containsExactly(1, 2, 3)
        }

    @Test
    fun `an entry parked at an offset its partition does not hold should count as placed by its key`(): Unit =
        runBlocking {
            // A topic created again: the partition the entry was read from holds nothing at its offset, nor after it.
            val topic = "wow.sales.recreated.state"
            topics.create(topic, LOG)
            val ref = order("r-1")
            val byKey = topics.partitionOf(topic, ref.toString())
            val parked = ParkedAggregate(BOARD_ID, ref, 1 - byKey, 1, "failed", 0, fromPosition = 7)
            val partition = withTimeout(5.seconds) { TopicEntries(topic, topics, LOG).partitionOf(parked) }
            partition.assert().isEqualTo(byKey)
        }

    @Test
    fun `the retry scan should route and run an entry parked at an offset its partition does not hold`(): Unit =
        runBlocking {
            // Parked after the start, so the scan, not the start, is the first to look for its record.
            val topic = "wow.sales.emptied.state"
            topics.create(topic, LOG)
            reading(topic)
            val ref = order("e-1")
            ledger.park(ParkedAggregate(BOARD_ID, ref, topics.partitionOf(topic, ref.toString()), 1, "failed", 0, 7))
            // Routed where its later entries are, then retried: nothing from its offset on, so it resolves.
            eventually { ledger.parked(BOARD_ID, ref) == null }
        }

    private companion object {
        /** The publishing context's log partitions. */
        const val LOG = 2

        /** The partitions the topic grows to. */
        const val GROWN = 8
    }
}
