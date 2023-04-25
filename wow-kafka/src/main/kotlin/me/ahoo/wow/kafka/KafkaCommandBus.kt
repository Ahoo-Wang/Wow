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

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.getReceiverGroupOrDefault
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderRecord
import reactor.util.concurrent.Queues
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration

internal val DEFAULT_RECEIVE_RETRY_SPEC: RetryBackoffSpec = Retry.backoff(3, Duration.ofSeconds(10))

class KafkaCommandBus(
    private val sender: KafkaSender<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val topicPrefix: String = Wow.WOW_PREFIX,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : CommandBus {
    companion object {
        const val DEFAULT_RECEIVER_GROUP = "Wow-AggregateProcessor"
        private val log = LoggerFactory.getLogger(KafkaCommandBus::class.java)
    }

    override fun <C : Any> send(command: CommandMessage<C>): Mono<Void> {
        val senderRecord = encode(command)
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

    /**
     * `CommandBus` 为消息队列模式，所以所有消费者共用同一个 `GroupId` .
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(receiverOptions)
                .consumerProperty(
                    ConsumerConfig.GROUP_ID_CONFIG,
                    contextView.getReceiverGroupOrDefault(DEFAULT_RECEIVER_GROUP),
                )
                .subscription(namedAggregates.map { it.asCommandTopic(topicPrefix) }.toSet())

            val customizedOptions = contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options
            KafkaReceiver.create(customizedOptions)
                .receive(Queues.SMALL_BUFFER_SIZE)
                .retryWhen(DEFAULT_RECEIVE_RETRY_SPEC)
                .mapNotNull {
                    val commandMessage = decode(it) ?: return@mapNotNull null
                    KafkaServerCommandExchange(commandMessage, it.receiverOffset())
                }
        }
    }

    private fun encode(commandMessage: CommandMessage<*>): SenderRecord<String, String, Sinks.Empty<Void>> {
        val producerRecord = ProducerRecord(
            /* topic = */ commandMessage.asCommandTopic(topicPrefix),
            /* partition = */ null,
            /* timestamp = */ commandMessage.createTime,
            /* key = */ commandMessage.aggregateId.id,
            /* value = */ commandMessage.asJsonString(),
        )
        return SenderRecord.create(producerRecord, Sinks.empty())
    }

    private fun decode(receiverRecord: ReceiverRecord<String, String>): CommandMessage<Any>? {
        return try {
            receiverRecord.value().asObject()
        } catch (e: Throwable) {
            if (log.isErrorEnabled) {
                log.error("Failed to decode ReceiverRecord[$receiverRecord].", e)
            }
            null
        }
    }
}
