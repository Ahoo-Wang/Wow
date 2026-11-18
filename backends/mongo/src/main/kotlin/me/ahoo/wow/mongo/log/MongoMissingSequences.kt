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

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_UNKNOWN
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.STALL_GRACE
import me.ahoo.wow.mongo.mongo
import org.bson.Document

/**
 * The sequence one tail of [partition] waits at ([MongoEventLog.tail]): missing while something lies past it, where an
 * append of some node may still land, so the tail never steps over it. One this node never gave out of its current
 * block cannot fill: it is closed at once with the rest of the block ([MongoSequencer.unallocated]). Any other,
 * missing for [STALL_GRACE], is resolved through the counter's open blocks ([MongoSequencer.resolve]), read from
 * [counters] as a majority committed them: this node's own remainder is closed, its own unfilled sequence filled,
 * another node's block sealed. One per tail.
 *
 * **What bounds the closes at once.** The grace paced the closes of a partition to one a second; these are not paced,
 * and the tail reads again at once after each. Each closes one block of this node, and is met only when a sequence
 * another node wrote lies past it: this node writes past its current block only once that block is retired. So a
 * partition's closes at once are at most the blocks this node opens that another node's appends land past, no more
 * than those foreign appends, which only a partition that changes hands has. Each costs one range placeholder, and
 * the node's next append there one counter update for a new block, both of which the grace path paid as well, later.
 */
internal class MongoMissingSequences(
    private val partition: Int,
    private val sequencer: MongoSequencer,
    private val placeholders: MongoLogPlaceholders,
    private val collections: MongoLogCollections,
    private val counters: MongoCollection<Document>,
    private val dispatcher: CoroutineDispatcher,
) {
    /** The sequence waited at, 0 for none, and since when. */
    private var stalled = 0L
    private var since = 0L

    /**
     * The tail's read found [missing], null for none: closed at once when it is this node's never given out, else
     * resolved once it has been missing for the grace. True when it was closed at once: the tail reads again unpaced.
     */
    suspend fun met(missing: Long?): Boolean {
        if (missing != null && closedAtOnce(missing)) {
            stalled = 0L
            return true
        }
        when {
            missing == null -> stalled = 0L
            missing != stalled -> {
                stalled = missing
                since = System.nanoTime()
            }

            System.nanoTime() - since >= STALL_GRACE.inWholeNanoseconds -> {
                dispatcher.mongo { resolve(missing) }
                since = System.nanoTime()
            }
        }
        return false
    }

    /** Whether [sequence] was this node's never given out: its block's remainder is then one range placeholder. */
    private suspend fun closedAtOnce(sequence: Long): Boolean {
        val closing = sequencer.unallocated(partition, sequence) ?: return false
        dispatcher.mongo { placeholders.close(closing) }
        return true
    }

    /**
     * Resolves [sequence], missing past the grace while something lies past it, through the counter's open blocks
     * ([MongoSequencer.resolve]).
     */
    private fun resolve(sequence: Long) {
        val counter = counters.find(Filters.eq(ID, partition)).first()
        when (val resolution = sequencer.resolve(partition, sequence, sequencer.openBlocks(counter))) {
            MongoSequencer.Resolution.Wait -> Unit
            is MongoSequencer.Resolution.Close -> placeholders.close(resolution.closing)
            is MongoSequencer.Resolution.Fill ->
                placeholders.fill(collections.named(resolution.collection), partition, sequence, GAP_UNKNOWN)

            is MongoSequencer.Resolution.Seal -> placeholders.seal(partition, sequence, resolution.block)
        }
    }
}
