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

package me.ahoo.wow.kafka

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.messaging.transport.TransportServerCommandExchange
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.tck.container.KafkaTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B8 on Kafka: a graceful stop while commands keep arriving. The runtime stops pulling (Reactor Kafka pauses the
 * partitions), drains what it pulled, commits exactly the processed offsets and leaves the rest to the group.
 */
class KafkaSustainedIngressShutdownTest {
    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val QUIET_PERIOD: Duration = Duration.ofMillis(500)
        private const val PARTITIONS = 2
        private const val AGGREGATES = 8
    }

    @JvmField
    @RegisterExtension
    val kafka = KafkaTestFixture()

    @Test
    fun `graceful stop under sustained ingress commits only processed records`() {
        val topicConverter = DefaultCommandTopicConverter(topicPrefix = kafka.topic("b8") + ".")
        val topic = topicConverter.convert(MOCK_AGGREGATE_METADATA)
        val group = kafka.clientId("b8_group")
        AdminClient.create(kafka.kafkaProperties(kafka.clientId("admin"))).use { admin ->
            admin.createTopics(listOf(NewTopic(topic, PARTITIONS, 1))).all().get(30, TimeUnit.SECONDS)

            val bus = KafkaCommandBus(
                topicConverter = topicConverter,
                senderOptions = kafka.senderOptions(),
                receiverOptions = kafka.receiverOptions(),
            )
            // partition -> offsets processed
            val processed = ConcurrentHashMap<Int, MutableSet<Long>>()
            val processCounts = ConcurrentHashMap<String, AtomicInteger>()
            val dispatcher = CommandDispatcher(
                name = group,
                namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
                commandBus = bus,
                commandHandler = object : CommandHandler {
                    override fun handle(
                        exchange: ServerCommandExchange<*>,
                        aggregateMetadata: AggregateMetadata<*, *>,
                    ): Mono<Void> = Mono.delay(Duration.ofMillis(2))
                        .then(
                            Mono.fromRunnable<Void> {
                                val (partition, offset) = (exchange as TransportServerCommandExchange<*>).record.id
                                    .split('-')
                                    .let { it[0].toInt() to it[1].toLong() }
                                processed.computeIfAbsent(partition) { ConcurrentHashMap.newKeySet() } += offset
                                processCounts.computeIfAbsent(exchange.message.id) { AtomicInteger() }
                                    .incrementAndGet()
                            },
                        )
                        .then(exchange.acknowledge())
                },
            )
            val runtime = WowRuntime(
                components = listOf(dispatcher),
                shutdownTimeout = SHUTDOWN_TIMEOUT,
                shutdownQuietPeriod = QUIET_PERIOD,
            )
            runtime.start().block(Duration.ofSeconds(60))
            val sequence = AtomicInteger()
            val sent = AtomicInteger()
            val producer = Flux.interval(Duration.ofMillis(2), Schedulers.single())
                .onBackpressureDrop()
                .concatMap {
                    val id = sequence.getAndIncrement()
                    val aggregateId = "aggregate-${id % AGGREGATES}"
                    bus.send(
                        MockCreateAggregate(id = aggregateId, data = "$id").toCommandMessage(aggregateId = aggregateId),
                    ).doOnSuccess { sent.incrementAndGet() }
                }
                .subscribe()
            try {
                awaitUntil(Duration.ofSeconds(60)) { processCounts.size >= 300 }

                val stopStarted = System.nanoTime()
                val stopFailure = runCatching { runtime.stopGracefully().block(SHUTDOWN_TIMEOUT.multipliedBy(2)) }
                    .exceptionOrNull()
                val stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted)
                println("Kafka graceful stop under sustained ingress took $stopMillis ms.")

                stopFailure.assert().isNull()
                stopMillis.assert().isLessThan(SHUTDOWN_TIMEOUT.toMillis() / 4)
                val sentAtStop = sent.get()
                awaitUntil(Duration.ofSeconds(10)) { sent.get() > sentAtStop + 100 }
            } finally {
                producer.dispose()
                runtime.forceStop()
            }
            // The consumer left the group after its final commit.
            awaitUntil(Duration.ofSeconds(30)) {
                admin.describeConsumerGroups(listOf(group)).all().get()[group]?.members().isNullOrEmpty()
            }
            val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get()
                .filterKeys { it.topic() == topic }
                .mapKeys { it.key.partition() }
                .mapValues { it.value.offset() }

            // Processed at most once.
            processCounts.values.forEach { it.get().assert().isEqualTo(1) }
            // Exactly the processed records are committed: nothing committed without processing, and nothing
            // processed is left for another member to process again.
            (0 until PARTITIONS).forEach { partition ->
                val committedOffset = committed[partition] ?: 0L
                processed[partition].orEmpty().assert().isEqualTo((0 until committedOffset).toSet())
            }
            // Ingress went on: records were left uncommitted for another member.
            committed.values.sum().assert().isLessThan(sent.get().toLong())
            bus.close()
        }
    }

    private fun awaitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not reached within $timeout." }
            Thread.sleep(20)
        }
    }
}
