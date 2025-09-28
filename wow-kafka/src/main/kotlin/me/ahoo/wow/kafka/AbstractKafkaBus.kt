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
import me.ahoo.wow.messaging.getReceiverGroup
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord
import reactor.util.concurrent.Queues

abstract class AbstractKafkaBus<M, E>(
    private val topicConverter: AggregateTopicConverter,
    private val senderOptions: SenderOptions<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : DistributedMessageBus<M, E>
    where M : Message<*, *>, M : AggregateIdCapable, M : NamedAggregate, E : MessageExchange<*, M> {
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
                        log.error(error) {
                            "Failed to send message[$message]."
                        }
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

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(receiverOptions)
                .consumerProperty(
                    ConsumerConfig.GROUP_ID_CONFIG,
                    contextView.getReceiverGroup(),
                )
                .subscription(namedAggregates.map { topicConverter.convert(it) }.toSet())
            val customizedOptions = contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options
            KafkaReceiver.create(customizedOptions)
                .receive(Queues.SMALL_BUFFER_SIZE)
                .retryWhen(DEFAULT_RECEIVE_RETRY_SPEC)
                .mapNotNull {
                    val message = decode(it) ?: return@mapNotNull null
                    message.toExchange(it.receiverOffset())
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

    @Suppress("TooGenericExceptionCaught")
    protected fun decode(receiverRecord: ReceiverRecord<String, String>): M? {
        return try {
            receiverRecord.value().toObject(messageType)
        } catch (e: Throwable) {
            log.error(e) {
                "Failed to decode ReceiverRecord[$receiverRecord]."
            }
            null
        }
    }

    override fun close() {
        log.info {
            "[${this.javaClass.simpleName}] Close KafkaSender."
        }
        sender.close()
    }
}
