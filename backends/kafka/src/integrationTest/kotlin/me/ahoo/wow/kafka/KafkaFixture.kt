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
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import me.ahoo.wow.tck.containers.WowContainers
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One Kafka broker for the test JVM (the image the 9.x tests use), and a fresh topic per test. */
object KafkaFixture {
    private val container get() = WowContainers.kafka

    val options: KafkaOptions by lazy { KafkaOptions(container.bootstrapServers) }

    val admin: Admin by lazy {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to container.bootstrapServers))
    }

    private val topics = AtomicInteger()

    private val servedWithin = 30.seconds
    private val servedPoll = 50.milliseconds

    /**
     * A new topic with [partitions] partitions, created and served by the broker before it returns: `createTopics`
     * completes once the controller has created the topic, a moment before the broker serves it in its metadata, and
     * until then a consumer's `partitionsFor` lists no partition.
     */
    fun newTopic(partitions: Int, prefix: String = "wow.it"): String {
        val topic = "$prefix.${topics.incrementAndGet()}.state"
        admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get()
        awaitServed(topic, partitions)
        return topic
    }

    private fun awaitServed(topic: String, partitions: Int) {
        val deadline = TimeSource.Monotonic.markNow() + servedWithin
        while (ledPartitions(topic) != partitions) {
            check(deadline.hasNotPassedNow()) { "The broker did not serve $topic's $partitions partitions within $servedWithin." }
            Thread.sleep(servedPoll.inWholeMilliseconds)
        }
    }

    /** How many partitions of [topic] the broker's metadata gives a leader: none while it does not know the topic. */
    private fun ledPartitions(topic: String): Int = try {
        admin.describeTopics(listOf(topic)).allTopicNames().get().getValue(topic).partitions().count { it.leader() != null }
    } catch (failure: ExecutionException) {
        if (failure.cause !is UnknownTopicOrPartitionException) {
            throw failure
        }
        0
    }
}
