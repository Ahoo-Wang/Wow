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

import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Projections
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_CLOSED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_NODE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_SEALED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_TO
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE_BITS
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.positionId
import org.bson.Document

/**
 * The placeholders of a context's log on MongoDB: a document at each sequence no stream took, so the tail never waits
 * on one. The appends write them for the sequences their streams did not take ([MongoLogAppender]); the tail for the
 * ones it resolves ([MongoSequencer.resolve]): a block's remainder closed, a sequence filled, a block sealed.
 *
 * **What settles a sequence** (#6745): this node's own write of it, acknowledged by a majority
 * without a write concern error, or a majority read that finds a stream or a placeholder at its position after a
 * duplicate key. A duplicate key alone says what the primary that answered holds, which a failover may roll back: a
 * sequence settled on it, and then emptied, would stall every tail of the partition for good, no block holding it.
 * One that a majority read does not find is left unfilled, and the tail fills it again after its grace, until it lands
 * or the document reaches the majority. A seal whose write was not acknowledged, or whose duplicates a majority does
 * not hold, keeps its block listed, and the tail seals it again.
 */
internal class MongoLogPlaceholders(
    private val sequencer: MongoSequencer,
    private val collections: MongoLogCollections,
) {
    /**
     * Writes a placeholder at each of [gaps] in [collection] with one unordered `insertMany`, and settles each that is
     * settled: written and acknowledged, or held by a majority after a duplicate key (a stream that landed there first).
     * The others are left to the tail, as [fill] leaves one.
     */
    fun fillAll(collection: AggregateCollection, gaps: List<Gap>) {
        if (gaps.size == 1) {
            return gaps.single().let { fill(collection, it.partition, it.sequence, it.reason) }
        }
        val unwritten: Set<Int> = try {
            collection.appends.insertMany(
                gaps.map { placeholder(it.partition, it.sequence, it.reason) },
                InsertManyOptions().ordered(false),
            )
            emptySet()
        } catch (bulk: MongoBulkWriteException) {
            // A write concern that failed leaves every placeholder unknown: filled again later, a duplicate then.
            if (bulk.writeConcernError != null) {
                gaps.indices.toSet()
            } else {
                val duplicates = bulk.writeErrors.filter { it.category == ErrorCategory.DUPLICATE_KEY }.map { it.index }
                val held = held(collection, duplicates.map { gaps[it].id })
                bulk.writeErrors.map { it.index }.filterTo(HashSet()) { gaps[it].id !in held }
            }
        } catch (
            failure: MongoException,
        ) {
            // Each sequence is left to the tail, which fills it again.
            gaps.indices.toSet()
        }
        gaps.forEachIndexed { at, gap ->
            if (at in unwritten) {
                sequencer.unfilled(gap.partition, gap.sequence, collection.name)
            } else {
                sequencer.settle(gap.partition, gap.sequence)
            }
        }
    }

    /**
     * Writes a placeholder at [sequence] of [partition] in [collection] and settles the sequence when it is settled:
     * written and acknowledged, or, after a duplicate key, held by a majority ([taken]). A placeholder that cannot be
     * written now is left to the tail.
     */
    fun fill(collection: AggregateCollection, partition: Int, sequence: Long, reason: String) {
        try {
            collection.appends.insertOne(placeholder(partition, sequence, reason))
        } catch (failure: MongoException) {
            if (failure.isDuplicateKey()) {
                taken(collection, partition, sequence)
            } else {
                sequencer.unfilled(partition, sequence, collection.name)
            }
            return
        }
        sequencer.settle(partition, sequence)
    }

    /**
     * [sequence] of [partition], whose write in [collection] met a duplicate key at its position: settled when a
     * majority holds a stream or placeholder there, else left unfilled, for the tail to fill again.
     */
    fun taken(collection: AggregateCollection, partition: Int, sequence: Long) {
        if (positionId(partition, sequence) in held(collection, listOf(positionId(partition, sequence)))) {
            sequencer.settle(partition, sequence)
        } else {
            sequencer.unfilled(partition, sequence, collection.name)
        }
    }

    /**
     * Writes [closing], a block's unused remainder, as one range placeholder, and settles it: written and
     * acknowledged, or, after a duplicate key at its first sequence, every sequence of it held by a majority (an
     * earlier closing of it, or a seal). A closing that is not settled is left to the tail, which closes the block
     * again ([MongoSequencer.resolve]).
     */
    fun close(closing: MongoSequencer.Closing) {
        val collection = collections.named(closing.collection)
        val placeholder = placeholder(closing.partition, closing.from, GAP_CLOSED).append(GAP_TO, closing.to)
        try {
            collection.appends.insertOne(placeholder)
        } catch (failure: MongoException) {
            if (!failure.isDuplicateKey() || !covered(collection, closing)) return
        }
        sequencer.settle(closing)
    }

    /**
     * Seals [block] from [from], another node's or one of this node that it never knew: a placeholder at each of its
     * sequences left in each collection it may write, so a write of that node still to come meets a taken position;
     * then takes it out of the counter's list, once every collection is sealed: its placeholders acknowledged, those
     * that met a duplicate key held by a majority. A seal not acknowledged (a write concern error) or not held keeps the
     * block listed, so the tail seals it again (#6745).
     */
    fun seal(partition: Int, from: Long, block: MongoSequencer.OpenBlock) {
        val ids = (from..block.to).map { positionId(partition, it) }
        val sealed = block.collections.map { name -> sealIn(collections.named(name), partition, from, block.to, ids) }
        if (sealed.all { it }) {
            sequencer.remove(partition, block)
        }
    }

    /** Whether the placeholders of [from]..[to] in [collection], at [ids], are written and held there. */
    private fun sealIn(
        collection: AggregateCollection,
        partition: Int,
        from: Long,
        to: Long,
        ids: List<Long>
    ): Boolean =
        try {
            collection.appends
                .insertMany(
                    (from..to).map { placeholder(partition, it, GAP_SEALED) },
                    InsertManyOptions().ordered(false)
                )
            true
        } catch (failure: MongoBulkWriteException) {
            if (failure.writeErrors.any { it.category != ErrorCategory.DUPLICATE_KEY }) throw failure
            val duplicates = failure.writeErrors.map { ids[it.index] }
            failure.writeConcernError == null && held(collection, duplicates).size == duplicates.size
        }

    /** Whether every sequence of [closing] is held by a majority in [collection]: one range, or each sequence. */
    private fun covered(collection: AggregateCollection, closing: MongoSequencer.Closing): Boolean {
        val first = positionId(closing.partition, closing.from)
        val found = try {
            collection.committed.find(
                Filters.and(
                    Filters.eq(PARTITION, closing.partition),
                    Filters.gte(ID, first),
                    Filters.lte(ID, positionId(closing.partition, closing.to)),
                ),
            ).projection(Projections.include(ID, GAP_TO)).toList()
        } catch (failure: MongoException) {
            return false
        }
        return found.any { it.getLong(ID) == first && (it.getLong(GAP_TO) ?: closing.from) >= closing.to } ||
            found.size.toLong() == closing.to - closing.from + 1
    }

    /**
     * Of [ids], the positions of [collection] where a majority holds a stream or a placeholder: none when the read
     * fails, so nothing is settled on it. The filter names their partitions, the shard key of a sharded collection.
     */
    private fun held(collection: AggregateCollection, ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val partitions = ids.map { (it ushr SEQUENCE_BITS).toInt() }.distinct()
        return try {
            collection.committed.find(Filters.and(Filters.`in`(PARTITION, partitions), Filters.`in`(ID, ids)))
                .projection(Projections.include(ID))
                .mapTo(HashSet()) { it.getLong(ID) }
        } catch (failure: MongoException) {
            emptySet()
        }
    }

    /** The placeholder document at [sequence] of [partition], written by this node for [reason]. */
    fun placeholder(partition: Int, sequence: Long, reason: String): Document =
        Document(ID, positionId(partition, sequence)).append(PARTITION, partition).append(SEQUENCE, sequence)
            .append(GAP, reason).append(GAP_NODE, sequencer.node)

    /** A sequence of [partition] that holds no stream, and why. */
    class Gap(val partition: Int, val sequence: Long, val reason: String) {
        /** Its position's `_id`. */
        val id: Long get() = positionId(partition, sequence)
    }
}
