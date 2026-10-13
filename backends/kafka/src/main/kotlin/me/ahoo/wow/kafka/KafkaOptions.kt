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

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How Wow reaches Kafka (decision D31: `kafka-clients` only): the [bootstrapServers], and [properties] passed to
 * every producer and consumer as they are (security, client id, timeouts, batching, ...).
 *
 * A few settings are Wow's and win over [properties], since the transport's guarantees rest on them:
 * - the producer is idempotent with `acks=all` ([producerConfig]): an acknowledged message is on every in-sync
 *   replica, and a retry within the producer writes no duplicate;
 * - a consumer has no group and commits nothing ([consumerConfig]): the runtime assigns partitions by its leases and
 *   keeps the offsets in its checkpoint store (decision D134); an offset no longer retained reads from the earliest;
 * - keys are strings, values bytes.
 *
 * [pollTimeout] bounds one poll of a source's read, so a cancelled read stops within it.
 */
data class KafkaOptions(
    val bootstrapServers: String,
    val properties: Map<String, String> = emptyMap(),
    val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
) {
    init {
        require(bootstrapServers.isNotBlank()) { "Kafka needs its bootstrap servers." }
        require(pollTimeout.isPositive()) { "The poll timeout is positive." }
    }

    /** The configuration of a producer. */
    fun producerConfig(): Map<String, Any> = buildMap {
        putAll(properties)
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java)
    }

    /** The configuration of a consumer. */
    fun consumerConfig(): Map<String, Any> = buildMap {
        putAll(properties)
        remove(ConsumerConfig.GROUP_ID_CONFIG)
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
    }

    companion object {
        val DEFAULT_POLL_TIMEOUT: Duration = 200.milliseconds
    }
}
