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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test

/**
 * What the intake refuses or parks: a technical failure parked at the command's offset, command and aggregate types the
 * context does not know, and messages that hold no command.
 */
class CommandIntakeRefusalsTest : CommandIntakeFixtures() {
    @Test
    fun `a technical failure should park the aggregate at the command's offset until it is retried`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val broken = reserve("c-broken", BREAKING, item = "i-b")
            val after = reserve("c-after", 1, item = "i-b")
            val elsewhere = reserve("c-elsewhere", 1, item = "i-e")
            val ledger = stockBackends.backends(STOCK).ledger
            eventually { ledger.isParked(INTAKE, broken.ref) }
            val parked = ledger.parked(INTAKE, broken.ref)!!
            parked.fromPosition.assert().isEqualTo(0L)
            parked.reason.assert().contains("unreachable")
            eventually { elsewhere.id in stock.decided }
            // The parked aggregate's later command waits; the checkpoint passes both.
            val end = topics.offsets(COMMANDS, Partitioner.partitionOf(broken.ref, PARTITIONS)).end
            eventually { checkpoint(node, broken.ref) == end }
            stock.decided.assert().doesNotContain(broken.id, after.id)
            stock.broken = false
            node.context(STOCK).intake!!.retry(CommandIntake.NAME, broken.ref).assert().isTrue()
            stock.decided.assert().contains(broken.id, after.id)
            ledger.isParked(INTAKE, broken.ref).assert().isFalse()
            // A command of the aggregate after the retry runs as any other.
            val later = reserve("c-later", 1, item = "i-b")
            eventually { later.id in stock.decided }
        }

    @Test
    fun `a command type the context does not know should be rejected, not park its aggregate`(): Unit =
        runBlocking {
            // Review X-5: a sender's mistake is its rejection; the aggregate it names takes its later commands.
            val node = stockNode().also { it.start() }
            val unknown = Command("c-u", "r-c-u", ITEM.ref(TENANT, "i-u"), Reserve(1))
            val partition = Partitioner.partitionOf(unknown.ref, PARTITIONS)
            val replyTo = ReplyTo(SALES, "OrderSaga.onOrderPlaced", ORDER.ref(TENANT, "o-u"), 1)
            val wire = ClusterCodec.wire("stock.item.Unknown", unknown, replyTo)
            topics.send(COMMANDS, CommandMessages.command(wire, partition))
            val later = reserve("c-u-later", 1, item = "i-u")
            eventually { later.id in stock.decided }
            val ledger = stockBackends.backends(STOCK).ledger
            ledger.isParked(INTAKE, unknown.ref).assert().isFalse()
            eventually { checkpoint(node, unknown.ref) == 2L }
            val rejected = CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
            rejected.commandId.assert().isEqualTo("c-u")
            rejected.commandType.assert().isEqualTo("stock.item.Unknown")
            rejected.ref.assert().isEqualTo(unknown.ref)
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
            rejected.error.message.assert().contains("no command type stock.item.Unknown")
            rejected.executionId.ref.assert().isEqualTo(ORDER.ref(TENANT, "o-u"))
        }

    @Test
    fun `an aggregate type the context does not know should be rejected without a receipt when none is asked`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val ref = AggregateRef(AggregateType(STOCK, "shelf"), TENANT, "s-1")
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            val wire = ClusterCodec.wire(
                "stock.shelf.Reserve",
                Command("c-s", "r-c-s", ref, Reserve(1)),
            )
            topics.send(COMMANDS, CommandMessages.command(wire, partition))
            eventually { checkpoint(node, ref) == 1L }
            stockBackends.backends(STOCK).ledger.isParked(INTAKE, ref).assert().isFalse()
            topics.messages(RECEIPTS).assert().isEmpty()
        }

    @Test
    fun `a saga's command of a type the other context does not know yet should come back as its rejection`(): Unit =
        runBlocking {
            // Review X-5: the sales service has a newer stock contract, which names the reservation ReserveV2.
            val stockNode = stockNode().also { it.start() }
            val sales = salesNode(peer = peer(renamed(stockManifest(), "Reserve", "ReserveV2"))).also { it.start() }
            sales.place("o-v2", 1).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // The saga's @OnRejected handler takes the rejection and cancels the order.
            eventually { cancelled.size == 1 }
            val rejected = rejections.single()
            rejected.commandType.assert().isEqualTo("stock.item.ReserveV2")
            rejected.error.code.assert().isEqualTo(FrameworkCodes.COMMAND_UNHANDLED)
            rejected.ref.assert().isEqualTo(ITEM.ref(TENANT, "o-v2"))
            stock.decided.assert().isEmpty()
            // The stock item is not parked: its later commands are executed.
            val item = ITEM.ref(TENANT, "o-v2")
            stockBackends.backends(STOCK).ledger.isParked(INTAKE, item).assert().isFalse()
            val later = reserve("c-v2-later", 1, item = "o-v2")
            eventually { later.id in stock.decided }
            eventually { checkpoint(stockNode, item) == 2L }
        }

    @Test
    fun `a message of an aggregate that holds no command should park the aggregate there`(): Unit = runBlocking {
        // Review A3-14: an undecodable command is not dropped silently.
        val node = stockNode().also { it.start() }
        val ref = ITEM.ref(TENANT, "i-garbled")
        val partition = Partitioner.partitionOf(ref, PARTITIONS)
        topics.send(COMMANDS, TopicMessage.of(ref.toString(), "not an envelope".toByteArray(), partition = partition))
        val after = reserve("c-after-garbled", 1, item = "i-garbled")
        val ledger = stockBackends.backends(STOCK).ledger
        eventually { ledger.isParked(INTAKE, ref) }
        ledger.parked(INTAKE, ref)!!.reason.assert().contains("cannot be decoded")
        eventually { checkpoint(node, ref) == topics.offsets(COMMANDS, partition).end }
        stock.decided.assert().doesNotContain(after.id)
        // Skipped by an operator, the aggregate takes its commands again.
        node.context(STOCK).intake!!.skip(CommandIntake.NAME, ref).assert().isTrue()
        val later = reserve("c-later-garbled", 1, item = "i-garbled")
        eventually { later.id in stock.decided }
    }

    @Test
    fun `a message that names no aggregate and holds no command should be passed over, parking nothing`(): Unit =
        runBlocking {
            val node = stockNode().also { it.start() }
            val ref = ITEM.ref(TENANT, "i-after-keyless")
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            topics.send(
                COMMANDS,
                TopicMessage.of("no aggregate", "not an envelope".toByteArray(), partition = partition)
            )
            val after = reserve("c-after-keyless", 1, item = "i-after-keyless")
            eventually { after.id in stock.decided }
            stockBackends.backends(STOCK).ledger.isParked(INTAKE, ref).assert().isFalse()
            node.context(STOCK).intake.assert().isNotNull()
        }
}
