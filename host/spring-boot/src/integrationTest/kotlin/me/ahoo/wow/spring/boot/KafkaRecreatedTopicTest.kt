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
import me.ahoo.wow.kafka.KafkaTopicAdmin
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kafka.OffsetGapPolicy
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.source.TopicSubscriptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.state.PublishedState
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.tck.containers.WowContainers
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A topic of published state events deleted and created again under its name on a real broker (#7232): another
 * incarnation, its id the broker's topic id (KIP-516). Its subscriptions keep their checkpoints per incarnation, so
 * nothing the new topic holds is skipped however many records it holds past the old checkpoints, and the forward-only
 * save of a checkpoint (`CheckpointStore.save`) starts again from 0, across a restart too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaRecreatedTopicTest {
    private val kafka = WowContainers.newKafka().also { it.start() }
    private val options = KafkaOptions(kafka.bootstrapServers)
    private val admin = Admin.create(
        mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)
    )
    private val topicAdmin = KafkaTopicAdmin.create(options)
    private val producer = KafkaTopicProducer.create(options)
    private val topics = AtomicInteger()
    private val started = CopyOnWriteArrayList<Pair<TopicSubscriptions, KafkaPartitionedSource>>()
    private val leases = InMemoryLeaseStore()
    private val checkpoints = InMemoryCheckpointStore()
    private val ledger = InMemoryFailureLedger()

    /** What was handled: (ref, version). */
    private val handled = CopyOnWriteArrayList<Pair<AggregateRef, Int>>()

    @AfterEach
    fun stopNodes(): Unit = runBlocking {
        started.forEach { (subscriptions, source) ->
            subscriptions.stop()
            source.close()
        }
        started.clear()
        handled.clear()
    }

    @AfterAll
    fun close() {
        producer.close()
        topicAdmin.close()
        admin.close()
        kafka.stop()
    }

    @Test
    fun `a node that starts after its topic was created again reads the new topic from its beginning, however long`():
        Unit = runBlocking {
        val topic = "wow.it.recreated.${topics.incrementAndGet()}.state"
        admin.createVisible(options, NewTopic(topic, PARTITIONS, 1.toShort()))
        val first = start(topic)
        publish(topic, idsOf(topic, "o", 2))
        eventually { handled.size == 6 }
        val old = groupOf(topic)
        eventually { checkpoints.list(old).sumOf { it.position } == 6L }
        first.stop()

        recreate(topic)
        // Five records a partition, past every old checkpoint (two a partition).
        val recreated = idsOf(topic, "n", 5)
        publish(topic, recreated)
        start(topic)

        eventually { handled.size == 6 + 15 }
        recreated.forEach { versionsOf(it).assert().containsExactly(1) }
        val new = groupOf(topic)
        new.assert().isNotEqualTo(old)
        eventually { checkpoints.list(new).sumOf { it.position } == 15L }
        checkpoints.list(old).sumOf { it.position }.assert().isEqualTo(6L)
    }

    @Test
    fun `a topic created again while it is read is followed at the next refresh, nothing of it skipped`(): Unit =
        runBlocking {
            val topic = "wow.it.recreated.${topics.incrementAndGet()}.state"
            admin.createVisible(options, NewTopic(topic, PARTITIONS, 1.toShort()))
            start(topic, partitionRefreshInterval = 300.milliseconds)
            publish(topic, idsOf(topic, "o", 2))
            eventually { handled.size == 6 }
            val old = groupOf(topic)
            eventually { checkpoints.list(old).sumOf { it.position } == 6L }

            recreate(topic)
            val recreated = idsOf(topic, "n", 5)
            publish(topic, recreated)

            eventually { handled.size == 6 + 15 }
            recreated.forEach { versionsOf(it).assert().containsExactly(1) }
            eventually { checkpoints.list(groupOf(topic)).sumOf { it.position } == 15L }
            checkpoints.list(old).sumOf { it.position }.assert().isEqualTo(6L)
        }

    /** The checkpoint group of [topic]'s incarnation now: the lease group's name and the topic's id. */
    private suspend fun groupOf(topic: String): String =
        "${LeaseGroup.topic(SERVICE, topic).name}#${topicAdmin.topicId(topic)}"

    /** Deletes [topic], waits until the broker no longer has it, and creates it again with as many partitions. */
    private suspend fun recreate(topic: String) {
        admin.deleteTopics(listOf(topic)).all().get()
        eventually { topic !in admin.listTopics().names().get() }
        eventually {
            try {
                admin.createVisible(options, NewTopic(topic, PARTITIONS, 1.toShort()))
                true
            } catch (failure: ExecutionException) {
                // Its deletion is not done on every broker yet.
                false
            }
        }
    }

    private suspend fun start(topic: String, partitionRefreshInterval: Duration = Duration.INFINITE): TopicSubscriptions {
        val board = Subscription(BOARD, SubscriptionFilter(ORDER.context)) { entry ->
            handled += entry.ref to entry.version
        }
        val source = KafkaPartitionedSource.create(
            KafkaOptions(options.bootstrapServers, options.properties, options.pollTimeout, 200.milliseconds),
            OffsetGapPolicy.FAIL,
            Observer.NONE,
            topicAdmin::topicId,
        )
        return TopicSubscriptions(
            service = SERVICE,
            topic = topic,
            node = "node-1",
            addressOf = { "node-1.internal:9090" },
            source = source,
            checkpoints = checkpoints,
            leases = leases,
            ledger = ledger,
            subscriptions = listOf(board),
            options = SubscriptionOptions(
                retry = RetryPolicy(maxAttempts = 2, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
                checkpointInterval = 20.milliseconds,
                readRetry = RetryPolicy(
                    maxAttempts = 1,
                    initialBackoff = 10.milliseconds,
                    maxBackoff = 100.milliseconds
                ),
            ),
            cluster = CLUSTER,
            partitionRefreshInterval = partitionRefreshInterval,
        ).also {
            started += it to source
            it.start()
            it.awaitStarted()
        }
    }

    /** [perPartition] aggregate ids named [prefix]-n for each partition of [topic], as their keys place them. */
    private suspend fun idsOf(topic: String, prefix: String, perPartition: Int): List<String> =
        KafkaPartitionedSource.create(options).use { source ->
            val byPartition = HashMap<Int, MutableList<String>>()
            var n = 0
            while (byPartition.size < PARTITIONS || byPartition.values.any { it.size < perPartition }) {
                val id = "$prefix-${n++}"
                val ids = byPartition.getOrPut(source.partitionOf(topic, ORDER.ref("t", id).toString())) { ArrayList() }
                if (ids.size < perPartition) {
                    ids += id
                }
            }
            byPartition.values.flatten()
        }

    private suspend fun publish(topic: String, ids: List<String>) {
        producer.send(topic, ids.map { EnvelopeCodec.message(record(ORDER.ref("t", it), 1)) })
    }

    private fun versionsOf(id: String): List<Int> = handled.filter { it.first.id == id }.map { it.second }

    private suspend fun eventually(timeout: Duration = 60.seconds, condition: suspend () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(50)
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
            state = PublishedState(version, "fp", WowJson.mapper.readTree("""{"total":$version}""")),
        )
    }
}
