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

package me.ahoo.wow.tck.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.transport.PartitionOffsets
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The contract of a topic transport, a [TopicProducer] and the [PartitionedSource] over the same
 * broker, that every transport passes. A transport's test extends it, supplies both and creates the topics; each
 * test writes to a topic of its own.
 */
abstract class TopicTransportSpec {
    protected abstract val producer: TopicProducer
    protected abstract val source: PartitionedSource

    /** A topic no other test writes to, with [partitions] partitions. */
    protected abstract suspend fun newTopic(partitions: Int): String

    /** How long a read may take to see what was sent. */
    protected open val readTimeout: Duration = 30.seconds

    /**
     * The provider of this transport and a configuration it opens one from, for the check that it reads only the
     * options it declares; null for a transport no provider opens (the topics in memory).
     */
    protected open fun provided(): Pair<TransportProvider, TransportConfig>? = null

    @Test
    fun `the transport should read only the options it declares, each declared once with a description`() {
        val provided = provided()
        assumeTrue(provided != null, "No provider opens this transport.")
        val (provider, config) = checkNotNull(provided)
        provider.open(config).close()
        val declared = TopicTransportOptions.of(provider)
        declared.map { it.key }.assert().describedAs("option keys, the topic transports' among them")
            .doesNotHaveDuplicates()
        declared.forEach { option -> option.description.assert().describedAs(option.key).isNotBlank() }
        config.optionsRead.assert().describedAs("the options the transport read").isSubsetOf(declared.map { it.key })
    }

    private suspend fun read(topic: String, partition: Int, from: Long, count: Int): List<SourceMessage> =
        if (count == 0) {
            emptyList()
        } else {
            withTimeout(readTimeout) { source.read(topic, partition, from).take(count).toList() }
        }

    private fun message(key: String, value: String, headers: Map<String, String> = emptyMap()) =
        TopicMessage.of(key, value.toByteArray(), headers)

    private val SourceMessage.text: String get() = String(value)

    @Test
    fun `a topic should list its partitions`(): Unit = runBlocking {
        val topic = newTopic(3)
        source.partitions(topic).assert().containsExactly(0, 1, 2)
    }

    @Test
    fun `the messages of a key should be read back from one partition in the order sent`(): Unit = runBlocking {
        val topic = newTopic(3)
        val headers = mapOf("wow-kind" to "state-event", "wow-type" to "shop.order")
        producer.send(topic, (0 until 5).map { message("order-a", "a-$it", headers) })
        producer.send(topic, message("order-b", "b-0"))
        producer.send(topic, message("order-a", "a-5", headers))
        val read = source.partitions(topic).flatMap { partition ->
            val end = source.offsets(topic, partition).end
            read(topic, partition, 0, end.toInt()).map { partition to it }
        }
        read.size.assert().isEqualTo(7)
        val ofA = read.filter { it.second.key == "order-a" }
        ofA.map { it.first }.toSet().assert().hasSize(1)
        ofA.map { it.second.text }.assert().containsExactly("a-0", "a-1", "a-2", "a-3", "a-4", "a-5")
        ofA.map { it.second.offset }.assert().isSortedAccordingTo(Comparator.naturalOrder())
        ofA.forEach { it.second.headers.assert().isEqualTo(headers) }
        read.single { it.second.key == "order-b" }.second.headers.assert().isEmpty()
    }

    @Test
    fun `a key's partition should be the one its messages land in`(): Unit = runBlocking {
        val topic = newTopic(3)
        val keys = (0 until 12).map { "order-$it" }
        producer.send(topic, keys.map { message(it, it) })
        val landed = source.partitions(topic).flatMap { partition ->
            val end = source.offsets(topic, partition).end
            read(topic, partition, 0, end.toInt()).map { it.key to partition }
        }.toMap()
        keys.forEach { key -> source.partitionOf(topic, key).assert().isEqualTo(landed.getValue(key)) }
    }

    @Test
    fun `a message naming its partition should land in it whatever its key`(): Unit = runBlocking {
        val topic = newTopic(3)
        producer.send(topic, (0 until 3).map { TopicMessage.of("same-key", "p-$it".toByteArray(), partition = it) })
        (0 until 3).forEach { partition ->
            read(topic, partition, 0, 1).single().text.assert().isEqualTo("p-$partition")
        }
    }

    @Test
    fun `a bounded read should complete after the messages before its bound`(): Unit = runBlocking {
        val topic = newTopic(1)
        producer.send(topic, (0 until 5).map { message("k", "v-$it") })
        withTimeout(readTimeout) { source.read(topic, 0, 1, 4).toList() }
            .map { it.text }.assert().containsExactly("v-1", "v-2", "v-3")
        withTimeout(readTimeout) { source.read(topic, 0, 3, 3).toList() }.assert().isEmpty()
        withTimeout(readTimeout) { source.read(topic, 0, 0, source.offsets(topic, 0).end).toList() }
            .size.assert().isEqualTo(5)
    }

    @Test
    fun `the offsets should run from the first message retained to the next one written`(): Unit = runBlocking {
        val topic = newTopic(1)
        source.offsets(topic, 0).assert().isEqualTo(PartitionOffsets.of(0, 0))
        producer.send(topic, (0 until 3).map { message("k", "v-$it") })
        source.offsets(topic, 0).assert().isEqualTo(PartitionOffsets.of(0, 3))
    }

    @Test
    fun `a read should start at the offset given`(): Unit = runBlocking {
        val topic = newTopic(1)
        producer.send(topic, (0 until 4).map { message("k", "v-$it") })
        val read = read(topic, 0, 2, 2)
        read.map { it.offset }.assert().containsExactly(2L, 3L)
        read.map { it.text }.assert().containsExactly("v-2", "v-3")
    }

    @Test
    fun `a read should wait for the messages sent after it started`(): Unit = runBlocking {
        val topic = newTopic(1)
        producer.send(topic, message("k", "before"))
        val reading = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { read(topic, 0, 1, 2) }
        yield()
        producer.send(topic, message("k", "after-1"))
        producer.send(topic, message("k", "after-2"))
        reading.await().map { it.text }.assert().containsExactly("after-1", "after-2")
    }
}
