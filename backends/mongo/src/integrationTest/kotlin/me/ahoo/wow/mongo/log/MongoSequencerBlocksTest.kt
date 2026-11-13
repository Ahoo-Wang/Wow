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

package me.ahoo.wow.mongo.log

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import org.bson.Document
import org.junit.jupiter.api.Test

/**
 * The blocks of sequences the sequencer reserves for each node (MongoSequencer), as the log's appends meet them: a
 * node's remainder closed, a stopped node's block sealed, a collection a block does not list.
 */
class MongoSequencerBlocksTest : MongoLogFixtures() {
    @Test
    fun `a node's unused remainder is closed when another node's block lies past it`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val first = MongoLogFixture.log(1, context)
        val second = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        first.append(stream("a", 1, aggregate = type))
        (second.append(stream("b", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
            .assert().isEqualTo(MongoEventLog.BLOCK + 1)
        // The first node's tail waits at 2, its own sequence not allocated, then closes its block there.
        tailed(first, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        (first.append(stream("c", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
            .assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        counter(context).getList("blocks", org.bson.Document::class.java).map { it.getLong("from") }
            .assert().doesNotContain(1L)
    }

    @Test
    fun `a stopped node's block is sealed, and its late write takes a new block`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val stopped = MongoLogFixture.log(1, context)
        val owner = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        stopped.append(stream("a", 1, aggregate = type))
        owner.append(stream("b", 1, aggregate = type))
        // The owner's tail waits at 2, in the stopped node's block, then seals the rest of it with placeholders.
        tailed(owner, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        // The node was not stopped after all: its next sequence is taken, so it appends from a new block.
        val late = stopped.append(stream("c", 1, aggregate = type)) as AppendOutcome.Committed
        late.position.sequence.assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        tailed(owner, 3).map { it.stream.ref.id }.assert().containsExactly("a", "b", "c")
    }

    @Test
    fun `an aggregate's next stream lands after its previous one, whichever node wrote that`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val first = MongoLogFixture.log(1, context)
        val second = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        first.append(stream("x", 1, aggregate = type))
        val v2 = second.append(stream("a", 1, aggregate = type)).let { second.append(stream("a", 2, aggregate = type)) }
        val v3 = first.append(stream("a", 3, aggregate = type))
        ((v3 as AppendOutcome.Committed).position.sequence > (v2 as AppendOutcome.Committed).position.sequence)
            .assert().isTrue()
        val versions = withTimeout(TAIL_TIMEOUT_MS + MongoEventLog.STALL_GRACE.inWholeMilliseconds * 4) {
            first.tail(0).take(4).toList()
        }.filter { it.stream.ref.id == "a" }.map { it.stream.version }
        versions.assert().containsExactly(1, 2, 3)
    }

    @Test
    fun `an append to a collection its block does not list takes a new block`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        log.append(stream("a", 1, aggregate = order))
        (log.append(stream("b", 1, aggregate = cart)) as AppendOutcome.Committed).position.sequence
            .assert().isEqualTo(MongoEventLog.BLOCK + 1)
        tailed(log, 2).map { it.stream.ref.type }.assert().containsExactly(order, cart)
    }

    @Test
    fun `a group whose block another node sealed is appended again, each stream alone`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val stopped = MongoLogFixture.log(1, context)
        val owner = MongoLogFixture.log(1, context)
        stopped.append(group(context, "a").single().stream)
        owner.append(group(context, "b").single().stream)
        tailed(owner, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
        stopped.appendAll(group(context, "c", "d")).forEach { outcome ->
            (outcome as AppendOutcome.Committed).position.sequence.assert().isGreaterThan(MongoEventLog.BLOCK * 2)
        }
        tailed(owner, 4).map { it.stream.ref.id }.assert().containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `a block of this node no lane holds is sealed by its own tail, as after a reservation's lost reply`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            val other = MongoLogFixture.log(1, context)
            val type = AggregateType(context, "order")
            log.append(stream("a", 1, aggregate = type))
            // The node's next reservation committed and its reply was lost: the block is listed in the node's name,
            // and none of its lanes knows it.
            val first = counter(context).getList("blocks", Document::class.java).single()
            val lost = Document(first).append("from", MongoEventLog.BLOCK + 1).append("to", MongoEventLog.BLOCK * 2)
            counters(context).updateOne(
                Filters.eq("_id", 0),
                Updates.combine(Updates.push("blocks", lost), Updates.set("sequence", MongoEventLog.BLOCK * 2)),
            )
            (other.append(stream("b", 1, aggregate = type)) as AppendOutcome.Committed).position.sequence
                .assert().isEqualTo(MongoEventLog.BLOCK * 2 + 1)
            // The node's tail closes its own remainder at 2, waits at the lost block's first sequence past the stall
            // grace, then seals the block.
            tailed(log, 2).map { it.stream.ref.id }.assert().containsExactly("a", "b")
            counter(context).getList("blocks", Document::class.java).map { it.getLong("from") }
                .assert().doesNotContain(MongoEventLog.BLOCK + 1)
        }

    private fun counters(context: String) =
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))

    private fun counter(context: String) =
        counters(context).find(Filters.eq("_id", 0)).first()!!
}
