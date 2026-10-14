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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kernel.InMemoryCheckpointStore
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.state.StateView
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Kafka-sourced subscriptions on a real broker (decision D134): two nodes share a topic's partitions by their leases,
 * with no consumer group, each through one consumer for the topic; every record is handled once, the checkpoints are
 * the offsets read, and a parked aggregate is resumed by re-scanning its partition from the offset parked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaSourcedSubscriptionTest {
    private val kafka = WowContainers.newKafka().also { it.start() }
    private val options = KafkaOptions(kafka.bootstrapServers)
    private val admin = Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))
    private val producer = KafkaTopicProducer.create(options)
    private val topics = AtomicInteger()
    private val started = CopyOnWriteArrayList<Pair<TopicSubscriptions, KafkaPartitionedSource>>()

    private val leases = InMemoryLeaseStore()
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()

    /** What each node handled: (node, ref, version). */
    private val handled = CopyOnWriteArrayList<Triple<String, AggregateRef, Int>>()

    /** The aggregates that fail, from a version on. */
    private val failing = ConcurrentHashMap<AggregateRef, Int>()

    @AfterEach
    fun stopNodes(): Unit = runBlocking {
        started.forEach { (subscriptions, source) ->
            subscriptions.stop()
            source.close()
        }
        started.clear()
        handled.clear()
        failing.clear()
    }

    @AfterAll
    fun close() {
        producer.close()
        admin.close()
        kafka.stop()
    }

    private fun newTopic(partitions: Int): String {
        val topic = "wow.it.order.${topics.incrementAndGet()}.state"
        admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get()
        return topic
    }

    private suspend fun start(
        node: String,
        topic: String,
        partitionRefresh: Duration = Duration.INFINITE,
    ): TopicSubscriptions {
        val board = Subscription(BOARD, SubscriptionFilter(ORDER.context)) { entry ->
            failing[entry.ref]?.let { from ->
                check(entry.version < from) { "${entry.ref}@${entry.version} fails" }
            }
            handled += Triple(node, entry.ref, entry.version)
        }
        val source = KafkaPartitionedSource.create(options.copy(metadataMaxAge = 200.milliseconds))
        return TopicSubscriptions(
            service = SERVICE,
            topic = topic,
            node = node,
            addressOf = { "$node.internal:9090" },
            source = source,
            checkpoints = checkpoints,
            leases = leases,
            ledger = ledger,
            subscriptions = listOf(board),
            options = SubscriptionOptions(
                retry = RetryPolicy(maxAttempts = 2, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
                checkpointInterval = 20.milliseconds,
                readRetry = RetryPolicy(maxAttempts = 1, initialBackoff = 10.milliseconds, maxBackoff = 100.milliseconds),
            ),
            cluster = CLUSTER,
            partitionRefresh = partitionRefresh,
        ).also {
            started += it to source
            it.start()
            it.awaitStarted()
        }
    }

    private suspend fun publish(topic: String, vararg records: StateEventRecord) {
        producer.send(topic, records.map(EnvelopeCodec::message))
    }

    private fun versionsOf(ref: AggregateRef): List<Int> = handled.filter { it.second == ref }.map { it.third }

    @Test
    fun `two nodes should share the topic's partitions by lease and handle every record once`(): Unit = runBlocking {
        val topic = newTopic(PARTITIONS)
        val one = start("node-1", topic)
        val two = start("node-2", topic)
        eventually {
            val owned = listOf(one, two).map { it.ownership!!.owned }
            owned.all { it.isNotEmpty() } && owned.flatten().sorted() == (0 until PARTITIONS).toList()
        }
        val refs = (0 until 30).map { ORDER.ref("t", "o-$it") }
        publish(topic, *refs.map { record(it, 1) }.toTypedArray())
        publish(topic, *refs.map { record(it, 2) }.toTypedArray())
        eventually { handled.size == refs.size * 2 }
        refs.forEach { versionsOf(it).assert().containsExactly(1, 2) }
        handled.map { it.first }.toSet().assert().containsExactlyInAnyOrder("node-1", "node-2")
        // The checkpoints are the offsets read, in the store, by subscription and partition.
        val group = LeaseGroup.topic(SERVICE, topic).name
        eventually {
            (0 until PARTITIONS).all { partition ->
                checkpoints.load(group, BOARD, partition) == endOf(topic, partition)
            }
        }
        // node-2 leaves: node-1 takes its partitions from their checkpoints.
        two.stop()
        eventually { one.ownership!!.owned == (0 until PARTITIONS).toSet() }
        publish(topic, *refs.map { record(it, 3) }.toTypedArray())
        eventually { handled.size == refs.size * 3 }
        refs.forEach { versionsOf(it).assert().containsExactly(1, 2, 3) }
    }

    @Test
    fun `a parked aggregate should be resumed by re-scanning its partition from the offset parked`(): Unit =
        runBlocking {
            val topic = newTopic(PARTITIONS)
            val node = start("node-1", topic)
            val parked = ORDER.ref("t", "o-parked")
            val other = ORDER.ref("t", "o-other")
            failing[parked] = 2
            publish(topic, record(parked, 1), record(other, 1), record(parked, 2), record(parked, 3), record(other, 2))
            eventually { ledger.parked(BOARD, parked) != null && versionsOf(other) == listOf(1, 2) }
            val entry = ledger.parked(BOARD, parked)!!
            entry.fromVersion.assert().isEqualTo(2)
            entry.fromPosition.assert().isNotNull()
            versionsOf(parked).assert().containsExactly(1)
            failing.remove(parked)
            node.resume(BOARD, parked).assert().isTrue()
            versionsOf(parked).assert().containsExactly(1, 2, 3)
            ledger.parked(BOARD, parked).assert().isNull()
            publish(topic, record(parked, 4))
            eventually { versionsOf(parked) == listOf(1, 2, 3, 4) }
        }

    @Test
    fun `partitions added to the topic while it is read should be read too (decision D168)`(): Unit = runBlocking {
        val topic = newTopic(2)
        val node = start("node-1", topic, partitionRefresh = 200.milliseconds)
        val refs = (0 until 10).map { ORDER.ref("t", "o-$it") }
        publish(topic, *refs.map { record(it, 1) }.toTypedArray())
        eventually { handled.size == refs.size }
        admin.createPartitions(mapOf(topic to NewPartitions.increaseTo(PARTITIONS))).all().get()
        eventually { node.ownership?.owned == (0 until PARTITIONS).toSet() }
        val added = ORDER.ref("t", "o-added")
        val message = EnvelopeCodec.message(record(added, 1))
        producer.send(topic, TopicMessage(message.key, message.value, message.headers, PARTITIONS - 1))
        eventually { versionsOf(added) == listOf(1) }
        delay(100)
        // What was read before the partitions were added is not handled again.
        handled.size.assert().isEqualTo(refs.size + 1)
    }

    private suspend fun endOf(topic: String, partition: Int): Long =
        KafkaPartitionedSource.create(options).use { it.offsets(topic, partition).end }

    private suspend fun eventually(timeout: Duration = 60.seconds, condition: suspend () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(20)
            }
        }
    }

    private companion object {
        const val SERVICE = "board-service"
        const val BOARD = "Board.onOrder"
        const val PARTITIONS = 3
        val ORDER = AggregateType("sales", "order")
        val CLUSTER = ClusterOptions(
            leaseTtl = 3.seconds,
            renewInterval = 500.milliseconds,
            memberTtl = 3.seconds,
            heartbeatInterval = 500.milliseconds,
            reconcileInterval = 200.milliseconds,
            routeRefreshInterval = 200.milliseconds,
        )

        fun record(ref: AggregateRef, version: Int) = StateEventRecord(
            ref = ref,
            version = version,
            commandId = "c-${ref.id}-$version",
            requestId = "r-${ref.id}-$version",
            commandType = "sales.order.Place",
            commitTime = version.toLong(),
            events = listOf(StateEventPayload("sales.order.Placed", 1, WowJson.mapper.readTree("""{"n":$version}"""))),
            state = StateView(version, "fp", WowJson.mapper.readTree("""{"total":$version}""")),
        )
    }
}
