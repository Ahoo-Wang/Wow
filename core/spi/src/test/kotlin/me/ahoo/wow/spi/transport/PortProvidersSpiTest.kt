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

package me.ahoo.wow.spi.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.StreamSinks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The configurations a transport or Sink provider opens from, and the defaults of what it opens (finding R5.B-5). */
class PortProvidersSpiTest {
    @Test
    fun `a transport configuration hands out its settings, by name and under a prefix`() {
        val config = TransportConfig.of(
            "kafka",
            "kafka",
            mapOf("bootstrap-servers" to "b:9092", "blank" to " ", "properties.linger.ms" to "5"),
            profiles = setOf("dev"),
        )
        config.key.assert().isEqualTo("wow.kafka")
        config.option("bootstrap-servers").assert().isEqualTo("b:9092")
        config.option("blank").assert().isNull()
        config.option("missing").assert().isNull()
        config.options("properties").assert().isEqualTo(mapOf("linger.ms" to "5"))
        config.profiles.assert().containsExactly("dev")
        config.toString().assert().contains("kafka").doesNotContain("b:9092")
        assertThrows<IllegalArgumentException> { TransportConfig.of(" ", "kafka") }
        assertThrows<IllegalArgumentException> { TransportConfig.of("kafka", " ") }
    }

    @Test
    fun `a sink configuration hands out its settings, by name and under a prefix`() {
        val config = SinkConfig.of("clickhouse", "clickhouse", mapOf("endpoint" to "http://c", "sink.ddl" to "none"))
        config.key.assert().isEqualTo("wow.clickhouse")
        config.option("endpoint").assert().isEqualTo("http://c")
        config.options("sink").assert().isEqualTo(mapOf("ddl" to "none"))
        config.toString().assert().contains("clickhouse").doesNotContain("http://c")
        assertThrows<IllegalArgumentException> { SinkConfig.of(" ", "clickhouse") }
        assertThrows<IllegalArgumentException> { SinkConfig.of("clickhouse", " ") }
    }

    @Test
    fun `a transport warns about nothing and a source counts no gap, unless they say otherwise`(): Unit = runBlocking {
        val source = object : PartitionedSource {
            override suspend fun partitions(topic: String): List<Int> = listOf(0)

            override suspend fun partitionOf(topic: String, key: String): Int = 0

            override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = PartitionOffsets(0, 0)

            override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> = emptyFlow()
        }
        val transport = object : TopicTransport {
            override val producer: TopicProducer = object : TopicProducer {
                override suspend fun send(topic: String, messages: List<TopicMessage>) = Unit
            }
            override val source: PartitionedSource = source
            override val admin: TopicAdmin = object : TopicAdmin {
                override suspend fun partitions(topics: Set<String>): Map<String, Int> = emptyMap()

                override suspend fun create(topics: Map<String, Int>) = Unit
            }
        }
        transport.warnings(setOf("wow.shop.commands")).assert().isEmpty()
        transport.close()
        transport.source.offsetGaps.assert().isEqualTo(0L)
        val sinks = object : StreamSinks {
            override val processor: String = "test-sink"

            override fun sinkOf(context: String, aggregate: String): Sink = Sink { }
        }
        sinks.close()
        sinks.processor.assert().isEqualTo("test-sink")
    }
}
