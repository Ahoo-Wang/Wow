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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.KafkaTopicAdmin
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicDdl
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * The `Kafka` router on a real broker (roadmap 6.5, decision D143; acceptance: after a 202, kill the node, and every
 * command is still committed): the node that accepts the commands of a type routed through Kafka writes them to the
 * context's command topic, which it creates (decision D168), and answers ACCEPTED once the broker acknowledged them.
 * It is then killed before it executes any; the node that takes its partitions over executes every one from the
 * command topic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaRouterTest {
    private val kafka = WowContainers.newKafka().also { it.start() }
    private val options = KafkaOptions(kafka.bootstrapServers)
    private val producer = KafkaTopicProducer.create(options)
    private val admin = KafkaTopicAdmin.create(options)
    private val sources = CopyOnWriteArrayList<KafkaPartitionedSource>()
    private val channel = LoopbackClusterChannel()
    private val stores = SharedStores()

    @AfterAll
    fun close() {
        sources.forEach { it.close() }
        admin.close()
        producer.close()
        kafka.stop()
    }

    private fun node(name: String, items: Items, backends: BackendFactory): ServiceNode = ServiceNode(
        SERVICE,
        name,
        "$name.internal:9090",
        listOf(items.model),
        MANIFEST,
        backends,
        channel,
        options = NODE_OPTIONS,
        topics = SourceTopics(
            KafkaPartitionedSource.create(options).also { sources += it },
            producer = producer,
            admin = admin,
            ddl = TopicDdl.AUTO,
        ),
    ).also { channel.bind(it.address, it.forwardHandler) }

    @Test
    fun `every command accepted before its node is killed should be committed`(): Unit = runBlocking {
        val doomed = Items()
        val killable = Killable(stores)
        val first = node("node-1", doomed, killable).also { it.start() }
        admin.partitions(setOf(COMMANDS)).assert().isEqualTo(mapOf(COMMANDS to PARTITIONS))
        val ids = (0 until SENT).map { "item-$it" }
        ids.forEach { doomed.gates[it] = CompletableDeferred() }
        val accepted = ids.map { id ->
            first.gateway.execute(
                CommandRequest(STOCK, "item", "Reserve", CommandBody.of("""{"quantity":1}"""), aggregateId = id, wait = WaitPlan.ACCEPTED),
            )
        }
        accepted.forEach {
            it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            it.hop.assert().isEqualTo(Hop.accepted(STOCK))
        }
        // Killed: nothing it does reaches the stores any more, nor the cluster channel.
        killable.kill()
        channel.unbind(first.address)
        doomed.gates.values.forEach { it.completeExceptionally(IllegalStateException("node-1 is gone")) }

        val survivor = Items()
        val second = node("node-2", survivor, stores).also { it.start() }
        // A command is decided before its events are appended: wait for the commits, not for the decisions.
        val log = stores.backends(STOCK).log
        withTimeout(60.seconds) {
            while (accepted.any { log.read(it.ref).toList().isEmpty() }) {
                delay(50.milliseconds)
            }
        }
        accepted.forEach { result ->
            log.read(result.ref).toList().single().commandId.assert().isEqualTo(result.commandId)
        }
        survivor.decided.toSet().assert().isEqualTo(accepted.map { it.commandId }.toSet())
        doomed.decided.assert().isEmpty()
        second.stop()
        // The dead node's stop cannot hand anything off; it is only cut short.
        withTimeoutOrNull(5.seconds) { runCatching { first.stop() } }
    }

    data class Reserve(val quantity: Int)
    data class Reserved(val quantity: Int)

    class ItemState {
        var reserved = 0
    }

    /** The stock items: each reservation decided is recorded by its command id, after the item's gate, if any. */
    class Items {
        val decided = CopyOnWriteArrayList<String>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        val model: AggregateModel<ItemState> = aggregate<ItemState>(STOCK, "item") {
            initial { ItemState() }
            decideSuspending<Reserve>(Creation.ALLOWED) { _, body ->
                gates[command.ref.id]?.await()
                decided += command.id
                Decision.events(Reserved(body.quantity))
            }
            evolve<Reserved> { reserved += it.quantity }
        }
    }

    /** Each context's stores in memory, shared by the nodes, as a database is. */
    private class SharedStores : BackendFactory {
        private val opened = ConcurrentHashMap<String, ContextBackends>()

        override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
            ContextBackends(InMemoryEventLog(PARTITIONS), InMemoryCheckpointStore(), InMemoryLeaseStore(), InMemoryFailureLedger())
        }
    }

    /** [backends] until [kill]: then every call to the log, checkpoints, leases and ledger fails, as for a dead process. */
    private class Killable(private val backends: BackendFactory) : BackendFactory {
        @Volatile
        private var killed = false

        fun kill() {
            killed = true
        }

        override fun backends(context: String): ContextBackends = backends.backends(context).let {
            it.copy(
                log = killable(it.log, EventLog::class.java),
                checkpoints = killable(it.checkpoints, CheckpointStore::class.java),
                leases = killable(it.leases, LeaseStore::class.java),
                ledger = killable(it.ledger, FailureLedger::class.java),
            )
        }

        private fun <T : Any> killable(store: T, type: Class<T>): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                check(!killed) { "The node is gone." }
                try {
                    method.invoke(store, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            },
        )
    }

    private companion object {
        const val SERVICE = "stock-service"
        const val STOCK = "stock"
        const val PARTITIONS = 4
        const val SENT = 20
        const val COMMANDS = "wow.stock.commands"
        val ITEM = AggregateType(STOCK, "item")

        val MANIFEST = Manifest(
            service = ServiceInfo(SERVICE),
            contexts = listOf(
                ContextManifest(
                    STOCK,
                    aggregates = listOf(
                        AggregateManifest(
                            "item",
                            state = StateManifest(Manifest.schemaRef("itemState")),
                            commands = listOf(
                                CommandManifest(
                                    "Reserve",
                                    schema = Manifest.schemaRef(Reserve::class.java.name),
                                    creation = ManifestCreation.ALLOWED,
                                ),
                            ),
                            events = listOf(EventManifest("Reserved", schema = Manifest.schemaRef(Reserved::class.java.name))),
                            lifecycle = Lifecycle.COMMANDS.keys.toList(),
                        ),
                    ),
                ),
            ),
        )

        val NODE_OPTIONS = ServiceNodeOptions(
            cluster = ClusterOptions(
                leaseTtl = 3.seconds,
                renewInterval = 500.milliseconds,
                memberTtl = 3.seconds,
                heartbeatInterval = 500.milliseconds,
                reconcileInterval = 200.milliseconds,
                routeRefreshInterval = 200.milliseconds,
                drainTimeout = 1.seconds,
            ),
            subscription = SubscriptionOptions(
                retry = RetryPolicy(maxAttempts = 3, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
                checkpointInterval = 20.milliseconds,
                readRetry = RetryPolicy(maxAttempts = 1, initialBackoff = 10.milliseconds, maxBackoff = 100.milliseconds),
            ),
            kafkaRouted = setOf(ITEM),
        )
    }
}
