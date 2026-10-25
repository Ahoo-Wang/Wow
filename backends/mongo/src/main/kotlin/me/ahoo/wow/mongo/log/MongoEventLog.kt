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
import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.WriteError
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFailures
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TagsBackfill
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.notAppendedAfter
import me.ahoo.wow.spi.log.requireChains
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonMaximumSizeExceededException
import org.bson.BsonString
import org.bson.BsonType
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The [EventLog] of one bounded context on MongoDB, a first-class log backend (decision D11;
 * documentation/designs/03-log-and-snapshot.md §2). It needs a replica set, for its majority reads and writes.
 *
 * The log takes one collection per aggregate type, `wow_log__<context>__<aggregate>` (decision D162); a context-level
 * collection holds one counter document per partition, so partitions, their sequences, leases and checkpoints stay
 * per context. A counter also holds its partition's lineage, `parent` (null) and `epoch` (0) (decision D240), which
 * [lineage] reads back, and the partition's open blocks.
 *
 * **Appends** (decision D271). An append is one `insertOne` acknowledged by a majority, at a sequence this node
 * allocated from a block it reserved on the partition's counter ([MongoSequencer]); no transaction. The unique keys
 * settle the rest: a taken version is a conflict, a taken request a duplicate, a taken position a block another node
 * sealed (the append takes a new sequence). Every sequence the tail passes is final: an entry, or a placeholder. A
 * sequence allocated and not written gets a placeholder at once (a conflict, a refusal) or as soon as it can (an
 * unknown outcome, written over by the stream if it landed); a block's unused remainder is closed by one range
 * placeholder; and the block of a node that stopped is sealed with a placeholder at each of its sequences left, in
 * each collection it may write, so a late write of that node meets a taken position. Placeholders have no aggregate,
 * version or request: the reads by aggregate and the version and request keys never see them. An aggregate's next
 * stream takes a sequence after its previous one, which the log remembers from its reads and appends, so a partition
 * keeps each aggregate's streams in version order across owners.
 *
 * **Aggregate collections.** An aggregate type's collection and its indexes are created the first time this log
 * touches the type, or up front by [createCollection]; a new aggregate type needs no restart. Every aggregate collection is recorded in a registry collection ([REGISTRY]) before its first entry is
 * written, and [tail] reads the context's collections from it.
 *
 * **Tail.** [tail] reads a partition in one aggregation: the partition's counter first, then, through `$unionWith`,
 * each aggregate collection's first entries and placeholders after the last sequence; it merges them by sequence and
 * emits the entries of the run from the last sequence that is final, stepping over placeholders. It never steps over
 * a missing sequence: an append of some node may still land there. Missing for [STALL_GRACE], the sequence is
 * resolved through the counter's open blocks: this node's own remainder is closed, its own unfilled sequence filled,
 * another node's block sealed; entries past it while it waits may also be in a collection this log does not know yet,
 * so it reads the registry again.
 *
 * **Read concern.** Every read, the tail, [head], the version slot reads ([readEntries], [positionOf]) and the
 * request lookups included, reads what a majority of the replica set has committed (`readConcern: majority`, finding
 * A2-2). With the default (`local`), a tail could deliver an entry the primary committed and lost in a failover
 * before it replicated, which is then rolled back: its subscriptions would have acted on a fact that never was. The
 * cost is latency: an entry reaches the tail once a majority has it, about one replication round trip after its
 * commit (appends already wait for it, `w: majority`). The backend so needs a replica set whose majority can commit:
 * an arbiter that makes a majority unreachable stalls the tail.
 *
 * **Request ids.** The request key is `(tenant_id, request_id)` within each aggregate collection: a request id reused
 * by two aggregate types commits twice (decision D55 makes the reuse the caller's error). The kernel's pre-check, the
 * [findByRequestId] taking the aggregate type, reads that type's collection alone; the context-wide one searches
 * every aggregate collection this log knows.
 *
 * A stream's facts are fields of its document; the document has no aggregate name, the collection is the aggregate
 * type. The transport is a field of its own, `transport` (decision D25), which only [tail] and [readEntries] read:
 * [read] and [findByRequestId] leave it out of their projection. Each event's payload is a BSON document whose
 * decimals are `Decimal128`, exact ([ExactBson]); an append whose payload holds a number MongoDB cannot store exactly
 * is refused with an [IllegalArgumentException].
 *
 * **Polling** (finding A2-14). A tail polls every `pollInterval` (50 ms by default) while it finds entries; each poll
 * that finds none doubles the wait, up to `maxPollInterval` (1 s). A commit this log makes wakes the partition's
 * tails at once, and a poll it woke that finds the partition caught up keeps the backoff, so a commit costs one read
 * of the tail; an entry another node appends is read within `maxPollInterval`. A change stream could wake the tails
 * of every node; it is not used, the backoff keeping an idle cluster cheap without a cursor per partition and node.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
@Suppress("LargeClass") // The log's port and the tags backfill's (decision D259), on one collection layout.
class MongoEventLog(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
    maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How the store's collections and indexes are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
    /**
     * How long an append's insert may take (decision D261): a server that does not answer fails it, so a command
     * appending during an outage is answered before its wait ends, as unknown. Below the default wait timeout.
     */
    appendTimeout: Duration = DEFAULT_APPEND_TIMEOUT,
) : EventLog,
    TagsBackfill {
    /** Validates the partition count first (decision D240): a log of another count is refused when it is built. */
    private val pacer = TailPacer(Partitioner.requirePartitions(partitions), pollInterval, maxPollInterval)

    private val database = client.getDatabase(databaseName)
    private val countersName = MongoNames.of(PREFIX, context, PARTITION_COUNTERS)
    private val counters: MongoCollection<Document> = database.getCollection(countersName)

    /** The counters as a majority has committed them: what the tail and [head] read (finding A2-2). */
    private val committedCounters: MongoCollection<Document> = counters.withReadConcern(ReadConcern.MAJORITY)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)
    private val appendMillis = appendTimeout.inWholeMilliseconds

    private val sequencer =
        MongoSequencer(
            counters.withWriteConcern(WriteConcern.MAJORITY),
            UUID.randomUUID().toString(),
            BLOCK,
            partitions,
        )

    /** Each aggregate's last sequence as this log saw it: an aggregate's next stream is allocated after it. */
    private val lastSequences = LastSequences(lastSequencesFor(Runtime.getRuntime().maxMemory()))

    /** The aggregate collections whose creation and registration this log has made sure of. */
    private val ensured = ConcurrentHashMap<String, AggregateCollection>()

    /** The aggregate collections of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateCollection>? = null

    /** The log collection of one aggregate type. */
    private inner class AggregateCollection(val aggregate: String, val name: String) {
        val log: MongoCollection<Document> = database.getCollection(name)

        /** The entries a majority has committed: every read (finding A2-2). */
        val committed: MongoCollection<Document> = log.withReadConcern(ReadConcern.MAJORITY)

        /** The appends and placeholders, acknowledged by a majority within the append timeout. */
        val appends: MongoCollection<Document> =
            log.withWriteConcern(WriteConcern.MAJORITY).withTimeout(appendMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * Creates the partition counters when missing; refuses a different partition count, and a log recorded in another
     * format version ([MongoFormat]). The aggregate collections are created on first use, or by [createCollection].
     */
    fun createSchema() {
        MongoFormat.claim(database, countersName, FORMAT, ddl)
        MongoFormat.claim(database, REGISTRY, FORMAT, ddl)
        // Every step is idempotent, so nodes creating a new log at once all succeed (finding A2-8). The partition count
        // is recorded before any counter, so a node that finds counters also finds the count the log was created with
        // and refuses another one before it adds a counter.
        val existingCounters = counters.countDocuments()
        val marker = "$countersName$PARTITIONS_MARKER"
        upsertOnce(registry, marker, PARTITIONS to if (existingCounters > 0) existingCounters.toInt() else partitions)
        val recorded = registry.find(Filters.eq(ID, marker)).first()!!.getInteger(PARTITIONS)
        check(recorded == partitions) {
            "The log of $context has $recorded partitions, not $partitions: changing it is a repartition."
        }
        // Each counter carries its partition's lineage (D240): no parent, epoch 0, reserved for the splits of 10.x.
        for (partition in 0 until partitions) {
            upsertOnce(counters, partition, SEQUENCE to 0L, PARENT to null, EPOCH to 0)
        }
        val existing = counters.countDocuments()
        check(existing == partitions.toLong()) {
            "The log of $context has $existing partitions, not $partitions: changing it is a repartition."
        }
    }

    /** Inserts the document [id] of [collection] with [fields] unless it exists; a concurrent insert wins. */
    private fun upsertOnce(collection: MongoCollection<Document>, id: Any, vararg fields: Pair<String, Any?>) {
        try {
            val insert = Updates.combine(fields.map { (field, value) -> Updates.setOnInsert(field, value) })
            collection.updateOne(Filters.eq(ID, id), insert, UpdateOptions().upsert(true))
        } catch (raced: MongoWriteException) {
            if (!raced.isDuplicateKey()) {
                throw raced
            }
        }
    }

    private fun collectionOf(type: AggregateType): AggregateCollection {
        require(type.context == context) { "$type is not in context $context." }
        return collectionOf(type.name)
    }

    /**
     * Creates the indexes of [aggregateName]'s log collection and registers it when missing, once per log; refuses a
     * collection recorded in another format version ([MongoFormat]), and an aggregate whose collection name another
     * aggregate has.
     */
    fun createCollection(aggregateName: String) {
        collectionOf(aggregateName)
    }

    /** The collection of [aggregate], created and registered when this log has not yet made sure it is. */
    private fun collectionOf(aggregate: String): AggregateCollection =
        ensured[aggregate] ?: ensure(aggregate).also { ensured[aggregate] = it }

    /**
     * Claims the format of [aggregate]'s collection, creates its indexes and records it in the registry. Each step is
     * idempotent, so nodes creating one collection at once agree; two aggregates that take one name are refused.
     */
    private fun ensure(aggregate: String): AggregateCollection {
        val collection = AggregateCollection(aggregate, MongoNames.of(PREFIX, context, aggregate))
        require(collection.name != countersName) {
            "The aggregate $aggregate of $context would take the name of the log's partition counters."
        }
        MongoFormat.claim(database, collection.name, FORMAT, ddl)
        val log = collection.log
        // An entry's position is its `_id` (roadmap §6 item 17): a log an alpha wrote has the driver's ObjectIds, and
        // its tail would read nothing of them.
        check(log.find(Filters.type(ID, BsonType.OBJECT_ID)).projection(Projections.include(ID)).first() == null) {
            "${collection.name} was written by an earlier 10.0 alpha, whose entries have no position as their _id: " +
                "rebuild the log, an alpha's data is not migrated."
        }
        // Placeholders have no version and no request: the two keys cover the streams alone. Both lead with the
        // partition, so they stay unique on a collection sharded by it (decision D277).
        MongoFormat.index(
            log,
            Indexes.ascending(PARTITION, AGGREGATE_ID, TENANT_ID, VERSION),
            streams(VERSION_KEY, VERSION),
            ddl,
        )
        MongoFormat.index(
            log,
            Indexes.ascending(PARTITION, TENANT_ID, REQUEST_ID),
            streams(REQUEST_KEY, REQUEST_ID),
            ddl
        )
        if (ddl != StoreDdl.AUTO) {
            // The registry is the schema's: a validated or foreign schema registered its collections already.
            return collection
        }
        try {
            registry.insertOne(Document(ID, collection.name).append(CONTEXT, context).append(AGGREGATE, aggregate))
        } catch (failure: MongoWriteException) {
            if (!failure.isDuplicateKey()) {
                throw failure
            }
        }
        val holder = registry.find(Filters.eq(ID, collection.name)).first()!!
        check(holder.getString(CONTEXT) == context && holder.getString(AGGREGATE) == aggregate) {
            "The aggregate $aggregate of $context takes the log collection ${collection.name}, " +
                "which the aggregate ${holder.getString(AGGREGATE)} of ${holder.getString(CONTEXT)} has."
        }
        return collection
    }

    private fun unique(name: String): IndexOptions = IndexOptions().unique(true).name(name)

    /** A unique index of the documents that have [field], the streams'. */
    private fun streams(name: String, field: String): IndexOptions =
        unique(name).partialFilterExpression(Filters.exists(field))

    /** The aggregate collections this log knows: the registry as last read, and the ones it made sure of since. */
    private fun known(): List<AggregateCollection> =
        ((registered ?: register()) + ensured.values).distinctBy { it.name }

    /** The aggregate collection named [name], reading the registry again when this log does not know it. */
    private fun collectionNamed(name: String): AggregateCollection =
        known().firstOrNull { it.name == name } ?: register().first { it.name == name }

    /** Reads the registry again: the aggregate collections of the context, in registry order. */
    private fun register(): List<AggregateCollection> =
        registry.find(Filters.eq(CONTEXT, context)).sort(Sorts.ascending(AGGREGATE))
            .map { AggregateCollection(it.getString(AGGREGATE), it.getString(ID)) }
            .toList()
            .also { registered = it }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        dispatcher.mongo { appendNow(stream, transport) }.also { outcome ->
            if (outcome is AppendOutcome.Committed) pacer.committed(outcome.position.partition)
        }

    private fun appendNow(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val collection = collectionOf(stream.ref.type)
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        val document = encode(partition, stream, transport).getOrElse { return AppendOutcome.Rejected(it) }
        val after = previousOf(collection, stream)
            ?: return duplicateOr(collection, stream, AppendOutcome.Conflict(currentVersion(collection, stream.ref)))
        // A taken position is a block another node sealed: the next allocation comes from a new block.
        repeat(MAX_SEALED) {
            val allocation = sequencer.allocate(partition, collection.name, after) { known().map { it.name } }
            allocation.closing?.let(::close)
            insert(collection, stream, document, allocation)?.let { return it }
        }
        error("The blocks of partition $partition of $context were sealed under $MAX_SEALED appends in a row.")
    }

    /** A group may hold the streams of several partitions (decision D275): each takes its own partition's sequence. */
    override val appendsAcrossPartitions: Boolean get() = true

    /**
     * Appends [streams], of one partition or of several, with one ordered `insertMany` per aggregate collection (group
     * commit, decisions D52, D271, D275): each stream takes its own partition's sequence, and gets the outcome [append]
     * would give it. A stream the store refuses alone fails alone; a sequence no stream took gets its placeholder; a
     * stream whose position another node sealed is appended again alone. A group whose write is unknown is unknown for
     * each stream it held (finding A2-6).
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        require(streams.all { (stream) -> stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        return dispatcher.mongo { appendGroup(streams) }.also { outcomes ->
            outcomes.filterIsInstance<AppendOutcome.Committed>().map { it.position.partition }.distinct()
                .forEach { pacer.committed(it) }
        }
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
    private fun appendGroup(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        val outcomes = arrayOfNulls<AppendOutcome>(streams.size)
        val failed = HashMap<AggregateRef, AppendOutcome>()
        val previous = HashMap<AggregateRef, Long>()
        val members = ArrayList<Member>(streams.size)
        streams.forEachIndexed { index, (stream, transport) ->
            failed[stream.ref]?.let {
                outcomes[index] = it
                return@forEachIndexed
            }
            val collection = collectionOf(stream.ref.type)
            val partition = stream.ref.partitionIn()
            val document = encode(partition, stream, transport).getOrElse {
                outcomes[index] = AppendOutcome.Rejected(it)
                failed[stream.ref] = notAppendedAfter(stream, AppendOutcome.Rejected(it))
                return@forEachIndexed
            }
            val after = previous[stream.ref] ?: previousOf(collection, stream) ?: run {
                val conflict = AppendOutcome.Conflict(currentVersion(collection, stream.ref))
                outcomes[index] = duplicateOr(collection, stream, conflict)
                failed[stream.ref] = notAppendedAfter(stream, checkNotNull(outcomes[index]))
                return@forEachIndexed
            }
            val allocation = sequencer.allocate(partition, collection.name, after) { known().map { it.name } }
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
            insertGroup(collection, group.sortedBy { it.stream.ref.partitionIn() })
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
            // went: a member that landed is committed, the others are appended alone, so it fails alone (R2.2-2).
            return group.associate { member ->
                if (landed(collection, member)) {
                    member.index to outcomeOf(collection, member, null)
                } else {
                    fill(collection, member.stream.ref.partitionIn(), member.sequence, GAP_FAILED)
                    member.index to null
                }
            }
        } catch (failure: MongoException) {
            if (!MongoFailures.isWriteUnknown(failure)) {
                group.forEach { fill(collection, it.stream.ref.partitionIn(), it.sequence, GAP_FAILED) }
                throw failure
            }
            return group.associate { it.index to unknown(it, failure) }
        }
        // Ordered: the members after the first failure were not tried; a placeholder takes each one's sequence.
        val stopped = failures.keys.minOrNull() ?: group.size
        return group.withIndex().associate { (at, member) ->
            if (at > stopped) {
                fill(collection, member.stream.ref.partitionIn(), member.sequence, GAP_FAILED)
                member.index to null
            } else {
                member.index to outcomeOf(collection, member, failures[at])
            }
        }
    }

    /** [member]'s outcome after its insert, which failed with [error] when there is one. */
    private fun outcomeOf(collection: AggregateCollection, member: Member, error: WriteError?): AppendOutcome? {
        val partition = member.stream.ref.partitionIn()
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
                requestIn(collection, stream.ref, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) }
                    ?: AppendOutcome.Unknown(MongoException(error.code, error.message))

            else -> duplicateOr(collection, stream, AppendOutcome.Conflict(currentVersion(collection, stream.ref)))
        }
    }

    /** Whether [member]'s stream is at its position: the document there has its request. */
    private fun landed(collection: AggregateCollection, member: Member): Boolean {
        val partition = member.stream.ref.partitionIn()
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
        fill(member.collection, member.stream.ref.partitionIn(), member.sequence, GAP_UNKNOWN)
        return AppendOutcome.Unknown(failure)
    }

    private fun AggregateRef.partitionIn(): Int = Partitioner.partitionOf(this, partitions)

    /** Every partition of the log: a lookup of the request key without the aggregate seeks it in each. */
    private val everyPartition: Bson = Filters.`in`(PARTITION, (0 until partitions).toList())

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
        val latest = collection.committed.find(refFilter(stream.ref))
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
            // The driver refused the document before sending it (finding R2.2-2).
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
     * a failed socket) is unknown (finding A2-6); any other failure the server answered wrote nothing and is thrown,
     * so the command is answered with the storage error.
     */
    private fun outcomeOf(failure: MongoException, collection: AggregateCollection, stream: EventStreamRecord) =
        when {
            MongoFailures.isTooLarge(failure) -> AppendOutcome.Rejected(failure)
            MongoFailures.isWriteUnknown(failure) -> AppendOutcome.Unknown(failure)
            !failure.isDuplicateKey() -> throw failure
            failure.message.orEmpty().contains(REQUEST_KEY) ->
                requestIn(collection, stream.ref, stream.requestId)
                    ?.let { AppendOutcome.AlreadyCommitted(it) } ?: AppendOutcome.Unknown(failure)

            else -> AppendOutcome.Conflict(currentVersion(collection, stream.ref))
        }

    /**
     * Writes a placeholder at [sequence] of [partition] in [collection] and settles the sequence: a stream that landed
     * there takes it first, and settles it as well. A placeholder that cannot be written now is left to the tail.
     */
    private fun fill(collection: AggregateCollection, partition: Int, sequence: Long, reason: String) {
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
    private fun close(closing: MongoSequencer.Closing) {
        val placeholder = placeholder(closing.partition, closing.from, GAP_CLOSED).append(GAP_TO, closing.to)
        try {
            collectionNamed(closing.collection).appends.insertOne(placeholder)
        } catch (failure: MongoException) {
            if (!failure.isDuplicateKey()) throw failure
        }
        sequencer.settle(closing)
    }

    private fun placeholder(partition: Int, sequence: Long, reason: String): Document =
        Document(ID, positionId(partition, sequence)).append(PARTITION, partition).append(SEQUENCE, sequence)
            .append(GAP, reason).append(GAP_NODE, sequencer.node)

    /**
     * [stream]'s document in [partition], its sequence still to be set: encoded before it is written, so a stream
     * MongoDB cannot store ([ExactBson]'s [IllegalArgumentException]) is refused alone (finding A2-5). A document
     * beyond MongoDB's size limit is only known when the driver writes it, and [insert] refuses it then (finding
     * R2.2-2).
     */
    private fun encode(partition: Int, stream: EventStreamRecord, transport: Transport?): Result<Document> = try {
        Result.success(documentOf(partition, stream, transport))
    } catch (refused: IllegalArgumentException) {
        Result.failure(refused)
    }

    /**
     * [AppendOutcome.AlreadyCommitted] when [stream]'s request is committed, else [conflict] (finding A2-1): a retry of
     * one request at one version collides on both keys, and the version is compared first.
     */
    private fun duplicateOr(
        collection: AggregateCollection,
        stream: EventStreamRecord,
        conflict: AppendOutcome.Conflict,
    ): AppendOutcome =
        requestIn(collection, stream.ref, stream.requestId)?.let { AppendOutcome.AlreadyCommitted(it) }
            ?: conflict

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = dispatcher.mongo {
            val collection = collectionOf(ref.type)
            collection.committed.find(
                Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .projection(FACTS)
                .sort(Sorts.ascending(VERSION))
                .map { streamOf(collection.aggregate, it) }
                .toList()
        }
        streams.forEach { emit(it) }
    }

    /**
     * The streams without tags after [after], read in the version key's order `(partition, aggregate_id, tenant_id,
     * version)` and folded into their aggregates until [limit] are found: the walk follows that index and stops there.
     */
    @InternalWowApi
    override suspend fun untagged(type: AggregateType, after: AggregateRef?, limit: Int): List<AggregateRef> {
        require(limit >= 1) { "A page holds at least one aggregate." }
        return dispatcher.mongo {
            val collection = collectionOf(type)
            val untagged = Filters.and(Filters.exists(VERSION), Filters.exists(TAGS, false))
            val filter = if (after == null) {
                untagged
            } else {
                Filters.and(
                    untagged,
                    Filters.or(
                        Filters.gt(PARTITION, after.partitionIn()),
                        Filters.and(
                            Filters.eq(PARTITION, after.partitionIn()),
                            Filters.or(
                                Filters.gt(AGGREGATE_ID, after.id),
                                Filters.and(Filters.eq(AGGREGATE_ID, after.id), Filters.gt(TENANT_ID, after.tenantId)),
                            ),
                        ),
                    ),
                )
            }
            val found = LinkedHashSet<AggregateRef>()
            collection.committed.find(filter)
                .projection(Projections.include(AGGREGATE_ID, TENANT_ID))
                .sort(Sorts.ascending(PARTITION, AGGREGATE_ID, TENANT_ID, VERSION))
                .batchSize(limit)
                .cursor().use { cursor ->
                    while (found.size < limit && cursor.hasNext()) {
                        val document = cursor.next()
                        found += type.ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID))
                    }
                }
            found.toList()
        }
    }

    /** One unordered bulk write, each version's update only where its stream records no tags. */
    @InternalWowApi
    override suspend fun recordTags(ref: AggregateRef, tagsByVersion: Map<Int, Map<String, List<String>>>): Int {
        if (tagsByVersion.isEmpty()) return 0
        return dispatcher.mongo {
            val updates = tagsByVersion.map { (version, tags) ->
                UpdateOneModel<Document>(
                    Filters.and(refFilter(ref), Filters.eq(VERSION, version), Filters.exists(TAGS, false)),
                    Updates.set(TAGS, Document(tags)),
                )
            }
            collectionOf(ref.type).log.bulkWrite(updates, BulkWriteOptions().ordered(false)).modifiedCount
        }
    }

    /**
     * One aggregation (decision D261): the tail by the version key, then, through `$unionWith` on the same
     * collection, the request keys of the latest streams before it, newest first. The first batch holds them all
     * ([READ_TAIL_BATCH] streams of tail beyond them), so a typical read is one command. Its stages are BSON
     * already ([tailStages]): the driver renders builders on every call, and the last stage once more.
     */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.mongo {
            val collection = collectionOf(ref.type)
            val pipeline = tailStages(ref, fromVersion, earlier, collection.name)
            val streams = ArrayList<EventStreamRecord>()
            val requests = ArrayList<CommittedRequest>()
            var last: LastSequence? = null
            collection.committed.aggregate(pipeline).batchSize(earlier + READ_TAIL_BATCH).forEach { document ->
                val version = document.getInteger(VERSION)
                if (last.let { it == null || version > it.version }) {
                    last = LastSequence(version, document.getLong(SEQUENCE))
                }
                if (document.getInteger(BRANCH) == 0) {
                    streams += streamOf(collection.aggregate, document)
                } else {
                    requests += CommittedRequest(
                        document.getInteger(VERSION),
                        document.getString(REQUEST_ID),
                        document.getString(COMMAND_TYPE),
                    )
                }
            }
            streams.sortBy { it.version }
            requests.sortByDescending { it.version }
            last?.let { lastSequences[ref] = it }
            AggregateTail(streams, requests)
        }
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val entries = dispatcher.mongo {
            val collection = collectionOf(ref.type)
            collection.committed.find(
                Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .sort(Sorts.ascending(VERSION))
                .map { entryOf(collection.aggregate, it) }
                .toList()
        }
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.mongo {
        collectionOf(ref.type).committed.find(Filters.and(refFilter(ref), Filters.eq(VERSION, version)))
            .projection(Projections.include(PARTITION, SEQUENCE))
            .firstOrNull()
            ?.let { LogPosition(it.getInteger(PARTITION), it.getLong(SEQUENCE)) }
    }

    /**
     * The stream committed for [requestId] in [ref]'s partition of its collection: one indexed find. The request key
     * is unique within a partition (decision D277), which is what an append of [ref] meets.
     */
    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        dispatcher.mongo {
            requestIn(collectionOf(ref.type), ref, requestId)
        }

    /**
     * The stream committed for [requestId] within [tenantId] in [type]'s collection, in any partition: one find, an
     * index seek per partition.
     */
    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.mongo { findByRequestIdIn(collectionOf(type), everyPartition, tenantId, requestId) }

    /**
     * The stream committed for [requestId] within [tenantId] in any aggregate collection of the context this log
     * knows (the registry as last read, and the collections it touched since), the earliest committed when two
     * aggregate types hold it: one aggregation over every collection, through `$unionWith`.
     */
    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.mongo {
            val known = ((registered ?: register()) + ensured.values).distinctBy { it.name }
            if (known.isEmpty()) return@mongo null
            fun branch(index: Int) = listOf(
                Aggregates.match(
                    Filters.and(everyPartition, Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId))
                ),
                Aggregates.limit(1),
                Aggregates.addFields(Field(BRANCH, index)),
            )
            val pipeline = branch(0) +
                known.withIndex().drop(1).map { (index, it) -> Aggregates.unionWith(it.name, branch(index)) } +
                listOf(
                    Aggregates.sort(Sorts.ascending(COMMIT_TIME, BRANCH)),
                    Aggregates.limit(1),
                    Aggregates.project(FACTS),
                )
            known.first().committed.aggregate(pipeline).firstOrNull()
                ?.let { streamOf(known[it.getInteger(BRANCH)].aggregate, it) }
        }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        var idle = pacer.interval
        var woken = false
        var stalled = 0L
        var since = 0L
        while (true) {
            val mark = pacer.mark(partition)
            val batch = dispatcher.mongo { readTail(partition, last) }
            batch.entries.forEach { emit(it) }
            last = batch.through
            val missing = batch.missing
            when {
                missing == null -> stalled = 0L
                missing != stalled -> {
                    stalled = missing
                    since = System.nanoTime()
                }

                System.nanoTime() - since >= STALL_GRACE.inWholeNanoseconds -> {
                    dispatcher.mongo { resolve(partition, missing) }
                    since = System.nanoTime()
                }
            }
            if (!batch.again) {
                val pace = pacer.await(partition, mark, batch.entries.isNotEmpty(), idle, woken && missing == null)
                idle = pace.idle
                woken = pace.woken
            }
        }
    }

    /**
     * One read of the tail after [last]: the counter of [partition], then each aggregate collection's first
     * [tailBatch] entries and placeholders after it, in one aggregation over the counters. It gives the entries of the
     * final run from `last + 1` (placeholders stepped over), the sequence that run reaches, and the first missing one
     * when anything lies past it. Reads again at once after a full batch, or when the counter's open blocks name a
     * collection this log does not know, or entries lie past a missing sequence (a collection created since: the
     * registry is read again).
     */
    private fun readTail(partition: Int, last: Long): TailBatch {
        val known = known()
        val pipeline = listOf(
            Aggregates.match(Filters.eq(ID, partition)),
            Aggregates.project(Projections.fields(Projections.excludeId(), Projections.include(MongoSequencer.BLOCKS))),
        ) + known.withIndex().map { (index, collection) ->
            Aggregates.unionWith(
                collection.name,
                listOf(
                    Aggregates.match(
                        Filters.and(
                            Filters.eq(PARTITION, partition),
                            Filters.gt(ID, positionId(partition, last)),
                            Filters.lt(ID, positionId(partition + 1, 0)),
                        ),
                    ),
                    Aggregates.sort(Sorts.ascending(ID)),
                    Aggregates.limit(tailBatch),
                    Aggregates.addFields(Field(BRANCH, index)),
                ),
            )
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
                else -> entries[sequence] = entryOf(known[branch].aggregate, document)
            }
        }
        val (run, next) = finalRun(entries, gaps, last, tailBatch)
        val missing = next.takeIf { entries.keys.any { it > next } || gaps.keys.any { it > next } }
        val names = known.map { it.name }.toSet()
        val unknown = sequencer.openBlocks(counter).any { block -> block.collections.any { it !in names } }
        val again = when {
            run.size == tailBatch -> true
            unknown || missing != null -> register().map { it.name }.toSet() != names
            else -> false
        }
        return TailBatch(run, through = next - 1, missing, again)
    }

    /**
     * One read of a tail: its [entries], the last sequence of its final run ([through], past placeholders), the first
     * missing sequence with something past it, and whether to read again at once.
     */
    private class TailBatch(val entries: List<LogEntry>, val through: Long, val missing: Long?, val again: Boolean)

    /**
     * Resolves [sequence] of [partition], missing past the grace while something lies past it, through the counter's
     * open blocks ([MongoSequencer.resolve]).
     */
    private fun resolve(partition: Int, sequence: Long) {
        val counter = committedCounters.find(Filters.eq(ID, partition)).firstOrNull()
        when (val resolution = sequencer.resolve(partition, sequence, sequencer.openBlocks(counter))) {
            MongoSequencer.Resolution.Wait -> Unit
            is MongoSequencer.Resolution.Close -> close(resolution.closing)
            is MongoSequencer.Resolution.Fill ->
                fill(collectionNamed(resolution.collection), partition, sequence, GAP_UNKNOWN)

            is MongoSequencer.Resolution.Seal -> seal(partition, sequence, resolution.block)
        }
    }

    /**
     * Seals [block], another node's, from [from]: a placeholder at each of its sequences left in each collection it may
     * write, so a write of that node still to come meets a taken position; then takes it out of the counter's list.
     */
    private fun seal(partition: Int, from: Long, block: MongoSequencer.OpenBlock) {
        val placeholders = (from..block.to).map { placeholder(partition, it, GAP_SEALED) }
        block.collections.forEach { name ->
            try {
                collectionNamed(name).appends
                    .insertMany(placeholders.map(::Document), InsertManyOptions().ordered(false))
            } catch (failure: MongoBulkWriteException) {
                if (failure.writeErrors.any { it.category != ErrorCategory.DUPLICATE_KEY }) throw failure
            }
        }
        sequencer.remove(partition, block)
    }

    /**
     * The lineage of [partition] from its counter document (decision D240): its `parent` and `epoch`, read as
     * [PartitionLineage.ROOT] when they are null or missing (a counter written before them).
     */
    override suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo {
            val counter = checkNotNull(committedCounters.find(Filters.eq(ID, partition)).firstOrNull()) {
                "The log of $context has no counter of partition $partition."
            }
            PartitionLineage.of(counter.getInteger(PARENT), counter.getInteger(EPOCH) ?: 0)
        }
    }

    /**
     * The highest sequence of a stream in [partition], in one aggregation over the collections this log knows; a
     * placeholder is no stream, and a reader that reads the tail up to the head waits for a stream there. The counter
     * is the blocks reserved, not the streams written (decision D271). An append in flight may still land below it:
     * one concurrent with this read, which a subscription starting from the head may count as committed before it.
     */
    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo {
            val known = known()
            if (known.isEmpty()) return@mongo null
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
            val pipeline = highest + known.drop(1).map { Aggregates.unionWith(it.name, highest) }
            known.first().committed.aggregate(pipeline).maxOfOrNull { it.getLong(SEQUENCE) }
        }
    }

    /** The stream committed for [requestId] that an append of [ref] meets: the request key in [ref]'s partition. */
    private fun requestIn(collection: AggregateCollection, ref: AggregateRef, requestId: String) =
        findByRequestIdIn(collection, Filters.eq(PARTITION, ref.partitionIn()), ref.tenantId, requestId)

    private fun findByRequestIdIn(collection: AggregateCollection, partitions: Bson, tenantId: String, requestId: String) =
        collection.committed.find(
            Filters.and(partitions, Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId))
        )
            .projection(FACTS)
            .firstOrNull()
            ?.let { streamOf(collection.aggregate, it) }

    private fun currentVersion(collection: AggregateCollection, ref: AggregateRef): Int =
        collection.committed.find(refFilter(ref)).sort(Sorts.descending(VERSION)).limit(1).firstOrNull()
            ?.getInteger(VERSION) ?: 0

    /** [readTail]'s stages, as BSON: the dynamic matches and limit built here, the rest rendered once. */
    private fun tailStages(ref: AggregateRef, fromVersion: Int, earlier: Int, collection: String): List<Bson> {
        fun versions(operator: String) = BsonDocument(
            "\$match",
            BsonDocument(PARTITION, BsonInt32(ref.partitionIn()))
                .append(AGGREGATE_ID, BsonString(ref.id))
                .append(TENANT_ID, BsonString(ref.tenantId))
                .append(VERSION, BsonDocument(operator, BsonInt32(fromVersion))),
        )
        val tail = listOf(versions("\$gte"), TAIL_FACTS, TAIL_BRANCH)
        if (earlier == 0) return tail
        val keys = BsonArray(
            listOf(versions("\$lt"), KEYS_ORDER, BsonDocument("\$limit", BsonInt32(earlier)), KEYS_FIELDS, KEYS_BRANCH),
        )
        return tail + BsonDocument("\$unionWith", BsonDocument("coll", BsonString(collection)).append("pipeline", keys))
    }

    /**
     * The streams of [ref]. The version key is a partial index over the documents with a version (decision D271), so
     * a read uses it only when its filter implies one: a lookup of the latest stream by its key alone would scan the
     * collection.
     */
    private fun refFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(PARTITION, ref.partitionIn()),
        Filters.eq(AGGREGATE_ID, ref.id),
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.exists(VERSION),
    )

    /** [stream]'s document, its sequence set when it is inserted. */
    private fun documentOf(partition: Int, stream: EventStreamRecord, transport: Transport?) = Document()
        .append(PARTITION, partition)
        .append(SEQUENCE, 0L)
        .append(TENANT_ID, stream.ref.tenantId)
        .append(AGGREGATE_ID, stream.ref.id)
        .append(VERSION, stream.version)
        .append(COMMAND_ID, stream.commandId)
        .append(REQUEST_ID, stream.requestId)
        .append(COMMAND_TYPE, stream.commandType)
        .append(COMMIT_TIME, stream.commitTime)
        .append(
            EVENTS,
            stream.events.map {
                Document(NAME, it.name).append(REVISION, it.revision).append(PAYLOAD, ExactBson.document(it.payload))
            },
        )
        .append(OWNER_ID, stream.scope.ownerId)
        .append(SPACE_ID, stream.scope.spaceId)
        .append(
            CAUSATION,
            stream.causation?.let { Document(CORRELATION_ID, it.correlationId).append(CAUSATION_ID, it.causationId) },
        )
        .append(HEADERS, Document(stream.headers))
        .apply { MongoOperators.document(stream.operator)?.let { append(OPERATOR, it) } }
        // The tags after the commit (decision D259): absent when not recorded, an empty document for none.
        .apply { stream.tags?.let { append(TAGS, Document(it)) } }
        .apply { transport?.let { append(TRANSPORT, transportDocument(it)) } }

    private fun transportDocument(transport: Transport) = Document()
        .apply { transport.wait?.let { append(WAIT, waitDocument(it)) } }
        .append(RESERVATIONS, transport.reservations.map(::keyDocument))
        .append(TRACE, Document(transport.trace))
        // Added within format 1 (finding A2-4): left out when empty, so an older reader never meets it.
        .apply { if (transport.releases.isNotEmpty()) append(RELEASES, transport.releases.map(::keyDocument)) }

    /** A reserved key; a system key (added within format 1) is marked, a tenant's is not. */
    private fun keyDocument(key: ReservationKey) = Document(NAMESPACE, key.namespace).append(KEY, key.key)
        .apply { if (key.system) append(SYSTEM, true) }

    private fun keysOf(documents: List<Document>?) = documents.orEmpty().map {
        ReservationKey(it.getString(NAMESPACE), it.getString(KEY), it.getBoolean(SYSTEM) ?: false)
    }

    private fun transportOf(document: Document) = Transport(
        wait = document.get(WAIT, Document::class.java)?.let(::waitOf),
        reservations = keysOf(document.getList(RESERVATIONS, Document::class.java)),
        trace = strings(document.get(TRACE, Document::class.java)),
        releases = keysOf(document.getList(RELEASES, Document::class.java)),
    )

    private fun waitDocument(wait: WaitContext) = Document(WAIT_ID, wait.waitId)
        .append(
            ORIGIN,
            Document(NODE, wait.origin.node).append(ADDRESS, wait.origin.address).append(SERVICE, wait.origin.service),
        )
        .append(
            REMAINING,
            wait.remaining.map { hop ->
                Document(STAGE, hop.stage.name)
                    .apply { hop.context?.let { append(CONTEXT, it) } }
                    .apply { hop.subscription?.let { append(SUBSCRIPTION, it) } }
            },
        )
        .append(DEADLINE, wait.deadline)

    private fun waitOf(document: Document): WaitContext {
        val origin = document.get(ORIGIN, Document::class.java)
        return WaitContext(
            waitId = document.getString(WAIT_ID),
            origin = Origin(origin.getString(NODE), origin.getString(ADDRESS), origin.getString(SERVICE)),
            remaining = document.getList(REMAINING, Document::class.java).map {
                Hop(Stage.valueOf(it.getString(STAGE)), it.getString(CONTEXT), it.getString(SUBSCRIPTION))
            },
            deadline = document.getLong(DEADLINE),
        )
    }

    private fun strings(document: Document): Map<String, String> = document.keys.associateWith {
        document.getString(
            it
        )
    }

    private fun entryOf(aggregate: String, document: Document) = LogEntry(
        LogPosition(document.getInteger(PARTITION), document.getLong(SEQUENCE)),
        streamOf(aggregate, document),
        document.get(TRANSPORT, Document::class.java)?.let(::transportOf),
    )

    private fun streamOf(aggregate: String, document: Document) = EventStreamRecord(
        ref = AggregateType(context, aggregate)
            .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
        version = document.getInteger(VERSION),
        commandId = document.getString(COMMAND_ID),
        requestId = document.getString(REQUEST_ID),
        commandType = document.getString(COMMAND_TYPE),
        commitTime = document.getLong(COMMIT_TIME),
        events = document.getList(EVENTS, Document::class.java).map {
            EventRecord(
                it.getString(NAME),
                it.getInteger(REVISION),
                ExactBson.json(it.get(PAYLOAD, Document::class.java)),
            )
        },
        scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
        causation = document.get(CAUSATION, Document::class.java)?.let {
            Causation(it.getString(CORRELATION_ID), it.getString(CAUSATION_ID))
        },
        headers = strings(document.get(HEADERS, Document::class.java)),
        operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
        tags = document.get(TAGS, Document::class.java)?.let { tags ->
            tags.keys.associateWith { tags.getList(it, String::class.java).toList() }
        },
    )

    internal companion object {
        /**
         * The entries of the final run after [last], at most [limit]: each sequence from `last + 1` is an entry of
         * [entries] or a placeholder of [gaps] (its first sequence to its last), until one is neither; and the first
         * sequence the run did not reach.
         */
        fun finalRun(
            entries: Map<Long, LogEntry>,
            gaps: Map<Long, Long>,
            last: Long,
            limit: Int,
        ): Pair<List<LogEntry>, Long> {
            val run = ArrayList<LogEntry>()
            var next = last + 1
            while (run.size < limit) {
                val entry = entries[next]
                when {
                    entry != null -> run += entry
                    next in gaps -> next = gaps.getValue(next)
                    else -> break
                }
                next++
            }
            return run to next
        }

        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        val DEFAULT_MAX_POLL_INTERVAL = 1.seconds
        const val DEFAULT_TAIL_BATCH = 500

        /** The registry of aggregate collections, shared by the logs of every context. */
        const val REGISTRY = "wow_log_aggregate"
        const val PREFIX = "wow_log"
        const val PARTITION_COUNTERS = "partition"

        /** The registry document recording a log's partition count: its counters' name, then this. */
        const val PARTITIONS_MARKER = "#partitions"
        const val PARTITIONS = "partitions"

        /** The lineage fields of a partition's counter document (decision D240). */
        const val PARENT = "parent"
        const val EPOCH = "epoch"
        const val AGGREGATE = "aggregate"
        const val BRANCH = "branch"
        const val HEAD = "head"

        /** How long an append's insert may take by default: below the default wait timeout of 10 seconds. */
        val DEFAULT_APPEND_TIMEOUT: Duration = 5.seconds

        /** The sequences one reservation of a partition's counter gives a node (decision D271). */
        const val BLOCK = 256L

        /** The most blocks an append takes when each was sealed under it before it gives up. */
        const val MAX_SEALED = 4

        /** The fewest aggregates whose last sequence the log remembers. */
        const val LAST_SEQUENCES = 65_536

        /**
         * The aggregates whose last sequence a log remembers on a heap of at most [maxHeap] bytes: as many states as
         * the node's state caches can hold at most (a tenth of the heap at 1 KB a state, `StateCacheSizing`), at least
         * [LAST_SEQUENCES]. A command on a cached state does not read its aggregate first, so the log reads the last
         * stream itself when it does not remember it: one more round trip on each append. An entry takes about 100
         * bytes, a hundredth of the heap at most.
         */
        fun lastSequencesFor(maxHeap: Long): Int =
            (maxHeap / CACHE_SHARE / STATE_BYTES).coerceIn(LAST_SEQUENCES.toLong(), Int.MAX_VALUE.toLong()).toInt()

        /** The share of the heap and the smallest state of `StateCacheSizing` and `CachedState.STATE_BYTES`. */
        private const val CACHE_SHARE = 10
        private const val STATE_BYTES = 1_024

        /** How long the tail waits at a missing sequence before it resolves it through the open blocks. */
        val STALL_GRACE: Duration = 1.seconds

        /** A placeholder's field: why its sequence holds no stream. */
        const val GAP = "gap"

        /** A range placeholder's last sequence. */
        const val GAP_TO = "gap_to"

        /** The node that wrote a placeholder. */
        const val GAP_NODE = "gap_node"

        /** A sequence whose stream was refused or conflicted. */
        const val GAP_FAILED = "failed"

        /** A sequence whose stream's write had an unknown outcome and did not land. */
        const val GAP_UNKNOWN = "unknown"

        /** A block's unused remainder. */
        const val GAP_CLOSED = "closed"

        /** A sequence of a block another node sealed. */
        const val GAP_SEALED = "sealed"

        /** The streams of tail [readTail]'s first batch holds beyond the earlier request keys. */
        const val READ_TAIL_BATCH = 1000

        const val ID = "_id"
        const val PARTITION = "partition"
        const val SEQUENCE = "sequence"
        const val TENANT_ID = "tenant_id"
        const val AGGREGATE_ID = "aggregate_id"
        const val VERSION = "version"
        const val COMMAND_ID = "command_id"
        const val REQUEST_ID = "request_id"
        const val COMMAND_TYPE = "command_type"
        const val COMMIT_TIME = "commit_time"
        const val OWNER_ID = "owner_id"
        const val SPACE_ID = "space_id"
        const val CAUSATION = "causation"
        const val CORRELATION_ID = "correlation_id"
        const val CAUSATION_ID = "causation_id"
        const val HEADERS = "headers"
        const val OPERATOR = "operator"
        const val TAGS = "tags"
        const val TRANSPORT = "transport"
        const val WAIT = "wait"
        const val WAIT_ID = "wait_id"
        const val ORIGIN = "origin"
        const val NODE = "node"
        const val ADDRESS = "address"
        const val SERVICE = "service"
        const val REMAINING = "remaining"
        const val STAGE = "stage"
        const val CONTEXT = "context"
        const val SUBSCRIPTION = "subscription"
        const val DEADLINE = "deadline"
        const val RESERVATIONS = "reservations"
        const val RELEASES = "releases"
        const val SYSTEM = "system"
        const val NAMESPACE = "namespace"
        const val KEY = "key"
        const val TRACE = "trace"
        const val EVENTS = "events"
        const val NAME = "name"
        const val REVISION = "revision"
        const val PAYLOAD = "payload"
        const val VERSION_KEY = "version_key"
        const val REQUEST_KEY = "request_key"

        /** The index of `_id`, an entry's position: the one a duplicate position names. */
        private const val POSITION_INDEX = "index: _id_ "

        /** The bits of a position's sequence in its `_id`, below its partition's (12 bits, at most 4,096 partitions). */
        const val SEQUENCE_BITS: Int = 51

        /**
         * An entry's `_id` (roadmap §6 item 17): its position, `partition << 51 | sequence`, one long, so that the
         * position needs no index of its own and a partition's entries are one range of `_id`, in sequence order.
         */
        fun positionId(partition: Int, sequence: Long): Long = (partition.toLong() shl SEQUENCE_BITS) or sequence

        /** The version of this layout (decision D59). */
        const val FORMAT = 1

        /** A stream's facts: every field but the transport. */
        val FACTS: Bson = Projections.exclude(TRANSPORT)

        /** The fixed stages of [readTail], rendered once. */
        private val TAIL_FACTS = Aggregates.project(FACTS).toBsonDocument()
        private val TAIL_BRANCH = Aggregates.addFields(Field(BRANCH, 0)).toBsonDocument()
        private val KEYS_ORDER = Aggregates.sort(Sorts.descending(VERSION)).toBsonDocument()
        private val KEYS_FIELDS = Aggregates.project(
            Projections.fields(Projections.include(VERSION, REQUEST_ID, COMMAND_TYPE, SEQUENCE)),
        ).toBsonDocument()
        private val KEYS_BRANCH = Aggregates.addFields(Field(BRANCH, 1)).toBsonDocument()
    }
}

/** An aggregate's last stream as a [MongoEventLog] saw it: its version and sequence. */
private class LastSequence(val version: Int, val sequence: Long)

/**
 * Each aggregate's last stream as a [MongoEventLog] saw it, for at most [capacity] aggregates, the least recently used
 * dropped first: an aggregate it does not hold is read from the log.
 */
private class LastSequences(private val capacity: Int) {
    private val sequences = object : LinkedHashMap<AggregateRef, LastSequence>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AggregateRef, LastSequence>): Boolean =
            size > capacity
    }

    operator fun get(ref: AggregateRef): LastSequence? = synchronized(sequences) { sequences[ref] }

    /** Records [last] as [ref]'s, unless a later version is recorded. */
    operator fun set(ref: AggregateRef, last: LastSequence) {
        synchronized(sequences) {
            sequences.merge(ref, last) { held, seen -> if (seen.version > held.version) seen else held }
        }
    }

    fun remove(ref: AggregateRef) {
        synchronized(sequences) { sequences.remove(ref) }
    }

    private companion object {
        const val INITIAL = 1_024
        const val LOAD_FACTOR = 0.75f
    }
}
