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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.transport.TopicAdmin
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import java.util.Optional
import java.util.concurrent.ExecutionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The [TopicAdmin] over a Kafka [admin] client (decision D168), made by [create] from [KafkaOptions]:
 *
 * - [partitions] describes the topics, a topic the broker does not know left out; it never creates one.
 * - [create] creates the missing topics with [replicationFactor] (the broker's `default.replication.factor` when
 *   null) and the topic [configs] (`retention.ms`, `min.insync.replicas`, ...); a topic that exists already is left
 *   as it is. It then waits, at most [visibleWithin], until every topic can be described, as metadata reaches the
 *   brokers a moment after the controller created it.
 * - [durability] reads what a topic's writes survive (decision D143, review A3-15): its replication factor and its
 *   `min.insync.replicas`, which an `acks=all` producer's acknowledgement rests on.
 *
 * The admin's blocking futures are awaited on virtual threads. [close] closes the admin client.
 */
class KafkaTopicAdmin(
    private val admin: Admin,
    private val replicationFactor: Short? = null,
    private val configs: Map<String, String> = emptyMap(),
    private val visibleWithin: Duration = DEFAULT_VISIBLE_WITHIN,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : TopicAdmin, AutoCloseable {
    init {
        require(replicationFactor == null || replicationFactor > 0) { "A topic has at least one replica." }
    }

    override suspend fun partitions(topics: Set<String>): Map<String, Int> {
        if (topics.isEmpty()) {
            return emptyMap()
        }
        return withContext(dispatcher) {
            admin.describeTopics(topics).topicNameValues().mapNotNull { (topic, description) ->
                try {
                    topic to description.get().partitions().size
                } catch (failure: ExecutionException) {
                    if (failure.cause !is UnknownTopicOrPartitionException) {
                        throw failure.cause ?: failure
                    }
                    null
                }
            }.toMap()
        }
    }

    override suspend fun create(topics: Map<String, Int>) {
        if (topics.isEmpty()) {
            return
        }
        val requested = topics.map { (topic, partitions) ->
            NewTopic(topic, Optional.of(partitions), Optional.ofNullable(replicationFactor)).configs(configs)
        }
        withContext(dispatcher) {
            admin.createTopics(requested).values().values.forEach { created ->
                try {
                    created.get()
                } catch (failure: ExecutionException) {
                    if (failure.cause !is TopicExistsException) {
                        throw failure.cause ?: failure
                    }
                }
            }
        }
        awaitVisible(topics.keys)
    }

    /**
     * The replication factor and `min.insync.replicas` of each of [topics] the broker knows (decision D143): what an
     * `acks=all` write to it survives. A topic the broker does not know is left out.
     */
    suspend fun durability(topics: Set<String>): Map<String, TopicDurability> {
        if (topics.isEmpty()) {
            return emptyMap()
        }
        return withContext(dispatcher) {
            val replicas = admin.describeTopics(topics).topicNameValues().mapNotNull { (topic, description) ->
                try {
                    topic to description.get().partitions().minOf { it.replicas().size }
                } catch (failure: ExecutionException) {
                    if (failure.cause !is UnknownTopicOrPartitionException) {
                        throw failure.cause ?: failure
                    }
                    null
                }
            }.toMap()
            val resources = replicas.keys.map { ConfigResource(ConfigResource.Type.TOPIC, it) }
            val configs = if (resources.isEmpty()) emptyMap() else admin.describeConfigs(resources).all().get()
            replicas.mapValues { (topic, factor) ->
                val minInsync = configs[ConfigResource(ConfigResource.Type.TOPIC, topic)]
                    ?.get(MIN_INSYNC_REPLICAS)?.value()?.toIntOrNull()
                TopicDurability(factor, minInsync ?: 1)
            }
        }
    }

    private suspend fun awaitVisible(topics: Set<String>) {
        val deadline = TimeSource.Monotonic.markNow() + visibleWithin
        while (partitions(topics).keys != topics && deadline.hasNotPassedNow()) {
            delay(VISIBLE_POLL)
        }
    }

    override fun close() {
        admin.close()
    }

    companion object {
        /** The topic config an `acks=all` write waits for that many replicas by. */
        const val MIN_INSYNC_REPLICAS: String = "min.insync.replicas"

        val DEFAULT_VISIBLE_WITHIN: Duration = 30.seconds
        private val VISIBLE_POLL = 100.milliseconds

        /**
         * An admin of [options]' bootstrap servers and properties, creating topics with [replicationFactor] and
         * [configs].
         */
        fun create(
            options: KafkaOptions,
            replicationFactor: Short? = null,
            configs: Map<String, String> = emptyMap(),
        ): KafkaTopicAdmin = KafkaTopicAdmin(
            Admin.create(
                buildMap<String, Any> {
                    putAll(options.properties)
                    put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, options.bootstrapServers)
                },
            ),
            replicationFactor,
            configs,
        )
    }
}

/**
 * What a write to a topic survives (decision D143): its partitions' fewest replicas ([replicationFactor]) and the
 * replicas an `acks=all` write waits for ([minInsyncReplicas], the broker's 1 when the topic sets none). A command
 * accepted on a topic is durable once acknowledged only when both are at least 2: then losing one broker loses no
 * acknowledged write.
 */
data class TopicDurability(val replicationFactor: Int, val minInsyncReplicas: Int) {
    /** Whether an acknowledged write survives the loss of one broker. */
    val survivesOneBroker: Boolean get() = replicationFactor >= DURABLE && minInsyncReplicas >= DURABLE

    companion object {
        /** The replication factor and `min.insync.replicas` decision D143 rests on, at least. */
        const val DURABLE: Int = 2
    }
}
