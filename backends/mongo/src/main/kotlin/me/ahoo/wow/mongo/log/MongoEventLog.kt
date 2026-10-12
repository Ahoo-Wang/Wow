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
import com.mongodb.ReadConcern
import com.mongodb.TransactionOptions
import com.mongodb.WriteConcern
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.isDuplicateKey
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
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [EventLog] of one bounded context on MongoDB, a first-class log backend (decision D11;
 * documentation/designs/v10/03-log-and-snapshot.md §2). It needs a replica set, for transactions.
 *
 * One collection holds the log; a second holds one counter document per partition. An append is one multi-document
 * transaction: read the aggregate's version, `$inc` the partition's counter, insert the stream at that sequence.
 * Two transactions bumping one counter conflict and the later one aborts and retries (the driver retries transient
 * transaction errors), so the sequence order is the order commits become visible and no sequence is skipped. That
 * abort-and-retry is why appends to one partition should come from its owner, serialized (finding F1).
 *
 * A stream's facts are fields of its document; the transport is a field of its own, `transport` (decision D25),
 * which only [tail] and [readEntries] read: [read] and [findByRequestId] leave it out of their projection.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
class MongoEventLog(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
) : EventLog {
    private val base = "wow_log_" + context.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    private val database = client.getDatabase(databaseName)
    private val log: MongoCollection<Document> = database.getCollection(base)
    private val counters: MongoCollection<Document> = database.getCollection("${base}_partition")
    private val transaction = TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT)
        .writeConcern(WriteConcern.MAJORITY)
        .build()

    init {
        require(partitions >= 1) { "There is at least one partition." }
    }

    /**
     * Creates the indexes and the partition counters when missing; refuses a different partition count, and a log
     * recorded in another format version ([MongoFormat]).
     */
    fun createSchema() {
        MongoFormat.claim(database, base, FORMAT)
        log.createIndex(
            Indexes.ascending(AGGREGATE_ID, TENANT_ID, AGGREGATE_NAME, VERSION),
            IndexOptions().unique(true).name(VERSION_KEY),
        )
        log.createIndex(Indexes.ascending(TENANT_ID, REQUEST_ID), IndexOptions().unique(true).name(REQUEST_KEY))
        log.createIndex(Indexes.ascending(PARTITION, SEQUENCE), IndexOptions().unique(true).name(POSITION_KEY))
        if (counters.countDocuments() == 0L) {
            counters.insertMany((0 until partitions).map { Document(ID, it).append(SEQUENCE, 0L) })
        }
        val existing = counters.countDocuments()
        check(existing == partitions.toLong()) {
            "The log of $context has $existing partitions, not $partitions: changing it is a repartition."
        }
    }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        withContext(dispatcher) { appendNow(stream, transport) }

    private fun appendNow(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        require(stream.ref.type.context == context) { "${stream.ref} is not in context $context." }
        val partition = Partitioner.partitionOf(stream.ref, partitions)
        return try {
            client.startSession().use { session ->
                session.withTransaction({
                    val current = log.find(session, refFilter(stream.ref))
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
                        log.insertOne(session, documentOf(partition, sequence, stream, transport))
                        AppendOutcome.Committed(LogPosition(partition, sequence))
                    }
                }, transaction)
            }
        } catch (failure: MongoException) {
            // Inside a transaction a duplicate key may surface as a write or a command error: go by its code.
            when {
                !failure.isDuplicateKey() -> AppendOutcome.Unknown(failure)
                failure.message.orEmpty().contains(REQUEST_KEY) ->
                    findByRequestIdNow(stream.ref.tenantId, stream.requestId)
                        ?.let { AppendOutcome.AlreadyCommitted(it) } ?: AppendOutcome.Unknown(failure)

                else -> AppendOutcome.Conflict(currentVersion(stream.ref))
            }
        }
    }

    /**
     * Group commit: one transaction reads every aggregate's version in one aggregation, bumps the partition counter
     * once by the number of streams that fit, and inserts them with one `insertMany`; streams whose version does not
     * fit get their conflict without a write. A duplicate key aborts the whole transaction, so the batch then falls
     * back to one append per stream, which gives each its own outcome.
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
        val grouped = withContext(dispatcher) {
            try {
                client.startSession().use { session ->
                    session.withTransaction({ appendGroup(session, partition, streams) }, transaction)
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
        appends: List<Pair<EventStreamRecord, Transport?>>,
    ): List<AppendOutcome> {
        val streams = appends.map { it.first }
        val versions = currentVersions(session, streams.map { it.ref })
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
        log.insertMany(
            session,
            fits.map { (stream, transport) ->
                documentOf(
                    partition,
                    sequences.getValue(stream.ref),
                    stream,
                    transport
                )
            },
        )
        return streams.map { stream ->
            sequences[stream.ref]?.let { AppendOutcome.Committed(LogPosition(partition, it)) }
                ?: AppendOutcome.Conflict(versions.getOrDefault(stream.ref, 0))
        }
    }

    /** The current version of each of [refs], read in one aggregation; an aggregate with no stream is absent. */
    private fun currentVersions(session: ClientSession, refs: List<AggregateRef>): Map<AggregateRef, Int> =
        log.aggregate(
            session,
            listOf(
                Aggregates.match(Filters.or(refs.map { refFilter(it) })),
                Aggregates.group(
                    Document(AGGREGATE_ID, "$$AGGREGATE_ID").append(TENANT_ID, "$$TENANT_ID")
                        .append(AGGREGATE_NAME, "$$AGGREGATE_NAME"),
                    Accumulators.max(VERSION, "$$VERSION"),
                ),
            ),
        ).associate { group ->
            val key = group.get(ID, Document::class.java)
            AggregateType(context, key.getString(AGGREGATE_NAME))
                .ref(key.getString(TENANT_ID), key.getString(AGGREGATE_ID)) to group.getInteger(VERSION)
        }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = withContext(dispatcher) {
            log.find(Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)))
                .projection(FACTS)
                .sort(Sorts.ascending(VERSION))
                .map { streamOf(it) }
                .toList()
        }
        streams.forEach { emit(it) }
    }

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = flow {
        val entries = withContext(dispatcher) {
            log.find(Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)))
                .sort(Sorts.ascending(VERSION))
                .map { entryOf(it) }
                .toList()
        }
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = withContext(dispatcher) {
        log.find(Filters.and(refFilter(ref), Filters.eq(VERSION, version)))
            .projection(Projections.include(PARTITION, SEQUENCE))
            .firstOrNull()
            ?.let { LogPosition(it.getInteger(PARTITION), it.getLong(SEQUENCE)) }
    }

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? =
        withContext(dispatcher) { findByRequestIdNow(tenantId, requestId) }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        while (true) {
            val batch = withContext(dispatcher) {
                log.find(Filters.and(Filters.eq(PARTITION, partition), Filters.gt(SEQUENCE, last)))
                    .sort(Sorts.ascending(SEQUENCE))
                    .limit(tailBatch)
                    .map { entryOf(it) }
                    .toList()
            }
            batch.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (batch.size < tailBatch) {
                delay(pollInterval)
            }
        }
    }

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return withContext(dispatcher) {
            // The counter is bumped in the transaction that inserts the entry, so it is the last committed sequence.
            counters.find(Filters.eq(ID, partition)).firstOrNull()?.getLong(SEQUENCE)?.takeIf { it > 0 }
        }
    }

    private fun findByRequestIdNow(tenantId: String, requestId: String): EventStreamRecord? =
        log.find(Filters.and(Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId)))
            .projection(FACTS)
            .firstOrNull()
            ?.let { streamOf(it) }

    private fun currentVersion(ref: AggregateRef): Int =
        log.find(refFilter(ref)).sort(Sorts.descending(VERSION)).limit(1).firstOrNull()?.getInteger(VERSION) ?: 0

    private fun refFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(AGGREGATE_ID, ref.id),
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.eq(AGGREGATE_NAME, ref.type.name),
    )

    private fun documentOf(partition: Int, sequence: Long, stream: EventStreamRecord, transport: Transport?) = Document()
        .append(PARTITION, partition)
        .append(SEQUENCE, sequence)
        .append(AGGREGATE_NAME, stream.ref.type.name)
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
                Document(NAME, it.name).append(REVISION, it.revision).append(PAYLOAD, Document.parse(it.payload))
            },
        )
        .append(OWNER_ID, stream.scope.ownerId)
        .append(SPACE_ID, stream.scope.spaceId)
        .append(
            CAUSATION,
            stream.causation?.let { Document(CORRELATION_ID, it.correlationId).append(CAUSATION_ID, it.causationId) },
        )
        .append(HEADERS, Document(stream.headers))
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

    private fun entryOf(document: Document) = LogEntry(
        LogPosition(document.getInteger(PARTITION), document.getLong(SEQUENCE)),
        streamOf(document),
        document.get(TRANSPORT, Document::class.java)?.let(::transportOf),
    )

    private fun streamOf(document: Document) = EventStreamRecord(
        ref = AggregateType(context, document.getString(AGGREGATE_NAME))
            .ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
        version = document.getInteger(VERSION),
        commandId = document.getString(COMMAND_ID),
        requestId = document.getString(REQUEST_ID),
        commandType = document.getString(COMMAND_TYPE),
        commitTime = document.getLong(COMMIT_TIME),
        events = document.getList(EVENTS, Document::class.java).map {
            EventRecord(it.getString(NAME), it.getInteger(REVISION), it.get(PAYLOAD, Document::class.java).toJson(JSON))
        },
        scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
        causation = document.get(CAUSATION, Document::class.java)?.let {
            Causation(it.getString(CORRELATION_ID), it.getString(CAUSATION_ID))
        },
        headers = strings(document.get(HEADERS, Document::class.java)),
    )

    private companion object {
        val DEFAULT_POLL_INTERVAL = 50.milliseconds
        const val DEFAULT_TAIL_BATCH = 500
        val JSON: JsonWriterSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

        const val ID = "_id"
        const val PARTITION = "partition"
        const val SEQUENCE = "sequence"
        const val AGGREGATE_NAME = "aggregate_name"
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
