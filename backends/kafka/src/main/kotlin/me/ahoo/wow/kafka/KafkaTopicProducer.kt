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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord

/**
 * The [TopicProducer] over a Kafka [producer]: made by [create] from [KafkaOptions], an
 * idempotent producer with `acks=all`.
 *
 * [send] hands every message to the producer in order, on a virtual thread since a send may block while the producer
 * fetches metadata or its buffer is full, then suspends until the broker acknowledged each one; it throws the first
 * failure, Kafka being unreachable or refusing a record for good as the SPI's typed failure ([KafkaFailures]).
 * A message's partition is the one it names ([TopicMessage.partition]), else its key's (the producer's
 * default partitioner), so the messages of an aggregate keep their order in one partition; the idempotent producer
 * keeps that order through its own retries.
 *
 * [close] flushes what is in flight and closes the producer.
 */
class KafkaTopicProducer(
    private val producer: Producer<String, ByteArray>,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : TopicProducer, AutoCloseable {
    override suspend fun send(topic: String, messages: List<TopicMessage>) {
        if (messages.isEmpty()) {
            return
        }
        val acknowledgements = withContext(dispatcher) {
            messages.map { message ->
                val acknowledged = CompletableDeferred<Unit>()
                val record = ProducerRecord(
                    topic,
                    message.partition,
                    message.key,
                    message.value,
                    KafkaHeaders.of(message.headers)
                )
                producer.send(record) { _, failure ->
                    if (failure == null) {
                        acknowledged.complete(Unit)
                    } else {
                        acknowledged.completeExceptionally(KafkaFailures.translate(failure))
                    }
                }
                acknowledged
            }
        }
        acknowledgements.awaitAll()
    }

    override fun close() {
        producer.close()
    }

    companion object {
        /** A producer of [options]' configuration ([KafkaOptions.producerConfig]). */
        fun create(options: KafkaOptions): KafkaTopicProducer =
            KafkaTopicProducer(KafkaProducer<String, ByteArray>(options.producerConfig()))
    }
}
