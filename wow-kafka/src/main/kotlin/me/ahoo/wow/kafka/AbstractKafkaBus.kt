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

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.getReceiverGroup
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOffset
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderRecord
import reactor.util.concurrent.Queues

abstract class AbstractKafkaBus<M, E>(
    private val sender: KafkaSender<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : MessageBus where M : Message<*>, M : AggregateIdCapable, M : NamedAggregate {
    companion object {
        private val log = LoggerFactory.getLogger(AbstractKafkaBus::class.java)
    }

    abstract val messageType: Class<M>
    protected fun sendMessage(message: M): Mono<Void> {
        val senderRecord = encode(message)
        return sender.send(Mono.just(senderRecord))
            .doOnNext {
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

    abstract fun NamedAggregate.asTopic(): String
    abstract fun M.asExchange(receiverOffset: ReceiverOffset): E

    protected fun receiveMessage(namedAggregates: Set<NamedAggregate>): Flux<E> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(receiverOptions)
                .consumerProperty(
                    ConsumerConfig.GROUP_ID_CONFIG,
                    contextView.getReceiverGroup(),
                )
                .subscription(namedAggregates.map { it.asTopic() }.toSet())

            val customizedOptions = contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options
            KafkaReceiver.create(customizedOptions)
                .receive(Queues.SMALL_BUFFER_SIZE)
                .retryWhen(DEFAULT_RECEIVE_RETRY_SPEC)
                .mapNotNull {
                    val message = decode(it) ?: return@mapNotNull null
                    message.asExchange(it.receiverOffset())
                }
        }
    }

    protected fun encode(message: M): SenderRecord<String, String, Sinks.Empty<Void>> {
        val producerRecord = ProducerRecord(
            /* topic = */ message.asTopic(),
            /* partition = */ null,
            /* timestamp = */ message.createTime,
            /* key = */ message.aggregateId.id,
            /* value = */ message.asJsonString(),
        )
        return SenderRecord.create(producerRecord, Sinks.empty())
    }

    @Suppress("TooGenericExceptionCaught")
    protected fun decode(receiverRecord: ReceiverRecord<String, String>): M? {
        return try {
            receiverRecord.value().asObject(messageType)
        } catch (e: Throwable) {
            if (log.isErrorEnabled) {
                log.error("Failed to decode ReceiverRecord[$receiverRecord].", e)
            }
            null
        }
    }
}
