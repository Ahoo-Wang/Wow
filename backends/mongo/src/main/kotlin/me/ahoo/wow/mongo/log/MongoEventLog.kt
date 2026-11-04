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
import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateManyModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoSharding
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.requireChains
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The [EventLog] of one bounded context on MongoDB, a first-class log backend
 * (documentation/designs/03-log-and-snapshot.md §2). It needs a replica set, for its majority reads and writes.
 *
 * The log takes one collection per aggregate type, `wow_log__<context>__<aggregate>`; a context-level
 * collection holds one counter document per partition, so partitions, their sequences, leases and checkpoints stay
 * per context. A counter also holds its partition's lineage, `parent` (null) and `epoch` (0), which
 * [lineage] reads back, and the partition's open blocks.
 *
 * **Appends**. An append is one `insertOne` acknowledged by a majority, at a sequence this node
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
 * request lookups included, reads what a majority of the replica set has committed (`readConcern: majority`).
 * With the default (`local`), a tail could deliver an entry the primary committed and lost in a failover
 * before it replicated, which is then rolled back: its subscriptions would have acted on a fact that never was. The
 * cost is latency: an entry reaches the tail once a majority has it, about one replication round trip after its
 * commit (appends already wait for it, `w: majority`). The backend so needs a replica set whose majority can commit:
 * an arbiter that makes a majority unreachable stalls the tail.
 *
 * **Request ids.** The request key is `(tenant_id, request_id)` within each aggregate collection: a request id reused
 * by two aggregate types commits twice (makes the reuse the caller's error). The kernel's pre-check, the
 * [findByRequestId] taking the aggregate type, reads that type's collection alone.
 *
 * A stream's facts are fields of its document; the document has no aggregate name, the collection is the aggregate
 * type. The transport is a field of its own, `transport`, binary data holding the bytes the runtime
 * encoded, stored and given back unchanged, which only [tail] and [readEntries] read:
 * [read] and [findByRequestId] leave it out of their projection. Each event's payload is a BSON document whose
 * decimals are `Decimal128`, exact ([ExactBson]); an append whose payload holds a number MongoDB cannot store exactly
 * is refused with an [IllegalArgumentException].
 *
 * **Polling**. A tail polls every `pollInterval` (50 ms by default) while it finds entries; each poll
 * that finds none doubles the wait, up to `maxPollInterval` (1 s). A commit this log makes wakes the partition's
 * tails at once, and a poll it woke that finds the partition caught up keeps the backoff, so a commit costs one read
 * of the tail; an entry another node appends is read within `maxPollInterval`. A change stream could wake the tails
 * of every node; it is not used, the backoff keeping an idle cluster cheap without a cursor per partition and node.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
@Suppress("LargeClass") // The log's port, on one collection layout.
class MongoEventLog(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    override val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val tailBatch: Int = DEFAULT_TAIL_BATCH,
    maxPollInterval: Duration = DEFAULT_MAX_POLL_INTERVAL,
    /** How the store's collections and indexes are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
    /**
     * How long an append's insert may take: a server that does not answer fails it, so a command
     * appending during an outage is answered before its wait ends, as unknown. Below the default wait timeout.
     */
    appendTimeout: Duration = DEFAULT_APPEND_TIMEOUT,
    /** Whether the log shards its aggregate collections on a sharded cluster. */
    sharding: MongoSharding = MongoSharding.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : EventLog,
    AccessWalks {
    /** Validates the partition count first: a log of another count is refused when it is built. */
    private val pacer = TailPacer(Partitioner.requirePartitions(partitions), pollInterval, maxPollInterval)

    private val database = MongoDatabases.of(client, databaseName, operationTimeout)

    /**
     * Whether this log shards its collections: asked of the server once, as the log is built when its port opens,
     * so a server that cannot answer fails the start, and no first write waits on the round trip under a lock.
     */
    private val sharded: Boolean = sharding.shards(client, ddl)
    private val countersName = MongoNames.of(PREFIX, context, PARTITION_COUNTERS)
    private val counters: MongoCollection<Document> = database.getCollection(countersName)

    /** The counters as a majority has committed them: what the tail and [head] read. */
    private val committedCounters: MongoCollection<Document> = counters.withReadConcern(ReadConcern.MAJORITY)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)

    /** The registry as a majority has committed it, which [head] reads with the collections' heads. */
    private val committedRegistry: MongoCollection<Document> = registry.withReadConcern(ReadConcern.MAJORITY)

    /** The registry the access walks are claimed in and read from: majority writes and reads, as the counters'. */
    private val walks: MongoCollection<Document> = committedRegistry.withWriteConcern(WriteConcern.MAJORITY)
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

    /** The context's aggregate collections and their registry. */
    private val collections =
        MongoLogCollections(client, database, registry, context, partitions, countersName, ddl, sharded, appendMillis)

    /** The documents of the streams and placeholders. */
    private val documents = MongoLogDocuments(context)

    /** Where an aggregate's streams are found. */
    private val lookups = MongoLogLookups(partitions, documents)

    /** The appends, alone or in groups, and the placeholders of the sequences no stream took. */
    private val appender = MongoLogAppender(
        context,
        partitions,
        sequencer,
        collections,
        documents,
        lookups,
        lastSequences
    )

    /**
     * Creates the partition counters when missing; refuses a different partition count, and a log recorded in another
     * format version ([MongoFormat]). The aggregate collections are created on first use, or by [createCollection].
     */
    fun createSchema() {
        MongoFormat.claim(database, countersName, FORMAT, ddl)
        MongoFormat.claim(database, REGISTRY, FORMAT, ddl)
        // Every step is idempotent, so nodes creating a new log at once all succeed. The partition count
        // is recorded before any counter, so a node that finds counters also finds the count the log was created with
        // and refuses another one before it adds a counter.
        val existingCounters = counters.countDocuments()
        val marker = "$countersName$PARTITIONS_MARKER"
        upsertOnce(registry, marker, PARTITIONS to if (existingCounters > 0) existingCounters.toInt() else partitions)
        val recorded = registry.find(Filters.eq(ID, marker)).first()!!.getInteger(PARTITIONS)
        check(recorded == partitions) {
            "The log of $context has $recorded partitions, not $partitions: changing it is a repartition."
        }
        // Each counter carries its partition's lineage: no parent, epoch 0, reserved for the splits of 10.x.
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

    /**
     * Creates the indexes of [aggregateName]'s log collection and registers it when missing, once per log; refuses a
     * collection recorded in another format version ([MongoFormat]), and an aggregate whose collection name another
     * aggregate has.
     */
    fun createCollection(aggregateName: String) {
        collections.of(aggregateName)
    }

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        dispatcher.mongo { appender.appendNow(stream, transport) }.also { outcome ->
            if (outcome is AppendOutcome.Committed) pacer.committed(outcome.position.partition)
        }

    /** A group may hold the streams of several partitions: each takes its own partition's sequence. */
    override val appendsAcrossPartitions: Boolean get() = true

    /**
     * Appends [streams], of one partition or of several, with one ordered `insertMany` per aggregate collection (group
     * commit): each stream takes its own partition's sequence, and gets the outcome [append]
     * would give it. A stream the store refuses alone fails alone, with its aggregate's later streams; a sequence no
     * stream took gets its placeholder; a stream whose position another node sealed is appended again alone. A group
     * whose write is unknown is unknown for each stream it held.
     *
     * Per collection, a group the store refuses nothing of is one insert. With a refusal, the streams of the other
     * aggregates that insert did not reach take at most two more (each aggregate's next stream unordered, then its
     * later ones ordered), so at most three inserts of streams, and each refusal in a later ordered insert two more;
     * the placeholders then take one write more.
     */
    override suspend fun appendAll(streams: List<Pair<EventStreamRecord, ByteArray?>>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { (stream, transport) -> append(stream, transport) }
        }
        require(streams.all { (stream) -> stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        return dispatcher.mongo { appender.appendGroup(streams) }.also { outcomes ->
            outcomes.filterIsInstance<AppendOutcome.Committed>().map { it.position.partition }.distinct()
                .forEach { pacer.committed(it) }
        }
    }

    /** Every partition of the log: a lookup of the request key without the aggregate seeks it in each. */
    private val everyPartition: Bson = Filters.`in`(PARTITION, (0 until partitions).toList())

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        val streams = dispatcher.mongo {
            val collection = collections.of(ref.type)
            collection.committed.find(
                Filters.and(lookups.refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .projection(FACTS)
                .sort(Sorts.ascending(VERSION))
                .map { documents.streamOf(collection.aggregate, it) }
                .toList()
        }
        streams.forEach { emit(it) }
    }

    /**
     * One update of the range by the version key, on the streams not stamped as of a later version. A stream not
     * stamped has no `access_version`, which `$lte` does not match, so the guard names its absence too; the count is of
     * the streams it matched, as a stamp that writes the same attributes again still counts them.
     */
    @InternalWowApi
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
        if (from >= until) return 0
        return dispatcher.mongo {
            collections.of(ref.type).log.updateMany(
                stampFilter(ref, asOf, from, until),
                Updates.combine(Updates.set(ACCESS, Document(access)), Updates.set(ACCESS_VERSION, asOf)),
            ).matchedCount.toInt()
        }
    }

    /**
     * One unordered `bulkWrite` of the stamps' guarded `updateMany`s per aggregate type, each as [stamp] writes one:
     * each guard holds on the document it writes, so stamps that overlap leave the later version's attributes, in
     * whatever order the server applies them.
     */
    @InternalWowApi
    override suspend fun stampAll(stamps: List<AccessStamp>): Int {
        val ranges = stamps.filter { it.from < it.until }
        if (ranges.isEmpty()) return 0
        return dispatcher.mongo {
            ranges.groupBy { it.ref.type }.entries.sumOf { (type, group) ->
                val updates = group.map {
                    UpdateManyModel<Document>(
                        stampFilter(it.ref, it.asOf, it.from, it.until),
                        Updates.combine(Updates.set(ACCESS, Document(it.access)), Updates.set(ACCESS_VERSION, it.asOf)),
                    )
                }
                collections.of(type).log.bulkWrite(updates, BulkWriteOptions().ordered(false)).matchedCount
            }
        }
    }

    /** The streams of [ref] from [from] until [until] not stamped as of a later version than [asOf]. */
    private fun stampFilter(ref: AggregateRef, asOf: Int, from: Int, until: Int): Bson = Filters.and(
        lookups.refFilter(ref),
        Filters.gte(VERSION, from),
        Filters.lt(VERSION, until),
        Filters.or(Filters.exists(ACCESS_VERSION, false), Filters.lte(ACCESS_VERSION, asOf)),
    )

    /**
     * One `find` of the refs' version keys, an `$or` of them on the majority's view, in version order; none of a type
     * whose collection does not exist.
     */
    override suspend fun histories(refs: List<AggregateRef>): Map<AggregateRef, List<EventStreamRecord>> {
        val type = refs.firstOrNull()?.type ?: return emptyMap()
        require(refs.all { it.type == type }) { "The histories of one read are of one aggregate type." }
        return dispatcher.mongo {
            val collection = collections.existing(type) ?: return@mongo emptyMap()
            collection.committed.find(Filters.or(refs.map(lookups::refFilter)))
                .projection(FACTS)
                .sort(Sorts.ascending(VERSION))
                .map { documents.streamOf(collection.aggregate, it) }
                .toList()
                .groupBy { it.ref }
        }
    }

    /**
     * Partition by partition, each by the version key (its aggregates' first streams); the cursor is the partition,
     * then [AggregatePage.cursorOf] within it.
     */
    override suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage {
        require(limit > 0) { "A page holds at least one aggregate." }
        return dispatcher.mongo {
            val log = collections.existing(type)?.log ?: return@mongo AggregatePage(emptyList(), null)
            var partition = after?.substringBefore(CURSOR_PARTITION)?.toInt() ?: 0
            var key = after?.substringAfter(CURSOR_PARTITION)?.let(AggregatePage::keyOf)
            val refs = ArrayList<AggregateRef>(limit)
            while (partition < partitions) {
                val filters = mutableListOf(Filters.eq(PARTITION, partition), Filters.eq(VERSION, 1))
                key?.let { (id, tenant) ->
                    filters += Filters.or(
                        Filters.gt(AGGREGATE_ID, id),
                        Filters.and(Filters.eq(AGGREGATE_ID, id), Filters.gt(TENANT_ID, tenant)),
                    )
                }
                log.find(Filters.and(filters))
                    .sort(Sorts.ascending(AGGREGATE_ID, TENANT_ID))
                    .projection(Projections.include(AGGREGATE_ID, TENANT_ID))
                    .limit(limit - refs.size)
                    .forEach { refs += type.ref(it.getString(TENANT_ID), it.getString(AGGREGATE_ID)) }
                if (refs.size == limit) {
                    break
                }
                partition++
                key = null
            }
            val last = refs.takeIf { it.size == limit }?.last()
            AggregatePage(refs, last?.let { "$partition$CURSOR_PARTITION${AggregatePage.cursorOf(it)}" })
        }
    }

    /** Whether [type]'s collection exists: this log made sure of it, or the registry lists it. */
    override suspend fun holds(type: AggregateType): Boolean = dispatcher.mongo { collections.existing(type) != null }

    /**
     * The registry document of [type]'s collection, its `access_walk` ([walkOf]); majority read. None before the
     * collection's first append: the read creates no collection.
     */
    override suspend fun walk(type: AggregateType): AccessWalk? = dispatcher.mongo {
        walks.find(collections.registryOf(type)).first()
            ?.get(ACCESS_WALK, Document::class.java)
            ?.let(::walkOf)
    }

    /**
     * One conditional update of the registry document, majority written: concurrent claims serialize on it. A type
     * whose collection does not exist has no registry document, and the update matches none.
     */
    override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean = dispatcher.mongo {
        val recorded = if (expected == null) Filters.eq(ACCESS_WALK, null) else Filters.eq(WALK_SCHEMA, expected)
        walks.updateOne(
            Filters.and(collections.registryOf(type), recorded),
            Updates.set(ACCESS_WALK, documentOf(walk)),
        ).matchedCount == 1L
    }

    override suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = dispatcher.mongo {
        walks.updateOne(
            Filters.and(collections.registryOf(type), Filters.eq(WALK_SCHEMA, schema)),
            Updates.combine(
                Updates.set("$ACCESS_WALK.$CURSOR", cursor),
                Updates.set("$ACCESS_WALK.$WALKED", walked),
                Updates.set("$ACCESS_WALK.$COMPLETED", completed),
            ),
        ).matchedCount == 1L
    }

    /**
     * One aggregation: the tail by the version key, then, through `$unionWith` on the same
     * collection, the request keys of the latest streams before it, newest first. The first batch holds them all
     * ([READ_TAIL_BATCH] streams of tail beyond them), so a typical read is one command. Its stages are BSON
     * already ([tailStages]): the driver renders builders on every call, and the last stage once more.
     */
    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
        require(fromVersion >= 1) { "A stream has a version from 1." }
        require(earlier >= 0) { "At most a non-negative number of earlier requests." }
        return dispatcher.mongo {
            val collection = collections.of(ref.type)
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
                    streams += documents.streamOf(collection.aggregate, document)
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
            val collection = collections.of(ref.type)
            collection.committed.find(
                Filters.and(lookups.refFilter(ref), Filters.gte(VERSION, fromVersion), Filters.lte(VERSION, toVersion)),
            )
                .sort(Sorts.ascending(VERSION))
                .map { documents.entryOf(collection.aggregate, it) }
                .toList()
        }
        entries.forEach { emit(it) }
    }

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = dispatcher.mongo {
        collections.of(ref.type).committed.find(Filters.and(lookups.refFilter(ref), Filters.eq(VERSION, version)))
            .projection(Projections.include(PARTITION, SEQUENCE))
            .firstOrNull()
            ?.let { LogPosition(it.getInteger(PARTITION), it.getLong(SEQUENCE)) }
    }

    /**
     * The stream committed for [requestId] in [ref]'s partition of its collection: one indexed find. The request key
     * is unique within a partition, which is what an append of [ref] meets.
     */
    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        dispatcher.mongo {
            lookups.requestIn(collections.of(ref.type), ref, requestId)
        }

    /**
     * The stream committed for [requestId] within [tenantId] in [type]'s collection, in any partition: one find, an
     * index seek per partition.
     */
    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        dispatcher.mongo { lookups.findByRequestIdIn(collections.of(type), everyPartition, tenantId, requestId) }

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
        val known = collections.known()
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
                else -> entries[sequence] = documents.entryOf(known[branch].aggregate, document)
            }
        }
        val (run, next) = finalRun(entries, gaps, last, tailBatch)
        val missing = next.takeIf { entries.keys.any { it > next } || gaps.keys.any { it > next } }
        val names = known.map { it.name }.toSet()
        val unknown = sequencer.openBlocks(counter).any { block -> block.collections.any { it !in names } }
        val again = when {
            run.size == tailBatch -> true
            unknown || missing != null -> collections.register().map { it.name }.toSet() != names
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
            is MongoSequencer.Resolution.Close -> appender.close(resolution.closing)
            is MongoSequencer.Resolution.Fill ->
                appender.fill(collections.named(resolution.collection), partition, sequence, GAP_UNKNOWN)

            is MongoSequencer.Resolution.Seal -> seal(partition, sequence, resolution.block)
        }
    }

    /**
     * Seals [block], another node's, from [from]: a placeholder at each of its sequences left in each collection it may
     * write, so a write of that node still to come meets a taken position; then takes it out of the counter's list.
     */
    private fun seal(partition: Int, from: Long, block: MongoSequencer.OpenBlock) {
        val placeholders = (from..block.to).map { appender.placeholder(partition, it, GAP_SEALED) }
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

    /**
     * The lineage of [partition] from its counter document: its `parent` and `epoch`, read as
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
     * is the blocks reserved, not the streams written. An append in flight may still land below it:
     * one concurrent with this read, which a subscription starting from the head may count as committed before it.
     *
     * The aggregation also reads the context's registry: a collection another node registered since this log last
     * read it (a new aggregate type's first commit, written by the partition's previous owner) makes it read the
     * registry again and look once more, so the head never misses the streams of a collection it has not seen yet.
     */
    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo { headOf(partition) }
    }

    private tailrec fun headOf(partition: Int): Long? {
        val known = collections.known()
        val names = known.mapTo(HashSet()) { it.name }
        var head: Long? = null
        var unknown = false
        for (document in committedRegistry.aggregate(headPipeline(partition, known))) {
            val sequence = document.getLong(SEQUENCE)
            when {
                sequence != null -> head = maxOf(head ?: sequence, sequence)
                document.getString(ID) !in names -> unknown = true
            }
        }
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
     * The context's registry entries, then each of [known]'s highest stream in [partition], in one aggregation over
     * the registry: an entry this log does not know is a collection registered since it last read the registry.
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
        return listOf(
            Aggregates.match(Filters.eq(CONTEXT, context)),
            Aggregates.project(Projections.include(ID)),
        ) + known.map { Aggregates.unionWith(it.name, highest) }
    }

    /** [readTail]'s stages, as BSON: the dynamic matches and limit built here, the rest rendered once. */
    private fun tailStages(ref: AggregateRef, fromVersion: Int, earlier: Int, collection: String): List<Bson> {
        fun versions(operator: String) = BsonDocument(
            "\$match",
            BsonDocument(PARTITION, BsonInt32(lookups.partitionOf(ref)))
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

    internal companion object {
        private val logger = KotlinLogging.logger {}

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

        /** The lineage fields of a partition's counter document. */
        const val PARENT = "parent"
        const val EPOCH = "epoch"
        const val AGGREGATE = "aggregate"
        const val BRANCH = "branch"
        const val HEAD = "head"

        /** How long an append's insert may take by default: below the default wait timeout of 10 seconds. */
        val DEFAULT_APPEND_TIMEOUT: Duration = 5.seconds

        /** The sequences one reservation of a partition's counter gives a node. */
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
        const val ACCESS = "access"
        const val ACCESS_VERSION = "access_version"

        /** A registry document's access walk: `{schema, heads: {partition: head}, cursor, walked, completed}`. */
        const val ACCESS_WALK = "access_walk"
        const val SCHEMA = "schema"
        const val HEADS = "heads"
        const val CURSOR = "cursor"
        const val WALKED = "walked"
        const val COMPLETED = "completed"
        private const val WALK_SCHEMA = "$ACCESS_WALK.$SCHEMA"

        /** Ends the partition of an [aggregates] cursor. */
        private const val CURSOR_PARTITION = "/"

        fun documentOf(walk: AccessWalk): Document = Document(SCHEMA, walk.schema)
            .append(HEADS, Document(walk.heads.toSortedMap().mapKeys { (partition, _) -> partition.toString() }))
            .append(CURSOR, walk.cursor)
            .append(WALKED, walk.walked)
            .append(COMPLETED, walk.completed)

        fun walkOf(document: Document): AccessWalk = AccessWalk(
            schema = document.getString(SCHEMA),
            heads = document.get(HEADS, Document::class.java).entries
                .associate { (partition, head) -> partition.toInt() to (head as Number).toLong() },
            cursor = document.getString(CURSOR),
            walked = (document[WALKED] as Number?)?.toLong() ?: 0,
            completed = document.getBoolean(COMPLETED, false),
        )
        const val TRANSPORT = "transport"
        const val CONTEXT = "context"
        const val EVENTS = "events"
        const val NAME = "name"
        const val REVISION = "revision"
        const val PAYLOAD = "payload"
        const val VERSION_KEY = "version_key"
        const val REQUEST_KEY = "request_key"

        /** The index of `_id`, an entry's position: the one a duplicate position names. */
        const val POSITION_INDEX = "index: _id_ "

        /** The bits of a position's sequence in its `_id`, below its partition's (12 bits, at most 4,096 partitions). */
        const val SEQUENCE_BITS: Int = 51

        /**
         * An entry's `_id`: its position, `partition << 51 | sequence`, one long, so that the
         * position needs no index of its own and a partition's entries are one range of `_id`, in sequence order.
         */
        fun positionId(partition: Int, sequence: Long): Long = (partition.toLong() shl SEQUENCE_BITS) or sequence

        /** The version of this layout. */
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
