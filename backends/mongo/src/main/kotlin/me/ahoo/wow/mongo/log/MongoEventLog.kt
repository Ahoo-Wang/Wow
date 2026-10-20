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
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.bson.BsonMaximumSizeExceededException
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The [EventLog] of one bounded context on MongoDB, a first-class log backend (decision D11;
 * documentation/designs/v10/03-log-and-snapshot.md §2). It needs a replica set, for transactions.
 *
 * The log takes one collection per aggregate type, `wow_log__<context>__<aggregate>` (decision D162); a context-level
 * collection holds one counter document per partition, so partitions, their sequences, leases and checkpoints stay
 * per context. A counter also holds its partition's lineage, `parent` (null) and `epoch` (0) (decision D240): format 1
 * reserves them for the online splits of 10.x, and [lineage] reads them back. An append is one multi-document transaction: read the aggregate's version from its collection, `$inc`
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
 * **Read concern.** Every read outside an append's transaction, the tail, [head], the version slot reads
 * ([readEntries], [positionOf]) and the request lookups included, reads what a majority of the replica set has
 * committed (`readConcern: majority`, finding A2-2). With the default (`local`), a tail could deliver an entry the
 * primary committed and lost in a failover before it replicated, which is then rolled back: its subscriptions would
 * have acted on a fact that never was, and the next append, taking its sequence again, would be skipped for good,
 * the checkpoints past it. The cost is latency: an entry reaches the tail once a majority has it, about one
 * replication round trip after its commit (appends already wait for it, `w: majority`). The backend so needs a
 * replica set whose majority can commit: an arbiter that makes a majority unreachable stalls the tail.
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
     * How long one attempt of an append's transaction may take (decision D261): a server that does not answer fails
     * it, and is not retried, so a command appending during an outage is answered before its wait ends, with the
     * retryable storage error when no commit was sent, else unknown. Below the default wait timeout. Write conflicts
     * on the partition's counter are retried, each attempt bounded anew ([transact]).
     */
    transactionTimeout: Duration = DEFAULT_TRANSACTION_TIMEOUT,
) : EventLog {
    /** Validates the partition count first (decision D240): a log of another count is refused when it is built. */
    private val pacer = TailPacer(Partitioner.requirePartitions(partitions), pollInterval, maxPollInterval)

    private val database = client.getDatabase(databaseName)
    private val countersName = MongoNames.of(PREFIX, context, PARTITION_COUNTERS)
    private val counters: MongoCollection<Document> = database.getCollection(countersName)

    /** The counters as a majority has committed them: what the tail and [head] read (finding A2-2). */
    private val committedCounters: MongoCollection<Document> = counters.withReadConcern(ReadConcern.MAJORITY)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)
    private val transaction = TransactionOptions.builder()
        .readConcern(ReadConcern.SNAPSHOT)
        .writeConcern(WriteConcern.MAJORITY)
        .timeout(transactionTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    /** The aggregate collections whose creation and registration this log has made sure of. */
    private val ensured = ConcurrentHashMap<String, AggregateCollection>()

    /** The aggregate collections of the context, as of the last read of the registry, in registry order. */
    @Volatile
    private var registered: List<AggregateCollection>? = null

    /** The log collection of one aggregate type. */
    private inner class AggregateCollection(val aggregate: String, val name: String) {
        val log: MongoCollection<Document> = database.getCollection(name)

        /** The entries a majority has committed: every read outside an append's transaction (finding A2-2). */
        val committed: MongoCollection<Document> = log.withReadConcern(ReadConcern.MAJORITY)
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
        MongoFormat.index(log, Indexes.ascending(AGGREGATE_ID, TENANT_ID, VERSION), unique(VERSION_KEY), ddl)
        MongoFormat.index(log, Indexes.ascending(TENANT_ID, REQUEST_ID), unique(REQUEST_KEY), ddl)
        MongoFormat.index(log, Indexes.ascending(PARTITION, SEQUENCE), unique(POSITION_KEY), ddl)
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
        // Whether a transaction's commit may have been sent: before it, any failure aborted the transaction for sure.
        var committing = false
        val outcome = try {
            client.startSession().use { session ->
                transact(session, committing = { committing = true }) {
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
                        collection.log.insertOne(session, document.append(SEQUENCE, sequence))
                        AppendOutcome.Committed(LogPosition(partition, sequence))
                    }
                }
            }
        } catch (tooLarge: BsonMaximumSizeExceededException) {
            // The driver refused the document before sending it, so the transaction wrote nothing (finding R2.2-2).
            AppendOutcome.Rejected(tooLarge)
        } catch (failure: MongoException) {
            outcomeOf(failure, committing, collection, stream)
        }
        return if (outcome is AppendOutcome.Conflict) duplicateOr(collection, stream, outcome) else outcome
    }

    /**
     * Runs [body] in a transaction of [session] and commits it, retrying as the driver's `withTransaction` does: the
     * whole transaction on a `TransientTransactionError` (a write conflict on the partition's counter, say), the commit
     * on an `UnknownTransactionCommitResult`, for at most [MAX_RETRY]. Unlike it, each attempt is bounded by the
     * transaction timeout, and a server that cannot be reached ([MongoFailures.isUnreachable]) is not retried, so a
     * command appending during an outage is answered before its wait ends. [committing] is called before each commit.
     */
    private fun <T> transact(session: ClientSession, committing: () -> Unit = {}, body: () -> T): T {
        val until = System.nanoTime() + MAX_RETRY.inWholeNanoseconds
        while (true) {
            session.startTransaction(transaction)
            val result = attempt(session, until, body) ?: continue
            committing()
            if (committed(session, until)) {
                return result.value
            }
        }
    }

    /** What [body] returned in one attempt of a transaction; null when the transaction is to be tried again. */
    private fun <T> attempt(session: ClientSession, until: Long, body: () -> T): Attempted<T>? = try {
        Attempted(body())
    } catch (failure: MongoException) {
        abort(session, failure)
        if (!retries(failure, MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL, until)) throw failure
        null
    } catch (
        @Suppress("TooGenericExceptionCaught") // Aborts whatever else failed, then rethrows it unchanged.
        failure: RuntimeException,
    ) {
        abort(session, failure)
        throw failure
    }

    /** Commits [session]'s transaction: true once committed, false when the whole transaction is to be tried again. */
    private fun committed(session: ClientSession, until: Long): Boolean {
        while (true) {
            try {
                session.commitTransaction()
                return true
            } catch (failure: MongoException) {
                when {
                    retries(failure, MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL, until) -> Unit
                    retries(failure, MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL, until) -> {
                        abort(session, failure)
                        return false
                    }

                    else -> throw failure
                }
            }
        }
    }

    /** Whether [failure], labelled [label], is retried: a server that answered, before [until] (nanoseconds). */
    private fun retries(failure: MongoException, label: String, until: Long): Boolean =
        failure.hasErrorLabel(label) && !MongoFailures.isUnreachable(failure) && System.nanoTime() < until

    private class Attempted<T>(val value: T)

    /** Aborts [session]'s transaction after [failure], when it is still open; a failing abort joins [failure]. */
    private fun abort(session: ClientSession, failure: Throwable) {
        if (!session.hasActiveTransaction()) return
        try {
            session.abortTransaction()
        } catch (aborting: MongoException) {
            failure.addSuppressed(aborting)
        }
    }

    /**
     * The outcome of [stream]'s transaction that failed with [failure]; [committing] says whether its commit may have
     * been sent. Inside a transaction a duplicate key may surface as a write or a command error: go by its code. A
     * transient failure before the commit aborted the transaction for sure: it is thrown, so the command is answered
     * with the retryable storage error rather than an unknown outcome (finding A2-6 keeps unknown for a commit).
     */
    private fun outcomeOf(
        failure: MongoException,
        committing: Boolean,
        collection: AggregateCollection,
        stream: EventStreamRecord,
    ): AppendOutcome = when {
        MongoFailures.isTooLarge(failure) -> AppendOutcome.Rejected(failure)
        !committing && MongoFailures.isTransient(failure) -> throw failure
        !failure.isDuplicateKey() -> AppendOutcome.Unknown(failure)
        failure.message.orEmpty().contains(REQUEST_KEY) ->
            findByRequestIdIn(collection, stream.ref.tenantId, stream.requestId)
                ?.let { AppendOutcome.AlreadyCommitted(it) } ?: AppendOutcome.Unknown(failure)

        else -> AppendOutcome.Conflict(currentVersion(collection, stream.ref))
    }

    /**
     * [stream]'s document in [partition], its sequence still to be set: encoded before any transaction, so a stream
     * MongoDB cannot store ([ExactBson]'s [IllegalArgumentException]) is refused alone (finding A2-5). A document beyond
     * MongoDB's size limit is only known when the driver writes it: [appendNow] refuses it, and [commitGroup] falls
     * back to one append per stream, so it fails alone as well (finding R2.2-2).
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
        findByRequestIdIn(collection, stream.ref.tenantId, stream.requestId)?.let { AppendOutcome.AlreadyCommitted(it) }
            ?: conflict

    /**
     * Group commit: one transaction reads every aggregate's version (one aggregation per aggregate type), bumps the
     * partition counter once by the number of streams that fit, and inserts them with one `insertMany` per aggregate
     * type; streams whose version does not fit get their conflict without a write.
     *
     * Each stream is encoded first, outside the transaction: one MongoDB cannot store is [AppendOutcome.Rejected] and
     * left out, the others commit together (finding A2-5). When the transaction fails, a definite abort (a duplicate
     * key, a transient error retried to the driver's limit) falls back to one append per stream, which gives each its
     * own outcome; a commit whose result is unknown, or a timeout, is [AppendOutcome.Unknown] for every stream of the
     * group, since the group may have committed (finding A2-6): the kernel reads each version slot to settle it.
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
        return dispatcher.mongo {
            val encoded = streams.map { (stream, transport) -> encode(partition, stream, transport) }
            val members = streams.indices.filter { encoded[it].isSuccess }
            val outcomes: Map<Int, AppendOutcome> = when (members.size) {
                0 -> emptyMap()
                1 -> members.associateWith { appendNow(streams[it].first, streams[it].second) }
                else -> {
                    val grouped = commitGroup(partition, members.map { streams[it].first to encoded[it].getOrThrow() })
                    members.zip(grouped).associate { (index, outcome) ->
                        index to (outcome ?: appendNow(streams[index].first, streams[index].second))
                    }
                }
            }
            streams.indices.map { index ->
                outcomes[index] ?: AppendOutcome.Rejected(checkNotNull(encoded[index].exceptionOrNull()))
            }
        }.also { outcomes ->
            if (outcomes.any { it is AppendOutcome.Committed }) pacer.committed(partition)
        }
    }

    /**
     * Commits [members], streams with their documents, in one transaction: their outcomes, each null when the
     * transaction was aborted for sure and the stream is to be appended alone.
     */
    private fun commitGroup(partition: Int, members: List<Pair<EventStreamRecord, Document>>): List<AppendOutcome?> {
        val collections = members.map { it.first.ref.type }.distinct().associateWith { collectionOf(it) }
        return try {
            client.startSession().use { session ->
                transact(session) { appendGroup(session, partition, collections, members) }
            }.mapIndexed { index, outcome ->
                val stream = members[index].first
                if (outcome is AppendOutcome.Conflict) {
                    duplicateOr(collections.getValue(stream.ref.type), stream, outcome)
                } else {
                    outcome
                }
            }
        } catch (tooLarge: BsonMaximumSizeExceededException) {
            // A member's document is beyond the size limit; the driver never sent it, so the group is aborted for
            // sure: each stream appended alone gets its own outcome, the oversized one its refusal (finding R2.2-2).
            members.map { null }
        } catch (failure: MongoException) {
            if (MongoFailures.isCommitUnknown(failure)) {
                members.map { AppendOutcome.Unknown(failure) }
            } else {
                members.map { null }
            }
        }
    }

    private fun appendGroup(
        session: ClientSession,
        partition: Int,
        collections: Map<AggregateType, AggregateCollection>,
        members: List<Pair<EventStreamRecord, Document>>,
    ): List<AppendOutcome> {
        val streams = members.map { it.first }
        val versions = streams.groupBy { it.ref.type }
            .flatMap { (type, ofType) -> currentVersions(session, collections.getValue(type), ofType.map { it.ref }) }
            .toMap()
        val fits = members.filter { (stream) -> versions.getOrDefault(stream.ref, 0) == stream.version - 1 }
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
        val sequences = fits.withIndex().associate { (index, member) -> member.first.ref to first + index }
        fits.groupBy { it.first.ref.type }.forEach { (type, ofType) ->
            collections.getValue(type).log.insertMany(
                session,
                ofType.map { (stream, document) -> document.append(SEQUENCE, sequences.getValue(stream.ref)) },
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
     * One aggregation (decision D261): the tail by the version key, then, through `$unionWith` on the same
     * collection, the request keys of the latest streams before it, newest first. The first batch holds them all
     * ([READ_TAIL_BATCH] streams of tail beyond them), so a typical read is one command.
     */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.mongo {
            val collection = collectionOf(ref.type)
            val tail = listOf(
                Aggregates.match(Filters.and(refFilter(ref), Filters.gte(VERSION, fromVersion))),
                Aggregates.project(FACTS),
                Aggregates.addFields(Field(BRANCH, 0)),
            )
            val keys = listOf(
                Aggregates.match(Filters.and(refFilter(ref), Filters.lt(VERSION, fromVersion))),
                Aggregates.sort(Sorts.descending(VERSION)),
                Aggregates.limit(earlier.coerceAtLeast(1)),
                Aggregates.project(Projections.fields(Projections.include(VERSION, REQUEST_ID, COMMAND_TYPE))),
                Aggregates.addFields(Field(BRANCH, 1)),
            )
            val pipeline = if (earlier == 0) tail else tail + Aggregates.unionWith(collection.name, keys)
            val streams = ArrayList<EventStreamRecord>()
            val requests = ArrayList<CommittedRequest>()
            collection.committed.aggregate(pipeline).batchSize(earlier + READ_TAIL_BATCH).forEach { document ->
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
            known.first().committed.aggregate(pipeline).firstOrNull()
                ?.let { streamOf(known[it.getInteger(BRANCH)].aggregate, it) }
        }

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        var idle = pacer.interval
        var woken = false
        while (true) {
            val mark = pacer.mark(partition)
            val batch = dispatcher.mongo { readTail(partition, last) }
            batch.entries.forEach {
                emit(it)
                last = it.position.sequence
            }
            if (!batch.again) {
                val pace = pacer.await(partition, mark, batch.entries.isNotEmpty(), idle, woken && !batch.behind)
                idle = pace.idle
                woken = pace.woken
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
        committedCounters.aggregate(pipeline).forEach { document ->
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
        return TailBatch(run, again, behind = run.size < expected)
    }

    /** One read of a tail: its [entries], whether to read again at once, whether the counter is ahead of them. */
    private class TailBatch(val entries: List<LogEntry>, val again: Boolean, val behind: Boolean)

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

    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo {
            // The counter is bumped in the transaction that inserts the entry, so it is the last committed sequence.
            committedCounters.find(Filters.eq(ID, partition)).firstOrNull()?.getLong(SEQUENCE)?.takeIf { it > 0 }
        }
    }

    private fun findByRequestIdIn(collection: AggregateCollection, tenantId: String, requestId: String) =
        collection.committed.find(Filters.and(Filters.eq(TENANT_ID, tenantId), Filters.eq(REQUEST_ID, requestId)))
            .projection(FACTS)
            .firstOrNull()
            ?.let { streamOf(collection.aggregate, it) }

    private fun currentVersion(collection: AggregateCollection, ref: AggregateRef): Int =
        collection.committed.find(refFilter(ref)).sort(Sorts.descending(VERSION)).limit(1).firstOrNull()
            ?.getInteger(VERSION) ?: 0

    private fun refFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(AGGREGATE_ID, ref.id),
        Filters.eq(TENANT_ID, ref.tenantId),
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
    )

    internal companion object {
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

        /** How long the driver's `withTransaction` retries a transaction; [transact] retries as long. */
        val MAX_RETRY: Duration = 2.minutes

        /** How long an append's transaction may take by default: below the default wait timeout of 10 seconds. */
        val DEFAULT_TRANSACTION_TIMEOUT: Duration = 5.seconds

        /** The streams of tail [readTail]'s first batch holds beyond the earlier request keys. */
        const val READ_TAIL_BATCH = 1000

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
        const val POSITION_KEY = "position_key"

        /** The version of this layout (decision D59). */
        const val FORMAT = 1

        /** A stream's facts: every field but the transport. */
        val FACTS: Bson = Projections.exclude(TRANSPORT)
    }
}
