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

package me.ahoo.wow.redis.bus

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisBusyException
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.transport.Transport
import me.ahoo.wow.messaging.transport.TransportDecodeFailure
import me.ahoo.wow.messaging.transport.TransportDecodeFailureAction
import me.ahoo.wow.messaging.transport.TransportDecodeFailureHandler
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import me.ahoo.wow.messaging.transport.TransportMessage
import me.ahoo.wow.messaging.transport.TransportReceiver
import me.ahoo.wow.messaging.transport.TransportRecord
import me.ahoo.wow.messaging.transport.TransportRecordMismatchException
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.stream.StreamReceiver
import org.springframework.data.redis.stream.StreamReceiver.StreamReceiverOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

const val MESSAGE_FIELD = "msg"

internal fun Throwable.isBusyGroup(): Boolean =
    generateSequence(this) { error -> error.cause }
        .filterIsInstance<RedisBusyException>()
        .any { error ->
            error.message
                ?.trimStart()
                ?.takeWhile { character -> !character.isWhitespace() } == "BUSYGROUP"
        }

/**
 * Redis Streams as a [Transport]: a topic is a stream, a message is one entry whose [MESSAGE_FIELD] field holds the
 * payload (keys, timestamps and headers are not written).
 *
 * [open] creates the consumer group at the stream's end (`$`) for every topic that lacks one, reaps idle consumers
 * when [RedisStreamRetentionOptions.consumerIdleTimeout] is set, then completes readiness; reading starts only once
 * processing opens. With recovery enabled, entries left pending by consumers whose lease expired are claimed and
 * delivered again ([RedisStreamRecoveryOptions]).
 *
 * A failed receive stream is subscribed again according to [failurePolicy] (as a new consumer of the same group,
 * whose leftover pending entries recovery claims); readiness fails, and the dispatcher sees the error, only once the
 * policy's retries are exhausted.
 */
@WowSpi
class RedisStreamTransport(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val pollTimeout: Duration = Duration.ofSeconds(2),
    private val recoveryOptions: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions.DEFAULT,
    private val messageBusObserver: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
    private val retentionOptions: RedisStreamRetentionOptions = RedisStreamRetentionOptions.DEFAULT,
    private val failurePolicy: TransportFailurePolicy = TransportFailurePolicy.DEFAULT,
) : Transport {
    private val streamOps = redisTemplate.opsForStream<String, String>()
    private val consumerReaper = RedisStreamConsumerReaper(redisTemplate)

    override fun send(message: TransportMessage): Mono<Void> =
        Mono.defer {
            val entry = mapOf(MESSAGE_FIELD to message.payload)
            if (retentionOptions.trims) {
                streamOps.add(message.topic, entry, retentionOptions.addOptions(System.currentTimeMillis())).then()
            } else {
                streamOps.add(message.topic, entry).then()
            }
        }

    override fun open(group: String, topics: Set<String>): TransportReceiver {
        val readiness = Sinks.empty<Void>()
        val readAdmission = Sinks.empty<Void>()
        val readinessTerminated = AtomicBoolean()
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
        val records = streamRecords(group, topics, ::completeReadiness, readAdmission)
            .doOnError(::failReadiness)
            .doOnCancel {
                failReadiness(
                    CancellationException("Redis receiver initialization was cancelled."),
                )
            }
        return object : TransportReceiver {
            override val records: Flux<TransportRecord> = records
            override val readiness: Mono<Void> = readiness.asMono()

            // Without demand the stream receiver stops reading; entries read but not handed over stay pending.
            override val durable: Boolean
                get() = true

            override fun openProcessing() {
                readAdmission.tryEmitEmpty()
            }
        }
    }

    private fun streamRecords(
        group: String,
        topics: Set<String>,
        onReady: () -> Unit,
        readAdmission: Sinks.Empty<Void>,
    ): Flux<TransportRecord> {
        val options = StreamReceiverOptions.builder().pollTimeout(pollTimeout)
            .build()

        val records = Flux.defer {
            val createGroupPublisher = topics.map { topic ->
                createGroup(topic, group).then(reapIdleConsumers(topic, group))
            }.let { publishers ->
                Flux.concat(publishers).then()
            }
            val consumer = Consumer.from(group, GlobalIdGenerator.generateAsString())
            val streamOffsets = topics.map { topic ->
                receive(topic, options, consumer, group)
            }
            val readPublisher = Flux.merge(streamOffsets)
            createGroupPublisher
                .doOnSuccess {
                    onReady()
                }
                .thenMany(
                    readAdmission.asMono()
                        .thenMany(readPublisher),
                )
        }
        return failurePolicy.retryReceive(records)
    }

    private fun createGroup(topic: String, group: String) = streamOps.createGroup(topic, ReadOffset.latest(), group)
        .onErrorResume {
            if (it.isBusyGroup()) {
                Mono.empty()
            } else {
                Mono.error(it)
            }
        }

    private fun reapIdleConsumers(topic: String, group: String): Mono<Void> {
        val idleTimeout = retentionOptions.consumerIdleTimeout ?: return Mono.empty()
        return consumerReaper.reap(topic, group, idleTimeout).then()
    }

    private fun receive(
        topic: String,
        options: StreamReceiverOptions<String, MapRecord<String, String, String>>,
        consumer: Consumer,
        group: String
    ): Flux<TransportRecord> {
        val streamOffset = StreamOffset.create(topic, ReadOffset.lastConsumed())
        // One connection for all of this stream's blocking reads, instead of a new one per read.
        val liveRecords = Flux.usingWhen(
            Mono.fromSupplier { RedisStreamReadConnectionFactory(redisTemplate.connectionFactory) },
            { connectionFactory -> StreamReceiver.create(connectionFactory, options).receive(consumer, streamOffset) },
            RedisStreamReadConnectionFactory::closeLater,
        )
        val records = if (recoveryOptions.enabled) {
            val leaseRegistry = DefaultRedisConsumerLeaseRegistry(redisTemplate, recoveryOptions)
            val leasedLiveRecords = leaseRegistry.withLease(
                topic = topic,
                consumer = consumer,
                source = liveRecords,
            )
            val recoveredRecords = RedisPendingMessageRecoverer(
                streamOps = streamOps,
                scanner = DefaultRedisPendingMessageScanner(
                    redisTemplate = redisTemplate,
                    streamOps = streamOps,
                    observer = messageBusObserver,
                ),
                leaseRegistry = leaseRegistry,
                options = recoveryOptions,
                observer = messageBusObserver,
            ).recover(topic, consumer)
            Flux.merge(
                leasedLiveRecords,
                recoveredRecords,
            )
        } else {
            liveRecords
        }
        return records.map { record ->
            RedisTransportRecord(
                topic = topic,
                group = group,
                consumerName = consumer.name,
                record = record,
            )
        }
    }

    /**
     * A received stream entry; acknowledging it sends `XACK` for its group.
     */
    inner class RedisTransportRecord internal constructor(
        override val topic: String,
        val group: String,
        val consumerName: String,
        private val record: MapRecord<String, String, String>,
    ) : TransportRecord {
        override val key: String?
            get() = null
        override val keyed: Boolean
            get() = false
        override val payload: String?
            get() = record.value[MESSAGE_FIELD]
        override val id: String
            get() = record.id.value

        override fun ack(): Mono<Void> = streamOps.acknowledge(topic, group, record.id).then()
    }
}

/**
 * The Redis decode-failure policy: report the record to the [observer] and the log (without its payload) and leave it
 * pending, so the consumer continues with the next entry.
 */
class RedisRecordDecodeFailureHandler(
    private val observer: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
) : TransportDecodeFailureHandler {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun handle(failure: TransportDecodeFailure): Mono<TransportDecodeFailureAction> =
        Mono.fromSupplier {
            val record = failure.record
            val missingPayload = record.payload == null
            val observation = RedisMessageBusObservation.RecordDecodeFailed(
                topic = record.topic,
                consumerGroup = failure.group,
                recordId = record.id,
                messageType = failure.messageType.name,
                reason = when {
                    missingPayload -> RedisRecordDecodeFailureReason.MISSING_MESSAGE_FIELD
                    failure.cause is TransportRecordMismatchException -> RedisRecordDecodeFailureReason.TOPIC_MISMATCH
                    else -> RedisRecordDecodeFailureReason.DESERIALIZATION_FAILED
                },
                failureType = if (missingPayload) null else failure.cause.javaClass.name,
            )
            observer.notifySafely(observation)
            val consumerName = (record as? RedisStreamTransport.RedisTransportRecord)?.consumerName
            log.error {
                "Failed to decode Redis Stream record [${observation.recordId}] from topic [${observation.topic}] " +
                    "for consumer group [${observation.consumerGroup}] as consumer [$consumerName] " +
                    "with message type [${observation.messageType}], reason [${observation.reason}], " +
                    "and failure type [${observation.failureType}]. " +
                    "The record remains pending; its payload was omitted from this log."
            }
            TransportDecodeFailureAction.LEAVE_PENDING
        }
}
