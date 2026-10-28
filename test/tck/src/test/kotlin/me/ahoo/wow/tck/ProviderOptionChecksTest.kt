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

package me.ahoo.wow.tck

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransport
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import me.ahoo.wow.tck.sink.SinkSpec
import me.ahoo.wow.tck.transport.TopicTransportSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

/**
 * The specs' check that a provider reads only the options it declares: it passes a provider that does, and fails one
 * that reads another key or declares a topic transport's own setting again.
 */
class ProviderOptionChecksTest {
    private val region = ProviderOption.string("region", "the region every acme resource is in.", "eu-1")

    /** A transport that reads [read] of its settings and declares [declared]. */
    private fun transport(declared: List<ProviderOption<*>>, vararg read: String) = object : TransportProvider {
        override val type: String = "acme"
        override val options: List<ProviderOption<*>> = declared

        override fun available(config: TransportConfig): Boolean = true

        override fun open(config: TransportConfig): TopicTransport {
            read.forEach { config.option(it) }
            config.option(TopicTransportOptions.PARTITION_REFRESH)
            return object : TopicTransport {
                override val producer: TopicProducer get() = error("not used")
                override val source: PartitionedSource get() = error("not used")
                override val admin: TopicAdmin get() = error("not used")
            }
        }
    }

    /** The transport spec's option check alone, over [provider]: no topic is written. */
    private fun transportSpec(provider: TransportProvider?) = object : TopicTransportSpec() {
        override val producer: TopicProducer get() = error("not used")
        override val source: PartitionedSource get() = error("not used")

        override suspend fun newTopic(partitions: Int): String = error("not used")

        override fun provided(): Pair<TransportProvider, TransportConfig>? =
            provider?.let { it to TransportConfig.of("acme", "acme", mapOf("region" to "us-2")) }
    }

    /** A Sink that reads [read] of its settings and declares [declared]. */
    private fun sink(declared: List<ProviderOption<*>>, vararg read: String) = object : SinkProvider {
        override val type: String = "acme"
        override val options: List<ProviderOption<*>> = declared

        override fun available(config: SinkConfig): Boolean = true

        override fun open(config: SinkConfig): StreamSinks {
            read.forEach { config.option(it) }
            return object : StreamSinks {
                override val processor: String = "acme-sink"

                override fun sinkOf(context: String, aggregate: String): Sink = Sink { }
            }
        }
    }

    /** The Sink spec's option check alone, over [provider]: no record is written. */
    private fun sinkSpec(provider: SinkProvider?) = object : SinkSpec() {
        override fun createSink(): Sink = error("not used")

        override suspend fun written(ref: AggregateRef): List<Int> = error("not used")

        override fun provided(): Pair<SinkProvider, SinkConfig>? =
            provider?.let { it to SinkConfig.of("acme", "acme", mapOf("region" to "us-2")) }
    }

    @Test
    fun `a transport reading only what it declares, and the topic settings, should pass`() {
        transportSpec(transport(listOf(region), "region"))
            .`the transport should read only the options it declares, each declared once with a description`()
    }

    @Test
    fun `a transport reading an undeclared key, or declaring a topic setting again, should fail`() {
        assertThrows<AssertionError> {
            transportSpec(transport(listOf(region), "colour"))
                .`the transport should read only the options it declares, each declared once with a description`()
        }.message.assert().contains("colour")
        assertThrows<AssertionError> {
            transportSpec(transport(listOf(region, TopicTransportOptions.TOPIC_PREFIX)))
                .`the transport should read only the options it declares, each declared once with a description`()
        }.message.assert().contains("topic-prefix")
    }

    @Test
    fun `a Sink reading only what it declares should pass, and one reading another key fail`() {
        sinkSpec(sink(listOf(region), "region"))
            .`the sink should read only the options it declares, each declared once with a description`()
        assertThrows<AssertionError> {
            sinkSpec(sink(listOf(region), "colour"))
                .`the sink should read only the options it declares, each declared once with a description`()
        }.message.assert().contains("colour")
    }

    @Test
    fun `a provider declaring a key twice should fail, and no option can be declared without a description`() {
        val again = ProviderOption.int("region", "the region again, as a number.")
        assertThrows<AssertionError> {
            transportSpec(transport(listOf(region, again), "region"))
                .`the transport should read only the options it declares, each declared once with a description`()
        }.message.assert().contains("region")
        assertThrows<AssertionError> {
            sinkSpec(sink(listOf(region, again), "region"))
                .`the sink should read only the options it declares, each declared once with a description`()
        }.message.assert().contains("region")
        // The description the specs require is required when an option is declared: a blank one is never built.
        assertThrows<IllegalArgumentException> { ProviderOption.string("region", " ") }
    }

    @Test
    fun `a spec no provider opens should skip the check`() {
        assertThrows<TestAbortedException> {
            transportSpec(null)
                .`the transport should read only the options it declares, each declared once with a description`()
        }
        assertThrows<TestAbortedException> {
            sinkSpec(null).`the sink should read only the options it declares, each declared once with a description`()
        }
    }
}
