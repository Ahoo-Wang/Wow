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
import com.mongodb.client.model.Projections
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import org.bson.Document

/**
 * The partitions in which another writer appeared since this log was last asked about them
 * ([MongoEventLog.appendedElsewhere]): each counter's list of open blocks, one find for every partition asked, against
 * the other nodes' blocks it listed at the previous call that asked about that partition.
 *
 * Another node appends to a partition only from a block the partition's counter lists ([MongoSequencer]): one it
 * reserved, the single sequence of an append without the partition's lease, or a block a stopped or restarted process
 * left. So a partition is reported when its counter lists another node's block, by its node and first sequence, that
 * reaches past the caller's cursor and was not listed there at the previous call. Once seen, a block is not reported
 * again: a node that goes on appending into a block already seen (around a change of owner, which the tails' scheduled
 * probes read with full heads, or after it lost the partition) is the caller's backstop's to find.
 *
 * A block reported that lies past this node's current block of the partition retires the current block, as a hand-off
 * does ([MongoSequencer.overtaken]): its remainder is closed, and this node's next append reserves past the other
 * node's block. Otherwise this node's own commits, contiguous in its block, would leave its tail no gap to read the
 * other node's entries at, and a tail its node keeps busy, never idle enough for the probe's wake, would read them only
 * once the block is used up (#7085). Only the current block is retired, and the next one is reserved by an append, so a
 * partition retires at most one block a call, however often another node appends there. A closing whose write fails, or
 * is cut short, is left unsettled: the block is allocated from no more, and the tail writes its closing again
 * ([MongoSequencer.resolve]).
 *
 * What it remembers is bounded by what the counters list: each call keeps, of each partition it asks about, the other
 * nodes' blocks listed now, and forgets every partition it does not ask about. A tail that closes is asked about no
 * more; one that opens starts with nothing seen, so its first call reports each of its partitions with another node's
 * block past its cursor once.
 */
internal class MongoForeignBlocks(
    /** The counters as a majority has committed them. */
    private val committedCounters: MongoCollection<Document>,
    private val sequencer: MongoSequencer,
    private val placeholders: MongoLogPlaceholders,
    private val context: String,
    private val partitions: Int,
) {
    private data class Key(val node: String, val from: Long)

    /** The other nodes' blocks each partition listed at the last call that asked about it. Guarded by itself. */
    private val seen = HashMap<Int, Set<Key>>()

    fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> {
        cursors.keys.forEach { require(it in 0 until partitions) { "No partition $it among $partitions." } }
        val counters = if (cursors.isEmpty()) {
            emptyMap()
        } else {
            committedCounters.find(Filters.`in`(ID, cursors.keys))
                .projection(Projections.include(ID, MongoSequencer.BLOCKS))
                .associateBy { it.getInteger(ID) }
        }
        val missing = cursors.keys - counters.keys
        check(missing.isEmpty()) { "The log of $context has no counter of partition ${missing.min()}." }
        val reported = HashMap<Int, Long>()
        synchronized(seen) {
            // A partition not asked about is forgotten: its tail closed, or never opened.
            seen.keys.retainAll(cursors.keys)
            for ((partition, cursor) in cursors) {
                val foreign = sequencer.openBlocks(counters[partition]).filter { it.node != sequencer.node }
                val before = seen[partition].orEmpty()
                foreign.filter { it.to > cursor && Key(it.node, it.from) !in before }.maxOfOrNull { it.from }
                    ?.let { reported[partition] = it }
                seen[partition] = foreign.mapTo(HashSet()) { Key(it.node, it.from) }
            }
        }
        // Written outside the lock: a closing is a write of the store, and settles under the sequencer's own lock.
        reported.forEach { (partition, from) ->
            sequencer.overtaken(partition, from)?.let { placeholders.close(it, deadline = null) }
        }
        return reported.keys
    }

    /** How many of [partition]'s other nodes' blocks it remembers: what the counters list, for the tests. */
    internal fun remembered(partition: Int): Int = synchronized(seen) { seen[partition]?.size ?: 0 }
}
