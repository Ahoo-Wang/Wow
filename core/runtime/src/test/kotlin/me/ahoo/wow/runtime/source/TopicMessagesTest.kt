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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.SourceMessage
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messages a topic subscription cannot take as they come: undecodable, of another producer, keyless or holding no state
 * event, passed over or parked at their offset, and a parked aggregate retried by re-scanning its partition.
 */
class TopicMessagesTest : TopicSubscriptionsFixtures() {
    /** The offset of [id]'s record at [version] in its partition. */
    private suspend fun offsetOf(id: String, version: Int): Long = topics.messages(ORDER_TOPIC).single {
        it.key == order(id).toString() && EnvelopeCodec.stateEventOf(it.value).version == version
    }.offset

    /** The ids of [count] orders of [partition]. */
    private suspend fun idsIn(partition: Int, count: Int): List<String> = buildList {
        var n = 0
        while (size < count) {
            val id = "o-${n++}"
            if (partitionOf(id) == partition) {
                add(id)
            }
        }
    }

    @Test
    fun `a message without a key that holds no state event should be passed over`(): Unit = runBlocking {
        start("node-1")
        val partition = partitionOf("o-1")
        topics.send(ORDER_TOPIC, TopicMessage.of("not a ref", BAD.toByteArray(), partition = partition))
        topics.publish(orderRecord("o-1", 1))
        eventually { handled.size == 1 }
        eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partition) == 2L }
        ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
        undecodable.assert().containsExactly(0L to false)
    }

    @Test
    fun `a record of another producer, such as 9x on a topic of the same name, should stop the read before it`(): Unit =
        runBlocking {
            val partition = partitionOf("o-1")
            topics.send(ORDER_TOPIC, TopicMessage.of("o-1", NINE_X_STATE_EVENT.toByteArray(), partition = partition))
            val foreign = assertThrows<ForeignTopicRecordException> {
                TopicEntries(ORDER_TOPIC, topics, PARTITIONS).tail(partition, null).toList()
            }
            foreign.offset.assert().isEqualTo(0L)
            foreign.message.assert().contains("Offset 0 of partition $partition of $ORDER_TOPIC")
                .contains("not a Wow envelope").contains("such as a 9.x service")
                .contains("wow.kafka.topics.prefix")

            // A wrong topic is no failure of an aggregate: nothing parks, and the record after it is never reached.
            val reads = AtomicInteger()
            val counting = object : PartitionedSource by topics {
                override fun read(topic: String, partition: Int, from: Long): Flow<SourceMessage> =
                    topics.read(topic, partition, from).also { counted(partition) }

                override fun read(topic: String, partition: Int, from: Long, until: Long): Flow<SourceMessage> =
                    topics.read(topic, partition, from, until).also { counted(partition) }

                private fun counted(read: Int) {
                    if (read == partition) reads.incrementAndGet()
                }
            }
            start("node-1", source = counting)
            topics.publish(orderRecord("o-1", 1))
            // Two more reads of the partition, each stopped at the foreign record: the second opens only once the first
            // failed, which opened after o-1 was published.
            val opened = reads.get()
            eventually { reads.get() >= opened + 2 }
            handled.size.assert().isZero()
            (checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partition) ?: 0L).assert().isZero()
            ledger.list(BOARD_ID).assert().isEmpty()
            undecodable.assert().isEmpty()
            // Another partition reads on.
            val other = idsIn((partition + 1) % PARTITIONS, 1).single()
            topics.publish(orderRecord(other, 1))
            eventually { handled.versionsOf(other) == listOf(1) }
        }

    @Test
    fun `an undecodable message keyed by a ref whose tenant holds a slash should park that aggregate`(): Unit =
        runBlocking {
            // D408: the key is the ref's own text, its '/' escaped, so it reads back as this aggregate and no other.
            start("node-1")
            val slashed = ORDER.ref("a/b", "o-1")
            topics.send(ORDER_TOPIC, TopicMessage.of(slashed.toString(), BAD.toByteArray()))
            eventually { ledger.parked(BOARD_ID, slashed) != null }
            ledger.parked(BOARD_ID, ORDER.ref("a", "b/o-1")).assert().isNull()
        }

    @Test
    fun `a message whose key is no ref's text should be passed over, as one without a key`(): Unit = runBlocking {
        start("node-1")
        val partition = partitionOf("o-1")
        topics.send(ORDER_TOPIC, TopicMessage.of("sales.order/t/%zz", BAD.toByteArray(), partition = partition))
        topics.publish(orderRecord("o-1", 1))
        eventually { handled.size == 1 }
        undecodable.assert().containsExactly(0L to false)
    }

    @Test
    fun `a message of an aggregate that holds no state event should park the aggregate there`(): Unit =
        runBlocking {
            // Review A3-14: failure is data; the operator sees it and decides.
            val subscriptions = start("node-1")
            topics.publish(orderRecord("o-1", 1))
            eventually { handled.versionsOf("o-1") == listOf(1) }
            topics.send(ORDER_TOPIC, TopicMessage.of(order("o-1").toString(), BAD.toByteArray()))
            topics.publish(orderRecord("o-1", 2))
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            val parked = ledger.parked(BOARD_ID, order("o-1"))!!
            parked.reason.assert().contains("cannot be decoded")
            undecodable.assert().containsExactly(parked.fromPosition!! to true)
            parked.fromPosition.assert().isEqualTo(
                topics.messages(ORDER_TOPIC).single { String(it.value) == BAD }.offset
            )
            // The aggregate's later record waits behind it; the checkpoint moves past both.
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf("o-1")) == 3L }
            handled.versionsOf("o-1").assert().containsExactly(1)
            // A retry meets it again; a skip resolves it.
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
        }

    @Test
    fun `a retry from a later version should stop at an undecodable message of the aggregate after it`(): Unit =
        runBlocking {
            // #5542: the undecodable message's entry has no version of its own; it is never older than the parked one.
            val subscriptions = start("node-1")
            failing = "o-1" to 2
            topics.publish(orderRecord("o-1", 1), orderRecord("o-1", 2))
            eventually { ledger.parked(BOARD_ID, order("o-1"))?.fromVersion == 2 }
            topics.send(ORDER_TOPIC, TopicMessage.of(order("o-1").toString(), BAD.toByteArray()))
            topics.publish(orderRecord("o-1", 4))
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf("o-1")) == 4L }
            failing = null
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            handled.versionsOf("o-1").assert().containsExactly(1, 2)
            val parked = ledger.parked(BOARD_ID, order("o-1"))!!
            parked.reason.assert().contains("cannot be decoded")
            parked.fromPosition.assert().isEqualTo(
                topics.messages(ORDER_TOPIC).single { String(it.value) == BAD }.offset
            )
            // Still parked there: neither a retry nor the tail goes past it, until it is skipped.
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            topics.publish(orderRecord("o-1", 5))
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partitionOf("o-1")) == 5L }
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            topics.publish(orderRecord("o-1", 6))
            eventually { handled.versionsOf("o-1") == listOf(1, 2, 6) }
        }

    @Test
    fun `a failing aggregate should be parked at its offset and retried by re-scanning its partition`(): Unit =
        runBlocking {
            val (parkedId, otherId) = idsIn(partitionOf("o-1"), 2)
            val subscriptions = start("node-1")
            failing = parkedId to 2
            topics.publish(orderRecord(parkedId, 1), orderRecord(parkedId, 2), orderRecord(otherId, 1))
            topics.publish(orderRecord(parkedId, 3))
            eventually { ledger.parked(BOARD_ID, order(parkedId)) != null }
            val parked = ledger.parked(BOARD_ID, order(parkedId))!!
            parked.fromVersion.assert().isEqualTo(2)
            parked.fromPosition.assert().isEqualTo(offsetOf(parkedId, 2))
            parked.skipOnly.assert().isFalse()
            // The other aggregate of the partition goes on; the parked one's later records wait.
            eventually { handled.versionsOf(otherId) == listOf(1) }
            handled.versionsOf(parkedId).assert().containsExactly(1)
            failing = null
            subscriptions.retry(BOARD, order(parkedId)).assert().isTrue()
            handled.versionsOf(parkedId).assert().containsExactly(1, 2, 3)
            ledger.parked(BOARD_ID, order(parkedId)).assert().isNull()
            topics.publish(orderRecord(parkedId, 4))
            eventually { handled.versionsOf(parkedId) == listOf(1, 2, 3, 4) }
        }

    @Test
    fun `a retry that fails again should stay parked from where it failed, at that record's offset`(): Unit =
        runBlocking {
            val subscriptions = start("node-1")
            failing = "o-1" to 2
            topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            failing = "o-1" to 3
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            val parked = ledger.parked(BOARD_ID, order("o-1"))!!
            parked.fromVersion.assert().isEqualTo(3)
            parked.fromPosition.assert().isEqualTo(offsetOf("o-1", 3))
            failing = null
            subscriptions.retry(BOARD, order("o-1")).assert().isTrue()
            handled.versionsOf("o-1").assert().containsExactly(1, 2, 3, 4)
        }

    @Test
    fun `a parked aggregate whose offset retention passed should be unrecoverable and only skipped`(): Unit =
        runBlocking {
            val subscriptions = start("node-1")
            failing = "o-1" to 2
            topics.publish(*(1..3).map { orderRecord("o-1", it) }.toTypedArray())
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            val position = ledger.parked(BOARD_ID, order("o-1"))!!.fromPosition!!
            topics.retain(ORDER_TOPIC, partitionOf("o-1"), position + 1)
            failing = null
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            val parked = ledger.parked(BOARD_ID, order("o-1"))!!
            parked.skipOnly.assert().isTrue()
            // The reason is the text alone: the field says it can only be skipped.
            parked.reason.assert().startsWith("Partition ").contains("no longer retains offset $position")
            parked.fromVersion.assert().isEqualTo(2)
            parked.fromPosition.assert().isEqualTo(position)
            // Marked once: a retry no longer tries, whatever an operator marks the entry.
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            ledger.amend(BOARD_ID, order("o-1"), ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE))
            subscriptions.retry(BOARD, order("o-1")).assert().isFalse()
            ledger.parked(BOARD_ID, order("o-1"))!!.skipOnly.assert().isTrue()
            subscriptions.skip(BOARD, order("o-1")).assert().isTrue()
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
            topics.publish(orderRecord("o-1", 4))
            eventually { handled.versionsOf("o-1") == listOf(1, 4) }
            assertThrows<IllegalArgumentException> { runBlocking { subscriptions.skip("Other.onOrder", order("o-1")) } }
        }
}
