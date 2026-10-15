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

import me.ahoo.wow.kafka.OffsetGapPolicy
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicDdl
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import kotlin.time.toJavaDuration

/**
 * The Kafka transport's configuration, `wow.kafka.*` (decision D110), read when wow-kafka is on the classpath
 * ([KafkaAutoConfiguration]):
 *
 * - `wow.kafka.bootstrap-servers`: the brokers; the transport, the Kafka Sink and the Kafka-sourced subscriptions are
 *   wired only when it is set.
 * - `wow.kafka.properties.*`: passed to every producer and consumer as they are (`security.protocol`, `client.id`,
 *   `linger.ms`, ...), except the settings the transport's guarantees rest on (`me.ahoo.wow.kafka.KafkaOptions`).
 * - `wow.kafka.topic-prefix`: put before every topic name, such as `staging.` for `staging.wow.shop.order.state`;
 *   none by default, so the topics are the Manifest's (`wow.<context>.<aggregate>.state`, decision D135). The sinks
 *   write under it and the subscriptions of other contexts' streams read under it.
 * - `wow.kafka.poll-timeout`: the longest a consumer's poll waits (200 ms by default); a read that is cancelled, and
 *   a partition that joins or leaves a topic's shared consumer, take effect within it.
 * - `wow.kafka.offset-gap` (finding A2-11): what a read of another context's topic does when its partition no longer
 *   holds the offset it is to read from (the retention deleted it, or the topic was created again): `fail` (the
 *   default) fails the read, so the subscription stays where it is, logging errors, until an operator moves its
 *   checkpoint; `earliest` reads on from the earliest offset retained. Either way the gap is logged as an error and
 *   counted (`wow.kafka.offset.gaps`).
 * - `wow.kafka.command-intake`: whether each context the node hosts executes the commands other contexts send to its
 *   command topic, `wow.<context>.commands` (decisions D135, D137); off by default. The topic must exist with as
 *   many partitions as the context's log, and the node checks it at start. Sending commands to other contexts and
 *   reading their receipts need no switch. A context with an aggregate type routed through Kafka
 *   (`wow.aggregates.[<context>.<aggregate>].routing=kafka`, decision D143) takes them whatever this says.
 * - `wow.kafka.topics.ddl` (decision D168): how the node manages the topics its contexts own at its start, see
 *   [Topics]: `validate` (the default) fails the start when one is missing or a command topic does not have exactly
 *   `wow.log.partitions` partitions; `auto` creates the missing ones first; `none` checks only the command topics the
 *   node reads, whose partitions must still be the log's.
 * - `wow.kafka.topics.replication-factor` (the broker's default) and `wow.kafka.topics.configs.*` (`retention.ms`,
 *   `min.insync.replicas`, ...): what `auto` creates a topic with.
 * - `wow.kafka.topics.partition-refresh` (30s): how often the node looks for partitions added to the topics it reads
 *   (other contexts' streams, its receipts), and its consumers' `metadata.max.age.ms`; the new partitions are read
 *   within about twice this.
 */
@ConfigurationProperties(prefix = KafkaProperties.PREFIX)
class KafkaProperties {
    var bootstrapServers: String? = null
    var properties: MutableMap<String, String> = LinkedHashMap()
    var topicPrefix: String = ""
    var pollTimeout: Duration = Duration.ofMillis(DEFAULT_POLL_TIMEOUT_MILLIS)
    var commandIntake: Boolean = false
    var offsetGap: OffsetGapPolicy = OffsetGapPolicy.FAIL
    var topics: Topics = Topics()

    /** The topics the node's contexts own (decision D168): their DDL, and how `auto` creates them. */
    class Topics {
        var ddl: TopicDdl = TopicDdl.VALIDATE
        var replicationFactor: Short? = null
        var configs: MutableMap<String, String> = LinkedHashMap()
        var partitionRefresh: Duration = SourceTopics.DEFAULT_PARTITION_REFRESH.toJavaDuration()
    }

    companion object {
        const val PREFIX: String = "wow.kafka"
        private const val DEFAULT_POLL_TIMEOUT_MILLIS = 200L
    }
}
