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
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val context: String,
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

    /** How many graces in a row the sequence waited at has had no open block holding it. */
    private var unheld = 0

    /**
     * The tail's read found [missing], null for none: closed at once when it is this node's never given out, else
     * resolved once it has been missing for the grace. True when it was closed at once: the tail reads again unpaced.
     */
    suspend fun met(missing: Long?): Boolean {
        if (missing != null && closedAtOnce(missing)) {
            stalled = 0L
            unheld = 0
            return true
        }
        when {
            missing == null -> {
                stalled = 0L
                unheld = 0
            }

            missing != stalled -> {
                stalled = missing
                since = System.nanoTime()
                unheld = 0
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
        dispatcher.mongo { placeholders.close(closing, deadline = null) }
        return true
    }

    /**
     * Resolves [sequence], missing past the grace while something lies past it, through the counter's open blocks
     * ([MongoSequencer.resolve]).
     */
    private fun resolve(sequence: Long) {
        val counter = counters.find(Filters.eq(ID, partition)).first()
        val blocks = sequencer.openBlocks(counter)
        unheld(sequence, blocks)
        when (val resolution = sequencer.resolve(partition, sequence, blocks)) {
            MongoSequencer.Resolution.Wait -> Unit
            is MongoSequencer.Resolution.Close -> placeholders.close(resolution.closing, deadline = null)
            is MongoSequencer.Resolution.Fill ->
                placeholders.fill(
                    collections.named(resolution.collection),
                    partition,
                    sequence,
                    GAP_UNKNOWN,
                    deadline = null
                )

            is MongoSequencer.Resolution.Seal -> sealListed(sequence, resolution.block)
        }
    }

    /**
     * Seals [block] from [sequence], unless it names a collection the registry does not list (#6768). The seal places
     * a placeholder at each of its sequences in every collection it names; a tail that does not read one of them would
     * find the placeholder where that collection holds a stream, and step over the stream. So such a block is left: the
     * tail waits at [sequence], and warns of the collection ([MongoTailReads]), until a node records it, as a node of
     * this version does at its first use of the type, or an operator does.
     */
    private fun sealListed(sequence: Long, block: MongoSequencer.OpenBlock) {
        if (block.collections.all(collections::lists)) {
            placeholders.seal(partition, sequence, block)
        }
    }

    /**
     * Counts the graces [sequence] has had no open block of [blocks] holding it, and warns once it has had none for
     * [UNHELD_WARN] graces, then every [UNHELD_REPEAT]. Nothing resolves such a sequence (its writer settled it), and
     * nothing heals it on its own: a log written by an earlier pre-release may hold one, a write a failover rolled back
     * after the log took it as settled (#6745). The warning names the placeholder an operator inserts once they have
     * checked that no stream is at that position (Operate › MongoDB).
     */
    private fun unheld(sequence: Long, blocks: List<MongoSequencer.OpenBlock>) {
        if (blocks.any { sequence in it.from..it.to }) {
            unheld = 0
            return
        }
        unheld++
        if (!warnsAt(unheld)) return
        val id = MongoEventLog.positionId(partition, sequence)
        val where = collections.known().firstOrNull()?.name ?: "a log collection of $context"
        logger.warn {
            "The tail of partition $partition of $context has waited at sequence $sequence, which no open block holds, " +
                "for $unheld graces: no append can land there any more, and a tail never steps over a missing " +
                "sequence. If a log collection of $context that the registry ${MongoEventLog.REGISTRY} does not list " +
                "holds a stream at that position, record that collection instead. Once no collection of $context " +
                "holds one, insert the placeholder " +
                "{_id: NumberLong(\"$id\"), partition: $partition, sequence: NumberLong(\"$sequence\"), " +
                "gap: \"recovered\", gap_node: \"operator\"} into $where with w: majority (Operate > MongoDB)."
        }
    }

    internal companion object {
        /** The graces without a holder after which the tail warns: ten, about ten seconds. */
        const val UNHELD_WARN = 10

        /** The graces without a holder after which it warns again: sixty, about a minute. */
        const val UNHELD_REPEAT = 60

        /** Whether a tail warns at its [graces]-th grace without a holder: the [UNHELD_WARN]-th, then each [UNHELD_REPEAT]-th. */
        fun warnsAt(graces: Int): Boolean = graces == UNHELD_WARN || graces % UNHELD_REPEAT == 0
    }
}

// The log's category, which the tail's lines keep.
private val logger = KotlinLogging.logger(MongoEventLog::class.java.name)
