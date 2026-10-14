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

package me.ahoo.wow.runtime.command

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.InMemoryTopics
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The `Kafka` router (design 02 §2; decisions D15, D143) over [InMemoryTopics]: the commands admitted for a type routed
 * through Kafka are written to the context's command topic, in their log partition, and accepted once the broker
 * acknowledged them; the owner of the partition executes them from there, signalling the later hops to the node that
 * admitted them; and a command accepted by a node that dies at once is still committed by the next owner.
 */
class KafkaRouterTest {
    private val topics = InMemoryTopics(1).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val backends = InMemoryBackends(PARTITIONS)

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { runCatching { it.stop() } }
    }

    private fun stockNode(
        name: String = "stock-1",
        stock: StockItems = StockItems(),
        producer: TopicProducer = topics,
        stores: BackendFactory = backends,
    ): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        name,
        "$name.internal:9090",
        listOf(stock.model),
        stockManifest(),
        stores,
        channel,
        options = fastNodeOptions().copy(kafkaRouted = setOf(ITEM)),
        topics = SourceTopics(topics, PREFIX, producer),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun reserve(item: String, quantity: Int = 1, wait: WaitPlan? = WaitPlan.ACCEPTED, requestId: String? = null) =
        CommandRequest(
            STOCK,
            "item",
            "Reserve",
            CommandBody.of("""{"quantity":$quantity}"""),
            aggregateId = item,
            requestId = requestId,
            wait = wait,
        )

    @Test
    fun `an accepted command should be on the command topic before it is answered, then executed by the owner`(): Unit =
        runBlocking {
            val stock = StockItems().apply { broken = false }
            val node = stockNode(stock = stock).also { it.start() }
            // The context takes the commands of its topic without wow.kafka.command-intake.
            node.context(STOCK).intake.assert().isNotNull()
            val result = node.gateway.execute(reserve("i-1"))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.accepted(STOCK))
            val message = topics.messages(COMMANDS).single()
            val envelope = EnvelopeCodec.decode(message.value)
            CommandMessages.commandOf(envelope).id.assert().isEqualTo(result.commandId)
            envelope.transport.assert().isNull()
            message.key.assert().isEqualTo(ITEM.ref(result.ref.tenantId, "i-1").toString())
            eventually { result.commandId in stock.decided }
        }

    @Test
    fun `a longer wait should go on with the signals of the owner that executed it`(): Unit = runBlocking {
        val stock = StockItems().apply { broken = false }
        val node = stockNode(stock = stock).also { it.start() }
        val results = node.gateway.send(reserve("i-2", wait = WaitPlan.COMMITTED.within(10.seconds))).toList()
        results.map { it.hop.stage }.assert().containsExactly(Stage.COMMITTED)
        val committed = results.single()
        committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        committed.version.assert().isEqualTo(1)
        // The command went through the topic with the wait's context, without its ACCEPTED hop.
        val envelope = EnvelopeCodec.decode(topics.messages(COMMANDS).single().value)
        envelope.transport!!.wait!!.remaining.assert().containsExactly(Hop.committed(STOCK))
        // A rejection comes back as the owner's signal too.
        val rejected = node.gateway.execute(reserve("i-3", AVAILABLE + 1, WaitPlan.COMMITTED.within(10.seconds)))
        rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejected.error!!.code.assert().isEqualTo(INSUFFICIENT)
    }

    @Test
    fun `a command the broker does not acknowledge should fail at ACCEPTED`(): Unit = runBlocking {
        val stock = StockItems()
        val node = stockNode(stock = stock).also { it.start() }
        topics.failure = IllegalStateException("The broker is down.")
        val accepted = node.gateway.execute(reserve("i-4"))
        accepted.status.assert().isEqualTo(CommandResult.Status.FAILED)
        accepted.hop.assert().isEqualTo(Hop.accepted(STOCK))
        accepted.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
        accepted.error!!.message.assert().contains("The broker is down.")
        val waited = node.gateway.execute(reserve("i-4", wait = WaitPlan.COMMITTED.within(10.seconds)))
        waited.status.assert().isEqualTo(CommandResult.Status.FAILED)
        waited.hop.assert().isEqualTo(Hop.accepted(STOCK))
        topics.failure = null
        topics.messages(COMMANDS).assert().isEmpty()
        stock.decided.assert().isEmpty()
    }

    @Test
    fun `a broker that does not acknowledge within the wait's timeout should time the wait out at ACCEPTED`(): Unit =
        runBlocking {
            val never = CompletableDeferred<Unit>()
            val stuck = object : TopicProducer {
                override suspend fun send(topic: String, messages: List<TopicMessage>) = never.await()
            }
            val node = stockNode(producer = stuck).also { it.start() }
            listOf(WaitPlan.ACCEPTED, WaitPlan.COMMITTED).forEach { plan ->
                val result = node.gateway.execute(reserve("i-5", wait = plan.within(100.milliseconds)))
                result.status.assert().isEqualTo(CommandResult.Status.FAILED)
                result.hop.assert().isEqualTo(Hop.accepted(STOCK))
                result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            }
        }

    @Test
    fun `every command accepted by a node that dies at once should still be committed`(): Unit = runBlocking {
        // The first node accepts the commands, but dies before it executes any: its decisions wait on a gate, and
        // once it is killed nothing it does reaches the stores any more, as for a process that is gone.
        val doomed = StockItems().apply { broken = false }
        val killed = Killable(backends)
        val first = stockNode("stock-1", doomed, stores = killed).also { it.start() }
        val ids = (0 until COMMANDS_SENT).map { "i-k$it" }
        ids.forEach { doomed.gates[it] = CompletableDeferred() }
        val accepted = ids.map { first.gateway.execute(reserve(it)) }
        accepted.map { it.status }.toSet().assert().containsExactly(CommandResult.Status.SUCCEEDED)
        killed.kill()
        channel.unbind(first.address)
        doomed.gates.values.forEach { it.completeExceptionally(IllegalStateException("The node is gone.")) }
        // The next owner takes the partitions once the dead node's leases expire, and executes every command from
        // the checkpoint the dead node never moved.
        val survivor = StockItems().apply { broken = false }
        stockNode("stock-2", survivor).start()
        eventually(30.seconds) { survivor.decided.toSet() == accepted.map { it.commandId }.toSet() }
        doomed.decided.assert().isEmpty()
        val log = backends.backends(STOCK).log
        accepted.forEach { result ->
            val streams = log.read(result.ref).toList()
            streams.single().commandId.assert().isEqualTo(result.commandId)
        }
    }

    @Test
    fun `the Kafka router should need a producer and a type the node serves`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                STOCK_SERVICE,
                "stock-x",
                "stock-x.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                backends,
                channel,
                options = fastNodeOptions().copy(kafkaRouted = setOf(ORDER)),
                topics = SourceTopics(topics, PREFIX, topics),
            )
        }.message.assert().contains("does not serve")
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                STOCK_SERVICE,
                "stock-x",
                "stock-x.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                backends,
                channel,
                options = fastNodeOptions().copy(kafkaRouted = setOf(ITEM)),
                topics = SourceTopics(topics, PREFIX),
            )
        }.message.assert().contains("no command transport")
        assertThrows<IllegalArgumentException> { TopicRouter(emptySet(), COMMANDS, PARTITIONS, topics) }
        assertThrows<IllegalArgumentException> { TopicRouter(setOf(ITEM, ORDER), COMMANDS, PARTITIONS, topics) }
        assertThrows<IllegalArgumentException> { TopicRouter(setOf(ITEM), COMMANDS, 0, topics) }
        TopicRouter(setOf(ITEM), COMMANDS, PARTITIONS, topics).routes(ORDER).assert().isFalse()
    }

    /**
     * The stores of [backends], until [kill]: from then on every call to the log, checkpoints, leases and ledger
     * fails, as when the process that held them is gone.
     */
    private class Killable(private val backends: BackendFactory) : BackendFactory {
        @Volatile
        private var killed = false

        fun kill() {
            killed = true
        }

        override fun backends(context: String): ContextBackends {
            val stores = backends.backends(context)
            return stores.copy(
                log = killable(stores.log, EventLog::class.java),
                checkpoints = killable(stores.checkpoints, CheckpointStore::class.java),
                leases = killable(stores.leases, LeaseStore::class.java),
                ledger = killable(stores.ledger, FailureLedger::class.java),
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
        const val PARTITIONS = 4
        const val COMMANDS_SENT = 12
        const val COMMANDS = PREFIX + "wow.stock.commands"
    }
}
