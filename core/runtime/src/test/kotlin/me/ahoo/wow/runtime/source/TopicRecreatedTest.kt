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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A topic deleted and created again under its name (#7232): another incarnation, whose offsets name other records.
 * Its subscriptions keep their checkpoints per incarnation, so nothing the new one holds is skipped, whatever its
 * length; until they join it, no read of it is placed from an offset of the one they left.
 */
class TopicRecreatedTest : TopicSubscriptionsFixtures() {
    private val source = Recreatable()

    private fun groupOf(subscriptions: TopicSubscriptions, incarnation: String) =
        "${subscriptions.group.name}${TopicIncarnations.SEPARATOR}$incarnation"

    private fun reading(refresh: Duration = Duration.INFINITE, node: String = "node-1") = TopicSubscriptions(
        service = SERVICE,
        topic = ORDER_TOPIC,
        node = node,
        addressOf = { "$node.internal:9090" },
        source = source,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(board),
        options = testOptions(),
        cluster = fastOptions(),
        partitionRefreshInterval = refresh,
    ).also { started += it }

    /** The records of [ids] at version 1 written to the topic as it is now. */
    private suspend fun write(vararg ids: String) = source.current.publish(
        *ids.map { orderRecord(it, 1) }.toTypedArray()
    )

    @Test
    fun `until its subscriptions join the new incarnation, no offset of the old one is placed in it or saved under it`(): Unit =
        runBlocking {
            // No refresh round: the subscriptions never join the new incarnation while running.
            val subscriptions = reading().also {
                it.start()
                it.awaitStarted()
            }
            write("o-1", "o-2", "o-3")
            eventually { handled.size == 3 }
            val old = groupOf(subscriptions, A)
            eventually { checkpoints.list(old).sumOf { it.position } == 3L }
            val before = checkpoints.list(old).map { it.partition to it.position }.toSet()

            source.recreate(B)
            // More records than the old incarnation held, on every partition.
            write(*(10 until 30).map { "o-$it" }.toTypedArray())
            // The old incarnation's reads fail, as a deleted topic's do, and are placed again, twice on each partition
            // at least: each is refused, and none starts in the new topic from an old offset.
            eventually { source.placedSince() >= PARTITIONS * 2 }

            handled.size.assert().isEqualTo(3)
            checkpoints.list(groupOf(subscriptions, B)).assert().isEmpty()
            checkpoints.list(old).map { it.partition to it.position }.toSet().assert().isEqualTo(before)
        }

    @Test
    fun `its subscriptions follow the new incarnation at the next refresh, from their start, with no memory of the old`(): Unit =
        runBlocking {
            val warnings = warningsOf<TopicSubscriptions>()
            val subscriptions = reading(refresh = 20.milliseconds).also {
                it.start()
                it.awaitStarted()
            }
            source.current.publish(orderRecord("o-1", 1), orderRecord("o-1", 2), orderRecord("o-1", 3))
            write("o-2", "o-3")
            eventually { handled.size == 5 }
            val old = groupOf(subscriptions, A)
            eventually { checkpoints.list(old).sumOf { it.position } == 5L }
            val before = checkpoints.list(old).map { it.partition to it.position }.toSet()

            source.recreate(B)
            // o-1 goes on at a version past the last one the old incarnation's subscriptions saw: no gap is told.
            source.current.publish(orderRecord("o-1", 7))
            write(*(10 until 30).map { "o-$it" }.toTypedArray())

            eventually { handled.size == 5 + 21 }
            handled.versionsOf("o-1").assert().containsExactly(1, 2, 3, 7)
            (10 until 30).forEach { handled.versionsOf("o-$it").assert().containsExactly(1) }
            ledger.list(BOARD_ID).assert().isEmpty()
            eventually { checkpoints.list(groupOf(subscriptions, B)).sumOf { it.position } == 21L }
            checkpoints.list(old).map { it.partition to it.position }.toSet().assert().isEqualTo(before)
            eventually { warnings.list.any { "created again" in it.formattedMessage } }
            warnings.list.single { "created again" in it.formattedMessage }.formattedMessage.assert()
                .contains(ORDER_TOPIC).contains("incarnation $A").contains("incarnation $B")
                .contains("4 checkpoints").contains("$A: 4 ($BOARD)")
        }

    @Test
    fun `a node that starts after the topic was created again reads the new incarnation from its start, and tells of the old`(): Unit =
        runBlocking {
            val first = reading().also {
                it.start()
                it.awaitStarted()
            }
            write("o-1", "o-2", "o-3")
            eventually { handled.size == 3 }
            eventually { checkpoints.list(groupOf(first, A)).sumOf { it.position } == 3L }
            first.stop()
            source.recreate(B)
            write(*(10 until 30).map { "o-$it" }.toTypedArray())
            val warnings = warningsOf<TopicSubscriptions>()

            reading().also {
                it.start()
                it.awaitStarted()
            }

            eventually { handled.size == 3 + 20 }
            (10 until 30).forEach { handled.versionsOf("o-$it").assert().containsExactly(1) }
            eventually { warnings.list.any { "earlier incarnations" in it.formattedMessage } }
            warnings.list.single { "earlier incarnations" in it.formattedMessage }.formattedMessage.assert()
                .contains("incarnation $B").contains("$A: 4 ($BOARD)").doesNotContain("created again")
        }

    /** Parks `o-1` at version 2 in incarnation A, read from the topic, and lets it succeed from then on. */
    private suspend fun parkedInA(refresh: Duration): TopicSubscriptions {
        failing = "o-1" to 2
        val subscriptions = reading(refresh).also {
            it.start()
            it.awaitStarted()
        }
        source.current.publish(orderRecord("o-1", 1), orderRecord("o-1", 2))
        eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
        val parked = ledger.parked(BOARD_ID, order("o-1"))!!
        parked.fromIncarnation.assert().isEqualTo(A)
        parked.fromPosition.assert().isEqualTo(1L)
        handled.versionsOf("o-1").assert().containsExactly(1)
        failing = null
        return subscriptions
    }

    @Test
    fun `an aggregate parked in the old incarnation holds back nothing of the new one, and its skip drops nothing`(): Unit =
        runBlocking {
            val subscriptions = parkedInA(refresh = 20.milliseconds)

            source.recreate(B)
            source.current.publish(orderRecord("o-1", 3), orderRecord("o-1", 4))

            // Read at once, not held behind the park of an offset the new topic gives to other records.
            eventually { handled.versionsOf("o-1") == listOf(1, 3, 4) }
            val left = ledger.parked(BOARD_ID, order("o-1"))!!
            left.skipOnly.assert().isTrue()
            left.fromIncarnation.assert().isEqualTo(A)
            left.reason.assert().contains("earlier incarnation of topic $ORDER_TOPIC ($A)").contains(B)
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
            source.current.publish(orderRecord("o-1", 5))
            eventually { handled.versionsOf("o-1") == listOf(1, 3, 4, 5) }
        }

    @Test
    fun `a retry of an aggregate parked in the old incarnation does not re-scan the new topic at its old offset`(): Unit =
        runBlocking {
            val subscriptions = parkedInA(refresh = 20.milliseconds)

            source.recreate(B)
            // At offsets 0 and 1 of the new topic: the old entry's offset, 1, is the second of them.
            source.current.publish(orderRecord("o-1", 3), orderRecord("o-1", 4))
            eventually { handled.versionsOf("o-1") == listOf(1, 3, 4) }

            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            handled.versionsOf("o-1").assert().containsExactly(1, 3, 4)
            ledger.parked(BOARD_ID, order("o-1"))!!.skipOnly.assert().isTrue()
        }

    @Test
    fun `a source that tells no incarnation keeps its checkpoints under the lease group's name, with no marker`(): Unit =
        runBlocking {
            val subscriptions = start("node-1")
            topics.publish(orderRecord("o-1", 1))
            eventually { handled.size == 1 }
            eventually { checkpoints.list(subscriptions.group.name).isNotEmpty() }
            val names = checkpoints.list(subscriptions.group.name).map { it.subscription }
            names.distinct().assert().containsExactly(BOARD)
            TopicIncarnations(ORDER_TOPIC, "g", checkpoints).groupOf(null).assert().isEqualTo("g")
        }

    private inline fun <reified T> warningsOf(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(T::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return appender
    }

    /**
     * A topic of [PARTITIONS] partitions that may be deleted and created again ([recreate]): another [InMemoryTopics],
     * another incarnation. A read placed on the one deleted fails, as a read of a deleted topic does.
     */
    private class Recreatable : PartitionedSource {
        @Volatile
        var current = InMemoryTopics(PARTITIONS).also { it.create(ORDER_TOPIC, PARTITIONS) }
            private set

        @Volatile
        private var incarnation = A

        private val placed = AtomicInteger()

        @Volatile
        private var recreatedAt = 0

        fun recreate(next: String) {
            current = InMemoryTopics(PARTITIONS).also { it.create(ORDER_TOPIC, PARTITIONS) }
            incarnation = next
            recreatedAt = placed.get()
        }

        /**
         * How many reads were placed since the last [recreate], each one either read or, when its placement asks
         * which incarnation the topic is first, asked: an event of a placement whether a guard refuses it or not.
         */
        fun placedSince(): Int = placed.get() - recreatedAt

        override suspend fun incarnation(topic: String): String = incarnation.also { placed.incrementAndGet() }

        override suspend fun partitions(topic: String): List<Int> = current.partitions(topic)

        override suspend fun partitionOf(topic: String, key: String): Int = current.partitionOf(topic, key)

        override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = current.offsets(
            topic,
            partition
        )

        override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = channelFlow {
            placed.incrementAndGet()
            val bound = current
            launch {
                while (bound === current) {
                    delay(2)
                }
                throw IllegalStateException("Topic $topic was deleted.")
            }
            bound.read(topic, partition, from).collect { send(it) }
        }
    }

    private companion object {
        const val A = "incarnation-a"
        const val B = "incarnation-b"
    }
}
