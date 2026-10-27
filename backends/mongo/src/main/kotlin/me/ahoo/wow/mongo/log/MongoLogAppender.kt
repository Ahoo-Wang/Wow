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
import com.mongodb.WriteError
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFailures
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_CLOSED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_FAILED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_NODE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_TO
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.GAP_UNKNOWN
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.MAX_SEALED
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.PARTITION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.POSITION_INDEX
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_ID
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.REQUEST_KEY
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.SEQUENCE
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.VERSION
import me.ahoo.wow.mongo.log.MongoEventLog.Companion.positionId
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.notAppendedAfter
import org.bson.BsonMaximumSizeExceededException
import org.bson.Document

/**
 * The appends of a context's log on MongoDB: a stream alone, at the sequence its
 * partition's block gives it, or a group with one ordered `insertMany` per aggregate collection; each stream's outcome
 * as [MongoEventLog.append] gives it, and a placeholder for each sequence no stream took, so the tail never waits on
 * one.
 */
internal class MongoLogAppender(
    private val context: String,
    private val partitions: Int,
    private val sequencer: MongoSequencer,
    private val collections: MongoLogCollections,
    private val documents: MongoLogDocuments,
    private val lookups: MongoLogLookups,
    private val lastSequences: LastSequences,
) {
    fun appendNow(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val collection = collections.of(stream.ref.type)
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        val document = encode(partition, stream, transport).getOrElse { return AppendOutcome.Rejected(it) }
        val after = previousOf(collection, stream)
            ?: return duplicateOr(collection, stream, AppendOutcome.Conflict(lookups.currentVersion(collection, stream.ref)))
        // A taken position is a block another node sealed: the next allocation comes from a new block.
        repeat(MAX_SEALED) {
            val allocation = sequencer.allocate(
                partition,
                collection.name,
                after
            ) { collections.known().map { it.name } }
            allocation.closing?.let(::close)
            insert(collection, stream, document, allocation)?.let { return it }
        }
        error("The blocks of partition $partition of $context were sealed under $MAX_SEALED appends in a row.")
    }

    /** One stream of a group, encoded, at the sequence allocated to it. */
    private class Member(
        val index: Int,
        val stream: EventStreamRecord,
        val collection: AggregateCollection,
        val document: Document,
        val sequence: Long,
    )

    /**
     * [streams] encoded and sequenced, each in its partition, then inserted with one `insertMany` per collection. An
     * aggregate's later streams follow the sequence its previous one in the group took, and are not appended once one
     * did not commit ([EventLog.appendAll]).
     */
    fun appendGroup(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
        val failed = HashMap<AggregateRef, AppendOutcome>()
        val previous = HashMap<AggregateRef, Long>()
        val members = ArrayList<Member>(streams.size)
        streams.forEachIndexed { index, (stream, transport) ->
            failed[stream.ref]?.let {
                outcomes[index] = it
                return@forEachIndexed
            }
            val collection = collections.of(stream.ref.type)
            val partition = lookups.partitionOf(stream.ref)
            val document = encode(partition, stream, transport).getOrElse {
                outcomes[index] = AppendOutcome.Rejected(it)
                failed[stream.ref] = notAppendedAfter(stream, AppendOutcome.Rejected(it))
                return@forEachIndexed
            }
            val after = previous[stream.ref] ?: previousOf(collection, stream) ?: run {
                val conflict = AppendOutcome.Conflict(lookups.currentVersion(collection, stream.ref))
                outcomes[index] = duplicateOr(collection, stream, conflict)
                failed[stream.ref] = notAppendedAfter(stream, checkNotNull(outcomes[index]))
                return@forEachIndexed
            }
            val allocation = sequencer.allocate(
                partition,
                collection.name,
                after
            ) { collections.known().map { it.name } }
            allocation.closing?.let(::close)
            document[SEQUENCE] = allocation.sequence
            document[ID] = positionId(partition, allocation.sequence)
            previous[stream.ref] = allocation.sequence
            members += Member(index, stream, collection, document, allocation.sequence)
        }
        // By partition (stable, so an aggregate's streams keep their order): the ordered insert stops at its first
        // failure, and the members after it are appended again alone; sorted, they are its own partition's and the
        // later partitions', not every partition's.
        members.groupBy { it.collection }.forEach { (collection, group) ->
            insertGroup(collection, group.sortedBy { lookups.partitionOf(it.stream.ref) })
                .forEach { (index, outcome) -> outcomes[index] = outcome }
        }
        // A member whose position was taken (its block sealed), or that its insert did not reach, has no outcome yet:
        // it is appended again, alone, in order, unless one of its aggregate's did not commit.
        failed.clear()
        return outcomes.mapIndexed { index, outcome ->
            val (stream, transport) = streams[index]
            failed[stream.ref] ?: (outcome ?: appendNow(stream, transport)).also {
                if (it !is AppendOutcome.Committed) failed[stream.ref] = notAppendedAfter(stream, it)
            }
        }
    }

    /**
     * Inserts [group], members of one collection, with one ordered `insertMany`: each member's outcome by its index,
     * none for a member whose position was taken or that the insert did not reach. It stops at its first failure, so
     * an aggregate's streams land as a prefix.
     */
    private fun insertGroup(collection: AggregateCollection, group: List<Member>): Map<Int, AppendOutcome?> {
        val failures: Map<Int, WriteError> = try {
            collection.appends.insertMany(group.map { it.document }, InsertManyOptions().ordered(true))
            emptyMap()
        } catch (bulk: MongoBulkWriteException) {
            if (bulk.writeConcernError != null) {
                return group.associate { it.index to unknown(it, bulk) }
            }
            bulk.writeErrors.associate { it.index to it }
        } catch (tooLarge: BsonMaximumSizeExceededException) {
            // A document beyond the size limit, which the driver refuses as it reaches it, after the batches before it
            // went: a member that landed is committed, the others are appended alone, so it fails alone.
            return group.associate { member ->
                if (landed(collection, member)) {
                    member.index to outcomeOf(collection, member, null)
                } else {
                    fill(collection, lookups.partitionOf(member.stream.ref), member.sequence, GAP_FAILED)
                    member.index to null
                }
            }
        } catch (failure: MongoException) {
            if (!MongoFailures.isWriteUnknown(failure)) {
                group.forEach { fill(collection, lookups.partitionOf(it.stream.ref), it.sequence, GAP_FAILED) }
                throw failure
            }
            return group.associate { it.index to unknown(it, failure) }
        }
        // Ordered: the members after the first failure were not tried; a placeholder takes each one's sequence.
        val stopped = failures.keys.minOrNull() ?: group.size
        return group.withIndex().associate { (at, member) ->
            if (at > stopped) {
                fill(collection, lookups.partitionOf(member.stream.ref), member.sequence, GAP_FAILED)
                member.index to null
            } else {
                member.index to outcomeOf(collection, member, failures[at])
            }
        }
    }

    /** [member]'s outcome after its insert, which failed with [error] when there is one. */
    private fun outcomeOf(collection: AggregateCollection, member: Member, error: WriteError?): AppendOutcome? {
        val partition = lookups.partitionOf(member.stream.ref)
        val stream = member.stream
        if (error == null) {
            sequencer.settle(partition, member.sequence)
            lastSequences[stream.ref] = LastSequence(stream.version, member.sequence)
            return AppendOutcome.Committed(LogPosition(partition, member.sequence))
        }
        if (error.category == ErrorCategory.DUPLICATE_KEY && isPositionTaken(error.message)) {
            sequencer.abandon(partition, member.sequence)
            sequencer.settle(partition, member.sequence)
            return null
        }
        lastSequences.remove(stream.ref)
        fill(collection, partition, member.sequence, GAP_FAILED)
        return when {
            error.code == MongoFailures.BSON_OBJECT_TOO_LARGE -> AppendOutcome.Rejected(
                MongoException(error.code, error.message)
            )
            error.category != ErrorCategory.DUPLICATE_KEY -> AppendOutcome.Unknown(
                MongoException(error.code, error.message)
            )
            error.message.contains(REQUEST_KEY) ->
                lookups.requestIn(collection, stream.ref, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(MongoException(error.code, error.message))

            else -> duplicateOr(
                collection,
                stream,
                AppendOutcome.Conflict(lookups.currentVersion(collection, stream.ref))
            )
        }
    }

    /** Whether [member]'s stream is at its position: the document there has its request. */
    private fun landed(collection: AggregateCollection, member: Member): Boolean {
        val partition = lookups.partitionOf(member.stream.ref)
        val position = Filters.and(
            Filters.eq(PARTITION, partition),
            Filters.eq(ID, positionId(partition, member.sequence))
        )
        return collection.committed.find(position).projection(Projections.include(REQUEST_ID)).firstOrNull()
            ?.getString(REQUEST_ID) == member.stream.requestId
    }

    /** [member]'s outcome when its group's write is unknown: a placeholder takes its sequence unless it landed. */
    private fun unknown(member: Member, failure: MongoException): AppendOutcome {
        lastSequences.remove(member.stream.ref)
        fill(member.collection, lookups.partitionOf(member.stream.ref), member.sequence, GAP_UNKNOWN)
        return AppendOutcome.Unknown(failure)
    }

    /** Whether a duplicate key [message] is the position's: another writer's entry, or a placeholder, took it. */
    private fun isPositionTaken(message: String): Boolean = message.contains(POSITION_INDEX)

    /**
     * The sequence [stream] must follow in its partition, its aggregate's previous stream's, or null when that is not
     * the version before [stream]'s (a conflict). An aggregate's first stream follows none; the previous stream is the
     * one this log saw last when its version fits, else the aggregate's latest, read. A version already taken by
     * another node since is the version key's to refuse.
     */
    private fun previousOf(collection: AggregateCollection, stream: EventStreamRecord): Long? {
        if (stream.version == 1) return 0
        lastSequences[stream.ref]?.takeIf { it.version == stream.version - 1 }?.let { return it.sequence }
        val latest = collection.committed.find(lookups.refFilter(stream.ref))
            .sort(Sorts.descending(VERSION))
            .projection(Projections.include(VERSION, SEQUENCE))
            .limit(1)
            .firstOrNull()
            ?: return null
        lastSequences[stream.ref] = LastSequence(latest.getInteger(VERSION), latest.getLong(SEQUENCE))
        return latest.getLong(SEQUENCE).takeIf { latest.getInteger(VERSION) == stream.version - 1 }
    }

    /**
     * Inserts [document] at [allocation]'s sequence: the outcome, or null when the position was taken (the block was
     * sealed). A sequence the stream did not take gets its placeholder, unless the outcome is unknown and the stream
     * landed.
     */
    private fun insert(
        collection: AggregateCollection,
        stream: EventStreamRecord,
        document: Document,
        allocation: MongoSequencer.Allocation,
    ): AppendOutcome? {
        val partition = allocation.partition
        val sequence = allocation.sequence
        document[SEQUENCE] = sequence
        document[ID] = positionId(partition, sequence)
        val outcome = try {
            collection.appends.insertOne(document)
            sequencer.settle(partition, sequence)
            lastSequences[stream.ref] = LastSequence(stream.version, sequence)
            return AppendOutcome.Committed(LogPosition(partition, sequence))
        } catch (tooLarge: BsonMaximumSizeExceededException) {
            // The driver refused the document before sending it.
            AppendOutcome.Rejected(tooLarge)
        } catch (failure: MongoException) {
            if (failure.isDuplicateKey() && isPositionTaken(failure.message.orEmpty())) {
                sequencer.abandon(partition, sequence)
                sequencer.settle(partition, sequence)
                return null
            }
            try {
                outcomeOf(failure, collection, stream)
            } catch (thrown: MongoException) {
                fill(collection, partition, sequence, GAP_FAILED)
                throw thrown
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever else failed, the sequence is filled before it is thrown.
            failure: RuntimeException,
        ) {
            fill(collection, partition, sequence, GAP_FAILED)
            throw failure
        }
        lastSequences.remove(stream.ref)
        fill(collection, partition, sequence, if (outcome is AppendOutcome.Unknown) GAP_UNKNOWN else GAP_FAILED)
        return if (outcome is AppendOutcome.Conflict) duplicateOr(collection, stream, outcome) else outcome
    }

    /**
     * The outcome of [stream]'s insert that failed with [failure]. A duplicate key is a conflict or a duplicate
     * request; a document beyond the size limit is refused; a write whose result the driver does not know (a timeout,
     * a failed socket) is unknown; any other failure the server answered wrote nothing and is thrown,
     * so the command is answered with the storage error.
     */
    private fun outcomeOf(failure: MongoException, collection: AggregateCollection, stream: EventStreamRecord) =
        when {
            MongoFailures.isTooLarge(failure) -> AppendOutcome.Rejected(failure)
            MongoFailures.isWriteUnknown(failure) -> AppendOutcome.Unknown(failure)
            !failure.isDuplicateKey() -> throw failure
            failure.message.orEmpty().contains(REQUEST_KEY) ->
                lookups.requestIn(collection, stream.ref, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) } ?: AppendOutcome.Unknown(failure)

            else -> AppendOutcome.Conflict(lookups.currentVersion(collection, stream.ref))
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

    /** Writes [closing], a block's unused remainder, as one range placeholder, and settles it. */
    fun close(closing: MongoSequencer.Closing) {
        val placeholder = placeholder(closing.partition, closing.from, GAP_CLOSED).append(GAP_TO, closing.to)
        try {
            collections.named(closing.collection).appends.insertOne(placeholder)
        } catch (failure: MongoException) {
            if (!failure.isDuplicateKey()) throw failure
        }
        sequencer.settle(closing)
    }

    fun placeholder(partition: Int, sequence: Long, reason: String): Document =
        Document(ID, positionId(partition, sequence)).append(PARTITION, partition).append(SEQUENCE, sequence)
            .append(GAP, reason).append(GAP_NODE, sequencer.node)

    /**
     * [stream]'s document in [partition], its sequence still to be set: encoded before it is written, so a stream
     * MongoDB cannot store ([ExactBson]'s [IllegalArgumentException]) is refused alone. A document
     * beyond MongoDB's size limit is only known when the driver writes it, and [insert] refuses it then.
     */
    private fun encode(partition: Int, stream: EventStreamRecord, transport: ByteArray?): Result<Document> = try {
        Result.success(documents.documentOf(partition, stream, transport))
    } catch (refused: IllegalArgumentException) {
        Result.failure(refused)
    }

    /**
     * [AppendOutcome.AlreadyCommitted] when [stream]'s request is committed, else [conflict]: a retry of
     * one request at one version collides on both keys, and the version is compared first.
     */
    private fun duplicateOr(
        collection: AggregateCollection,
        stream: EventStreamRecord,
        conflict: AppendOutcome.Conflict,
    ): AppendOutcome =
        lookups.requestIn(collection, stream.ref, stream.requestId)?.let { AppendOutcome.AlreadyCommitted(it) }
            ?: conflict
}
