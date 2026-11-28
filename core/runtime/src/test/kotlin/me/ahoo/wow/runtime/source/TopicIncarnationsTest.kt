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
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
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

        override suspend fun incarnation(topic: String): String = incarnation
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
    fun `a read placed in its incarnation is refused at its first message when the topic was created again meanwhile`():
        Unit = runBlocking {
        val guarded = incarnations.guarded(told, A)
        // Placed while the topic is still A: the first message comes once it is B.
        val placed = guarded.read(TOPIC, 0, 0).take(1)
        val read = runCatching {
            withTimeout(10.seconds) {
                coroutineScope {
                    val reading = async { placed.toList() }
                    delay(50)
                    told.incarnation = B
                    write(1)
                    reading.await()
                }
            }
        }

        read.exceptionOrNull().assert().isInstanceOf(TopicIncarnationException::class.java)
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

    private companion object {
        const val TOPIC = "wow.sales.order.state"
        const val BASE = "board-service/topic:wow.sales.order.state"
        const val A = "incarnation-a"
        const val B = "incarnation-b"
    }
}
