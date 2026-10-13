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

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.kafka.ConfluentKafkaContainer
import java.util.concurrent.atomic.AtomicInteger

/** One Kafka broker for the test JVM (the image the 9.x tests use), and a fresh topic per test. */
object KafkaFixture {
    private val container: ConfluentKafkaContainer by lazy {
        ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0").also { it.start() }
    }

    val options: KafkaOptions by lazy { KafkaOptions(container.bootstrapServers) }

    private val admin: Admin by lazy {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to container.bootstrapServers))
    }

    private val topics = AtomicInteger()

    /** A new topic with [partitions] partitions, created before it returns. */
    fun newTopic(partitions: Int, prefix: String = "wow.it"): String {
        val topic = "$prefix.${topics.incrementAndGet()}.state"
        admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get()
        return topic
    }
}
