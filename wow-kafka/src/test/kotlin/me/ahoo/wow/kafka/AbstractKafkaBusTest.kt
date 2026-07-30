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
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetCommitCallback
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverPartition
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.SenderOptions
import reactor.kotlin.test.test
import reactor.util.retry.Retry
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AbstractKafkaBusTest {

    @Test
    fun `assignment positions are anchored asynchronously`() {
        val first = TopicPartition("topic", 0)
        val second = TopicPartition("topic", 1)
        val consumer = mockk<Consumer<String, String>>()
        val callback = slot<OffsetCommitCallback>()
        every {
            consumer.commitAsync(
                any<Map<TopicPartition, OffsetAndMetadata>>(),
                capture(callback),
            )
        } returns Unit
        var completed = false
        var completionFailure: Throwable? = null

        consumer.anchorAssignedPositions(
            mapOf(
                first to 10,
                second to 20,
            ),
        ) { failure ->
            completed = true
            completionFailure = failure
        }

        completed.assert().isFalse()
        verify(exactly = 1) {
            consumer.commitAsync(
                match<Map<TopicPartition, OffsetAndMetadata>> { offsets ->
                    offsets.getValue(first).offset() == 10L &&
                        offsets.getValue(second).offset() == 20L
                },
                callback.captured,
            )
        }
        callback.captured.onComplete(emptyMap(), null)
        completed.assert().isTrue()
        completionFailure.assert().isNull()
    }

    @Test
    fun `assignment anchor reports asynchronous failure`() {
        val partition = TopicPartition("topic", 0)
        val consumer = mockk<Consumer<String, String>>()
        val callback = slot<OffsetCommitCallback>()
        every {
            consumer.commitAsync(
                any<Map<TopicPartition, OffsetAndMetadata>>(),
                capture(callback),
            )
        } returns Unit
        val failure = IllegalStateException("commit")
        var completionFailure: Throwable? = null

        consumer.anchorAssignedPositions(mapOf(partition to 10)) {
            completionFailure = it
        }
        callback.captured.onComplete(emptyMap(), failure)

        completionFailure.assert().isSameAs(failure)
    }

    @Test
    fun `empty assignment anchor completes immediately`() {
        val consumer = mockk<Consumer<String, String>>()
        var completed = false

        consumer.anchorAssignedPositions(emptyMap()) {
            it.assert().isNull()
            completed = true
        }

        completed.assert().isTrue()
    }

    @Test
    fun `assignment anchor propagates submission failure`() {
        val partition = TopicPartition("topic", 0)
        val consumer = mockk<Consumer<String, String>>()
        val failure = IllegalStateException("commit-submission")
        every {
            consumer.commitAsync(
                any<Map<TopicPartition, OffsetAndMetadata>>(),
                any<OffsetCommitCallback>(),
            )
        } throws failure

        assertThrows<IllegalStateException> {
            consumer.anchorAssignedPositions(mapOf(partition to 10)) {}
        }.assert().isSameAs(failure)
    }

    @Test
    fun `receiver anchors a safe boundary after a forward seek`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val calls = mutableListOf<String>()
        val partition = mockk<ReceiverPartition>()
        val topicPartition = TopicPartition("topic", 0)
        val consumer = mockk<Consumer<String, String>>()
        every { partition.topicPartition() } returns topicPartition
        var positionCall = 0
        every { partition.position() } answers {
            calls += "position"
            listOf(7L, 20L)[positionCall++]
        }
        every { partition.seek(20) } returns Unit
        var anchorCompletion: ((Throwable?) -> Unit)? = null
        val bus = TestKafkaBus(
            receiver = receiver,
            anchorAction = { anchoredConsumer, positions, completion ->
                anchoredConsumer.assert().isSameAs(consumer)
                positions.assert().isEqualTo(mapOf(topicPartition to 7L))
                calls += "anchor"
                anchorCompletion = completion
            },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val messages = messageReceiver.messages
            .contextWrite {
                it.writeReceiverOptionsCustomizer { options ->
                    options
                        .clearAssignListeners()
                        .addAssignListener { partitions ->
                            calls += "context"
                            partitions.single().seek(20)
                        }
                }
            }
            .subscribe()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
            bus.capturedOptions!!.assignListeners().forEach { listener ->
                listener.accept(listOf(partition))
            }

            val readiness = messageReceiver.readiness.toFuture()
            readiness.isDone.assert().isFalse()
            calls.assert().containsExactly("position", "context", "position", "anchor")
            checkNotNull(anchorCompletion)(null)
            readiness.get(1, TimeUnit.SECONDS)
        } finally {
            messages.dispose()
            bus.close()
        }
    }

    @Test
    fun `cooperative assignments cannot overtake an in-flight anchor`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val firstPartition = mockk<ReceiverPartition>()
        val secondPartition = mockk<ReceiverPartition>()
        val firstTopicPartition = TopicPartition("topic", 0)
        val secondTopicPartition = TopicPartition("topic", 1)
        val consumer = mockk<Consumer<String, String>>()
        every { firstPartition.topicPartition() } returns firstTopicPartition
        every { firstPartition.position() } returns 0L
        every { secondPartition.topicPartition() } returns secondTopicPartition
        every { secondPartition.position() } returns 0L
        val anchorCompletions =
            mutableMapOf<Set<TopicPartition>, (Throwable?) -> Unit>()
        val bus = TestKafkaBus(
            receiver = receiver,
            anchorAction = { _, positions, completion ->
                anchorCompletions[positions.keys] = completion
            },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val messages = messageReceiver.messages.then().toFuture()
        val readiness = messageReceiver.readiness.toFuture()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
            val assignListeners = bus.capturedOptions!!.assignListeners()
            assignListeners.forEach { listener ->
                listener.accept(listOf(firstPartition))
            }
            assignListeners.forEach { listener ->
                listener.accept(emptyList())
            }
            assignListeners.forEach { listener ->
                listener.accept(listOf(secondPartition))
            }

            checkNotNull(anchorCompletions[setOf(secondTopicPartition)])(null)
            readiness.isDone.assert().isFalse()

            val failure = IllegalStateException("first-anchor")
            checkNotNull(anchorCompletions[setOf(firstTopicPartition)])(failure)

            assertThrows<ExecutionException> {
                readiness.get(1, TimeUnit.SECONDS)
            }.cause.assert().isSameAs(failure)
            assertThrows<ExecutionException> {
                messages.get(1, TimeUnit.SECONDS)
            }.cause.assert().isSameAs(failure)
        } finally {
            messages.cancel(true)
            bus.close()
        }
    }

    @Test
    fun `readiness waits for every in-flight cooperative anchor`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val firstPartition = mockk<ReceiverPartition>()
        val secondPartition = mockk<ReceiverPartition>()
        val firstTopicPartition = TopicPartition("topic", 0)
        val secondTopicPartition = TopicPartition("topic", 1)
        val consumer = mockk<Consumer<String, String>>()
        every { firstPartition.topicPartition() } returns firstTopicPartition
        every { firstPartition.position() } returns 0L
        every { secondPartition.topicPartition() } returns secondTopicPartition
        every { secondPartition.position() } returns 0L
        val anchorCompletions =
            mutableMapOf<Set<TopicPartition>, (Throwable?) -> Unit>()
        val bus = TestKafkaBus(
            receiver = receiver,
            anchorAction = { _, positions, completion ->
                anchorCompletions[positions.keys] = completion
            },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val readiness = messageReceiver.readiness.toFuture()
        val messages = messageReceiver.messages.then().toFuture()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
            val assignListeners = bus.capturedOptions!!.assignListeners()
            assignListeners.forEach { listener ->
                listener.accept(listOf(firstPartition))
            }
            assignListeners.forEach { listener ->
                listener.accept(listOf(secondPartition))
            }

            checkNotNull(anchorCompletions[setOf(secondTopicPartition)])(null)
            readiness.isDone.assert().isFalse()
            checkNotNull(anchorCompletions[setOf(firstTopicPartition)])(null)
            readiness.get(1, TimeUnit.SECONDS)
        } finally {
            messages.cancel(true)
            bus.close()
        }
    }

    @Test
    fun `rebalance anchor failure terminates messages after readiness`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val firstPartition = mockk<ReceiverPartition>()
        val secondPartition = mockk<ReceiverPartition>()
        val consumer = mockk<Consumer<String, String>>()
        every { firstPartition.topicPartition() } returns TopicPartition("topic", 0)
        every { firstPartition.position() } returns 0L
        every { secondPartition.topicPartition() } returns TopicPartition("topic", 1)
        every { secondPartition.position() } returns 0L
        val anchorCompletions = mutableListOf<(Throwable?) -> Unit>()
        val bus = TestKafkaBus(
            receiver = receiver,
            anchorAction = { _, _, completion ->
                anchorCompletions += completion
            },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val messages = messageReceiver.messages.then().toFuture()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
            val assignListeners = bus.capturedOptions!!.assignListeners()
            assignListeners.forEach { listener ->
                listener.accept(listOf(firstPartition))
            }
            anchorCompletions.single()(null)
            messageReceiver.readiness.block(Duration.ofSeconds(1))

            assignListeners.forEach { listener ->
                listener.accept(listOf(secondPartition))
            }
            val failure = IllegalStateException("rebalance-anchor")
            anchorCompletions.last()(failure)

            assertThrows<ExecutionException> {
                messages.get(1, TimeUnit.SECONDS)
            }.cause.assert().isSameAs(failure)
        } finally {
            messages.cancel(true)
            bus.close()
        }
    }

    @Test
    fun `Kafka asynchronous anchor failure fails readiness`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val failure = IllegalStateException("async-anchor")
        val partition = mockk<ReceiverPartition>()
        val consumer = mockk<Consumer<String, String>>()
        every { partition.topicPartition() } returns TopicPartition("topic", 0)
        every { partition.position() } returns 0L
        var anchorCompletion: ((Throwable?) -> Unit)? = null
        val bus = TestKafkaBus(
            receiver = receiver,
            anchorAction = { _, _, completion ->
                anchorCompletion = completion
            },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val messages = messageReceiver.messages.then().toFuture()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
            bus.capturedOptions!!.assignListeners().forEach { listener ->
                listener.accept(listOf(partition))
            }
            checkNotNull(anchorCompletion)(failure)

            messageReceiver.readiness.test()
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(failure)
                }
                .verify()
            assertThrows<ExecutionException> {
                messages.get(1, TimeUnit.SECONDS)
            }.cause.assert().isSameAs(failure)
        } finally {
            messages.cancel(true)
            bus.close()
        }
    }

    @Test
    fun `Kafka anchor failure fails readiness`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        val failure = IllegalStateException("anchor")
        val partition = mockk<ReceiverPartition>()
        val consumer = mockk<Consumer<String, String>>()
        every { partition.topicPartition() } returns TopicPartition("topic", 0)
        every { partition.position() } returns 0L
        lateinit var bus: TestKafkaBus
        every { receiver.receive(1) } answers {
            Flux.defer {
                bus.capturedOptions!!.consumerListener()!!.consumerAdded("test", consumer)
                bus.capturedOptions!!.assignListeners().forEach { listener ->
                    listener.accept(listOf(partition))
                }
                Flux.never()
            }
        }
        bus = TestKafkaBus(
            receiver = receiver,
            receiverPolicy = KafkaReceiverPolicy(
                retrySpec = Retry.max(0)
                    .onRetryExhaustedThrow { _, signal -> signal.failure() },
            ),
            anchorAction = { _, _, _ -> throw failure },
        )
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )

        try {
            messageReceiver.messages.test()
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(failure)
                }
                .verify()
            messageReceiver.readiness.test()
                .expectErrorSatisfies { error ->
                    error.assert().isSameAs(failure)
                }
                .verify()
        } finally {
            bus.close()
        }
    }

    @Test
    fun `empty Kafka assignment is ready`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.never()
        val bus = TestKafkaBus(receiver)
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )
        val messages = messageReceiver.messages.subscribe()

        try {
            bus.capturedOptions!!.consumerListener()!!.consumerAdded(
                "test",
                mockk<Consumer<String, String>>(),
            )
            bus.capturedOptions!!.assignListeners().forEach { listener ->
                listener.accept(emptyList())
            }

            messageReceiver.readiness.test().verifyComplete()
        } finally {
            messages.dispose()
            bus.close()
        }
    }

    @Test
    fun `completion before Kafka assignment fails readiness`() {
        val receiver = mockk<KafkaReceiver<String, String>>()
        every { receiver.receive(1) } returns Flux.empty()
        val bus = TestKafkaBus(receiver)
        val messageReceiver = bus.receiver(
            MessageSubscription(message(), generateGlobalId()),
        )

        try {
            messageReceiver.messages.test().verifyComplete()
            messageReceiver.readiness.test()
                .expectErrorSatisfies { error ->
                    error.assert()
                        .isInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("before partition assignment")
                }
                .verify()
        } finally {
            bus.close()
        }
    }

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
        private val anchorAction: (
            Consumer<*, *>,
            Map<TopicPartition, Long>,
            (Throwable?) -> Unit,
        ) -> Unit = { _, _, completion -> completion(null) },
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

        override fun anchorAssignedPartitions(
            consumer: Consumer<*, *>,
            positions: Map<TopicPartition, Long>,
            completion: (Throwable?) -> Unit,
        ) {
            anchorAction(consumer, positions, completion)
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
