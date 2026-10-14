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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.kernel.InMemoryTopics
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
    private val authorizer = CommandAuthorizer { caller, _ ->
        if (caller.subject == "mallory") Authorization.Denied("Not an operator.") else Authorization.Allowed
    }
    private val node = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(stock.model),
        stockManifest(),
        backends,
        channel,
        options = fastNodeOptions().copy(ledger = LedgerPublishing(interval = 20.milliseconds)),
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

    private suspend fun ledgerCommand(type: String, body: Any, parked: AggregateRef, operator: String) {
        val ref = Ledger.targetOf(STOCK, Ledger.parkedId(STOCK_SERVICE, CommandIntake.NAME, parked))
        val wire = CommandWire(
            type = type,
            id = "l-$operator-$type",
            requestId = "l-$operator-$type",
            ref = ref,
            body = ClusterCodec.mapper.valueToTree(body),
            operator = Operator(operator, TENANT),
            replyTo = ReplyTo("sales", "OpsSaga.onParked", ref, 1),
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

            // An operator ops does not authorize as one: the owning service refuses, with a receipt to the sender.
            ledgerCommand(
                Ledger.SKIP,
                SkipParked(CommandIntake.NAME, LedgerAggregate.of(broken.ref)),
                broken.ref,
                "mallory"
            )
            eventually { topics.messages(RECEIPTS).isNotEmpty() }
            ledger.isParked(CommandIntake.NAME, broken.ref).assert().isTrue()

            stock.broken = false
            ledgerCommand(
                Ledger.RESUME,
                ResumeParked(CommandIntake.NAME, LedgerAggregate.of(broken.ref)),
                broken.ref,
                "alice"
            )
            eventually { !ledger.isParked(CommandIntake.NAME, broken.ref) }
            stock.decided.assert().contains(broken.id)
            eventually { records(Ledger.RECORD_RESOLVED).isNotEmpty() }
            records(Ledger.RECORD_RESOLVED).first()["outcome"].asString().assert().isEqualTo(LedgerResolution.RESUMED)
        }

    @Test
    fun `a node that publishes its ledger needs a command transport`() {
        assertThrows<IllegalArgumentException> {
            ServiceNode(
                STOCK_SERVICE,
                "stock-2",
                "stock-2.internal:9090",
                listOf(StockItems().model),
                stockManifest(),
                InMemoryBackends(PARTITIONS),
                channel,
                options = fastNodeOptions().copy(ledger = LedgerPublishing()),
                contracts = ContractDirectory(stockManifest()),
            )
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val OPS_COMMANDS = PREFIX + "wow.ops.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
    }
}
