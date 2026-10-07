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
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.messaging.transport.TransportMessage
import me.ahoo.wow.messaging.transport.TransportReceiver
import me.ahoo.wow.messaging.transport.TransportRecord
import me.ahoo.wow.runtime.RuntimeResource
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.receiver.KafkaReceiver
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

internal fun <K, V> ReceiverOptions<K, V>.withReceiverPolicy(policy: KafkaReceiverPolicy): ReceiverOptions<K, V> =
    maxDeferredCommits(policy.maxDeferredCommits)

/**
 * Caps `commitBatchSize` at `maxDeferredCommits`: the consumer stops polling once that many acknowledged offsets are
 * waiting, so a commit must be due by then.
 */
internal fun <K, V> ReceiverOptions<K, V>.withCommitBeforePause(
    onCapped: (commitBatchSize: Int, maxDeferredCommits: Int) -> Unit = { _, _ -> },
): ReceiverOptions<K, V> {
    val maxDeferredCommits = maxDeferredCommits()
    val commitBatchSize = commitBatchSize()
    if (maxDeferredCommits <= 0 || commitBatchSize in 1..maxDeferredCommits) {
        return this
    }
    if (commitBatchSize > maxDeferredCommits) {
        onCapped(commitBatchSize, maxDeferredCommits)
    }
    return commitBatchSize(maxDeferredCommits)
}

/**
 * Kafka as a [Transport]: one producer per transport, one consumer per [open].
 *
 * A record's key, timestamp and value are the [TransportMessage]'s, with no headers. [open] subscribes the consumer
 * [group] to the topics and completes readiness only after every assigned partition's position has been committed
 * as the group's offset ([anchorAssignedPartitions]), so the first assignment never skips records published after
 * readiness. Receive errors are retried with [failurePolicy].
 */
@WowSpi
open class KafkaTransport(
    private val senderOptions: SenderOptions<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer,
    private val receiverPolicy: KafkaReceiverPolicy = KafkaReceiverPolicy(),
    private val failurePolicy: TransportFailurePolicy = TransportFailurePolicy.DEFAULT,
) : Transport {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val sender: KafkaSender<String, String> = KafkaSender.create(senderOptions)

    /**
     * The producer as a runtime resource: the runtime closes it, flushing the records it buffered, after its
     * dispatchers stop and within the same shutdown deadline ([me.ahoo.wow.runtime.RuntimeResources]). Closing is
     * idempotent, so a later [close] returns at once. The Kafka client cannot cancel a running close: on force stop
     * the runtime stops waiting for it.
     */
    override val runtimeResource: RuntimeResource = object : RuntimeResource {
        override fun stopGracefully(): Mono<Void> = Mono.fromRunnable(this@KafkaTransport::close)

        override fun forceStop() = Unit

        override fun toString(): String = "${this@KafkaTransport.javaClass.simpleName}.sender"
    }

    override fun send(message: TransportMessage): Mono<Void> =
        sender.send(Mono.just(SenderRecord.create(message.toProducerRecord(), null as Void?)))
            .next()
            .flatMap { result ->
                result.exception()?.let { Mono.error<Void>(it) } ?: Mono.empty()
            }

    protected open fun createReceiver(
        receiverOptions: ReceiverOptions<String, String>,
    ): KafkaReceiver<String, String> {
        return KafkaReceiver.create(receiverOptions)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun open(group: String, topics: Set<String>): TransportReceiver {
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
        val records = streamRecords(group, topics) { consumer, positions ->
            if (positions.isEmpty()) {
                if (pendingAnchors.get() == 0L) {
                    completeReadiness()
                }
                return@streamRecords
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
        return object : TransportReceiver {
            override val records: Flux<TransportRecord> = records
            override val readiness: Mono<Void> = readiness.asMono()

            // Without demand Reactor Kafka pauses the assigned partitions and keeps polling, so the consumer stays in
            // the group and still commits acknowledged offsets; offsets of records not handed over are not committed.
            override val durable: Boolean
                get() = true
        }
    }

    private fun streamRecords(
        group: String,
        topics: Set<String>,
        onAssigned: KafkaAssignmentListener,
    ): Flux<TransportRecord> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(
                receiverOptions.withReceiverPolicy(receiverPolicy),
            )
                .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, group)
                .subscription(topics)
            val customizedOptions = (contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options)
                .withCommitBeforePause(::logCommitBatchSizeCapped)
            failurePolicy.retryReceive(
                createReceiver(readinessReceiverOptions(customizedOptions, onAssigned))
                    .receive(receiverPolicy.prefetchBatches),
            ).map<TransportRecord>(::KafkaTransportRecord)
        }
    }

    private val commitBatchSizeCapLogged = AtomicBoolean()

    private fun logCommitBatchSizeCapped(commitBatchSize: Int, maxDeferredCommits: Int) {
        if (commitBatchSizeCapLogged.compareAndSet(false, true)) {
            log.info {
                "[${this.javaClass.simpleName}] Cap commitBatchSize[$commitBatchSize] at " +
                    "maxDeferredCommits[$maxDeferredCommits]: the consumer stops polling at that many " +
                    "acknowledged offsets, so a commit must start by then."
            }
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

    override fun close() {
        log.info {
            "[${this.javaClass.simpleName}] Close KafkaSender."
        }
        sender.close()
    }
}

/**
 * The Kafka record of [this] message: no partition (the key's), no headers.
 */
internal fun TransportMessage.toProducerRecord(): ProducerRecord<String, String> =
    ProducerRecord(
        /* topic = */
        topic,
        /* partition = */
        null,
        /* timestamp = */
        timestamp,
        /* key = */
        key,
        /* value = */
        payload,
    )

/**
 * A received Kafka record; acknowledging it marks its offset for the next commit.
 */
internal class KafkaTransportRecord(
    private val record: ReceiverRecord<String, String>,
) : TransportRecord {
    override val topic: String
        get() = record.topic()
    override val key: String?
        get() = record.key()
    override val payload: String?
        get() = record.value()
    override val id: String
        get() = "${record.partition()}-${record.offset()}"

    override fun ack(): Mono<Void> =
        Mono.fromRunnable {
            record.receiverOffset().acknowledge()
        }
}
