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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.manifest.tools.ManifestJson
import me.ahoo.wow.manifest.tools.ManifestTools
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.wait.WaitChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the contract generator names and what the nodes accept, decided in two modules, held to each other (#6661):
 * the sales context's saga has the four shapes of a subscription, an event handler, an `@OnRejected` handler alone,
 * an `@OnCommitted` handler alone and a handler of both receipts. Every hop of the `Wait` object the generator writes
 * from its Manifest is accepted by the sales node, which runs the saga, and by a peer's directory, which knows the
 * saga from the sales contract; the `@OnRejected` handler alone, which no wait can follow, has none.
 */
class ContractWaitHopsTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private lateinit var node: ServiceNode

    @TempDir
    lateinit var dir: File

    @AfterEach
    fun stop(): Unit = runBlocking {
        node.stop()
    }

    @Test
    fun `every hop the contract names should be one the owner's node and a peer accept`(): Unit = runBlocking {
        val hops = generatedHops()
        node = salesNode().also { it.start() }
        val own = hops.mapNotNull { (name, hop) ->
            val result = node.place("o-$name", WaitPlan.of(hop).within(HOP_TIMEOUT.milliseconds))
            "own $name: ${result.error?.message}".takeIf { result.error?.code == FrameworkCodes.WAIT_INVALID }
        }
        val peer = ContractDirectory(stockManifest(), listOf(peer(fixtureManifest())))
        val atPeer = hops.filterValues { it.stage == Stage.HANDLED }.mapNotNull { (name, hop) ->
            peer.refusal(hop)?.let { "peer $name: $it" }
        }
        (own + atPeer).assert().isEmpty()
        hops.keys.assert().containsExactlyInAnyOrder(
            "ACCEPTED",
            "COMMITTED",
            "SNAPSHOT",
            "QUERYABLE",
            "ORDER_SAGA__ON_ORDER_PLACED",
            "ORDER_SAGA__ON_RESERVED",
            "ORDER_SAGA__ON_RESTOCK_SETTLED",
        )
        // The @OnRejected handler alone is no hop: the node and a peer refuse it, as the contract names none.
        val rejectedOnly = Hop.handled(ON_RESERVE_REJECTED, SALES)
        node.place("o-rejected", WaitPlan.of(rejectedOnly)).error?.code.assert().isEqualTo(FrameworkCodes.WAIT_INVALID)
        peer.refusal(rejectedOnly).assert().isNotNull()
    }

    /** Why a peer refuses a chain of [hop] alone, for a command of the stock context; null when it accepts it. */
    private fun ContractDirectory.refusal(hop: Hop): String? =
        (WaitChain.of(WaitPlan.of(hop), ITEM, this, MAX_HOPS) as? WaitChain.Invalid)?.reason

    /** The hops of the generated `SalesContract.Wait`, by constant, read as the generated source declares them. */
    private fun generatedHops(): Map<String, Hop> {
        val contract = File(dir, "contract.json").also { ManifestJson.write(fixtureManifest(), it) }
        val packages = File(dir, "contract-packages.properties").apply { writeText("$SALES=$PACKAGE\n") }
        val output = File(dir, "sources")
        ManifestTools.generateContractSources(listOf(contract), listOf(packages), emptyList(), output, File(dir, "res"))
        val text = File(output, PACKAGE.replace('.', '/') + "/SalesContract.kt").readText()
        return HOP.findAll(text).associate { match ->
            val (constant, factory, subscription) = match.destructured
            constant to when (factory) {
                "accepted" -> Hop.accepted(SALES)
                "committed" -> Hop.committed(SALES)
                "snapshot" -> Hop.snapshot(SALES)
                "queryable" -> Hop.queryable(SALES)
                else -> Hop.handled(subscription, SALES)
            }
        }
    }

    private fun salesNode(): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel()),
        fixtureManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(fourShapedSaga()))),
        contracts = ContractDirectory(fixtureManifest(), listOf(peer(stockManifest()))),
        topics = SourceTopics(topics, PREFIX, topics),
    ).also { channel.bind(it.address, it.forwardHandler) }

    private suspend fun ServiceNode.place(id: String, wait: WaitPlan): CommandResult {
        val context = context(SALES)
        return context.coordinator.execute(
            context.models.first(),
            Command("place-$id", "place-$id", ORDER.ref(TENANT, id), PlaceOrder(1), wait = wait),
        )
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val PACKAGE = "me.ahoo.wow.fixture.sales"
        const val HOP_TIMEOUT = 300L
        const val MAX_HOPS = 8
        const val ON_ORDER_PLACED = "OrderSaga.onOrderPlaced"
        const val ON_RESERVE_REJECTED = "OrderSaga.onReserveRejected"
        const val ON_RESERVED = "OrderSaga.onReserved"
        const val ON_RESTOCK_SETTLED = "OrderSaga.onRestockSettled"

        /** The source of an event handler: the sales context's orders. */
        val ORDERS = SubscriptionSource(aggregates = listOf("order"))

        /** A constant of the generated `Wait` object: its name, the factory of `Hop` and the subscription it names. */
        val HOP = Regex(
            """public val (\w+): me\.ahoo\.wow\.api\.result\.Hop = me\.ahoo\.wow\.api\.result\.Hop\.(\w+)\((?:"([^"]*)", )?SalesContract\.NAME\)"""
        )

        /**
         * The order saga with the four shapes: [ON_ORDER_PLACED] reserves each placed order's stock, [ON_RESERVE_REJECTED]
         * handles a reservation's rejection alone, [ON_RESERVED] its commit alone, and [ON_RESTOCK_SETTLED] both
         * receipts of a restock.
         */
        fun fourShapedSaga(): ProcessorBinding<OrderState> {
            val none: suspend (Any, Any) -> List<CommandRequest<*>> = { _, _ -> emptyList() }
            return ProcessorBinding.saga(
                "OrderSaga",
                "onOrderPlaced",
                OrderState::class.java,
                rejections = listOf(
                    RejectionBinding.of("OrderSaga", "onReserveRejected", Reserve::class.java, handle = none),
                    RejectionBinding.of("OrderSaga", "onRestockSettled", Restock::class.java, handle = none),
                ),
                commits = listOf(
                    CommittedBinding.of("OrderSaga", "onReserved", Reserve::class.java, handle = none),
                    CommittedBinding.of("OrderSaga", "onRestockSettled", Restock::class.java, handle = none),
                ),
                derives = listOf(Reserve::class.java, Restock::class.java),
            ) { _, _ -> emptyList() }
        }

        /** The sales Manifest with the saga's four subscriptions, as the compiler lists them. */
        fun fixtureManifest(): Manifest {
            val manifest = salesManifest()
            fun subscription(name: String, rejected: Class<*>? = null, committed: Class<*>? = null) =
                SubscriptionManifest(
                    name,
                    SubscriptionKind.SAGA,
                    if (rejected == null && committed == null) ORDERS else SubscriptionSource(),
                    FunctionManifest(FunctionKind.SAGA, SALES, "OrderSaga", name.substringAfter('.')),
                    derives = emptyList(),
                    rejected = rejected?.let { listOf(Manifest.schemaRef(it.name)) },
                    committed = committed?.let { listOf(Manifest.schemaRef(it.name)) },
                )
            val subscriptions = listOf(
                subscription(ON_ORDER_PLACED),
                subscription(ON_RESERVE_REJECTED, rejected = Reserve::class.java),
                subscription(ON_RESERVED, committed = Reserve::class.java),
                subscription(ON_RESTOCK_SETTLED, rejected = Restock::class.java, committed = Restock::class.java),
            )
            return manifest.copyWith(contexts = manifest.contexts.map { it.copyWith(subscriptions = subscriptions) })
        }
    }
}
