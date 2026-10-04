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

package me.ahoo.wow.it.mixed

import me.ahoo.wow.tck.container.ContainerImages
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ConsumerGroupDescription
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.utils.Utils
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.containers.Network
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A released example server and the build under test sharing one MongoDB and one Kafka, as in a rolling upgrade.
 *
 * Both nodes run the same service name, so they join the same consumer groups and split every topic's partitions.
 */
class MixedVersionCluster(
    previousImage: String?,
    previousHome: Path?,
    currentHome: Path,
) : AutoCloseable {
    private val network: Network = Network.newNetwork()
    private val mongo: MongoDBContainer = MongoDBContainer(DockerImageName.parse(ContainerImages.MONGO))
        .withNetwork(network)
        .withNetworkAliases(MONGO_ALIAS)
    private val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse(ContainerImages.KAFKA))
        .withNetwork(network)
        .withNetworkAliases(KAFKA_ALIAS)
        .withKraft()
        .withListener { KAFKA_NETWORK_BOOTSTRAP }
        // Several partitions per topic, so the two nodes in a group both own some.
        .withEnv("KAFKA_NUM_PARTITIONS", DEFAULT_PARTITIONS.toString())

    val previous: ExampleServerNode
    val current: ExampleServerNode
    private val admin: Admin

    init {
        mongo.start()
        kafka.start()
        admin = Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
        createTopics()
        previous = if (previousImage != null) {
            ImageNode(PREVIOUS, previousImage, network, machineId = 1)
        } else {
            ProcessNode(PREVIOUS, checkNotNull(previousHome), 1, mongoUri(), kafka.bootstrapServers)
        }
        current = ProcessNode(CURRENT, currentHome, 2, mongoUri(), kafka.bootstrapServers)
    }

    val nodes: List<ExampleServerNode>
        get() = listOf(previous, current)

    fun start() {
        nodes.forEach { node ->
            runCatching { node.start() }.onFailure {
                throw IllegalStateException("Node [${node.name}] failed to start:\n${node.logTail()}", it)
            }
        }
    }

    /**
     * Every consumer group orders its members the same way, so with equal partition counts a key's command, event and
     * state partitions all land on one node, and an event would never be consumed by the other version. Distinct
     * counts per topic kind let the test pick keys whose command and event (or state) partitions land on different
     * nodes.
     */
    private fun createTopics() {
        val topics = TOPIC_AGGREGATES.flatMap { aggregate ->
            TOPIC_PARTITIONS.map { (kind, partitions) ->
                NewTopic("$TOPIC_PREFIX$aggregate.$kind", partitions, 1.toShort())
            }
        }
        admin.createTopics(topics).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun mongoUri(): String = mongo.getReplicaSetUrl(DATABASE)

    fun node(name: String): ExampleServerNode = nodes.single { it.name == name }

    fun other(node: ExampleServerNode): ExampleServerNode = nodes.single { it !== node }

    /** Waits for the topic whose name ends with [suffix], e.g. `.cart.command`, and returns its name. */
    fun awaitTopic(suffix: String): String {
        val deadline = System.nanoTime() + BALANCE_TIMEOUT.toNanos()
        var topics: Set<String> = emptySet()
        while (System.nanoTime() < deadline) {
            topics = admin.listTopics().names().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            topics.singleOrNull { it.endsWith(suffix) }?.let { return it }
            TimeUnit.SECONDS.sleep(1)
        }
        error("No single topic ends with [$suffix]: $topics")
    }

    /**
     * Waits until the consumer group of [topic] chosen by [groupFilter] has a member from each node, each owning
     * partitions of [topic], and returns the group.
     */
    fun awaitBalancedGroup(topic: String, groupFilter: (String) -> Boolean = { true }): String {
        val deadline = System.nanoTime() + BALANCE_TIMEOUT.toNanos()
        var last: List<ConsumerGroupDescription> = emptyList()
        while (System.nanoTime() < deadline) {
            last = groupsOf(topic).filter { groupFilter(it.groupId()) }
            val group = last.singleOrNull()
            if (group != null && nodes.all { node -> partitionsOf(group, topic, node.name).isNotEmpty() }) {
                return group.groupId()
            }
            TimeUnit.SECONDS.sleep(1)
        }
        error("The consumer group of [$topic] did not balance across both nodes: ${last.describe()}")
    }

    /** The node whose consumer in [group] owns the partition the default partitioner gives [key] in [topic]. */
    fun ownerOf(topic: String, group: String, key: String): ExampleServerNode {
        val partitions = admin.describeTopics(listOf(topic)).allTopicNames()
            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).getValue(topic).partitions().size
        val partition = Utils.toPositive(Utils.murmur2(key.toByteArray())) % partitions
        val description = describe(group)
        return nodes.singleOrNull { node -> partition in partitionsOf(description, topic, node.name) }
            ?: error("No node owns [$topic-$partition] in [$group]: ${listOf(description).describe()}")
    }

    /** The first key `prefix-n` that satisfies [predicate], e.g. one whose partition a given node owns. */
    fun key(prefix: String, predicate: (String) -> Boolean): String =
        generateSequence(0) { it + 1 }
            .take(MAX_KEY_ATTEMPTS)
            .map { "$prefix-$it" }
            .firstOrNull(predicate)
            ?: error("No key with prefix [$prefix] satisfies the routing the test needs.")

    fun groupsOf(topic: String): List<ConsumerGroupDescription> {
        val groupIds = admin.listConsumerGroups().all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { it.groupId() }
        if (groupIds.isEmpty()) {
            return emptyList()
        }
        return admin.describeConsumerGroups(groupIds).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).values
            .filter { group ->
                group.members().any { member ->
                    member.assignment().topicPartitions().any { it.topic() == topic }
                }
            }
    }

    private fun describe(group: String): ConsumerGroupDescription =
        admin.describeConsumerGroups(listOf(group)).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getValue(group)

    private fun partitionsOf(group: ConsumerGroupDescription, topic: String, clientId: String): Set<Int> =
        group.members()
            .filter { it.clientId() == clientId }
            .flatMap { it.assignment().topicPartitions() }
            .filter { it.topic() == topic }
            .map(TopicPartition::partition)
            .toSet()

    private fun List<ConsumerGroupDescription>.describe(): String =
        joinToString(prefix = "[", postfix = "]") { group ->
            group.groupId() + " " + group.members().map { member ->
                member.clientId() + "=" + member.assignment().topicPartitions()
            }
        }

    override fun close() {
        admin.close()
        nodes.forEach { runCatching { it.close() } }
        kafka.stop()
        mongo.stop()
        network.close()
    }

    companion object {
        const val PREVIOUS = "mixed-previous"
        const val CURRENT = "mixed-current"
        const val MONGO_ALIAS = "mongo"
        const val KAFKA_ALIAS = "kafka"
        const val KAFKA_NETWORK_BOOTSTRAP = "$KAFKA_ALIAS:19092"
        const val DATABASE = "wow_mixed_db"
        private const val DEFAULT_PARTITIONS = 4
        private const val TOPIC_PREFIX = "wow.example."
        private val TOPIC_AGGREGATES = listOf("cart", "order")
        private val TOPIC_PARTITIONS = mapOf("command" to 4, "event" to 3, "state" to 5)
        private const val ADMIN_TIMEOUT_SECONDS = 30L
        private const val MAX_KEY_ATTEMPTS = 1000
        private val BALANCE_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}
