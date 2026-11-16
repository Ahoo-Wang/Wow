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
import com.mongodb.client.model.InsertManyOptions
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_CLOSED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_NODE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_SEALED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_TO
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.positionId
import org.bson.Document

/**
 * The placeholders of a context's log on MongoDB: a document at each sequence no stream took, so the tail never waits
 * on one. The appends write them for the sequences their streams did not take ([MongoLogAppender]); the tail for the
 * ones it resolves ([MongoSequencer.resolve]): a block's remainder closed, a sequence filled, a block sealed.
 */
internal class MongoLogPlaceholders(
    private val sequencer: MongoSequencer,
    private val collections: MongoLogCollections,
) {
    /**
     * Writes a placeholder at each of [gaps] in [collection] with one unordered `insertMany`, and settles each: a
     * stream that landed at its position takes it first, and settles it as well. A placeholder that cannot be written
     * now is left to the tail, as [fill] leaves it.
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
                bulk.writeErrors.filterNot { it.category == ErrorCategory.DUPLICATE_KEY }.mapTo(HashSet()) { it.index }
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
     * Writes a placeholder at [sequence] of [partition] in [collection] and settles the sequence: a stream that landed
     * there takes it first, and settles it as well. A placeholder that cannot be written now is left to the tail.
     */
    fun fill(collection: AggregateCollection, partition: Int, sequence: Long, reason: String) {
        try {
            collection.appends.insertOne(placeholder(partition, sequence, reason))
        } catch (failure: MongoException) {
            if (!failure.isDuplicateKey()) {
                sequencer.unfilled(partition, sequence, collection.name)
                return
            }
        }
        sequencer.settle(partition, sequence)
    }

    /**
     * Writes [closing], a block's unused remainder, as one range placeholder, and settles it. A closing that cannot be
     * written now is left to the tail, which closes the block again ([MongoSequencer.resolve]).
     */
    fun close(closing: MongoSequencer.Closing) {
        val placeholder = placeholder(closing.partition, closing.from, GAP_CLOSED).append(GAP_TO, closing.to)
        try {
            collections.named(closing.collection).appends.insertOne(placeholder)
        } catch (failure: MongoException) {
            if (!failure.isDuplicateKey()) return
        }
        sequencer.settle(closing)
    }

    /**
     * Seals [block] from [from], another node's or one of this node that it never knew: a placeholder at each of its
     * sequences left in each collection it may write, so a write of that node still to come meets a taken position;
     * then takes it out of the counter's list.
     */
    fun seal(partition: Int, from: Long, block: MongoSequencer.OpenBlock) {
        val placeholders = (from..block.to).map { placeholder(partition, it, GAP_SEALED) }
        block.collections.forEach { name ->
            try {
                collections.named(name).appends
                    .insertMany(placeholders.map(::Document), InsertManyOptions().ordered(false))
            } catch (failure: MongoBulkWriteException) {
                if (failure.writeErrors.any { it.category != ErrorCategory.DUPLICATE_KEY }) throw failure
            }
        }
        sequencer.remove(partition, block)
    }

    /** The placeholder document at [sequence] of [partition], written by this node for [reason]. */
    fun placeholder(partition: Int, sequence: Long, reason: String): Document =
        Document(ID, positionId(partition, sequence)).append(PARTITION, partition).append(SEQUENCE, sequence)
            .append(GAP, reason).append(GAP_NODE, sequencer.node)

    /** A sequence of [partition] that holds no stream, and why. */
    class Gap(val partition: Int, val sequence: Long, val reason: String)
}
