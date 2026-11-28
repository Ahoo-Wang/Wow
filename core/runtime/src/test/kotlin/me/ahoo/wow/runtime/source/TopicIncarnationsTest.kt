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

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.subscription.UnrecoverableException
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The checkpoint groups of a topic's incarnations, and the guard of their reads ([TopicIncarnations]): a read placed
 * by the subscriptions of one incarnation never starts from, nor hands on, a message of another, however many the
 * new incarnation holds past their checkpoint (#7232, whose two review cases these are at the level of the fix).
 */
class TopicIncarnationsTest {
    private val topics = InMemoryTopics(1).also { it.create(TOPIC, 1) }

    /** The topic of [topics], told as incarnation [incarnation]. */
    private val told = object : PartitionedSource by topics {
        @Volatile
        var incarnation = A

        /** How often the incarnation was asked. */
        val asked = AtomicInteger()

        override suspend fun incarnation(topic: String): String = incarnation.also { asked.incrementAndGet() }
    }
    private val checkpoints = InMemoryCheckpointStore()
    private val incarnations = TopicIncarnations(TOPIC, BASE, checkpoints)

    private suspend fun write(count: Int) =
        topics.send(TOPIC, (0 until count).map { TopicMessage.of("k-$it", byteArrayOf(it.toByte()), emptyMap(), 0) })

    @Test
    fun `a read of the new incarnation, shorter than the checkpoint, is refused by name and not as an offset gap`(): Unit =
        runBlocking {
            val guarded = incarnations.guarded(told, A)
            write(3)
            told.incarnation = B

            assertThrownBy<TopicIncarnationException> { runBlocking { guarded.read(TOPIC, 0, 10).toList() } }
                .hasMessageContaining("created again").hasMessageContaining(B).hasMessageContaining(A)
        }

    @Test
    fun `a read of the new incarnation, already longer than the checkpoint, is refused by name, nothing read`(): Unit =
        runBlocking {
            val guarded = incarnations.guarded(told, A)
            told.incarnation = B
            write(15)

            assertThrownBy<TopicIncarnationException> { runBlocking { guarded.read(TOPIC, 0, 10).toList() } }
            assertThrownBy<TopicIncarnationException> { runBlocking { guarded.read(TOPIC, 0, 10, 12).toList() } }
            assertThrownBy<TopicIncarnationException> { runBlocking { guarded.readAt(TOPIC, 0, listOf(11L)) } }
            assertThrownBy<TopicIncarnationException> { runBlocking { guarded.offsets(TOPIC, 0) } }
        }

    @Test
    fun `a read placed in its incarnation is checked once, at its placement, and hands the source's messages on as they are`():
        Unit = runBlocking {
        val guarded = incarnations.guarded(told, A)
        val placed = guarded.read(TOPIC, 0, 0).take(3)
        val read = coroutineScope {
            val reading = async { placed.toList() }
            // Placed: the topic's incarnation was asked once.
            withTimeout(10.seconds) {
                while (told.asked.get() < 1) {
                    delay(1)
                }
            }
            // Created again after the check: what the source emits then is the source's (the window of a running read).
            told.incarnation = B
            write(3)
            withTimeout(10.seconds) { reading.await() }
        }

        read.map { it.offset }.assert().containsExactly(0L, 1L, 2L)
        // Asked at the placement alone: nothing per message.
        told.asked.get().assert().isEqualTo(1)
    }

    @Test
    fun `a read of its own incarnation reads as the source does, and a source that tells none is not guarded`(): Unit =
        runBlocking {
            write(3)
            incarnations.guarded(
                told,
                A
            ).read(TOPIC, 0, 1, 3).toList().map { it.offset }.assert().containsExactly(1L, 2L)
            incarnations.guarded(told, A).readAt(TOPIC, 0, listOf(0L)).keys.assert().containsExactly(0L)
            incarnations.guarded(topics, null).assert().isSameAs(topics)
        }

    @Test
    fun `a guarded source reads another topic, and its own incarnation's offsets and empty reads, as the source does`():
        Unit = runBlocking {
        topics.create(OTHER, 1)
        topics.send(OTHER, listOf(TopicMessage.of("k", byteArrayOf(1), emptyMap(), 0)))
        val guarded = incarnations.guarded(told, A)
        guarded.offsets(TOPIC, 0).end.assert().isEqualTo(0L)
        guarded.readAt(TOPIC, 0, listOf(0L)).assert().isEmpty()
        // The topic is another incarnation now: a read of another topic of the source is not its concern.
        told.incarnation = B
        guarded.offsets(OTHER, 0).end.assert().isEqualTo(1L)
        guarded.read(OTHER, 0, 0, 1).toList().map { it.offset }.assert().containsExactly(0L)
        guarded.readAt(OTHER, 0, listOf(0L)).keys.assert().containsExactly(0L)
    }

    @Test
    fun `a refusal names the topic and both incarnations, or says the topic does not exist now`() {
        TopicIncarnationException(TOPIC, A, B).message.assert().contains(TOPIC).contains("incarnation $B").contains(A)
            .endsWith("join $B, at the next refresh of the topic's partitions.")
        TopicIncarnationException(TOPIC, A, null).message.assert().contains("does not exist now").contains(A)
    }

    @Test
    fun `each incarnation has its own checkpoint group, and the ones before the current are told with their checkpoints`():
        Unit = runBlocking {
        incarnations.groupOf(A).assert().isEqualTo("$BASE#$A")
        incarnations.groupOf(null).assert().isEqualTo(BASE)
        incarnations.earlier(null).assert().isEmpty()
        incarnations.record(A)
        incarnations.record(null)
        checkpoints.save(incarnations.groupOf(A), "Board.onOrder", 0, 7)
        // Saved by a node of a pre-release, or of a source that told no incarnation.
        checkpoints.save(BASE, "Board.onOrder", 0, 3)
        incarnations.record(B)

        incarnations.earlier(A).assert().isEqualTo(mapOf("" to listOf(Checkpoint.of("Board.onOrder", 0, 3))))
        incarnations.earlier(B).assert().isEqualTo(
            mapOf(
                "" to listOf(Checkpoint.of("Board.onOrder", 0, 3)),
                A to listOf(Checkpoint.of("Board.onOrder", 0, 7)),
            ),
        )
    }

    @Test
    fun `an entry parked in another incarnation is neither re-scanned nor read again where its key maps, by either source`():
        Unit = runBlocking {
        write(3)
        val subscription = SubscriptionId("sales", "Board.onOrder")
        val ofA = ParkedAggregate.of(
            subscription,
            order("o-1"),
            0,
            2,
            "failed",
            1,
            fromPosition = 1,
            fromIncarnation = A
        )
        val unnamed = ofA.copyWith(fromIncarnation = null)
        val entries = TopicEntries(TOPIC, told, 1, incarnation = B)
        val receipts = MessageEntries.receipts(TOPIC, told, 1, incarnation = B)
        listOf(ofA, unnamed).forEach { parked ->
            entries.ofAnotherIncarnation(parked).assert().isTrue()
            assertThrownBy<UnrecoverableException> { runBlocking { entries.retryEntries(parked) } }
                .hasMessageContaining("earlier incarnation of topic $TOPIC").hasMessageContaining(B)
            assertThrownBy<UnrecoverableException> { runBlocking { receipts.retryEntries(parked) } }
        }
        // Where it is parked: its position is not read again in the new incarnation.
        entries.partitionsOf(listOf(ofA.copyWith(partition = 7))).assert().containsExactly(7)
        entries.ofAnotherIncarnation(ofA.copyWith(fromIncarnation = B)).assert().isFalse()
        TopicEntries(TOPIC, told, 1).ofAnotherIncarnation(unnamed).assert().isFalse()
    }

    private companion object {
        const val TOPIC = "wow.sales.order.state"
        const val OTHER = "wow.sales.other.state"
        const val BASE = "board-service/topic:wow.sales.order.state"
        const val A = "incarnation-a"
        const val B = "incarnation-b"
    }
}
