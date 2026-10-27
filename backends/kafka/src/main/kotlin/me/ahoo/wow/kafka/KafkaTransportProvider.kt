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

import kotlinx.coroutines.withTimeout
import me.ahoo.wow.spi.transport.TopicTransport
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Kafka transport, type `kafka`: available once `bootstrap-servers` is set.
 * Its settings, relative to its configuration (`wow.kafka.*`):
 *
 * - `bootstrap-servers`, `properties.*` (passed to every client as they are), `poll-timeout`, and
 *   `topics.partition-refresh` (the clients' metadata age): [KafkaOptions];
 * - `offset-gap`: what a read does with an offset its partition no longer holds ([OffsetGapPolicy], `fail` by default);
 * - `topics.replication-factor`, `topics.min-insync-replicas` and `topics.configs.*`: what the topics it creates get.
 *
 * The producer is idempotent with `acks=all`; the source reads with no consumer group ([KafkaPartitionedSource]).
 * [TopicTransport.warnings] warns about a configuration that creates topics losing commands with one broker, and about
 * each of the node's command topics that does not survive the loss of a broker, unless the node runs
 * for development ([TransportConfig.development]).
 */
class KafkaTransportProvider : TransportProvider {
    override val type: String = TYPE

    override val requirement: String = "bootstrap-servers, the Kafka brokers"

    override fun available(config: TransportConfig): Boolean = config.option(BOOTSTRAP_SERVERS) != null

    override fun open(config: TransportConfig): TopicTransport {
        val options = options(config)
        return KafkaTopicTransport(
            KafkaTopicProducer.create(options),
            KafkaPartitionedSource.create(options, offsetGap(config)),
            KafkaTopicAdmin.create(options, replicationFactor(config), creationConfigs(config)),
            durability(config),
        )
    }

    private class KafkaTopicTransport(
        override val producer: KafkaTopicProducer,
        override val source: KafkaPartitionedSource,
        override val admin: KafkaTopicAdmin,
        private val durability: Durability,
    ) : TopicTransport {
        override suspend fun warnings(topics: Set<String>): List<String> =
            durability.configured() + durability.checkQuietly { admin.durability(topics) }

        override fun close() {
            listOf<AutoCloseable>(producer, source, admin).forEach { closeable ->
                runCatching { closeable.close() }.onFailure { logger.warn("Closing {} failed.", closeable, it) }
            }
        }

        override fun toString(): String = "KafkaTopicTransport"
    }

    /**
     * Whether the topics carry the promise "accepted is durable": a command topic keeps at
     * least two replicas and acknowledges a write only once two have it ([TopicDurability.survivesOneBroker]), else an
     * acknowledged command is lost with one broker. A node that runs for development ([dev]), on a single broker, gives
     * no warnings.
     */
    internal class Durability(
        private val replicationFactor: Int?,
        private val minInsyncReplicas: Int?,
        private val dev: Boolean,
    ) {
        /** The warnings about the configuration topics are created with: none in development. */
        fun configured(): List<String> = if (dev) {
            emptyList()
        } else {
            buildList {
                if (replicationFactor != null && replicationFactor < TopicDurability.DURABLE) {
                    add(
                        "wow.kafka.topics.replication-factor is $replicationFactor: topics created with fewer than " +
                            "2 replicas lose the commands they accepted with one broker."
                    )
                }
                if (minInsyncReplicas != null && minInsyncReplicas < TopicDurability.DURABLE) {
                    add(
                        "wow.kafka.topics.min-insync-replicas is $minInsyncReplicas: an acknowledged command may be " +
                            "on one replica only."
                    )
                }
            }
        }

        /** The warnings about the command topics [durability] reads: each that does not survive one broker's loss. */
        fun warnings(durability: Map<String, TopicDurability>): List<String> = if (dev) {
            emptyList()
        } else {
            durability.filterValues { !it.survivesOneBroker }.map { (topic, it) ->
                "Command topic $topic has replication factor ${it.replicationFactor} and min.insync.replicas " +
                    "${it.minInsyncReplicas}: a command accepted on it is lost with one broker. A command topic " +
                    "needs both at least ${TopicDurability.DURABLE}."
            }
        }

        /**
         * The warnings about what [read] reads, within [timeout]; none in development, and none when the
         * broker cannot be asked (logged): the check is advice, never a reason to stop.
         */
        @Suppress("TooGenericExceptionCaught") // Whatever stops the check, the node runs on.
        suspend fun checkQuietly(
            timeout: Duration = CHECK_TIMEOUT,
            read: suspend () -> Map<String, TopicDurability>,
        ): List<String> {
            if (dev) {
                return emptyList()
            }
            return try {
                warnings(withTimeout(timeout) { read() })
            } catch (cancelled: CancellationException) {
                logger.info("The durability of the command topics could not be read in {}.", timeout, cancelled)
                emptyList()
            } catch (failure: Exception) {
                logger.info("The durability of the command topics could not be read.", failure)
                emptyList()
            }
        }
    }

    companion object {
        /** The type of the Kafka transport. */
        const val TYPE: String = "kafka"

        private const val BOOTSTRAP_SERVERS = "bootstrap-servers"
        private const val TOPICS = "topics"
        private const val MIN_INSYNC = "min.insync.replicas"
        private val CHECK_TIMEOUT: Duration = 10.seconds

        // kafka-clients logs through SLF4J: so does its transport, with no logging dependency of its own.
        private val logger = LoggerFactory.getLogger(KafkaTransportProvider::class.java)

        /** The clients' options of [config]. */
        fun options(config: TransportConfig): KafkaOptions = KafkaOptions(
            bootstrapServers = requireNotNull(config.option(BOOTSTRAP_SERVERS)) {
                "${config.key}.$BOOTSTRAP_SERVERS is not set: set it to the Kafka brokers, host:port, separated by commas."
            },
            properties = config.options("properties"),
            pollTimeout = duration(config, "poll-timeout") ?: KafkaOptions.DEFAULT_POLL_TIMEOUT,
            metadataMaxAge = duration(config, "$TOPICS.partition-refresh") ?: KafkaOptions.DEFAULT_METADATA_MAX_AGE,
        )

        /** The replication factor the topics are created with; the broker's when not set. */
        internal fun replicationFactor(config: TransportConfig): Short? =
            config.option("$TOPICS.replication-factor")?.toShort()

        /** The topic settings the topics are created with: `topics.configs.*`, and `topics.min-insync-replicas`. */
        internal fun creationConfigs(config: TransportConfig): Map<String, String> = buildMap {
            putAll(config.options("$TOPICS.configs"))
            config.option("$TOPICS.min-insync-replicas")?.let { put(MIN_INSYNC, it.toInt().toString()) }
        }

        /** The durability check of [config]'s topics. */
        internal fun durability(config: TransportConfig): Durability {
            val creation = creationConfigs(config)
            return Durability(
                replicationFactor(config)?.toInt(),
                creation[MIN_INSYNC]?.toIntOrNull(),
                config.development,
            )
        }

        internal fun offsetGap(config: TransportConfig): OffsetGapPolicy {
            val text = config.option("offset-gap") ?: return OffsetGapPolicy.FAIL
            return requireNotNull(OffsetGapPolicy.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }) {
                "${config.key}.offset-gap is $text: set it to fail or earliest."
            }
        }

        /** A duration setting: `200ms`, `10s`, ISO-8601 (`PT0.2S`), or a number of milliseconds. */
        private fun duration(config: TransportConfig, name: String): Duration? {
            val text = config.option(name)?.trim() ?: return null
            return text.toLongOrNull()?.milliseconds ?: Duration.parse(text)
        }
    }
}
