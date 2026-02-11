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

import io.lettuce.core.RedisBusyException
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.messaging.getReceiverGroup
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
import java.time.Duration

const val MESSAGE_FIELD = "msg"

abstract class AbstractRedisMessageBus<M, E>(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val topicConverter: AggregateTopicConverter,
    private val pollTimeout: Duration = Duration.ofSeconds(2)
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

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        val options = StreamReceiverOptions.builder().pollTimeout(pollTimeout)
            .build()

        return Flux.deferContextual { contextView ->
            val group = contextView.getReceiverGroup()
            val topics = namedAggregates.map(topicConverter::convert)
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
        options: StreamReceiverOptions<String, MapRecord<String, String, String>>?,
        consumer: Consumer,
        group: String
    ): Flux<E> {
        val streamOffset = StreamOffset.create(topic, ReadOffset.lastConsumed())
        return StreamReceiver.create<String, MapRecord<String, String, String>>(
            redisTemplate.connectionFactory,
            options
        )
            .receive(consumer, streamOffset).map {
                val message = requireNotNull(it.value[MESSAGE_FIELD]).toObject(messageType)
                message.withReadOnly()
                val acknowledgePublisher = streamOps.acknowledge(topic, group, it.id).then()
                message.toExchange(acknowledgePublisher)
            }
    }

    abstract fun M.toExchange(acknowledgePublisher: Mono<Void>): E
}
