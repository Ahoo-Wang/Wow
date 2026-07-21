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
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.command.CommandBusSpec
import me.ahoo.wow.tck.container.KafkaTestFixture
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.sender.KafkaSender
import reactor.kotlin.test.test
import java.time.Duration

internal class KafkaCommandBusTest : CommandBusSpec() {

    @JvmField
    @RegisterExtension
    val kafka = KafkaTestFixture()

    override fun createMessageBus(): CommandBus {
        return KafkaCommandBus(
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
        )
    }

    override fun Flux<ServerCommandExchange<*>>.onReceive(onReady: Sinks.Empty<Void>): Flux<ServerCommandExchange<*>> {
        return contextWrite {
            it.writeReceiverOptionsCustomizer { receiverOptions ->
                receiverOptions.addAssignListener {
                    it.forEach { receiverPartition ->
                        receiverPartition.seekToEnd()
                    }
                    onReady.tryEmitEmpty()
                }
            }
        }
    }

    @Test
    fun `should preserve an earlier unacknowledged offset`() {
        val topicConverter = DefaultCommandTopicConverter("test-${generateGlobalId()}.")
        val bus = KafkaCommandBus(
            topicConverter = topicConverter,
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions()
                .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
        )
        val receiverGroup = generateGlobalId()
        val aggregateId = generateGlobalId()
        val messages = listOf(
            MockCreateAggregate(id = aggregateId, data = "first").toCommandMessage(),
            MockCreateAggregate(id = aggregateId, data = "second").toCommandMessage(),
        )
        val firstReady = Sinks.empty<Void>()

        try {
            bus.receive(MessageSubscription(namedAggregate, receiverGroup))
                .contextWrite {
                    it.writeReceiverOptionsCustomizer { options ->
                        options.addAssignListener {
                            firstReady.tryEmitEmpty()
                        }
                    }
                }
                .doOnSubscribe {
                    firstReady.asMono()
                        .thenMany(Flux.fromIterable(messages).concatMap(bus::send))
                        .subscribe()
                }
                .index()
                .concatMap { indexed ->
                    if (indexed.t1 == 1L) {
                        indexed.t2.acknowledge().thenReturn(indexed.t2)
                    } else {
                        Mono.just(indexed.t2)
                    }
                }
                .take(2)
                .test()
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofMinutes(2))

            bus.receive(MessageSubscription(namedAggregate, receiverGroup))
                .take(1)
                .test()
                .consumeNextWith {
                    it.message.id.assert().isEqualTo(messages.first().id)
                }
                .expectComplete()
                .verify(Duration.ofSeconds(10))
        } finally {
            bus.close()
        }
    }

    @Test
    fun `should fail closed without exposing an undecodable payload`() {
        val topicConverter = DefaultCommandTopicConverter("test-${generateGlobalId()}.")
        val aggregateId = generateGlobalId()
        val payload = """{"secret":"must-not-appear"}"""

        assertDecodeFailure(
            topicConverter = topicConverter,
            record = ProducerRecord(
                topicConverter.convert(namedAggregate),
                aggregateId,
                payload,
            ),
        ) {
            it.message.assert().doesNotContain(payload)
        }
    }

    @Test
    fun `should reject a record whose key does not match the aggregate id`() {
        val topicConverter = DefaultCommandTopicConverter("test-${generateGlobalId()}.")
        val message = MockCreateAggregate(id = generateGlobalId(), data = "valid").toCommandMessage()

        assertDecodeFailure(
            topicConverter = topicConverter,
            record = ProducerRecord(
                topicConverter.convert(namedAggregate),
                "wrong-key",
                message.toJsonString(),
            ),
        )
    }

    @Test
    fun `should reject a record whose topic does not match the decoded aggregate`() {
        val topicConverter = DefaultCommandTopicConverter("test-${generateGlobalId()}.")
        val message = MockCreateAggregate(id = generateGlobalId(), data = "valid").toCommandMessage()

        assertDecodeFailure(
            topicConverter = topicConverter,
            record = ProducerRecord(
                "test-${generateGlobalId()}.wrong.command",
                message.aggregateId.id,
                message.toJsonString(),
            ),
        )
    }

    @Test
    fun `should acknowledge an undecodable record only after the handler completes`() {
        val topicConverter = DefaultCommandTopicConverter("test-${generateGlobalId()}.")
        val bus = KafkaCommandBus(
            topicConverter = topicConverter,
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
            receiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
            receiverPolicy = KafkaReceiverPolicy(),
            recordDecodeFailureHandler = AcknowledgeKafkaRecordDecodeFailureHandler,
        )
        val rawSender = KafkaSender.create(kafka.senderOptions())
        val receiverGroup = generateGlobalId()
        val aggregateId = generateGlobalId()
        val validMessage = MockCreateAggregate(id = aggregateId, data = "valid").toCommandMessage()
        val ready = Sinks.empty<Void>()

        try {
            bus.receive(MessageSubscription(namedAggregate, receiverGroup))
                .contextWrite {
                    it.writeReceiverOptionsCustomizer { options ->
                        options.addAssignListener {
                            ready.tryEmitEmpty()
                        }
                    }
                }
                .doOnSubscribe {
                    ready.asMono()
                        .then(
                            rawSender.createOutbound()
                                .send(
                                    Mono.just(
                                        ProducerRecord(
                                            topicConverter.convert(namedAggregate),
                                            aggregateId,
                                            "not-json",
                                        ),
                                    ),
                                ).then(),
                        )
                        .then(bus.send(validMessage))
                        .subscribe()
                }
                .take(1)
                .test()
                .consumeNextWith {
                    it.message.id.assert().isEqualTo(validMessage.id)
                }
                .expectComplete()
                .verify(Duration.ofMinutes(2))
        } finally {
            rawSender.close()
            bus.close()
        }
    }

    private fun assertDecodeFailure(
        topicConverter: CommandTopicConverter,
        record: ProducerRecord<String, String>,
        assertFailure: (Throwable) -> Unit = {},
    ) {
        val bus = KafkaCommandBus(
            topicConverter = topicConverter,
            senderOptions = kafka.senderOptions(),
            receiverOptions = kafka.receiverOptions(),
        )
        val rawSender = KafkaSender.create(kafka.senderOptions())
        val receiverGroup = generateGlobalId()
        val ready = Sinks.empty<Void>()

        try {
            bus.receive(MessageSubscription(namedAggregate, receiverGroup))
                .contextWrite {
                    it.writeReceiverOptionsCustomizer { options ->
                        options.subscription(setOf(record.topic()))
                            .addAssignListener {
                                ready.tryEmitEmpty()
                            }
                    }
                }
                .doOnSubscribe {
                    ready.asMono()
                        .then(
                            rawSender.createOutbound()
                                .send(Mono.just(record))
                                .then(),
                        ).subscribe()
                }
                .test()
                .expectErrorSatisfies {
                    it.assert().isInstanceOf(KafkaRecordDecodeException::class.java)
                    assertFailure(it)
                }
                .verify(Duration.ofMinutes(2))
        } finally {
            rawSender.close()
            bus.close()
        }
    }
}
