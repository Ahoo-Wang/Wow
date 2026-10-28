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
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

/** The configurations a transport or Sink provider opens from, and the defaults of what it opens (finding R5.B-5). */
class PortProvidersSpiTest {
    @Test
    fun `a transport configuration hands out its settings, by name and under a prefix`() {
        val config = TransportConfig.of(
            "kafka",
            "kafka",
            mapOf("bootstrap-servers" to "b:9092", "blank" to " ", "properties.linger.ms" to "5"),
            development = true,
        )
        config.key.assert().isEqualTo("wow.kafka")
        config.option("bootstrap-servers").assert().isEqualTo("b:9092")
        config.option("blank").assert().isNull()
        config.option("missing").assert().isNull()
        config.options("properties").assert().isEqualTo(mapOf("linger.ms" to "5"))
        config.development.assert().isTrue()
        config.toString().assert().contains("kafka").doesNotContain("b:9092")
        assertThrows<IllegalArgumentException> { TransportConfig.of(" ", "kafka") }
        assertThrows<IllegalArgumentException> { TransportConfig.of("kafka", " ") }
    }

    @Test
    fun `a sink configuration hands out its settings, by name and under a prefix`() {
        val config = SinkConfig.of(
            "clickhouse",
            "clickhouse",
            mapOf("endpoint" to "http://c", "sink.ddl" to "none", "blank" to " "),
        )
        config.key.assert().isEqualTo("wow.clickhouse")
        config.option("endpoint").assert().isEqualTo("http://c")
        config.option("blank").assert().isNull()
        config.options("sink").assert().isEqualTo(mapOf("ddl" to "none"))
        config.toString().assert().contains("clickhouse").doesNotContain("http://c")
        assertThrows<IllegalArgumentException> { SinkConfig.of(" ", "clickhouse") }
        assertThrows<IllegalArgumentException> { SinkConfig.of("clickhouse", " ") }
    }

    @Test
    fun `a provider declares nothing by default, and its configuration reads declared options and records them`() {
        val transports = object : TransportProvider {
            override val type: String = "acme"

            override fun available(config: TransportConfig): Boolean = false

            override fun open(config: TransportConfig): TopicTransport = error("not opened")
        }
        val sinks = object : SinkProvider {
            override val type: String = "acme"

            override fun available(config: SinkConfig): Boolean = false

            override fun open(config: SinkConfig): StreamSinks = error("not opened")
        }
        transports.options.assert().isEmpty()
        transports.requirement.assert().isEqualTo("its settings")
        sinks.options.assert().isEmpty()
        sinks.requirement.assert().isEqualTo("its settings")
        val labels = ProviderOption.map("labels", "the labels of every acme resource.")
        val settings = mapOf("topic-prefix" to "staging.", "labels.team" to "a", "labels.cost" to "b")
        val transport = TransportConfig.of("acme", "acme", settings)
        transport.option(TopicTransportOptions.TOPIC_PREFIX).assert().isEqualTo("staging.")
        transport.option(TopicTransportOptions.PARTITION_REFRESH).assert().isEqualTo(30.seconds)
        transport.options(labels).assert().isEqualTo(mapOf("team" to "a", "cost" to "b"))
        transport.optionsRead.assert().containsExactlyInAnyOrder("topic-prefix", "topics.partition-refresh", "labels")
        assertThrows<WowConfigurationException> {
            TransportConfig.of("acme", "acme", mapOf("command-intake.enabled" to "maybe"))
                .option(TopicTransportOptions.COMMAND_INTAKE)
        }.key.assert().isEqualTo("wow.acme.command-intake.enabled")
        val sink = SinkConfig.of("acme", "acme", settings)
        sink.options(labels).assert().isEqualTo(mapOf("team" to "a", "cost" to "b"))
        sink.option(ProviderOption.string("region", "the region.", "eu-1")).assert().isEqualTo("eu-1")
        sink.optionsRead.assert().containsExactlyInAnyOrder("labels", "region")
    }

    @Test
    fun `a transport warns about nothing and a source counts no gap, unless they say otherwise`(): Unit = runBlocking {
        val source = object : PartitionedSource {
            override suspend fun partitions(topic: String): List<Int> = listOf(0)

            override suspend fun partitionOf(topic: String, key: String): Int = 0

            override suspend fun offsets(topic: String, partition: Int): PartitionOffsets = PartitionOffsets.of(0, 0)

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
