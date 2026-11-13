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

import com.mongodb.MongoWriteException
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.PartitionLineage
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.requireChains
import me.ahoo.wow.spi.store.HeldStoreCallMarks
import me.ahoo.wow.spi.store.StoreCallMarks
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import kotlin.time.Duration
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
 * **Polling**. A tail polls every [MongoEventLogOptions.pollInterval] (50 ms by default) while it finds entries;
 * each poll that finds none doubles the wait, up to [MongoEventLogOptions.maxPollInterval] (1 s). A commit this log
 * makes wakes the partition's tails at once, and a poll it woke that finds the partition caught up keeps the backoff,
 * so a commit costs one read of the tail; an entry another node appends is read within the longest interval. A change
 * stream could wake the tails of every node; it is not used, the backoff keeping an idle cluster cheap without a
 * cursor per partition and node.
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
    /** Its settings: how its tails poll, how long its calls may take, how it manages and shards its collections. */
    options: MongoEventLogOptions = MongoEventLogOptions(),
) : EventLog,
    AccessWalks {
    /** Validates the partition count first: a log of another count is refused when it is built. */
    private val pacer =
        TailPacer(Partitioner.requirePartitions(partitions), options.pollInterval, options.maxPollInterval)

    private val ddl = options.ddl

    private val database = MongoDatabases.of(client, databaseName, options.operationTimeout)

    /**
     * Whether this log shards its collections: asked of the server once, as the log is built when its port opens,
     * so a server that cannot answer fails the start, and no first write waits on the round trip under a lock.
     */
    private val sharded: Boolean = options.sharding.shards(client, ddl)
    private val countersName = MongoNames.of(PREFIX, context, PARTITION_COUNTERS)
    private val counters: MongoCollection<Document> = database.getCollection(countersName)

    /** The counters as a majority has committed them: what the tail and [head] read. */
    private val committedCounters: MongoCollection<Document> = counters.withReadConcern(ReadConcern.MAJORITY)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)

    /** The registry as a majority has committed it, which [head] reads with the collections' heads. */
    private val committedRegistry: MongoCollection<Document> = registry.withReadConcern(ReadConcern.MAJORITY)

    /** The registry the access walks are claimed in and read from: majority writes and reads, as the counters'. */
    private val walks: MongoCollection<Document> = committedRegistry.withWriteConcern(WriteConcern.MAJORITY)
    private val appendMillis = options.appendTimeout.inWholeMilliseconds

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

    /** The reads of the partitions' tails. */
    private val tailReads = MongoTailReads(
        committedCounters,
        collections,
        sequencer,
        documents,
        options.tailBatch,
        committedRegistry,
        context,
        DoneBlocks(options.doneGrace),
    )

    /** Where an aggregate's streams are found. */
    private val lookups = MongoLogLookups(partitions, documents)

    /** The reads of one aggregate's streams. */
    private val aggregateReads =
        MongoAggregateReads(dispatcher, collections, documents, lookups, lastSequences, partitions)

    /** The access attributes of the streams, and the walks of the aggregate types. */
    private val logAccess = MongoLogAccess(dispatcher, collections, lookups, walks, partitions)

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
     *
     * A group the append queue clocks ([StoreCallMarks] in its context) is marked from its entry to its work done
     * ([MongoLogAppender.appendGroup]); the store's connection is the driver's, checked out within each command, so
     * the call has none of its own: it is marked acquired once on its thread. A call of one stream is not marked.
     */
    override suspend fun appendAll(streams: List<Append>): List<AppendOutcome> {
        if (streams.size <= 1) {
            return streams.map { append(it.stream, it.transport) }
        }
        require(streams.all { it.stream.ref.type.context == context }) {
            "A group commit appends streams of $context."
        }
        requireChains(streams)
        val marks = currentCoroutineContext()[StoreCallMarks]?.apply { entered() }
        return dispatcher.mongo {
            marks?.onThread()
            marks?.acquired()
            HeldStoreCallMarks.hold(marks)
            try {
                appender.appendGroup(streams, marks).also { marks?.released() }
            } finally {
                HeldStoreCallMarks.release(marks)
            }
        }.also { outcomes ->
            outcomes.filterIsInstance<AppendOutcome.Committed>().map { it.position.partition }.distinct()
                .forEach { pacer.committed(it) }
        }
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        aggregateReads.read(ref, fromVersion, toVersion)

    @InternalWowApi
    override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int =
        logAccess.stamp(ref, access, asOf, from, until)

    @InternalWowApi
    override suspend fun stampAll(stamps: List<AccessStamp>): Int = logAccess.stampAll(stamps)

    override suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage =
        logAccess.aggregates(type, after, limit)

    override suspend fun holds(type: AggregateType): Boolean = logAccess.holds(type)

    override suspend fun walk(type: AggregateType): AccessWalk? = logAccess.walk(type)

    override suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean =
        logAccess.claim(type, walk, expected)

    override suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = logAccess.advance(type, schema, cursor, walked, completed)

    override fun serve(schemas: Map<AggregateType, String>) {
        collections.served = schemas.filterKeys { it.context == context }.mapKeys { (type, _) -> type.name }
    }

    override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail =
        aggregateReads.readTail(ref, fromVersion, earlier)

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
        aggregateReads.readEntries(ref, fromVersion, toVersion)

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? =
        aggregateReads.positionOf(ref, version)

    override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? =
        aggregateReads.findByRequestId(ref, requestId)

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        aggregateReads.findByRequestId(type, tenantId, requestId)

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        var last = after ?: 0L
        var idle = pacer.interval
        var woken = false
        var stalled = 0L
        var since = 0L
        while (true) {
            val mark = pacer.mark(partition)
            val batch = dispatcher.mongo { tailReads.read(partition, last) }
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
     * Resolves [sequence] of [partition], missing past the grace while something lies past it, through the counter's
     * open blocks ([MongoSequencer.resolve]).
     */
    private fun resolve(partition: Int, sequence: Long) {
        val counter = committedCounters.find(Filters.eq(ID, partition)).first()
        when (val resolution = sequencer.resolve(partition, sequence, sequencer.openBlocks(counter))) {
            MongoSequencer.Resolution.Wait -> Unit
            is MongoSequencer.Resolution.Close -> appender.close(resolution.closing)
            is MongoSequencer.Resolution.Fill ->
                appender.fill(collections.named(resolution.collection), partition, sequence, GAP_UNKNOWN)

            is MongoSequencer.Resolution.Seal -> appender.seal(partition, sequence, resolution.block)
        }
    }

    /**
     * The lineage of [partition] from its counter document: its `parent` (null for a partition the log was created
     * with) and `epoch`.
     */
    override suspend fun lineage(partition: Int): PartitionLineage {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo {
            val counter = checkNotNull(committedCounters.find(Filters.eq(ID, partition)).first()) {
                "The log of $context has no counter of partition $partition."
            }
            PartitionLineage.of(counter.getInteger(PARENT), counter.getInteger(EPOCH))
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
     * It starts from the partition's counter: a partition without one is refused, as [lineage] refuses it.
     */
    override suspend fun head(partition: Int): Long? {
        require(partition in 0 until partitions) { "No partition $partition among $partitions." }
        return dispatcher.mongo { tailReads.headOf(partition) }
    }

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

        /** The tenant-scoped keys of a stream's built-in access attributes ([me.ahoo.wow.mongo.MongoAccess.keys]). */
        const val ACCESS_KEY = "access_key"

        /** A registry document's access walk: `{schema, heads: {partition: head}, cursor, walked, completed}`. */
        const val ACCESS_WALK = "access_walk"
        const val SCHEMA = "schema"
        const val HEADS = "heads"
        const val CURSOR = "cursor"
        const val WALKED = "walked"
        const val COMPLETED = "completed"
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
    }
}
