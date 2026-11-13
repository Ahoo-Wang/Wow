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
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.BRANCH
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.CONTEXT
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_TO
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.finalRun
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.positionId
import me.ahoo.wow.spi.log.LogEntry
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The reads of a partition's tail ([MongoEventLog.tail]), a window of [tailBatch] sequences at a time across the
 * context's aggregate collections ([collections]), with the partition's counter, as a majority committed them.
 */
internal class MongoTailReads(
    private val committedCounters: MongoCollection<Document>,
    private val collections: MongoLogCollections,
    private val sequencer: MongoSequencer,
    private val documents: MongoLogDocuments,
    private val tailBatch: Int,
    /** The registry as a majority committed it, which [headOf] reads with the counter and the collections' heads. */
    private val committedRegistry: MongoCollection<Document>,
    private val context: String,
    /** The done blocks this log's tails take out of the counter's list, once read through for a grace. */
    private val doneBlocks: DoneBlocks = DoneBlocks(),
) {
    /**
     * One read of the tail after [last]: the counter of [partition], then each aggregate collection's entries and
     * placeholders of the window `last + 1 .. last + tailBatch`, in one aggregation over the counters. Each collection
     * holds a sequence once, so a window is at most [tailBatch] documents a collection (a sealed block leaves its
     * placeholders in each collection it listed), and no branch needs a sort or a limit: the read orders by sequence.
     *
     * It gives the entries of the final run from `last + 1` (placeholders stepped over), the sequence that run reaches,
     * and that sequence as missing when anything lies past it. Past the window it looks only when the window holds
     * nothing past the run and the counter reserved sequences beyond it ([beyond]): a hole as long as the window, with
     * streams past it, is found whatever the window against a block. Reads again at once when the run reached the
     * window's end, or when the counter's open blocks name a collection this log does not know, or a sequence is
     * missing (a collection created since: the registry is read again).
     */
    fun read(partition: Int, last: Long): TailBatch {
        val known = collections.known()
        val end = last + tailBatch
        val window = Filters.and(
            Filters.eq(PARTITION, partition),
            Filters.gt(ID, positionId(partition, last)),
            Filters.lte(ID, positionId(partition, end)),
        )
        val pipeline = listOf(
            Aggregates.match(Filters.eq(ID, partition)),
            Aggregates.project(
                Projections.fields(Projections.excludeId(), Projections.include(MongoSequencer.BLOCKS, SEQUENCE)),
            ),
        ) + known.withIndex().map { (index, collection) ->
            Aggregates.unionWith(collection.name, listOf(Aggregates.match(window), Aggregates.addFields(Field(BRANCH, index))))
        }
        val entries = HashMap<Long, LogEntry>()
        val gaps = HashMap<Long, Long>()
        var counter: Document? = null
        committedCounters.aggregate(pipeline).forEach { document ->
            val branch = document.getInteger(BRANCH)
            val sequence = document.getLong(SEQUENCE)
            when {
                branch == null -> counter = document
                document.containsKey(GAP) -> gaps.merge(sequence, document.getLong(GAP_TO) ?: sequence, ::maxOf)
                else -> entries[sequence] = documents.entryOf(known[branch].aggregate, document)
            }
        }
        val (run, next) = finalRun(entries, gaps, last, tailBatch)
        val pastRun = entries.keys.any { it > next } || gaps.keys.any { it > next }
        val missing = next.takeIf { pastRun || unread(counter, next, end) && beyond(partition, end, known) }
        val names = known.map { it.name }.toSet()
        val blocks = sequencer.openBlocks(counter)
        val unknown = blocks.any { block -> block.collections.any { it !in names } }
        val again = when {
            next > end -> true
            unknown || missing != null -> collections.register().map { it.name }.toSet() != names
            else -> false
        }
        // A listed block wholly at or below the run this read went through (`through`: each of its sequences an entry
        // or a placeholder) is done, whoever's it is: taken out of the counter's list once seen so for a grace, its
        // node's next reservation having not come by then (DoneBlocks). A write only then; none on a poll before.
        doneBlocks.due(partition, blocks, through = next - 1).forEach { sequencer.remove(partition, it) }
        return TailBatch(run, through = next - 1, missing, again)
    }

    /**
     * Whether something may lie past the window ending at [end] that its read could not see: the run stopped inside it
     * at [next], and [counter] reserved sequences after it (blocks of some node, in part at least).
     */
    private fun unread(counter: Document?, next: Long, end: Long): Boolean =
        next <= end && (counter?.getLong(SEQUENCE) ?: 0L) > end

    /**
     * Whether a collection of [known] holds an entry or placeholder of [partition] after the sequence [end], in one
     * aggregation over the counters: the first document past it in each, by `_id`, which is all a missing sequence
     * needs (not where it lies).
     */
    private fun beyond(partition: Int, end: Long, known: List<AggregateCollection>): Boolean {
        val past = listOf(
            Aggregates.match(
                Filters.and(
                    Filters.eq(PARTITION, partition),
                    Filters.gt(ID, positionId(partition, end)),
                    Filters.lt(ID, positionId(partition + 1, 0)),
                ),
            ),
            Aggregates.limit(1),
            Aggregates.project(Projections.include(ID)),
        )
        val pipeline = listOf(Aggregates.match(Filters.eq(ID, partition)), Aggregates.project(Projections.include(ID))) +
            known.map { Aggregates.unionWith(it.name, past) }
        // The counter, then whatever lies past the window.
        return committedCounters.aggregate(pipeline).count() > 1
    }

    tailrec fun headOf(partition: Int): Long? {
        val known = collections.known()
        val names = known.mapTo(HashSet()) { it.name }
        var head: Long? = null
        var unknown = false
        var counted = false
        for (document in committedCounters.aggregate(headPipeline(partition, known))) {
            val sequence = document.getLong(SEQUENCE)
            val id = document[ID]
            when {
                sequence != null -> head = maxOf(head ?: sequence, sequence)
                id !is String -> counted = true
                id !in names -> unknown = true
            }
        }
        check(counted) { "The log of $context has no counter of partition $partition." }
        if (!unknown) {
            return head
        }
        val found = collections.register().mapTo(HashSet()) { it.name } - names
        if (found.isEmpty()) {
            return head
        }
        logger.info { "The log of $context found the collections $found, registered by another node; it reads them now." }
        return headOf(partition)
    }

    /**
     * The counter of [partition], the context's registry entries, then each of [known]'s highest stream in [partition],
     * in one aggregation over the counters: an entry this log does not know is a collection registered since it last
     * read the registry, and no counter is a damaged store.
     */
    private fun headPipeline(partition: Int, known: List<AggregateCollection>): List<Bson> {
        val highest = listOf(
            Aggregates.match(
                Filters.and(
                    Filters.eq(PARTITION, partition),
                    Filters.gte(ID, positionId(partition, 0)),
                    Filters.lt(ID, positionId(partition + 1, 0)),
                    Filters.exists(VERSION),
                ),
            ),
            Aggregates.sort(Sorts.descending(ID)),
            Aggregates.limit(1),
            Aggregates.project(Projections.include(SEQUENCE)),
        )
        val registry =
            listOf(Aggregates.match(Filters.eq(CONTEXT, context)), Aggregates.project(Projections.include(ID)))
        return listOf(
            Aggregates.match(Filters.eq(ID, partition)),
            Aggregates.project(Projections.include(ID)),
            Aggregates.unionWith(committedRegistry.namespace.collectionName, registry),
        ) + known.map { Aggregates.unionWith(it.name, highest) }
    }
}

/**
 * One read of a tail: its [entries], the last sequence of its final run ([through], past placeholders), the first
 * missing sequence with something past it, and whether to read again at once.
 */
internal class TailBatch(val entries: List<LogEntry>, val through: Long, val missing: Long?, val again: Boolean)

// The log's category, which the head's line on a collection another node registered keeps.
private val logger = KotlinLogging.logger(MongoEventLog::class.java.name)
