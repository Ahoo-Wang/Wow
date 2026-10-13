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

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

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
 * - `wow.kafka.command-intake`: whether each context the node hosts executes the commands other contexts send to its
 *   command topic, `wow.<context>.commands` (decisions D135, D137); off by default. The topic must exist with as
 *   many partitions as the context's log, and the node checks it at start. Sending commands to other contexts and
 *   reading their receipts need no switch.
 */
@ConfigurationProperties(prefix = KafkaProperties.PREFIX)
class KafkaProperties {
    var bootstrapServers: String? = null
    var properties: MutableMap<String, String> = LinkedHashMap()
    var topicPrefix: String = ""
    var pollTimeout: Duration = Duration.ofMillis(DEFAULT_POLL_TIMEOUT_MILLIS)
    var commandIntake: Boolean = false

    companion object {
        const val PREFIX: String = "wow.kafka"
        private const val DEFAULT_POLL_TIMEOUT_MILLIS = 200L
    }
}
