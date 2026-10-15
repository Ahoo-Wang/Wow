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

import me.ahoo.wow.api.annotation.InternalWowApi
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How Wow reaches Kafka (decision D31: `kafka-clients` only): the [bootstrapServers], and [properties] passed to
 * every producer and consumer as they are (security, client id, timeouts, batching, ...).
 *
 * A few settings are Wow's and win over [properties], since the transport's guarantees rest on them:
 * - the producer is idempotent with `acks=all` ([producerConfig]): an acknowledged message is on every in-sync
 *   replica, and a retry within the producer writes no duplicate;
 * - a consumer has no group and commits nothing ([consumerConfig]): the runtime assigns partitions by its leases and
 *   keeps the offsets in its checkpoint store (decision D134); an offset no longer retained reads from the earliest;
 *   asking about a topic never creates it (`allow.auto.create.topics=false`), so a topic is made only by its owner,
 *   by an operator or by `wow.kafka.topics.ddl=auto` (decision D168), with the partitions it needs;
 * - keys are strings, values bytes.
 *
 * [pollTimeout] bounds one poll of a source's read, so a cancelled read stops within it. [metadataMaxAge] is the
 * consumers' `metadata.max.age.ms` unless [properties] set it: the longest before a consumer sees partitions added to
 * a topic it reads (decision D168), 30 seconds instead of Kafka's 5 minutes.
 *
 * Fields may be added (decision D216): Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class KafkaOptions @InternalWowApi constructor(
    val bootstrapServers: String,
    val properties: Map<String, String> = emptyMap(),
    val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
    val metadataMaxAge: Duration = DEFAULT_METADATA_MAX_AGE,
) {
    init {
        require(bootstrapServers.isNotBlank()) { "Kafka needs its bootstrap servers." }
        require(pollTimeout.isPositive()) { "The poll timeout is positive." }
        require(metadataMaxAge.isPositive()) { "The metadata's maximum age is positive." }
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
        put(CommonClientConfigs.METADATA_MAX_AGE_CONFIG, metadataMaxAge.inWholeMilliseconds)
        putAll(properties)
        remove(ConsumerConfig.GROUP_ID_CONFIG)
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is KafkaOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "KafkaOptions(bootstrapServers=$bootstrapServers, properties=$properties, pollTimeout=$pollTimeout, " +
            "metadataMaxAge=$metadataMaxAge)"

    private fun valueFields(): List<Any?> = listOf(bootstrapServers, properties, pollTimeout, metadataMaxAge)

    companion object {
        val DEFAULT_POLL_TIMEOUT: Duration = 200.milliseconds
        val DEFAULT_METADATA_MAX_AGE: Duration = 30.seconds
    }
}
