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

package me.ahoo.wow.benchmark.infrastructure.transport

import me.ahoo.wow.kafka.KafkaDomainEventBus
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.SenderOptions
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.UUID

/**
 * Kafka event bus: send → poll → commit (acknowledge), one event stream per operation, through one receiver.
 *
 * Not part of any Gradle suite: the benchmarks have no Kafka service yet. Run it from the JMH jar against a broker
 * named by `-Dwow.benchmark.kafka.bootstrap-servers=host:port` (default `localhost:9092`); the README shows the
 * command. `-Dwow.benchmark.kafka.commit-interval=PT0.1S` overrides the receiver's commit interval (ISO-8601).
 *
 * Audit 9.3.0 B §F5 / §F7 (design WP G2; X7 compares against this).
 */
@State(Scope.Benchmark)
open class KafkaEventReceiveAckBenchmark {
    internal lateinit var harness: TransportReceiveAckHarness

    @Setup(Level.Trial)
    fun setup() {
        val bootstrapServers = System.getProperty(BOOTSTRAP_SERVERS_PROPERTY, DEFAULT_BOOTSTRAP_SERVERS)
        requireKafka(bootstrapServers)
        val clientId = "wow-benchmark-${UUID.randomUUID()}"
        val common = mapOf<String, Any>(
            CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            CommonClientConfigs.CLIENT_ID_CONFIG to clientId,
        )
        val senderOptions = SenderOptions.create<String, String>(
            common + mapOf(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ),
        )
        val receiverOptions = ReceiverOptions.create<String, String>(
            common + mapOf(
                ConsumerConfig.GROUP_ID_CONFIG to "$clientId-group",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).let { options ->
            // Experiment knob: Reactor Kafka commits acknowledged offsets every commitInterval (default 5 s).
            System.getProperty(COMMIT_INTERVAL_PROPERTY)?.let { options.commitInterval(Duration.parse(it)) } ?: options
        }
        harness = TransportReceiveAckHarness(
            KafkaDomainEventBus(senderOptions = senderOptions, receiverOptions = receiverOptions),
            "$clientId-group",
        )
        check(harness.roundTrip(harness.newProducer().next())) { "Kafka probe round trip timed out." }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        harness.close()
    }

    @Benchmark
    fun sendReceiveAck(producer: Producer, blackhole: Blackhole) {
        check(harness.roundTrip(producer.next())) { "Kafka receive/ack round trip timed out." }
        blackhole.consume(producer)
    }

    @State(Scope.Thread)
    open class Producer {
        private lateinit var delegate: TransportReceiveAckHarness.Producer

        @Setup(Level.Trial)
        fun setup(benchmark: KafkaEventReceiveAckBenchmark) {
            delegate = benchmark.harness.newProducer()
        }

        fun next() = delegate.next()
    }

    private companion object {
        const val BOOTSTRAP_SERVERS_PROPERTY = "wow.benchmark.kafka.bootstrap-servers"
        const val DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092"
        const val COMMIT_INTERVAL_PROPERTY = "wow.benchmark.kafka.commit-interval"
        const val CONNECT_TIMEOUT_MILLIS = 2000

        fun requireKafka(bootstrapServers: String) {
            val endpoint = bootstrapServers.split(',').first().trim()
            val host = endpoint.substringBeforeLast(':')
            val port = endpoint.substringAfterLast(':').toInt()
            val available = runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS) }
            }.isSuccess
            require(available) {
                "Kafka is required at $bootstrapServers; set -D$BOOTSTRAP_SERVERS_PROPERTY to a running broker."
            }
        }
    }
}
