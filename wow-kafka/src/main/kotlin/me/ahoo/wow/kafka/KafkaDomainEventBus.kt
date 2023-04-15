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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.messaging.getReceiverGroup
import me.ahoo.wow.serialization.asJsonString
import me.ahoo.wow.serialization.asObject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.receiver.ReceiverRecord
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderRecord

class KafkaDomainEventBus(
    private val sender: KafkaSender<String, String>,
    private val receiverOptions: ReceiverOptions<String, String>,
    private val topicPrefix: String = Wow.WOW_PREFIX,
    private val receiverOptionsCustomizer: ReceiverOptionsCustomizer = NoOpReceiverOptionsCustomizer
) : DomainEventBus {
    companion object {
        private val log = LoggerFactory.getLogger(KafkaDomainEventBus::class.java)
    }

    override fun send(eventStream: DomainEventStream): Mono<Void> {
        return Mono.defer {
            val senderRecord = encode(eventStream)
            sender.send(Mono.just(senderRecord))
                .doOnError {
                    if (log.isErrorEnabled) {
                        log.error("Send eventStream[${eventStream.id}] failed!", it)
                    }
                }
                .then()
        }
    }

    /**
     * `DomainEventBus` 为发布订阅模式,下游订阅者自定义 `GroupId`
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> {
        return Flux.deferContextual { contextView ->
            val options = receiverOptionsCustomizer.customize(receiverOptions)
                .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, contextView.getReceiverGroup())
                .subscription(namedAggregates.map { it.asEventStreamTopic(topicPrefix) }.toSet())
            val customizedOptions = contextView.getReceiverOptionsCustomizer()?.customize(options) ?: options
            KafkaReceiver.create(customizedOptions)
                .receive()
                .retryWhen(DEFAULT_RECEIVE_RETRY_SPEC)
                .mapNotNull {
                    val eventStream = decode(it) ?: return@mapNotNull null
                    KafkaEventStreamExchange(eventStream, it.receiverOffset())
                }
        }
    }

    private fun encode(domainEventStream: DomainEventStream): SenderRecord<String, String, String> {
        val producerRecord = ProducerRecord(
            /* topic = */ domainEventStream.asEventStreamTopic(topicPrefix),
            /* partition = */ null,
            /* timestamp = */ domainEventStream.createTime,
            /* key = */ domainEventStream.aggregateId.id,
            /* value = */ domainEventStream.asJsonString(),
        )
        return SenderRecord.create(producerRecord, domainEventStream.id)
    }

    private fun decode(receiverRecord: ReceiverRecord<String, String>): DomainEventStream? {
        return try {
            receiverRecord.value().asObject<DomainEventStream>()
        } catch (e: Throwable) {
            if (log.isErrorEnabled) {
                log.error("Failed to decode ReceiverRecord[$receiverRecord].", e)
            }
            null
        }
    }
}
