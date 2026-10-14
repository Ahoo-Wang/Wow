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
import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.TransactionOptions
import com.mongodb.WriteConcern
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [EventLog] of one bounded context on MongoDB, a first-class log backend (decision D11;
 * documentation/designs/v10/03-log-and-snapshot.md §2). It needs a replica set, for transactions.
 *
 * The log takes one collection per aggregate type, `wow_log__<context>__<aggregate>` (decision D162); a context-level
 * collection holds one counter document per partition, so partitions, their sequences, leases and checkpoints stay
 * per context. An append is one multi-document transaction: read the aggregate's version from its collection, `$inc`
 * the partition's counter, insert the stream into its aggregate's collection at that sequence. Two transactions
 * bumping one counter conflict and the later one aborts and retries (the driver retries transient transaction
 * errors), so the sequence order is the order commits become visible, whichever collection they land in, and no
 * sequence is skipped. That abort-and-retry is why appends to one partition should come from its owner, serialized
 * (finding F1).
 *
 * **Aggregate collections.** An aggregate type's collection and its indexes are created the first time this log
 * touches the type, or up front by [createCollection]; a new aggregate type needs no restart. Every aggregate collection is recorded in a registry collection ([REGISTRY]) before its first entry is
 * written, and [tail] reads the context's collections from it.
 *
 * **Tail.** [tail] reads a partition in one aggregation: the partition's counter first, then, through `$unionWith`,
 * each aggregate collection's first entries after the last sequence; it merges them by sequence and emits only the
 * contiguous run from the last sequence. Every entry up to the counter it read committed before the counter was read,
 * so a run shorter than the counter calls for means a collection this log does not know yet (it reads the registry
 * again and retries) or one read before a commit landed (it retries after the poll interval): it never steps over a
 * missing sequence.
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
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
class MongoEventLog(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
) : EventLog {
    private val database = client.getDatabase(databaseName)
    private val countersName = MongoNames.of(PREFIX, context, PARTITION_COUNTERS)
    private val counters: MongoCollection<Document> = database.getCollection(countersName)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)
    private val transaction = TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT)
        .writeConcern(WriteConcern.MAJORITY)
        .build()

    /** The aggregate collections whose creation and registration this log has made sure of. */
    private val ensured = ConcurrentHashMap<String, AggregateCollection>()

    /** The aggregate collections of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateCollection>? = null

    /** The log collection of one aggregate type. */
    private inner class AggregateCollection(val aggregate: String, val name: String) {
        val log: MongoCollection<Document> = database.getCollection(name)
    }

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    /**
     * Creates the partition counters when missing; refuses a different partition count, and a log recorded in another
     * format version ([MongoFormat]). The aggregate collections are created on first use, or by [createCollection].
     */
    fun createSchema() {
        MongoFormat.claim(database, countersName, FORMAT)
        MongoFormat.claim(database, REGISTRY, FORMAT)
        if (counters.countDocuments() == 0L) {
            counters.insertMany((0 until partitions).map { Document(ID, it).append(SEQUENCE, 0L) })
        }
        val existing = counters.countDocuments()
        check(existing == partitions.toLong()) {
            "The log of $context has $existing partitions, not $partitions: changing it is a repartition."
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
        MongoFormat.claim(database, collection.name, FORMAT)
        collection.log.createIndex(
            Indexes.ascending(AGGREGATE_ID, TENANT_ID, VERSION),
            IndexOptions().unique(true).name(VERSION_KEY),
        )
        collection.log.createIndex(
            Indexes.ascending(TENANT_ID, REQUEST_ID),
            IndexOptions().unique(true).name(REQUEST_KEY),
        )
        collection.log.createIndex(
            Indexes.ascending(PARTITION, SEQUENCE),
            IndexOptions().unique(true).name(POSITION_KEY),
        )
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

    /** Reads the registry again: the aggregate collections of the context, in registry order. */
    private fun register(): List<AggregateCollection> =
        registry.find(Filters.eq(CONTEXT, context)).sort(Sorts.ascending(AGGREGATE))
            .map { AggregateCollection(it.getString(AGGREGATE), it.getString(ID)) }
            .toList()
            .also { registered = it }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        dispatcher.mongo { appendNow(stream, transport) }

    private fun appendNow(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val collection = collectionOf(stream.ref.type)
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        return try {
            client.startSession().use { session ->
                session.withTransaction({
                    val current = collection.log.find(session, refFilter(stream.ref))
                        .sort(Sorts.descending(VERSION))
                        .limit(1)
                        .firstOrNull()
                        ?.getInteger(VERSION) ?: 0
                    if (current != stream.version - 1) {
                        AppendOutcome.Conflict(current)
                    } else {
                        val sequence = counters.findOneAndUpdate(
                            session,
                            Filters.eq(ID, partition),
                            Updates.inc(SEQUENCE, 1L),
                            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
                        )!!.getLong(SEQUENCE)
                        collection.log.insertOne(session, documentOf(partition, sequence, stream, transport))
                        AppendOutcome.Committed(LogPosition(partition, sequence))
                    }
                }, transaction)
            }
        } catch (failure: MongoException) {
            // Inside a transaction a duplicate key may surface as a write or a command error: go by its code.
            when {
                !failure.isDuplicateKey() -> AppendOutcome.Unknown(failure)
                failure.message.orEmpty().contains(REQUEST_KEY) ->
                    findByRequestIdIn(collection, stream.ref.tenantId, stream.requestId)
                        ?.let { AppendOutcome.AlreadyCommitted(it) } ?: AppendOutcome.Unknown(failure)

                else -> AppendOutcome.Conflict(currentVersion(collection, stream.ref))
            }
        }
    }

    /**
     * Group commit: one transaction reads every aggregate's version (one aggregation per aggregate type), bumps the
     * partition counter once by the number of streams that fit, and inserts them with one `insertMany` per aggregate
     * type; streams whose version does not fit get their conflict without a write. A duplicate key aborts the whole
     * transaction, so the batch then falls back to one append per stream, which gives each its own outcome.
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, Transport?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        val partition = Partitioner.partitionOf(streams.first().first.ref, partitions)
        require(
            streams.all { (stream) ->
                stream.ref.type.context == context && Partitioner.partitionOf(stream.ref, partitions) == partition
            }
        ) {
            "A group commit appends streams of one partition of $context."
        }
        require(streams.map { it.first.ref }.toSet().size == streams.size) {
            "A group commit appends distinct aggregates."
        }
        val grouped = dispatcher.mongo {
            val collections = streams.map { it.first.ref.type }.distinct().associateWith { collectionOf(it) }
            try {
                client.startSession().use { session ->
                    session.withTransaction({ appendGroup(session, partition, collections, streams) }, transaction)
                }
            } catch (failure: MongoException) {
                null
            }
        }
        return grouped ?: streams.map { (stream, transport) -> append(stream, transport) }
    }

    private fun appendGroup(
        session: ClientSession,
        partition: Int,
        collections: Map<AggregateType, AggregateCollection>,
        appends: List<Pair<EventStreamRecord, Transport?>>,
    ): List<AppendOutcome> {
        val streams = appends.map { it.first }
        val versions = streams.groupBy { it.ref.type }
            .flatMap { (type, ofType) -> currentVersions(session, collections.getValue(type), ofType.map { it.ref }) }
            .toMap()
        val fits = appends.filter { (stream) -> versions.getOrDefault(stream.ref, 0) == stream.version - 1 }
        if (fits.isEmpty()) {
            return streams.map { AppendOutcome.Conflict(versions.getOrDefault(it.ref, 0)) }
        }
        val last = counters.findOneAndUpdate(
            session,
            Filters.eq(ID, partition),
            Updates.inc(SEQUENCE, fits.size.toLong()),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )!!.getLong(SEQUENCE)
        val first = last - fits.size + 1
        val sequences = fits.withIndex().associate { (index, append) -> append.first.ref to first + index }
        fits.groupBy { it.first.ref.type }.forEach { (type, ofType) ->
            collections.getValue(type).log.insertMany(
                session,
                ofType.map { (stream, transport) ->
                    documentOf(partition, sequences.getValue(stream.ref), stream, transport)
                },
            )
        }
        return streams.map { stream ->
            sequences[stream.ref]?.let { AppendOutcome.Committed(LogPosition(partition, it)) }
                ?: AppendOutcome.Conflict(versions.getOrDefault(stream.ref, 0))
        }
    }

    /** The current version of each of [refs], read in one aggregation; an aggregate with no stream is absent. */
    private fun currentVersions(
        session: ClientSession,
        collection: AggregateCollection,
        refs: List<AggregateRef>,
    ): List<Pair<AggregateRef, Int>> =
        collection.log.aggregate(
            session,
            listOf(
                Aggregates.match(Filters.or(refs.map { refFilter(it) })),
                Aggregates.group(
                    Document(AGGREGATE_ID, "$$AGGREGATE_ID").append(TENANT_ID, "$$TENANT_ID"),
                    Accumulators.max(VERSION, "$$VERSION"),
                ),
            ),
        ).map { group ->
            val key = group.get(ID, Document::class.java)
            AggregateType(context, collection.aggregate)
                .ref(key.getString(TENANT_ID), key.getString(AGGREGATE_ID)) to group.getInteger(VERSION)
        }.toList()

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = dispatcher.mongo {
            val collection = collectionOf(ref.type)
            collection.log.find(
                Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .projection(FACTS)
                .sort(Sorts.ascending(VERSION))
                .map { streamOf(collection.aggregate, it) }
                .toList()
        }
        streams.forEach { emit(it) }
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val entries = dispatcher.mongo {
            val collection = collectionOf(ref.type)
            collection.log.find(
                Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .sort(Sorts.ascending(VERSION))
                .map { entryOf(collection.aggregate, it) }
                .toList()
        }
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.mongo {
        collectionOf(ref.type).log.find(Filters.and(refFilter(ref), Filters.eq(VERSION, version)))
            .projection(Projections.include(PARTITION, SEQUENCE))
            .firstOrNull()
            ?.let { LogPosition(it.getInteger(PARTITION), it.getLong(SEQUENCE)) }
    }

    /** The stream committed for [requestId] within [tenantId] in [type]'s collection: one indexed find. */
    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.mongo { findByRequestIdIn(collectionOf(type), tenantId, requestId) }

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
                Aggregates.match(Filters.and(Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId))),
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
            known.first().log.aggregate(pipeline).firstOrNull()
                ?.let { streamOf(known[it.getInteger(BRANCH)].aggregate, it) }
        }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        while (true) {
            val batch = dispatcher.mongo { readTail(partition, last) }
            batch.entries.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (!batch.again) {
                delay(pollInterval)
            }
        }
    }

    /**
     * One read of the tail after [last]: the counter of [partition], then each aggregate collection's first
     * [tailBatch] entries after it, in one aggregation over the counters; the contiguous run of entries from
     * `last + 1`, and whether to read again at once. A run shorter than the counter calls for reads the registry and,
     * when that finds new collections, reads again at once.
     */
    private fun readTail(partition: Int, last: Long): TailBatch {
        val known = registered ?: register()
        val pipeline = listOf(
            Aggregates.match(Filters.eq(ID, partition)),
            Aggregates.project(Projections.fields(Projections.excludeId(), Projections.computed(HEAD, "$$SEQUENCE"))),
        ) + known.withIndex().map { (index, collection) ->
            Aggregates.unionWith(
                collection.name,
                listOf(
                    Aggregates.match(Filters.and(Filters.eq(PARTITION, partition), Filters.gt(SEQUENCE, last))),
                    Aggregates.sort(Sorts.ascending(SEQUENCE)),
                    Aggregates.limit(tailBatch),
                    Aggregates.addFields(Field(BRANCH, index)),
                ),
            )
        }
        var head = 0L
        val entries = ArrayList<LogEntry>()
        counters.aggregate(pipeline).forEach { document ->
            val branch = document.getInteger(BRANCH)
            if (branch == null) {
                head = document.getLong(HEAD)
            } else {
                entries += entryOf(known[branch].aggregate, document)
            }
        }
        entries.sortBy { it.position.sequence }
        val run = contiguousRun(entries, last).take(tailBatch)
        val expected = minOf(head - last, tailBatch.toLong()).coerceAtLeast(0)
        val again = when {
            run.size < expected -> register().map { it.name } != known.map { it.name }
            else -> run.size == tailBatch
        }
        return TailBatch(run, again)
    }

    private class TailBatch(val entries: List<LogEntry>, val again: Boolean)

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo {
            // The counter is bumped in the transaction that inserts the entry, so it is the last committed sequence.
            counters.find(Filters.eq(ID, partition)).firstOrNull()?.getLong(SEQUENCE)?.takeIf { it > 0 }
        }
    }

    private fun findByRequestIdIn(collection: AggregateCollection, tenantId: String, requestId: String) =
        collection.log.find(Filters.and(Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId)))
            .projection(FACTS)
            .firstOrNull()
            ?.let { streamOf(collection.aggregate, it) }

    private fun currentVersion(collection: AggregateCollection, ref: AggregateRef): Int =
        collection.log.find(refFilter(ref)).sort(Sorts.descending(VERSION)).limit(1).firstOrNull()
            ?.getInteger(VERSION) ?: 0

    private fun refFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(AGGREGATE_ID, ref.id),
        Filters.eq(TENANT_ID, ref.tenantId),
    )

    private fun documentOf(partition: Int, sequence: Long, stream: EventStreamRecord, transport: Transport?) = Document()
        .append(PARTITION, partition)
        .append(SEQUENCE, sequence)
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
        .apply { transport?.let { append(TRANSPORT, transportDocument(it)) } }

    private fun transportDocument(transport: Transport) = Document()
        .apply { transport.wait?.let { append(WAIT, waitDocument(it)) } }
        .append(RESERVATIONS, transport.reservations.map { Document(NAMESPACE, it.namespace).append(KEY, it.key) })
        .append(TRACE, Document(transport.trace))

    private fun transportOf(document: Document) = Transport(
        wait = document.get(WAIT, Document::class.java)?.let(::waitOf),
        reservations = document.getList(RESERVATIONS, Document::class.java).map {
            ReservationKey(it.getString(NAMESPACE), it.getString(KEY))
        },
        trace = strings(document.get(TRACE, Document::class.java)),
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
    )

    private companion object {
        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        const val DEFAULT_TAIL_BATCH = 500

        /** The registry of aggregate collections, shared by the logs of every context. */
        const val REGISTRY = "wow_log_aggregate"
        const val PREFIX = "wow_log"
        const val PARTITION_COUNTERS = "partition"
        const val AGGREGATE = "aggregate"
        const val BRANCH = "branch"
        const val HEAD = "head"

        /** The entries of [entries], in sequence order, that continue `last` without a gap. */
        fun contiguousRun(entries: List<LogEntry>, last: Long): List<LogEntry> {
            var next = last + 1
            return entries.takeWhile { it.position.sequence == next++ }
        }

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
        const val NAMESPACE = "namespace"
        const val KEY = "key"
        const val TRACE = "trace"
        const val EVENTS = "events"
        const val NAME = "name"
        const val REVISION = "revision"
        const val PAYLOAD = "payload"
        const val VERSION_KEY = "version_key"
        const val REQUEST_KEY = "request_key"
        const val POSITION_KEY = "position_key"

        /** The version of this layout (decision D59). */
        const val FORMAT = 1

        /** A stream's facts: every field but the transport. */
        val FACTS: Bson = Projections.exclude(TRANSPORT)
    }
}
