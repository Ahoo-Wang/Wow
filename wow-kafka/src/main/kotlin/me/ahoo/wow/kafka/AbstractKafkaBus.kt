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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverOptions.ConsumerListener
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private typealias KafkaAssignmentListener =
    (Consumer<*, *>, Map<TopicPartition, Long>) -> Unit

internal fun Consumer<*, *>.anchorAssignedPositions(
    positions: Map<TopicPartition, Long>,
    completion: (Throwable?) -> Unit,
) {
    if (positions.isEmpty()) {
        completion(null)
        return
    }
    val initialOffsets = positions
        .mapValues { (_, position) -> OffsetAndMetadata(position) }
    commitAsync(initialOffsets) { _, error -> completion(error) }
}

abstract class AbstractKafkaBus<M, E>(
    private val topicConverter: AggregateTopicConverter,
    private val senderOptions: SenderOptions<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer,
    private val receiverPolicy: KafkaReceiverPolicy,
    private val recordDecodeFailureHandler: KafkaRecordDecodeFailureHandler,
) : DistributedMessageBus<M, E>
    where M : Message<*, *>, M : AggregateIdCapable, M : NamedAggregate, E : MessageExchange<*, M> {
    constructor(
        topicConverter: AggregateTopicConverter,
        senderOptions: SenderOptions<String, String>,
        receiverOptions: ReceiverOptions<String, String>,
        receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
    ) : this(
        topicConverter = topicConverter,
        senderOptions = senderOptions,
        receiverOptions = receiverOptions,
        receiverOptionsCustomizer = receiverOptionsCustomizer,
        receiverPolicy = KafkaReceiverPolicy(),
        recordDecodeFailureHandler = FailKafkaRecordDecodeFailureHandler,
    )

    companion object {
        private val log = KotlinLogging.logger {}
    }

    protected val sender: KafkaSender<String, String> = KafkaSender.create(senderOptions)
    abstract val messageType: Class<M>
    override fun send(message: M): Mono<Void> {
        return Mono.defer {
            log.debug {
                "Send $message."
            }
            message.withReadOnly()
            val senderRecord = encode(message)
            sender.send(Mono.just(senderRecord))
                .doOnNext {
                    @Suppress("ThrowingExceptionsWithoutMessageOrCause")
                    val error = it.exception()
                    if (error != null) {
                        it.correlationMetadata().tryEmitError(error)
                    } else {
                        it.correlationMetadata().tryEmitEmpty()
                    }
                }
                .flatMap {
                    it.correlationMetadata().asMono()
                }
                .next()
        }
    }

    abstract fun M.toExchange(receiverOffset: ReceiverOffset): E

    protected open fun createReceiver(
        receiverOptions: ReceiverOptions<String, String>,
    ): KafkaReceiver<String, String> {
        return KafkaReceiver.create(receiverOptions)
    }

    override fun receive(subscription: MessageSubscription): Flux<E> =
        receive(subscription, onAssigned = null)

    @Suppress("TooGenericExceptionCaught")
    override fun receiver(subscription: MessageSubscription): MessageReceiver<E> {
        val readiness = Sinks.empty<Void>()
        val readinessTerminated = AtomicBoolean()
        val assignmentFailure = Sinks.empty<Void>()
        val pendingAnchors = AtomicLong()
        fun completeReadiness() {
            if (readinessTerminated.compareAndSet(false, true)) {
                readiness.tryEmitEmpty()
            }
        }
        fun failReadiness(error: Throwable) {
            if (readinessTerminated.compareAndSet(false, true)) {
                readiness.tryEmitError(error)
            }
        }
        val messages = receive(subscription) { consumer, positions ->
            if (positions.isEmpty()) {
                if (pendingAnchors.get() == 0L) {
                    completeReadiness()
                }
                return@receive
            }
            pendingAnchors.incrementAndGet()
            try {
                anchorAssignedPartitions(consumer, positions) { error ->
                    val remaining = pendingAnchors.decrementAndGet()
                    if (error == null) {
                        if (remaining == 0L) {
                            completeReadiness()
                        }
                    } else {
                        failReadiness(error)
                        assignmentFailure.tryEmitError(error)
                    }
                }
            } catch (error: Throwable) {
                pendingAnchors.decrementAndGet()
                throw error
            }
        }
            .takeUntilOther(assignmentFailure.asMono())
            .doOnError(::failReadiness)
            .doOnComplete {
                failReadiness(
                    IllegalStateException(
                        "Kafka receiver completed before partition assignment.",
                    ),
                )
            }
            .doOnCancel {
                failReadiness(
                    CancellationException("Kafka receiver initialization was cancelled."),
                )
            }
        return MessageReceiver(
            messages = messages,
            readiness = readiness.asMono(),
        )
    }

    private fun receive(
        subscription: MessageSubscription,
        onAssigned: KafkaAssignmentListener?,
    ): Flux<E> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(
                receiverOptions.maxDeferredCommits(receiverPolicy.maxDeferredCommits),
            )
                .consumerProperty(
                    ConsumerConfig.GROUP_ID_CONFIG,
                    subscription.receiverGroup,
                )
                .subscription(subscription.namedAggregates.map { topicConverter.convert(it) }.toSet())
            val customizedOptions = contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options
            val readyOptions = if (onAssigned == null) {
                customizedOptions
            } else {
                readinessReceiverOptions(customizedOptions, onAssigned)
            }
            createReceiver(readyOptions)
                .receive(receiverPolicy.prefetchBatches)
                .retryWhen(receiverPolicy.retrySpec)
                .concatMap(::decodeRecord)
        }
    }

    private fun readinessReceiverOptions(
        options: ReceiverOptions<String, String>,
        onAssigned: KafkaAssignmentListener,
    ): ReceiverOptions<String, String> {
        val consumer = AtomicReference<Consumer<*, *>?>()
        val initialPositions = AtomicReference<Map<TopicPartition, Long>?>()
        val captureInitialPositions = options
            .consumerListener(
                readinessConsumerListener(
                    delegate = options.consumerListener(),
                    consumer = consumer,
                ),
            )
            .clearAssignListeners()
            .addAssignListener { partitions ->
                initialPositions.set(
                    partitions.associate { partition ->
                        partition.topicPartition() to partition.position()
                    },
                )
            }
        val customizedAssignments =
            options.assignListeners().fold(captureInitialPositions) { currentOptions, listener ->
                currentOptions.addAssignListener(listener)
            }
        return customizedAssignments.addAssignListener { partitions ->
            val initial = checkNotNull(initialPositions.getAndSet(null)) {
                "Kafka initial positions are unavailable during partition assignment."
            }
            val safePositions = partitions.associate { partition ->
                val topicPartition = partition.topicPartition()
                topicPartition to minOf(
                    initial.getValue(topicPartition),
                    partition.position(),
                )
            }
            onAssigned(
                checkNotNull(consumer.get()) {
                    "Kafka consumer is unavailable during partition assignment."
                },
                safePositions,
            )
        }
    }

    /**
     * Persists a conservative assignment boundary before readiness is published.
     * Forward seeks remain session-local until normal processing commits them,
     * so readiness never advances an existing group offset or skips retained data.
     *
     * Overrides must invoke [completion] exactly once. Assignment callbacks and
     * their completions must preserve the consumer event-loop serialization used
     * by Reactor Kafka.
     */
    protected open fun anchorAssignedPartitions(
        consumer: Consumer<*, *>,
        positions: Map<TopicPartition, Long>,
        completion: (Throwable?) -> Unit,
    ) {
        consumer.anchorAssignedPositions(positions, completion)
    }

    private fun readinessConsumerListener(
        delegate: ConsumerListener?,
        consumer: AtomicReference<Consumer<*, *>?>,
    ): ConsumerListener =
        object : ConsumerListener {
            override fun consumerAdded(id: String, addedConsumer: Consumer<*, *>) {
                delegate?.consumerAdded(id, addedConsumer)
                consumer.set(addedConsumer)
            }

            override fun consumerRemoved(id: String, removedConsumer: Consumer<*, *>) {
                try {
                    delegate?.consumerRemoved(id, removedConsumer)
                } finally {
                    consumer.compareAndSet(removedConsumer, null)
                }
            }
        }

    protected fun encode(message: M): SenderRecord<String, String, Sinks.Empty<Void>> {
        val producerRecord = ProducerRecord(
            /* topic = */
            topicConverter.convert(message),
            /* partition = */
            null,
            /* timestamp = */
            message.createTime,
            /* key = */
            message.aggregateId.id,
            /* value = */
            message.toJsonString(),
        )
        return SenderRecord.create(producerRecord, Sinks.empty())
    }

    private fun decodeRecord(receiverRecord: ReceiverRecord<String, String>): Mono<E> {
        return Mono.fromCallable {
            decode(receiverRecord)
        }.onErrorResume(Exception::class.java) {
            val failure = KafkaRecordDecodeFailure(receiverRecord, it)
            recordDecodeFailureHandler.handle(failure)
                .then(
                    Mono.fromRunnable {
                        receiverRecord.receiverOffset().acknowledge()
                    },
                ).then(Mono.empty())
        }.map {
            it.toExchange(receiverRecord.receiverOffset())
        }
    }

    protected fun decode(receiverRecord: ReceiverRecord<String, String>): M {
        val message = receiverRecord.value().toObject(messageType)
        require(receiverRecord.key() == message.aggregateId.id) {
            "Kafka record key does not match the decoded aggregate id."
        }
        require(receiverRecord.topic() == topicConverter.convert(message)) {
            "Kafka record topic does not match the decoded aggregate."
        }
        return message
    }

    override fun close() {
        log.info {
            "[${this.javaClass.simpleName}] Close KafkaSender."
        }
        sender.close()
    }
}
