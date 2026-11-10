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
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.PartitionLineage
import org.bson.Document
import org.junit.jupiter.api.Test

/** The reads of a partition: its head, against the partition's counter. */
class MongoTailReadsTest : MongoLogFixtures() {
    @Test
    fun `head refuses a partition without its counter, as lineage does`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(2, context)
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "partition"))
            .deleteOne(Filters.eq("_id", 1))
        // A damaged store: the partition is not empty, it has no counter, and both reads say so.
        val missing = "The log of $context has no counter of partition 1."
        assertThrownBy<IllegalStateException> { runBlocking { log.head(1) } }.hasMessage(missing)
        assertThrownBy<IllegalStateException> { runBlocking { log.lineage(1) } }.hasMessage(missing)
    }

    @Test
    fun `a partition's stored lineage reads back, and a counter written without it reads as created`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(2, context)
            val counters = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
                .getCollection(MongoNames.of("wow_log", context, "partition"))
            counters.find(Filters.eq("_id", 0)).first()!!.let { counter ->
                counter.containsKey("parent").assert().isTrue()
                counter["parent"].assert().isNull()
                counter.getInteger("epoch").assert().isZero()
            }
            // What an online split of 10.x writes: partition 1 split from partition 0 (decision D240).
            counters.updateOne(Filters.eq("_id", 1), Updates.combine(Updates.set("parent", 0), Updates.set("epoch", 1)))
            log.lineage(0).assert().isEqualTo(PartitionLineage.ROOT)
            log.lineage(1).assert().isEqualTo(PartitionLineage.of(0, 1))
            // A counter of format 1 written before the lineage fields reads as a partition the log was created with.
            counters.updateOne(Filters.eq("_id", 1), Updates.combine(Updates.unset("parent"), Updates.unset("epoch")))
            log.lineage(1).assert().isEqualTo(PartitionLineage.ROOT)
            MongoLogFixture.log(2, context).lineage(1).assert().isEqualTo(PartitionLineage.ROOT)
            counters.deleteOne(Filters.eq("_id", 1))
            runCatching { log.lineage(1) }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }

    @Test
    fun `a tail finds the aggregate collections another log created after it started`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val reader = MongoLogFixture.log(1, context, listOf("order"))
        val writer = MongoLogFixture.log(1, context)
        val order = AggregateType(context, "order")
        val cart = AggregateType(context, "cart")
        writer.append(stream("a", 1, aggregate = order))
        reader.tail(0).first().stream.ref.assert().isEqualTo(order.ref("tenant", "a"))
        writer.append(stream("b", 1, aggregate = cart))
        writer.append(stream("a", 2, aggregate = order))
        val entries = withTimeout(TAIL_TIMEOUT_MS) { reader.tail(0).take(3).toList() }
        entries.map { it.stream.ref.type }.assert().containsExactly(order, cart, order)
        reader.findByRequestId(cart, "tenant", entries[1].stream.requestId)?.ref.assert().isEqualTo(cart.ref("tenant", "b"))
    }

    @Test
    fun `a tail stops at a missing sequence rather than skip it`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        (1..3).forEach { log.append(stream("a", it, aggregate = AggregateType(context, "order"))) }
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "order"))
            .deleteOne(Filters.eq("sequence", 2L))
        withTimeoutOrNull(TAIL_STALL_MS) { log.tail(0).take(2).toList() }.assert().isNull()
        withTimeout(TAIL_TIMEOUT_MS) { log.tail(0).first() }.position.sequence.assert().isEqualTo(1L)
    }

    @Test
    fun `the head counts the streams of a collection another node registered after this log read the registry`(): Unit =
        runBlocking {
            // A partition's new owner read the registry before its previous owner committed a new aggregate type's
            // first stream; the owner's tails read the store only once the head is past them.
            val context = MongoLogFixture.newContext()
            val owner = MongoLogFixture.log(1, context)
            owner.head(0).assert().isNull()
            val previous = MongoLogFixture.log(1, context)
            previous.append(stream("a", 1, aggregate = AggregateType(context, "order")))
            owner.head(0).assert().isEqualTo(1L)
            val invoice = previous.append(stream("b", 1, aggregate = AggregateType(context, "invoice")))
            owner.head(0).assert().isEqualTo((invoice as AppendOutcome.Committed).position.sequence)
        }

    @Test
    fun `a placeholder that cannot be written is filled by the tail`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        var failures = 2
        val client = faulty { _: Document, insert: () -> Unit ->
            // The stream's insert and then its placeholder's are lost; the tail's fill goes through.
            if (failures > 0) failures-- else insert()
        }
        val log = MongoEventLog(client, MongoLogFixture.DATABASE, context, 1).also { it.createSchema() }
        val type = AggregateType(context, "order")
        log.append(stream("a", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Unknown::class.java)
        log.append(stream("b", 1, aggregate = type)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        tailed(log, 1).single().stream.ref.id.assert().isEqualTo("b")
    }

    private companion object {
        const val TAIL_STALL_MS = 500L
    }
}
