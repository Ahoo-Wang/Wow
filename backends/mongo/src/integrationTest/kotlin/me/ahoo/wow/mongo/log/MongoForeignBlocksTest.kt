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

import com.mongodb.ReadConcern
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.ExecutionLease
import me.ahoo.wow.spi.log.Partitioner
import org.bson.Document
import org.junit.jupiter.api.Test

/**
 * Which partitions another writer appeared in since the log was last asked (`MongoEventLog.appendedElsewhere`): each
 * kind of block another node appends through is reported once, a block already seen or read through is not, this
 * log's own blocks never are, and what it remembers is what the counters list of the partitions it is asked about.
 */
class MongoForeignBlocksTest : MongoLogFixtures() {
    @Test
    fun `another node's newly listed block is reported once, and not once seen`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val owner = MongoLogFixture.log(1, context)
        val other = MongoLogFixture.log(1, context)
        val type = AggregateType(context, "order")
        owner.append(stream("a", 1, aggregate = type))
        // Its own block is never another writer.
        owner.appendedElsewhere(mapOf(0 to 1L)).assert().isEmpty()
        other.append(stream("b", 1, aggregate = type))
        owner.appendedElsewhere(mapOf(0 to 1L)).assert().containsExactly(0)
        // Seen: not reported again, whether the caller has read it or not.
        owner.appendedElsewhere(mapOf(0 to 1L)).assert().isEmpty()
        other.append(stream("c", 1, aggregate = type))
        owner.appendedElsewhere(mapOf(0 to 1L)).assert().isEmpty()
    }

    @Test
    fun `an append without the partition's lease is reported once, and not once the caller read it`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val owner = MongoLogFixture.log(1, context)
            owner.append(stream("a", 1, aggregate = type))
            owner.appendedElsewhere(mapOf(0 to 1L)).assert().isEmpty()
            val degraded = MongoLogFixture.log(1, context)
            val exact = withContext(ExecutionLease.NOT_HELD) {
                degraded.append(stream("b", 1, aggregate = type)) as AppendOutcome.Committed
            }
            owner.appendedElsewhere(mapOf(0 to 1L)).assert().containsExactly(0)
            owner.appendedElsewhere(mapOf(0 to exact.position.sequence)).assert().isEmpty()
        }

    @Test
    fun `a block a restarted process left is another writer's to the new process, reported on first sight only`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val type = AggregateType(context, "order")
            val before = MongoLogFixture.log(1, context)
            before.append(stream("a", 1, aggregate = type))
            // The same node, restarted: a new process, with a block of 256 sequences its former one left open.
            val restarted = MongoLogFixture.log(1, context)
            restarted.appendedElsewhere(mapOf(0 to 1L)).assert().containsExactly(0)
            restarted.appendedElsewhere(mapOf(0 to 1L)).assert().isEmpty()
        }

    @Test
    fun `a block the caller has read through is not reported, even on first sight`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val degraded = MongoLogFixture.log(1, context)
        val exact = withContext(ExecutionLease.NOT_HELD) {
            degraded.append(stream("a", 1, aggregate = type)) as AppendOutcome.Committed
        }
        val owner = MongoLogFixture.log(1, context)
        owner.appendedElsewhere(mapOf(0 to exact.position.sequence)).assert().isEmpty()
    }

    @Test
    fun `what is remembered is what the counters list, of the partitions asked about`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val type = AggregateType(context, "order")
        val owner = MongoLogFixture.log(2, context)
        val other = MongoLogFixture.log(2, context)
        val id = (0..100).map { "o-$it" }.first { Partitioner.partitionOf(type.ref("tenant", it), 2) == 0 }
        other.append(stream(id, 1, aggregate = type))
        val counters = MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "partition"))
        val sequencer = MongoSequencer(counters, "owner", 256, 2)
        // This node holds no block, so it retires none: its placeholders are never written.
        val blocks = MongoForeignBlocks(
            counters.withReadConcern(ReadConcern.MAJORITY),
            sequencer,
            mockk<MongoLogPlaceholders>(),
            context,
            2,
        )
        blocks.appendedElsewhere(mapOf(0 to 0L, 1 to 0L)).assert().containsExactly(0)
        blocks.remembered(0).assert().isEqualTo(1)
        // A partition not asked about is forgotten (its tail closed): asked again, it is reported again.
        blocks.appendedElsewhere(mapOf(1 to 0L)).assert().isEmpty()
        blocks.remembered(0).assert().isZero()
        blocks.appendedElsewhere(mapOf(0 to 0L)).assert().containsExactly(0)
        // A block the counter no longer lists (taken out by its node, or sealed) is no longer remembered.
        counters.updateOne(Filters.eq("_id", 0), Updates.set(MongoSequencer.BLOCKS, emptyList<Document>()))
        blocks.appendedElsewhere(mapOf(0 to 0L)).assert().isEmpty()
        blocks.remembered(0).assert().isZero()
    }

    @Test
    fun `a partition the log does not have, or one without its counter, is refused`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(2, context)
        log.appendedElsewhere(emptyMap()).assert().isEmpty()
        assertThrownBy<IllegalArgumentException> { runBlocking { log.appendedElsewhere(mapOf(2 to 0L)) } }
            .hasMessage("No partition 2 among 2.")
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "partition"))
            .deleteOne(Filters.eq("_id", 1))
        assertThrownBy<IllegalStateException> { runBlocking { log.appendedElsewhere(mapOf(0 to 0L, 1 to 0L)) } }
            .hasMessage("The log of $context has no counter of partition 1.")
    }
}
