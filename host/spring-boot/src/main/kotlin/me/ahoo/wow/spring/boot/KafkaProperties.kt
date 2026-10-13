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

/**
 * The Kafka transport's configuration, `wow.kafka.*` (decision D110), read when wow-kafka is on the classpath
 * ([KafkaAutoConfiguration]):
 *
 * - `wow.kafka.bootstrap-servers`: the brokers; the transport and the Kafka Sink are wired only when it is set.
 * - `wow.kafka.properties.*`: passed to every producer and consumer as they are (`security.protocol`, `client.id`,
 *   `linger.ms`, ...), except the settings the transport's guarantees rest on (`me.ahoo.wow.kafka.KafkaOptions`).
 * - `wow.kafka.topic-prefix`: put before every topic name, such as `staging.` for `staging.wow.shop.order.state`;
 *   none by default, so the topics are the Manifest's (`wow.<context>.<aggregate>.state`, decision D135).
 */
@ConfigurationProperties(prefix = KafkaProperties.PREFIX)
class KafkaProperties {
    var bootstrapServers: String? = null
    var properties: MutableMap<String, String> = LinkedHashMap()
    var topicPrefix: String = ""

    companion object {
        const val PREFIX: String = "wow.kafka"
    }
}
