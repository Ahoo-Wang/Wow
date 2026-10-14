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

package me.ahoo.wow.kernel.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransportSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class InMemoryTopicsTest : TopicTransportSpec() {
    private val topics = InMemoryTopics()
    private val created = AtomicInteger()

    override val producer: TopicProducer = topics
    override val source: PartitionedSource = topics

    override suspend fun newTopic(partitions: Int): String =
        "topic-${created.incrementAndGet()}".also { topics.create(it, partitions) }

    @Test
    fun `a failing broker should acknowledge nothing`(): Unit = runBlocking {
        val topics = InMemoryTopics(1)
        topics.failure = IllegalStateException("down")
        assertThrows<IllegalStateException> {
            runBlocking { topics.send("t", TopicMessage("k", byteArrayOf(1))) }
        }
        topics.messages("t").assert().isEmpty()
        topics.failure = null
        topics.send("t", TopicMessage("k", byteArrayOf(1)))
        topics.messages("t").single().offset.assert().isEqualTo(0L)
        topics.partitionOf("t", "k").assert().isEqualTo(0)
    }

    @Test
    fun `retention should drop the oldest messages and keep the offsets`(): Unit = runBlocking {
        val topics = InMemoryTopics(1)
        topics.send("t", (0 until 4).map { TopicMessage("k", byteArrayOf(it.toByte())) })
        topics.retain("t", 0, 2)
        topics.offsets("t", 0).assert().isEqualTo(PartitionOffsets(2, 4))
        topics.read("t", 0, 0, 4).toList().map { it.offset }.assert().containsExactly(2L, 3L)
        topics.send("t", TopicMessage("k", byteArrayOf(9)))
        topics.messages("t").map { it.offset }.assert().containsExactly(2L, 3L, 4L)
        topics.retain("t", 0, 10)
        topics.offsets("t", 0).assert().isEqualTo(PartitionOffsets(5, 5))
    }

    @Test
    fun `a message should describe itself without its bytes`() {
        TopicMessage("k", byteArrayOf(1, 2), mapOf("h" to "v")).toString()
            .assert().isEqualTo("TopicMessage(key=k, headers={h=v}, 2 bytes)")
        SourceMessage(3, "k", emptyMap(), byteArrayOf(1)).toString()
            .assert().isEqualTo("SourceMessage(offset=3, key=k, headers={}, 1 bytes)")
    }
}
