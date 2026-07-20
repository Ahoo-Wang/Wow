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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.SenderOptions
import reactor.kotlin.test.test
import reactor.util.retry.Retry

class AbstractKafkaBusTest {

    @Test
    fun `should apply receiver policy and decode a valid record`() {
        val message = message()
        val receiverOffset = mockk<ReceiverOffset>()
        val record = receiverRecord(message, receiverOffset = receiverOffset)
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(2) } returns Flux.just(record)
        val baseCustomizer = mockk<ReceiverOptionsCustomizer>()
        every { baseCustomizer.customize(any()) } answers { firstArg() }
        val policy = KafkaReceiverPolicy(
            prefetchBatches = 2,
            maxDeferredCommits = 7,
            retrySpec = Retry.max(0),
        )
        val bus = TestKafkaBus(
            receiver = receiver,
            receiverOptionsCustomizer = baseCustomizer,
            receiverPolicy = policy,
        )
        val receiverGroup = generateGlobalId()

        try {
            bus.receive(MessageSubscription(message, receiverGroup))
                .contextWrite {
                    it.writeReceiverOptionsCustomizer { options ->
                        options.consumerProperty(CONTEXT_CUSTOMIZED, true)
                    }
                }
                .test()
                .consumeNextWith {
                    it.message.id.assert().isEqualTo(message.id)
                }
                .verifyComplete()

            verify(exactly = 1) {
                baseCustomizer.customize(any())
            }
            bus.capturedOptions!!.maxDeferredCommits().assert().isEqualTo(7)
            bus.capturedOptions!!.groupId().assert().isEqualTo(receiverGroup)
            bus.capturedOptions!!.subscriptionTopics().assert()
                .isEqualTo(setOf(DefaultCommandTopicConverter().convert(message)))
            bus.capturedOptions!!.consumerProperty(CONTEXT_CUSTOMIZED).assert().isEqualTo(true)
        } finally {
            bus.close()
        }
    }

    @Test
    fun `should acknowledge a record after the decode handler completes`() {
        val message = message()
        val receiverOffset = mockk<ReceiverOffset>(relaxed = true)
        val record = receiverRecord(
            message = message,
            receiverOffset = receiverOffset,
            value = "not-json",
        )
        val failureHandler = mockk<KafkaRecordDecodeFailureHandler>()
        every { failureHandler.handle(any()) } returns Mono.empty()
        val bus = testBus(record, failureHandler)

        try {
            bus.receive(MessageSubscription(message, generateGlobalId()))
                .test()
                .verifyComplete()

            verify(exactly = 1) {
                failureHandler.handle(any())
                receiverOffset.acknowledge()
            }
        } finally {
            bus.close()
        }
    }

    @Test
    fun `should leave a record unacknowledged when the decode handler fails`() {
        val message = message()
        val receiverOffset = mockk<ReceiverOffset>(relaxed = true)
        val record = receiverRecord(
            message = message,
            receiverOffset = receiverOffset,
            value = "not-json",
        )
        val expected = IllegalStateException("failure-handler")
        val failureHandler = mockk<KafkaRecordDecodeFailureHandler>()
        every { failureHandler.handle(any()) } returns Mono.error(expected)
        val bus = testBus(record, failureHandler)

        try {
            bus.receive(MessageSubscription(message, generateGlobalId()))
                .test()
                .expectErrorSatisfies {
                    it.assert().isSameAs(expected)
                }
                .verify()

            verify(exactly = 0) {
                receiverOffset.acknowledge()
            }
        } finally {
            bus.close()
        }
    }

    @Test
    fun `should reject a key that does not match the aggregate id`() {
        val message = message()
        val receiverOffset = mockk<ReceiverOffset>(relaxed = true)
        assertRejectedRecord(
            message = message,
            record = receiverRecord(
                message = message,
                receiverOffset = receiverOffset,
                key = "wrong-key",
            ),
            receiverOffset = receiverOffset,
            expectedMessage = "Kafka record key does not match the decoded aggregate id.",
        )
    }

    @Test
    fun `should reject a topic that does not match the aggregate`() {
        val message = message()
        val receiverOffset = mockk<ReceiverOffset>(relaxed = true)
        assertRejectedRecord(
            message = message,
            record = receiverRecord(
                message = message,
                receiverOffset = receiverOffset,
                topic = "wrong.topic",
            ),
            receiverOffset = receiverOffset,
            expectedMessage = "Kafka record topic does not match the decoded aggregate.",
        )
    }

    private fun assertRejectedRecord(
        message: CommandMessage<*>,
        record: ReceiverRecord<String, String>,
        receiverOffset: ReceiverOffset,
        expectedMessage: String,
    ) {
        var failure: KafkaRecordDecodeFailure? = null
        val failureHandler = KafkaRecordDecodeFailureHandler {
            failure = it
            Mono.empty()
        }
        val bus = testBus(record, failureHandler)

        try {
            bus.receive(MessageSubscription(message, generateGlobalId()))
                .test()
                .verifyComplete()

            failure!!.cause.message.assert().isEqualTo(expectedMessage)
            verify(exactly = 1) {
                receiverOffset.acknowledge()
            }
        } finally {
            bus.close()
        }
    }

    private fun testBus(
        record: ReceiverRecord<String, String>,
        failureHandler: KafkaRecordDecodeFailureHandler,
    ): TestKafkaBus {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.just(record)
        return TestKafkaBus(
            receiver = receiver,
            recordDecodeFailureHandler = failureHandler,
        )
    }

    private fun message(): CommandMessage<*> {
        return MockCreateAggregate(
            id = generateGlobalId(),
            data = generateGlobalId(),
        ).toCommandMessage()
    }

    private fun receiverRecord(
        message: CommandMessage<*>,
        receiverOffset: ReceiverOffset,
        topic: String = DefaultCommandTopicConverter().convert(message),
        key: String = message.aggregateId.id,
        value: String = message.toJsonString(),
    ): ReceiverRecord<String, String> {
        return mockk {
            every { topic() } returns topic
            every { key() } returns key
            every { value() } returns value
            every { receiverOffset() } returns receiverOffset
        }
    }

    private class TestKafkaBus(
        private val receiver: KafkaReceiver<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
        receiverPolicy: KafkaReceiverPolicy = KafkaReceiverPolicy(retrySpec = Retry.max(0)),
        recordDecodeFailureHandler: KafkaRecordDecodeFailureHandler = FailKafkaRecordDecodeFailureHandler,
    ) : AbstractKafkaBus<CommandMessage<*>, ServerCommandExchange<*>>(
        topicConverter = DefaultCommandTopicConverter(),
        senderOptions = senderOptions(),
        receiverOptions = receiverOptions(),
        receiverOptionsCustomizer = receiverOptionsCustomizer,
        receiverPolicy = receiverPolicy,
        recordDecodeFailureHandler = recordDecodeFailureHandler,
    ) {
        var capturedOptions: ReceiverOptions<String, String>? = null

        override val messageType: Class<CommandMessage<*>>
            get() = CommandMessage::class.java

        override fun CommandMessage<*>.toExchange(receiverOffset: ReceiverOffset): ServerCommandExchange<*> {
            return KafkaServerCommandExchange(this, receiverOffset)
        }

        override fun createReceiver(
            receiverOptions: ReceiverOptions<String, String>,
        ): KafkaReceiver<String, String> {
            capturedOptions = receiverOptions
            return receiver
        }
    }

    companion object {
        private const val CONTEXT_CUSTOMIZED = "context.customized"

        private fun senderOptions(): SenderOptions<String, String> {
            return SenderOptions.create(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ),
            )
        }

        private fun receiverOptions(): ReceiverOptions<String, String> {
            return ReceiverOptions.create(
                mapOf(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ),
            )
        }
    }
}
