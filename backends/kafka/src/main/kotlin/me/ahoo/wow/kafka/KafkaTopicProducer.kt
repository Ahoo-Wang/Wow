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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * The [TopicProducer] over a Kafka [producer]: made by [create] from [KafkaOptions], an
 * idempotent producer with `acks=all`.
 *
 * [send] hands each key's messages to the broker one at a time (#6782): a key's next message goes only once its
 * previous one was acknowledged, so a message Kafka refuses for good is never overtaken by its key's later ones,
 * which neither the idempotent producer nor `max.in.flight` keeps from being written after a refusal. The first
 * message of every key is handed at once, in the call's order, on a virtual thread since a send may block while the
 * producer fetches metadata or its buffer is full; each key then goes on as its own acknowledgements come, whatever
 * the others wait for. A failure stops its key: its later messages of the call are not sent, while the other keys'
 * go on. [send] returns once every key is done, and throws the failure of the earliest message of the call that
 * failed, Kafka being unreachable or refusing a record for good as the SPI's typed failure ([KafkaFailures]).
 * Messages of distinct keys go at once, as one batch did before; a key costs one acknowledged step per message it
 * has after its first, the producer's `linger.ms` (5 ms by default) plus the round trip. Only a saga's commands derived
 * to one aggregate of another context (`CommandSender.sendAll`) put several messages of one key in a call: a
 * published stream's sink sends one record a call, and a receipt, a timer's command or a routed command one message.
 * A message's partition is the one it names ([TopicMessage.partition]), else its key's (the producer's
 * default partitioner), so the messages of an aggregate keep their order in one partition; the idempotent producer
 * keeps that order through its own retries.
 *
 * [close] flushes what is in flight and closes the producer, waiting at most [closeTimeout]
 * ([KafkaOptions.CLOSE_TIMEOUT]); past it, the producer fails the records it has not delivered, and a warning says so.
 * Their senders had not had them acknowledged, so they send them again.
 */
class KafkaTopicProducer(
    private val producer: Producer<String, ByteArray>,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val closeTimeout: Duration = KafkaOptions.CLOSE_TIMEOUT,
) : TopicProducer, AutoCloseable {

    override suspend fun send(topic: String, messages: List<TopicMessage>) {
        if (messages.isEmpty()) {
            return
        }
        val keys = messages.withIndex().groupBy { it.value.key }.values
        val firsts = withContext(dispatcher) { keys.map { write(topic, it.first().value) } }
        val failures = coroutineScope {
            keys.zip(firsts) { chain, first -> async { failureOf(topic, chain, first) } }.awaitAll()
        }
        failures.filterNotNull().minByOrNull { it.index }?.let { throw it.value }
    }

    /**
     * The failure of [chain], one key's messages with their places in the call, whose first was handed as [first]: each
     * next one is handed once the previous one was acknowledged, until one fails.
     */
    private suspend fun failureOf(
        topic: String,
        chain: List<IndexedValue<TopicMessage>>,
        first: Deferred<Throwable?>,
    ): IndexedValue<Throwable>? {
        var outcome = first.await()
        var index = chain.first().index
        for (next in chain.drop(1)) {
            if (outcome != null) {
                break
            }
            outcome = withContext(dispatcher) { write(topic, next.value) }.await()
            index = next.index
        }
        return outcome?.let { IndexedValue(index, it) }
    }

    /** Hands [message] to the producer: its outcome is null once the broker acknowledged it, else its failure. */
    private fun write(topic: String, message: TopicMessage): Deferred<Throwable?> {
        val outcome = CompletableDeferred<Throwable?>()
        val record = ProducerRecord(
            topic,
            message.partition,
            message.key,
            message.value,
            KafkaHeaders.of(message.headers),
        )
        producer.send(record) { _, failure -> outcome.complete(failure?.let(KafkaFailures::translate)) }
        return outcome
    }

    override fun close() {
        val closing = TimeSource.Monotonic.markNow()
        producer.close(closeTimeout.toJavaDuration())
        if (closing.elapsedNow() >= closeTimeout) {
            log.warn(
                "The Kafka producer's close took its whole bound of {}: it failed the records it had not delivered, " +
                    "which their senders send again.",
                closeTimeout,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(KafkaTopicProducer::class.java)

        /** A producer of [options]' configuration ([KafkaOptions.producerConfig]). */
        fun create(options: KafkaOptions): KafkaTopicProducer =
            KafkaTopicProducer(KafkaProducer<String, ByteArray>(options.producerConfig()))
    }
}
