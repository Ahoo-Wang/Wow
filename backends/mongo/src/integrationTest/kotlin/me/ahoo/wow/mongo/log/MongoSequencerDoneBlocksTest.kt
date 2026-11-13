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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import org.bson.Document
import org.junit.jupiter.api.Test

/**
 * The done blocks of the counter's list (MongoSequencer): a node takes its own out in its next reservation; a tail that
 * read through one leaves it listed for that reservation until a grace passed (DoneBlocks, whose grace is unit-tested
 * on a test time source), then takes it out whoever's it is.
 *
 * Each case fills a node's first block exactly: one partition, and one group of [MongoEventLog.BLOCK] first versions,
 * which allocates the block's every sequence and settles each, so the block is done with no reservation after it.
 */
class MongoSequencerDoneBlocksTest : MongoLogFixtures() {
    private fun fill(log: MongoEventLog, context: String, node: Int) {
        val ids = (1..MongoEventLog.BLOCK).map { "n$node-$it" }.toTypedArray()
        runBlocking { log.appendAll(group(context, *ids)) }.forEach {
            it.assert().isInstanceOf(AppendOutcome.Committed::class.java)
        }
    }

    /** The sequence [log] appends one more first version of [context] at. */
    private fun next(log: MongoEventLog, context: String): Long {
        val outcome = runBlocking { log.append(stream("next", 1, aggregate = AggregateType(context, "order"))) }
        return (outcome as AppendOutcome.Committed).position.sequence
    }

    private fun listed(context: String): List<Long> =
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE).getCollection(collection(context, "partition"))
            .find(Filters.eq("_id", 0)).first()!!
            .getList("blocks", Document::class.java).orEmpty().map { it.getLong("from") }

    @Test
    fun `a done block a tail read through should stay listed within the grace`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            fill(MongoLogFixture.log(1, context), context, 1)
            listed(context).assert().containsExactly(1L)
            val owner = MongoLogFixture.log(1, context)
            // Certain: the read that delivered the block's last sequence went through it, well within the grace
            // (DoneBlocks, unit-tested on a test time source), so it left the block listed.
            tailed(owner, MongoEventLog.BLOCK.toInt()).size.assert().isEqualTo(MongoEventLog.BLOCK.toInt())
            listed(context).assert().containsExactly(1L)
        }

    @Test
    fun `a block the read has not gone through should stay listed`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(1, context)
        next(log, context).assert().isEqualTo(1L)
        tailed(MongoLogFixture.log(1, context), 1)
        // Its sequences after the first are not written: the read did not go through the block.
        listed(context).assert().containsExactly(1L)
    }

    @Test
    fun `a node's next reservation should take its done block out in its own update`(): Unit =
        runBlocking {
            val context = MongoLogFixture.newContext()
            val log = MongoLogFixture.log(1, context)
            fill(log, context, 1)
            // The node's own next reservation takes its done block out in the same update.
            next(log, context).assert().isEqualTo(MongoEventLog.BLOCK + 1)
            listed(context).assert().containsExactly(MongoEventLog.BLOCK + 1)
        }
}
