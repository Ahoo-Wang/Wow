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

package me.ahoo.wow.runtime.ledger

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.command.BREAKING
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.CommandMessages
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.command.ITEM
import me.ahoo.wow.runtime.command.PREFIX
import me.ahoo.wow.runtime.command.Reserve
import me.ahoo.wow.runtime.command.STOCK
import me.ahoo.wow.runtime.command.STOCK_SERVICE
import me.ahoo.wow.runtime.command.StockItems
import me.ahoo.wow.runtime.command.TENANT
import me.ahoo.wow.runtime.command.stockManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.LedgerAggregate
import me.ahoo.wow.spi.ledger.LedgerEntry
import me.ahoo.wow.spi.ledger.LedgerResolution
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import kotlin.time.Duration.Companion.milliseconds

/**
 * A service's failure ledger and the ops context end to end on one node (decision D141), over [InMemoryTopics]: the
 * stock context's intake parks an item, its node publishes the entry to `wow.ops.commands`, and a `wow.ledger.Resume`
 * on the stock context's command topic, as the ops context sends it, resumes the item there and is published resolved.
 */
class LedgerNodeTest {
    private val topics = InMemoryTopics(2).also {
        it.create(COMMANDS, PARTITIONS)
        it.create(OPS_COMMANDS, 2)
        it.create(RECEIPTS, 1)
    }
    private val channel = LoopbackClusterChannel()
    private val stock = StockItems()
    private val backends = InMemoryBackends(PARTITIONS)

    /** An authorizer that refuses every caller: the ledger's commands are not the operator's to authorize (D226). */
    private val authorizer = CommandAuthorizer { _, _ -> Authorization.Denied("Not an operator.") }
    private val node = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(stock.model),
        stockManifest(),
        backends,
        channel,
        options = fastNodeOptions().copyWith(ledger = LedgerPublishing(interval = 20.milliseconds)),
        admission = AdmissionPlugins(authorizer = authorizer),
        contracts = ContractDirectory(stockManifest()),
        topics = SourceTopics(topics, PREFIX, topics, intake = true),
    ).also { channel.bind(it.address, it.forwardHandler) }
    private val sender = CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private fun records(type: String): List<JsonNode> = topics.messages(OPS_COMMANDS)
        .map { CommandMessages.commandOf(EnvelopeCodec.decode(it.value)) }
        .filter { it.type == "ops.parked.$type" }
        .map { it.record }

    /** [type] with [body], as the saga of [source] derives it for [operator]. */
    private suspend fun ledgerCommand(type: String, body: Any, parked: AggregateRef, operator: String, source: String) {
        val ref = Ledger.targetOf(STOCK, Ledger.parkedId(STOCK_SERVICE, CommandIntake.NAME, parked))
        val entry = AggregateType(source, "parked").ref(TENANT, "entry-1")
        val wire = CommandWire(
            type = type,
            id = "l-$operator-$type",
            requestId = "l-$operator-$type",
            ref = ref,
            body = ClusterCodec.mapper.valueToTree(body),
            causation = Causation("root-$operator", "$entry@2"),
            operator = Operator(operator, TENANT),
            replyTo = ReplyTo(source, "OpsSaga.onParked", entry, 2),
        )
        sender.send(wire)
    }

    @Test
    fun `a parked entry is published to ops, and a resume from ops resolves it on the owning service`(): Unit =
        runBlocking {
            node.ledgerPublisher.assert().isNotNull()
            node.start()
            val broken = Command("c-broken", "r-broken", ITEM.ref(TENANT, "i-b"), Reserve(BREAKING))
            sender.send(broken)
            val ledger = backends.backends(STOCK).ledger
            eventually { ledger.isParked(CommandIntake.NAME, broken.ref) }
            eventually { records(Ledger.RECORD_PARKED).isNotEmpty() }
            val parked = ClusterCodec.mapper.treeToValue(
                records(Ledger.RECORD_PARKED).first(),
                LedgerEntry::class.java
            )
            parked.service.assert().isEqualTo(STOCK_SERVICE)
            parked.context.assert().isEqualTo(STOCK)
            parked.subscription.assert().isEqualTo(CommandIntake.NAME)
            parked.aggregate.assert().isEqualTo(LedgerAggregate.of(broken.ref))
            parked.fromPosition.assert().isEqualTo(0L)
            val target = topics.messages(OPS_COMMANDS).first()
            target.key.assert().isEqualTo(
                "ops.parked/(0)/" + Ledger.parkedId(STOCK_SERVICE, CommandIntake.NAME, broken.ref)
            )

            // A ledger command another context's saga sends: the owning service refuses it, with a receipt to the
            // sender, whoever its operator (decision D226).
            ledgerCommand(
                Ledger.SKIP,
                SkipParked.of(CommandIntake.NAME, broken.ref),
                broken.ref,
                "mallory",
                source = "sales",
            )
            eventually { topics.messages(RECEIPTS).isNotEmpty() }
            ledger.isParked(CommandIntake.NAME, broken.ref).assert().isTrue()

            stock.broken = false
            // The ops context's saga: taken by its source, though the node's authorizer allows no operator.
            ledgerCommand(
                Ledger.RESUME,
                ResumeParked.of(CommandIntake.NAME, broken.ref),
                broken.ref,
                "alice",
                source = Ledger.OPS_CONTEXT,
            )
            eventually { !ledger.isParked(CommandIntake.NAME, broken.ref) }
            stock.decided.assert().contains(broken.id)
            eventually { records(Ledger.RECORD_RESOLVED).isNotEmpty() }
            records(Ledger.RECORD_RESOLVED).first()["outcome"].asString().assert().isEqualTo(LedgerResolution.RESUMED)
        }

    @Test
    fun `a node that publishes its ledger takes the ledger's commands from its topic, though it takes no other`(): Unit =
        runBlocking {
            // Finding F19: without general command intake, the operators' actions would never arrive.
            val broker = InMemoryTopics(2).also {
                it.create(COMMANDS, PARTITIONS)
                it.create(OPS_COMMANDS, 2)
                it.create(RECEIPTS, 1)
            }
            val items = StockItems()
            val publishing = fastNodeOptions().copyWith(ledger = LedgerPublishing(interval = 20.milliseconds))
            val own = LoopbackClusterChannel()
            val publisher = ServiceNode(
                STOCK_SERVICE,
                "stock-4",
                "stock-4.internal:9090",
                listOf(items.model),
                stockManifest(),
                InMemoryBackends(PARTITIONS),
                own,
                options = publishing,
                contracts = ContractDirectory(stockManifest()),
                topics = SourceTopics(broker, PREFIX, broker),
            ).also { own.bind(it.address, it.forwardHandler) }
            val to = CommandSender(ContractDirectory(stockManifest()), SourceTopics(broker, PREFIX, broker))
            publisher.start()
            try {
                // It starts at the end of its topic: ask until it answers, each ask a new command.
                var asks = 0
                eventually {
                    asks++
                    val parked = ITEM.ref(TENANT, "i-$asks")
                    val wire = CommandWire(
                        type = Ledger.SKIP,
                        id = "l-$asks",
                        requestId = "l-$asks",
                        ref = Ledger.targetOf(STOCK, Ledger.parkedId(STOCK_SERVICE, CommandIntake.NAME, parked)),
                        body = ClusterCodec.mapper.valueToTree(SkipParked.of(CommandIntake.NAME, parked)),
                        causation = Causation("root-$asks", "sales.parked/(0)/entry@2"),
                        operator = Operator("mallory", TENANT),
                        replyTo = ReplyTo(
                            "sales",
                            "OpsSaga.onParked",
                            AggregateType("sales", "parked").ref(TENANT, "e"),
                            2
                        ),
                    )
                    to.send(wire)
                    broker.messages(RECEIPTS).isNotEmpty()
                }
                // Any other command on its topic is not the node's to take.
                to.send(Command("c-other", "r-other", ITEM.ref(TENANT, "i-o"), Reserve(1)))
                delay(200)
                items.decided.assert().doesNotContain("c-other")
            } finally {
                publisher.stop()
            }
        }

    @Test
    fun `a node whose command topic has another count runs without the ledger's commands, and starts`(): Unit =
        runBlocking {
            // The topic was made before, by someone else: the node does not refuse to start for what it did not need.
            val broker = InMemoryTopics(2).also {
                it.create(COMMANDS, 1)
                it.create(OPS_COMMANDS, 2)
                it.create(RECEIPTS, 1)
            }
            val own = LoopbackClusterChannel()
            val publisher = ServiceNode(
                STOCK_SERVICE,
                "stock-5",
                "stock-5.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                InMemoryBackends(PARTITIONS),
                own,
                options = fastNodeOptions().copyWith(ledger = LedgerPublishing(interval = 20.milliseconds)),
                contracts = ContractDirectory(stockManifest()),
                topics = SourceTopics(broker, PREFIX, broker, admin = broker),
            ).also { own.bind(it.address, it.forwardHandler) }
            publisher.start()
            try {
                publisher.ledgerPublisher.assert().isNotNull()
                broker.partitions(setOf(COMMANDS)).getValue(COMMANDS).assert().isEqualTo(1)
            } finally {
                publisher.stop()
            }
        }

    @Test
    fun `a node that publishes its ledger needs a command transport`() {
        assertThrows<WowConfigurationException> {
            ServiceNode(
                STOCK_SERVICE,
                "stock-2",
                "stock-2.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                InMemoryBackends(PARTITIONS),
                channel,
                options = fastNodeOptions().copyWith(ledger = LedgerPublishing()),
                contracts = ContractDirectory(stockManifest()),
            )
        }
    }

    @Test
    fun `an automatic publishing opens once the ops command topic exists, and stays shut without an admin to ask`(): Unit =
        runBlocking {
            // Decision D312: the ops service creates its command topic, so it existing means ops is deployed.
            val broker = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
            val publishing = LedgerPublishing(interval = 20.milliseconds, automatic = true)
            val automatic = fastNodeOptions().copyWith(ledger = publishing)
            fun nodeOver(topics: SourceTopics) = ServiceNode(
                STOCK_SERVICE,
                "stock-3",
                "stock-3.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                InMemoryBackends(PARTITIONS),
                LoopbackClusterChannel(),
                options = automatic,
                contracts = ContractDirectory(stockManifest()),
                topics = topics.copyWith(partitionRefresh = 20.milliseconds),
            )
            val asking = nodeOver(SourceTopics(broker, PREFIX, broker, admin = broker))
            asking.start()
            try {
                val publisher = checkNotNull(asking.ledgerPublisher)
                publisher.open.assert().isFalse()
                broker.create(OPS_COMMANDS, 2)
                eventually { publisher.open }
            } finally {
                asking.stop()
            }
            val blind = nodeOver(SourceTopics(broker, PREFIX, broker))
            blind.start()
            try {
                delay(100)
                checkNotNull(blind.ledgerPublisher).open.assert().isFalse()
            } finally {
                blind.stop()
            }
        }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val OPS_COMMANDS = PREFIX + "wow.ops.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
    }
}
