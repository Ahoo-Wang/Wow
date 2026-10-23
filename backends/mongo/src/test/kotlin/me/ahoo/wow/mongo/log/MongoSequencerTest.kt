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

import com.mongodb.MongoException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.FindOneAndUpdateOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test

/** [MongoSequencer] over a counter that hands out blocks of [BLOCK] (decision D271). */
class MongoSequencerTest {
    private var reserved = 0L
    private var failReservation = false
    private val counters = mockk<MongoCollection<Document>>(relaxed = true).also { counters ->
        every { counters.findOneAndUpdate(any<Bson>(), any<List<Bson>>(), any<FindOneAndUpdateOptions>()) } answers {
            if (failReservation) throw MongoException("unreachable")
            reserved += BLOCK
            Document(MongoEventLog.SEQUENCE, reserved)
        }
    }
    private val sequencer = MongoSequencer(counters, NODE, BLOCK, 1)
    private val registered = { listOf(ORDER) }

    private fun allocate(collection: String = ORDER, after: Long = 0) =
        sequencer.allocate(0, collection, after, registered)

    private fun removals() = verify(exactly = 0) { counters.updateOne(any<Bson>(), any<Bson>()) }

    @Test
    fun `a block gives its sequences in turn, one reservation for all of them`() {
        (1..BLOCK).map { allocate().sequence }.assert().isEqualTo((1L..BLOCK).toList())
        allocate().sequence.assert().isEqualTo(BLOCK + 1)
        reserved.assert().isEqualTo(BLOCK * 2)
    }

    @Test
    fun `an append to a collection the block does not list takes a new block and closes the old one`() {
        allocate().sequence.assert().isEqualTo(1L)
        val cart = allocate(CART)
        cart.sequence.assert().isEqualTo(BLOCK + 1)
        val closing = checkNotNull(cart.closing)
        listOf(closing.from, closing.to, closing.collection).assert().containsExactly(2L, BLOCK, ORDER)
        removals()
        sequencer.settle(0, 1)
        sequencer.settle(closing)
        verify(exactly = 1) { counters.updateOne(any<Bson>(), any<Bson>()) }
    }

    @Test
    fun `a sequence after one beyond the block takes a new block`() {
        allocate()
        allocate(after = BLOCK * 3).sequence.assert().isEqualTo(BLOCK + 1)
    }

    @Test
    fun `a reservation that fails leaves the current block as it was`() {
        allocate()
        failReservation = true
        assertThrownBy<MongoException> { allocate(CART) }
        failReservation = false
        allocate().sequence.assert().isEqualTo(2L)
    }

    @Test
    fun `a used-up block whose sequences are settled leaves the list when the next is reserved`() {
        (1..BLOCK).forEach { sequencer.settle(0, allocate().sequence) }
        removals()
        allocate()
        verify(exactly = 1) { counters.updateOne(any<Bson>(), any<Bson>()) }
    }

    @Test
    fun `an abandoned block gives no more sequences`() {
        val first = allocate().sequence
        sequencer.abandon(0, first)
        allocate().sequence.assert().isEqualTo(BLOCK + 1)
    }

    @Test
    fun `the tail waits at a sequence in flight or in no block, and seals another node's block`() {
        val inFlight = allocate().sequence
        val mine = MongoSequencer.OpenBlock(NODE, 1, BLOCK, listOf(ORDER))
        val theirs = MongoSequencer.OpenBlock("other", BLOCK + 1, BLOCK * 2, listOf(ORDER))
        sequencer.resolve(0, inFlight, listOf(mine)).assert().isEqualTo(MongoSequencer.Resolution.Wait)
        sequencer.resolve(0, BLOCK * 5, listOf(mine)).assert().isEqualTo(MongoSequencer.Resolution.Wait)
        (sequencer.resolve(0, BLOCK + 3, listOf(mine, theirs)) as MongoSequencer.Resolution.Seal).block
            .assert().isEqualTo(theirs)
    }

    @Test
    fun `the tail closes this node's unallocated remainder, and closes it again while its range is unwritten`() {
        allocate()
        val mine = listOf(MongoSequencer.OpenBlock(NODE, 1, BLOCK, listOf(ORDER)))
        val close = sequencer.resolve(0, 2, mine) as MongoSequencer.Resolution.Close
        close.closing.from.assert().isEqualTo(2L)
        (sequencer.resolve(0, 2, mine) as MongoSequencer.Resolution.Close).closing.from.assert().isEqualTo(2L)
        sequencer.settle(close.closing)
        sequencer.resolve(0, 2, mine).assert().isEqualTo(MongoSequencer.Resolution.Wait)
    }

    @Test
    fun `the tail fills an unfilled sequence in the collection its append wrote to`() {
        allocate()
        val sequence = allocate(CART).sequence
        sequencer.unfilled(0, sequence, CART)
        val mine = listOf(MongoSequencer.OpenBlock(NODE, BLOCK + 1, BLOCK * 2, listOf(ORDER, CART)))
        (sequencer.resolve(0, sequence, mine) as MongoSequencer.Resolution.Fill).collection.assert().isEqualTo(CART)
        sequencer.settle(0, sequence)
        sequencer.resolve(0, sequence, mine).assert().isEqualTo(MongoSequencer.Resolution.Wait)
    }

    @Test
    fun `the counter's open blocks read back`() {
        sequencer.openBlocks(null).assert().isEmpty()
        val counter = Document(
            MongoSequencer.BLOCKS,
            listOf(
                Document(MongoSequencer.NODE, NODE).append(MongoSequencer.FROM, 1L).append(MongoSequencer.TO, BLOCK)
                    .append(MongoSequencer.COLLECTIONS, listOf(ORDER)),
            ),
        )
        sequencer.openBlocks(counter).assert().containsExactly(MongoSequencer.OpenBlock(NODE, 1, BLOCK, listOf(ORDER)))
    }

    @Test
    fun `a block holds at least one sequence`() {
        assertThrownBy<IllegalArgumentException> { MongoSequencer(counters, NODE, 0, 1) }
    }

    private companion object {
        const val BLOCK = 4L
        const val NODE = "node"
        const val ORDER = "wow_log__shop__order"
        const val CART = "wow_log__shop__cart"
    }
}
