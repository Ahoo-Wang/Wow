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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.runtime.cluster.ReplyTo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The intake's checkpoint against a command's outcome: a copy decided once, the checkpoint passing a command only once
 * its outcome is settled, the receipt durable first, and a crash in between sending the receipt again.
 */
class CommandIntakeCheckpointTest : CommandIntakeFixtures() {
    @Test
    fun `a command delivered twice should be decided once`(): Unit = runBlocking {
        val node = stockNode().also { it.start() }
        val command = reserve("c-1", 1)
        sender.send(command)
        val ref = command.ref
        eventually { checkpoint(node, ref) == 2L }
        stock.decided.assert().containsExactly("c-1")
    }

    @Test
    fun `the checkpoint should pass a command only once its outcome is settled`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        stock.gates["i-slow"] = gate
        val node = stockNode().also { it.start() }
        val slow = reserve("c-slow", 1, item = "i-slow")
        // Another aggregate in the same partition goes on meanwhile, but the checkpoint stays before the slow one.
        val other = generateSequence(0) { it + 1 }.map { "i-$it" }
            .first { Partitioner.partitionOf(ITEM.ref(TENANT, it), PARTITIONS) == Partitioner.partitionOf(slow.ref, PARTITIONS) }
        reserve("c-other", 1, item = other)
        eventually { "c-other" in stock.decided }
        delay(100.milliseconds)
        checkpoint(node, slow.ref).assert().isEqualTo(0L)
        gate.complete(Unit)
        eventually { checkpoint(node, slow.ref) == 2L }
        stock.decided.assert().containsExactlyInAnyOrder("c-slow", "c-other")
    }

    @Test
    fun `a receipt should be durable before the checkpoint passes the rejected command`(): Unit = runBlocking {
        val held = CompletableDeferred<Unit>()
        val producer = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                if (topic == RECEIPTS) {
                    held.await()
                }
                topics.send(topic, messages)
            }
        }
        val node = stockNode(producer = producer).also { it.start() }
        val command = Command(
            "c-r",
            "r-c-r",
            ITEM.ref(TENANT, "i-r"),
            Reserve(AVAILABLE + 1),
        )
        CommandSender(ContractDirectory(stockManifest()), SourceTopics(topics, PREFIX, topics))
            .send(
                command,
                replyTo = me.ahoo.wow.runtime.cluster.ReplyTo(
                    SALES,
                    "OrderSaga.onOrderPlaced",
                    ORDER.ref(TENANT, "o"),
                    1
                )
            )
        eventually { "c-r" in stock.decided }
        delay(100.milliseconds)
        checkpoint(node, command.ref).assert().isEqualTo(0L)
        topics.messages(RECEIPTS).assert().isEmpty()
        held.complete(Unit)
        eventually { checkpoint(node, command.ref) == 1L }
        CommandMessages.rejectedOf(EnvelopeCodec.decode(topics.messages(RECEIPTS).single().value))
            .commandId.assert().isEqualTo("c-r")
    }

    @Test
    fun `a crash between the receipt and the checkpoint should send the receipt again, handled once`(): Unit =
        runBlocking {
            val sales = salesNode().also { it.start() }
            // The first stock node never saves a checkpoint: it crashes after the receipt, before the save.
            val crashing = BackendFactory { context ->
                stockBackends.backends(context).copyWith(checkpoints = DroppedCheckpoints)
            }
            val first = stockNode("stock-1", backends = crashing).also { it.start() }
            sales.place("o-3", AVAILABLE + 1)
            eventually { rejections.size == 1 }
            first.stop()
            nodes -= first
            stockNode("stock-2").start()
            eventually { topics.messages(RECEIPTS).size == 2 }
            val (receipt, copy) = topics.messages(RECEIPTS).map { EnvelopeCodec.decode(it.value).id }
            copy.assert().isEqualTo(receipt)
            stock.decided.size.assert().isEqualTo(2)
            delay(100.milliseconds)
            rejections.size.assert().isEqualTo(1)
            cancelled.size.assert().isEqualTo(1)
        }

    /** A checkpoint store that never saves: the node crashes before each save. */
    private object DroppedCheckpoints : CheckpointStore {
        override suspend fun load(group: String, subscription: String, partition: Int): Long? = null

        override suspend fun list(group: String): List<Checkpoint> = emptyList()

        override suspend fun save(group: String, subscription: String, partition: Int, position: Long) = Unit
    }
}
