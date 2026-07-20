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
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.stream.StreamReceiver
import org.springframework.data.redis.stream.StreamReceiver.StreamReceiverOptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SynchronousSink
import tools.jackson.core.JacksonException
import java.time.Duration

const val MESSAGE_FIELD = "msg"

abstract class AbstractRedisMessageBus<M, E>(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val topicConverter: AggregateTopicConverter,
    private val pollTimeout: Duration = Duration.ofSeconds(2),
    private val recoveryOptions: RedisStreamRecoveryOptions = RedisStreamRecoveryOptions.DEFAULT,
    private val messageBusObserver: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
) : DistributedMessageBus<M, E>
    where M : Message<*, *>, M : AggregateIdCapable, M : NamedAggregate, E : MessageExchange<*, M> {
    private val streamOps = redisTemplate.opsForStream<String, String>()
    abstract val messageType: Class<M>
    override fun send(message: M): Mono<Void> {
        return Mono.defer {
            message.withReadOnly()
            val topic = topicConverter.convert(message)
            streamOps.add(topic, mapOf(MESSAGE_FIELD to message.toJsonString())).then()
        }
    }

    override fun receive(subscription: MessageSubscription): Flux<E> {
        val options = StreamReceiverOptions.builder().pollTimeout(pollTimeout)
            .build()

        return Flux.defer {
            val group = subscription.receiverGroup
            val topics = subscription.namedAggregates.map(topicConverter::convert)
            val createGroupPublisher = topics.map { topic ->
                createGroup(topic, group)
            }.let { publishers ->
                Mono.zip(publishers) {
                    it
                }.then()
            }
            val consumer = Consumer.from(group, GlobalIdGenerator.generateAsString())
            val streamOffsets = topics.map { topic ->
                receive(topic, options, consumer, group)
            }
            val readPublisher = Flux.merge(streamOffsets)
            createGroupPublisher.thenMany(readPublisher)
        }
    }

    private fun createGroup(topic: String, group: String) = streamOps.createGroup(topic, ReadOffset.latest(), group)
        .onErrorResume {
            if (it.cause is RedisBusyException) {
                Mono.empty()
            } else {
                Mono.error(it)
            }
        }

    private fun receive(
        topic: String,
        options: StreamReceiverOptions<String, MapRecord<String, String, String>>,
        consumer: Consumer,
        group: String
    ): Flux<E> {
        val streamOffset = StreamOffset.create(topic, ReadOffset.lastConsumed())
        val liveRecords = StreamReceiver.create(
            redisTemplate.connectionFactory,
            options
        )
            .receive(consumer, streamOffset)
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
        return records.handle<E> { record, sink ->
            record.decode(topic, group, consumer.name, sink)
        }
    }

    private fun MapRecord<String, String, String>.decode(
        topic: String,
        group: String,
        consumerName: String,
        sink: SynchronousSink<E>,
    ) {
        val encodedMessage = value[MESSAGE_FIELD]
        if (encodedMessage == null) {
            reportDecodeFailure(
                failure = RedisMessageBusObservation.RecordDecodeFailed(
                    topic = topic,
                    consumerGroup = group,
                    recordId = id.value,
                    messageType = messageType.name,
                    reason = RedisRecordDecodeFailureReason.MISSING_MESSAGE_FIELD,
                    failureType = null,
                ),
                consumerName = consumerName,
            )
            return
        }
        try {
            val message = encodedMessage.toObject(messageType)
            message.withReadOnly()
            val acknowledgePublisher = streamOps.acknowledge(topic, group, id).then()
            sink.next(message.toExchange(acknowledgePublisher))
        } catch (failure: JacksonException) {
            reportDecodeFailure(
                failure = RedisMessageBusObservation.RecordDecodeFailed(
                    topic = topic,
                    consumerGroup = group,
                    recordId = id.value,
                    messageType = messageType.name,
                    reason = RedisRecordDecodeFailureReason.DESERIALIZATION_FAILED,
                    failureType = failure.javaClass.name,
                ),
                consumerName = consumerName,
            )
        }
    }

    private fun reportDecodeFailure(
        failure: RedisMessageBusObservation.RecordDecodeFailed,
        consumerName: String,
    ) {
        messageBusObserver.notifySafely(failure)
        log.error {
            "Failed to decode Redis Stream record [${failure.recordId}] from topic [${failure.topic}] " +
                "for consumer group [${failure.consumerGroup}] as consumer [$consumerName] " +
                "with message type [${failure.messageType}], reason [${failure.reason}], " +
                "and failure type [${failure.failureType}]. " +
                "The record remains pending; its payload was omitted from this log."
        }
    }

    abstract fun M.toExchange(acknowledgePublisher: Mono<Void>): E

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
