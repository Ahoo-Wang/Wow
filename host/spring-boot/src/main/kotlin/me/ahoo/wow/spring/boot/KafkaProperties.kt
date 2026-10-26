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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicDdl
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import kotlin.time.toJavaDuration

/**
 * The Kafka transport's configuration, `wow.kafka.*`: the brokers, the clients' settings and the topics, which the
 * transport provider of type `kafka` reads (`me.ahoo.wow.kafka.KafkaTransportProvider`, finding R5.B-5), and the
 * topic prefix, the command intake and the topics' DDL and partition refresh, which the node reads whatever transport
 * carries them ([TransportAutoConfiguration]). The keys stay until the configuration surface is final.
 */
@ConfigurationProperties(prefix = KafkaProperties.PREFIX)
class KafkaProperties {
    /**
     * The brokers; the transport, the Kafka Sink and the Kafka-sourced subscriptions are wired only when it is set.
     */
    var bootstrapServers: String? = null

    /**
     * Passed to every producer and consumer as they are (`security.protocol`, `client.id`, `linger.ms`, ...), except
     * the settings the transport's guarantees rest on (`me.ahoo.wow.kafka.KafkaOptions`).
     */
    var properties: MutableMap<String, String> = LinkedHashMap()

    /**
     * Put before every topic name, such as `staging.` for `staging.wow.shop.order.state`; none by default, so the
     * topics are the Manifest's (`wow.<context>.<aggregate>.state`). The sinks write and the subscriptions read under
     * it.
     */
    var topicPrefix: String = ""

    /**
     * The longest a consumer's poll waits; a read that is cancelled, and a partition that joins or leaves a topic's
     * shared consumer, take effect within it.
     */
    var pollTimeout: Duration = Duration.ofMillis(DEFAULT_POLL_TIMEOUT_MILLIS)

    /**
     * Whether each context the node hosts executes the commands other contexts send to its command topic,
     * `wow.<context>.commands`. The topic must exist with as many partitions as the context's log. A context with an
     * aggregate type routed through Kafka takes them whatever this says.
     */
    var commandIntake: Boolean = false

    /**
     * What a read of another context's topic does when its partition no longer holds the offset to read from (the
     * retention deleted it, or the topic was created again): `fail` fails the read, so the subscription stays where it
     * is; `earliest` reads on from the earliest offset retained, and the skipped range is lost to that subscription.
     * Either way the gap is logged as an error and counted (`wow.kafka.offset.gaps`).
     */
    var offsetGap: String = "fail"

    /** The topics the node's contexts own, and how `auto` creates them. */
    var topics: Topics = Topics()

    /**
     * The topics the node's contexts own: their DDL, and how `auto` creates them. A command accepted on a command topic
     * is durable only when the replication factor and the minimum in-sync replicas are both at least 2: at start the
     * node warns when they are below that, unless a profile of [devProfiles] is active.
     */
    class Topics {
        /**
         * How the node manages the topics its contexts own at its start: `validate` fails the start when one is
         * missing or a command topic does not have exactly `wow.log.partitions` partitions; `auto` creates the
         * missing ones first; `none` checks only the command topics the node reads.
         */
        var ddl: TopicDdl = TopicDdl.VALIDATE

        /** The replication factor `auto` creates a topic with; the broker's default when not set. */
        var replicationFactor: Short? = null

        /** The `min.insync.replicas` `auto` creates a topic with; the broker's default when not set. */
        var minInsyncReplicas: Int? = null

        /** Other topic configs `auto` creates a topic with, such as `retention.ms`. */
        var configs: MutableMap<String, String> = LinkedHashMap()

        /** The profiles that run without durable topics, a single broker: no warning about it. */
        var devProfiles: MutableSet<String> = linkedSetOf("dev", "local", "test", "demo")

        /** The configs `auto` creates a topic with: [configs], and [minInsyncReplicas] when it is set. */
        fun creationConfigs(): Map<String, String> = buildMap {
            putAll(configs)
            minInsyncReplicas?.let { put(MIN_INSYNC_REPLICAS, it.toString()) }
        }

        /**
         * How often the node looks for partitions added to the topics it reads, and its consumers'
         * `metadata.max.age.ms`; new partitions are read within about twice this.
         */
        var partitionRefresh: Duration = SourceTopics.DEFAULT_PARTITION_REFRESH.toJavaDuration()
    }

    companion object {
        const val PREFIX: String = "wow.kafka"
        private const val MIN_INSYNC_REPLICAS = "min.insync.replicas"
        private const val DEFAULT_POLL_TIMEOUT_MILLIS = 200L
    }
}
