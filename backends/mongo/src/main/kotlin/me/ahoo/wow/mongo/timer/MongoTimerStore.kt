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

package me.ahoo.wow.mongo.timer

import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.mongo.EnsuredCollections
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.MongoRules
import me.ahoo.wow.mongo.MongoSharding
import me.ahoo.wow.mongo.MongoShards
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration

/**
 * The [TimerStore] of one bounded context on MongoDB: a collection per aggregate
 * type, `wow_timer__<context>__<aggregate>`, named by the rule of the log's collections, created on the type's first
 * use and recorded in the registry `wow_timer_aggregate`. One document per timer, keyed by a unique index on its
 * partition, its owner's tenant and id (`owner: {tenant_id, id}`, its context and aggregate the collection's)
 * and `key`.
 *
 * A timer is in its owner's partition, among the [partitions] of the context's log ([Timer.partition]). On a sharded
 * cluster each collection is sharded by `{partition: 1}`, one chunk per partition, spread over the shards while it is
 * new (as the log is): every filter by owner carries the owner's partition, so the reads and
 * writes by owner and the due read of a node's partitions reach the shards of those partitions alone, and the key
 * stays unique across the shards. A resume's read of a target's parked timers ([parked]) asks every shard.
 *
 * Every write is one single-document operation, but for [cancelAll] and [settle]. A put is a conditional upsert (see
 * [conditionalUpsert]) whose filter admits only another timer at the same or a later version; a cancel is a
 * conditional update; a settle is one unordered bulk write of conditional updates per collection, each on the document
 * as it was loaded. The due read is one aggregation over the registry: the count of the context's collections, then,
 * through `$unionWith`, each collection's first due timers over its partial index on `(partition, due_at, timer_id)`
 * of the PENDING and FIRING (claimed) timers, merged by due time. A count other than the collections read tells the
 * store to read the registry again, so a collection another node created is read at once. The body is kept as the
 * command's JSON text, the rule as a subdocument (`{type, ...}`, as Wow's JSON writes it). Format 5 is the layout per
 * aggregate type; the timers are a view rebuilt from the log, so a collection of an earlier format is refused, dropped
 * and rebuilt.
 *
 * Calls run on [dispatcher], virtual threads by default.
 */
@InternalWowApi
class MongoTimerStore(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    /** The partitions of the context's log: a timer is in its owner's. */
    private val partitions: Int,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's collections and indexes are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
    /** Whether the store shards its collections on a sharded cluster. */
    private val sharding: MongoSharding = MongoSharding.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : TimerStore {
    init {
        Partitioner.requirePartitions(partitions)
    }

    private val database = MongoDatabases.of(client, databaseName, operationTimeout)
    private val registry: MongoCollection<Document> = database.getCollection(REGISTRY)
    private val sharded by lazy { sharding.shards(client, ddl) }

    /** The timer collections whose creation and registration this store has made sure of, each once. */
    private val ensured = EnsuredCollections<TimerCollection>()

    /** The timer collections of the context, as of the last read of the registry. */
    @Volatile
    private var registered: List<TimerCollection>? = null

    /** The name of [aggregate]'s timer collection, `wow_timer__<context>__<aggregate>`. */
    fun collectionName(aggregate: String): String = MongoNames.of(PREFIX, context, aggregate)

    /**
     * Claims the registry's format; refuses one recorded in another format version ([MongoFormat]).
     * Each aggregate type's collection is created on its first use.
     */
    fun createSchema() {
        MongoFormat.claim(database, REGISTRY, FORMAT, ddl)
        registered = null
    }

    /** The collection of [type]'s timers, created and registered when this store has not yet made sure it is. */
    private fun collectionOf(type: AggregateType): TimerCollection {
        require(type.context == context) { "$type keeps its timers in its own context, not $context." }
        return ensured.get(type.name) { ensure(type.name) }
    }

    /**
     * Claims the format of [aggregate]'s collection, creates its indexes, shards it on a sharded cluster and records
     * it in the registry. Each step is idempotent, so nodes creating one collection at once agree; two aggregates that
     * take one name are refused.
     */
    private fun ensure(aggregate: String): TimerCollection {
        val collection = TimerCollection(aggregate, database.getCollection(collectionName(aggregate)))
        val timers = collection.timers
        MongoFormat.claim(database, collection.name, FORMAT, ddl)
        // The key leads with the partition, so it stays unique on a collection sharded by it.
        MongoFormat.index(
            timers,
            Indexes.ascending(PARTITION, OWNER_TENANT_ID, OWNER_AGGREGATE_ID, KEY),
            IndexOptions().unique(true).name(TIMER_KEY),
            ddl,
        )
        MongoFormat.index(
            timers,
            Indexes.ascending(PARTITION, DUE_AT, TIMER_ID),
            IndexOptions().name(DUE_INDEX).partialFilterExpression(Filters.`in`(STATUS, DUE_STATUSES)),
            ddl,
        )
        MongoFormat.index(
            timers,
            Indexes.ascending(TARGET_CONTEXT, TARGET_AGGREGATE, TARGET_TENANT_ID, TARGET_ID),
            IndexOptions().name(PARKED_INDEX).partialFilterExpression(Filters.eq(STATUS, TimerStatus.PARKED.name)),
            ddl,
        )
        if (sharded) {
            MongoShards.shardTimers(client, database, timers, partitions)
        }
        if (ddl != Ddl.AUTO) {
            // The registry is the schema's: a validated or foreign schema registered its collections already.
            return collection
        }
        try {
            registry.insertOne(
                Document(REGISTRY_ID, collection.name).append(CONTEXT, context).append(AGGREGATE, aggregate),
            )
        } catch (failure: MongoWriteException) {
            if (!failure.isDuplicateKey()) {
                throw failure
            }
        }
        val holder = registry.find(Filters.eq(REGISTRY_ID, collection.name)).first()!!
        check(holder.getString(CONTEXT) == context && holder.getString(AGGREGATE) == aggregate) {
            "The aggregate $aggregate of $context takes the timer collection ${collection.name}, " +
                "which the aggregate ${holder.getString(AGGREGATE)} of ${holder.getString(CONTEXT)} has."
        }
        return collection
    }

    /** Reads the registry again: the timer collections of the context. */
    private fun register(): List<TimerCollection> =
        registry.find(Filters.eq(CONTEXT, context)).sort(Sorts.ascending(AGGREGATE))
            .map { TimerCollection(it.getString(AGGREGATE), database.getCollection(it.getString(REGISTRY_ID))) }
            .toList()
            .also { registered = it }

    /** The collections this store knows: the registry as last read, and the ones it made sure of since. */
    private fun known(): List<TimerCollection> = ((registered ?: register()) + ensured.values).distinctBy { it.name }

    override suspend fun put(timer: Timer): Boolean = dispatcher.mongo {
        require(timer.partition == partitionOf(timer.owner)) {
            "The timer ${timer.id} is in partition ${timer.partition}, not its owner's ${partitionOf(timer.owner)} " +
                "of $partitions."
        }
        val timers = collectionOf(timer.owner.type).timers
        val replaceable = Filters.and(
            keyFilter(timer.owner, timer.key),
            Filters.ne(TIMER_ID, timer.id),
            Filters.lte(VERSION, timer.version),
        )
        val write = Updates.combine(
            Updates.set(TIMER_ID, timer.id),
            Updates.set(VERSION, timer.version),
            Updates.set(RULE, MongoRules.document(timer.rule)),
            Updates.set(SLOT, timer.slot),
            Updates.set(DUE_AT, timer.dueAt),
            Updates.set(SLOT_DUE_AT, timer.slotDueAt),
            Updates.set(SKIPPED, timer.skipped),
            Updates.set(TARGET_CONTEXT, timer.target.type.context),
            Updates.set(TARGET_AGGREGATE, timer.target.type.name),
            Updates.set(TARGET_TENANT_ID, timer.target.tenantId),
            Updates.set(TARGET_ID, timer.target.id),
            Updates.set(COMMAND_TYPE, timer.commandType),
            Updates.set(BODY, timer.body),
            Updates.set(CORRELATION_ID, timer.causation?.correlationId),
            Updates.set(CAUSATION_ID, timer.causation?.causationId),
            Updates.set(HEADERS, Document(timer.headers)),
            Updates.set(STATUS, TimerStatus.PENDING.name),
            Updates.set(ATTEMPTS, 0),
            Updates.set(OUTCOME, null),
            Updates.set(OPERATOR, MongoOperators.document(timer.operator)),
            Updates.set(OWNER_ID, timer.scope.ownerId),
            Updates.set(SPACE_ID, timer.scope.spaceId),
        )
        conditionalUpsert { upsert ->
            val result = timers.updateOne(replaceable, write, UpdateOptions().upsert(upsert))
            result.upsertedId != null || result.matchedCount == 1L
        }
    }

    override suspend fun cancel(owner: AggregateRef, key: String, version: Long): Boolean = dispatcher.mongo {
        collectionOf(owner.type).timers.updateOne(
            Filters.and(keyFilter(owner, key), cancellable(version)),
            cancelled(version),
        ).matchedCount == 1L
    }

    override suspend fun cancelAll(owner: AggregateRef, version: Long): Int = dispatcher.mongo {
        collectionOf(owner.type).timers
            .updateMany(Filters.and(ownerFilter(owner), cancellable(version)), cancelled(version))
            .matchedCount.toInt()
    }

    override suspend fun find(owner: AggregateRef, key: String): Timer? = dispatcher.mongo {
        collectionOf(owner.type).timers.find(keyFilter(owner, key)).firstOrNull()
            ?.let { timerOf(it, owner.type.name) }
    }

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        if (partitions.isEmpty()) {
            return emptyList()
        }
        return dispatcher.mongo { unioned(dueBranch(partitions, until), limit) }
    }

    /** Each collection's share of the due read: its due timers of [partitions] by [until], earliest first. */
    private fun dueBranch(partitions: Set<Int>, until: Long): List<Bson> = listOf(
        Aggregates.match(
            Filters.and(
                Filters.`in`(PARTITION, partitions),
                Filters.`in`(STATUS, DUE_STATUSES),
                Filters.lte(DUE_AT, until),
            ),
        ),
        Aggregates.sort(DUE_ORDER),
    )

    /**
     * The due read's aggregation over [known]: the registry's count of the context's collections, then each
     * collection's first timers by [branch] (and [limit]), each tagged with the collection's index in [known], merged
     * by due time. A test asserts its plan: each branch over the collection's partial index.
     */
    private fun pipeline(known: List<TimerCollection>, branch: List<Bson>, limit: Int?): List<Bson> = buildList {
        add(Aggregates.match(Filters.eq(CONTEXT, context)))
        add(Aggregates.count(REGISTERED))
        known.forEachIndexed { index, collection ->
            val read = branch + listOfNotNull(limit?.let(Aggregates::limit)) +
                Aggregates.set(Field(BRANCH, index))
            add(Aggregates.unionWith(collection.name, read))
        }
        // The count has no due time: it sorts first.
        add(Aggregates.sort(DUE_ORDER))
        limit?.let { add(Aggregates.limit(it + 1)) }
    }

    /** The due read's aggregation as this store runs it now, for [partitions] and [until]: a test explains it. */
    internal fun duePipeline(partitions: Set<Int>, until: Long, limit: Int): List<Bson> =
        pipeline(known(), dueBranch(partitions, until), limit)

    /** The registry, which the due read aggregates over: a test explains it. */
    internal val registryCollection: MongoCollection<Document> get() = registry

    /**
     * Runs [branch] over every timer collection of the context in one aggregation; when the registry holds other
     * collections than those read, reads the registry and runs it once more.
     */
    private fun unioned(branch: List<Bson>, limit: Int?): List<Timer> {
        var known = known()
        while (true) {
            var count = 0
            val timers = buildList {
                registry.aggregate(pipeline(known, branch, limit)).forEach { document ->
                    val branchIndex = document.getInteger(BRANCH)
                    if (branchIndex == null) {
                        count = document.getInteger(REGISTERED)
                    } else {
                        add(timerOf(document, known[branchIndex].aggregate))
                    }
                }
            }
            if (count == known.size) {
                return timers
            }
            val read = register()
            if (read.map { it.name }.toSet() == known.map { it.name }.toSet()) {
                return timers
            }
            known = read
        }
    }

    override suspend fun settle(changes: List<TimerChange>): Int {
        if (changes.isEmpty()) {
            return 0
        }
        return dispatcher.mongo {
            changes.groupBy { it.loaded.owner.type }.entries.sumOf { (type, ofType) ->
                val writes = ofType.map { change ->
                    val loaded = change.loaded
                    val next = change.next
                    UpdateOneModel<Document>(
                        Filters.and(
                            keyFilter(loaded.owner, loaded.key),
                            Filters.eq(TIMER_ID, loaded.id),
                            Filters.eq(STATUS, loaded.status.name),
                            Filters.eq(SLOT, loaded.slot),
                            Filters.eq(SLOT_DUE_AT, loaded.slotDueAt),
                            Filters.eq(ATTEMPTS, loaded.attempts),
                            Filters.eq(DUE_AT, loaded.dueAt),
                        ),
                        Updates.combine(
                            Updates.set(STATUS, next.status.name),
                            Updates.set(SLOT, next.slot),
                            Updates.set(SLOT_DUE_AT, next.slotDueAt),
                            Updates.set(SKIPPED, next.skipped),
                            Updates.set(DUE_AT, next.dueAt),
                            Updates.set(ATTEMPTS, next.attempts),
                            Updates.set(OUTCOME, next.outcome),
                        ),
                    )
                }
                collectionOf(type).timers.bulkWrite(writes, BulkWriteOptions().ordered(false)).matchedCount
            }
        }
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = dispatcher.mongo {
        val filter = Filters.and(
            Filters.eq(TARGET_CONTEXT, target.type.context),
            Filters.eq(TARGET_AGGREGATE, target.type.name),
            Filters.eq(TARGET_TENANT_ID, target.tenantId),
            Filters.eq(TARGET_ID, target.id),
            Filters.eq(STATUS, TimerStatus.PARKED.name),
        )
        unioned(listOf(Aggregates.match(filter)), null)
    }

    private fun partitionOf(owner: AggregateRef): Int = Partitioner.partitionOf(owner, partitions)

    /** The owner's timers: by its partition first, so a sharded collection reads the one shard holding them. */
    private fun ownerFilter(owner: AggregateRef): Bson = Filters.and(
        Filters.eq(PARTITION, partitionOf(owner)),
        Filters.eq(OWNER_TENANT_ID, owner.tenantId),
        Filters.eq(OWNER_AGGREGATE_ID, owner.id),
    )

    private fun keyFilter(owner: AggregateRef, key: String): Bson = Filters.and(
        ownerFilter(owner),
        Filters.eq(KEY, key)
    )

    private fun cancellable(version: Long): Bson = Filters.and(
        Filters.`in`(STATUS, TimerStatus.PENDING.name, TimerStatus.FIRING.name, TimerStatus.PARKED.name),
        Filters.lte(VERSION, version),
    )

    private fun cancelled(version: Long): Bson =
        Updates.combine(Updates.set(STATUS, TimerStatus.CANCELLED.name), Updates.max(VERSION, version))

    private fun timerOf(document: Document, aggregate: String) = Timer(
        id = document.getString(TIMER_ID),
        owner = document.get(OWNER, Document::class.java).let { owner ->
            AggregateType(context, aggregate).ref(owner.getString(TENANT_ID), owner.getString(ID))
        },
        key = document.getString(KEY),
        version = document.getLong(VERSION),
        partition = document.getInteger(PARTITION),
        rule = MongoRules.of(document.get(RULE, Document::class.java)),
        slot = document.getLong(SLOT),
        dueAt = document.getLong(DUE_AT),
        target = AggregateType(document.getString(TARGET_CONTEXT), document.getString(TARGET_AGGREGATE))
            .ref(document.getString(TARGET_TENANT_ID), document.getString(TARGET_ID)),
        commandType = document.getString(COMMAND_TYPE),
        body = document.getString(BODY),
        causation = document.getString(CORRELATION_ID)?.let { Causation(it, document.getString(CAUSATION_ID)) },
        headers = document.get(HEADERS, Document::class.java).let { headers ->
            headers.keys.associateWith { headers.getString(it) }
        },
        status = TimerStatus.valueOf(document.getString(STATUS)),
        attempts = document.getInteger(ATTEMPTS),
        outcome = document.getString(OUTCOME),
        operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
        scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
        slotDueAt = document.getLong(SLOT_DUE_AT),
        // Added within format 3: a document written before it skipped none.
        skipped = document.getLong(SKIPPED) ?: 0L,
    )

    /** The timer collection of one aggregate type. */
    internal class TimerCollection(val aggregate: String, val timers: MongoCollection<Document>) {
        val name: String get() = timers.namespace.collectionName
    }

    private companion object {
        /** The version of this layout. */
        const val FORMAT = 5
        const val PREFIX = "wow_timer"

        /** The registry of timer collections, shared by every context. */
        const val REGISTRY = "wow_timer_aggregate"
        const val REGISTRY_ID = "_id"
        const val CONTEXT = "context"
        const val REGISTERED = "registered"
        const val BRANCH = "branch"
        const val TIMER_KEY = "timer_key"
        const val DUE_INDEX = "timer_due"
        const val PARKED_INDEX = "timer_parked"
        val DUE_STATUSES = listOf(TimerStatus.PENDING.name, TimerStatus.FIRING.name)
        const val OWNER = "owner"
        const val AGGREGATE = "aggregate"
        const val TENANT_ID = "tenant_id"
        const val ID = "id"
        const val OWNER_TENANT_ID = "$OWNER.$TENANT_ID"
        const val OWNER_AGGREGATE_ID = "$OWNER.$ID"
        const val KEY = "key"
        const val TIMER_ID = "timer_id"
        const val VERSION = "version"
        const val PARTITION = "partition"
        const val DUE_AT = "due_at"
        const val SLOT_DUE_AT = "slot_due_at"
        const val SKIPPED = "skipped"
        const val RULE = "rule"
        const val SLOT = "slot"
        const val TARGET_CONTEXT = "target_context"
        const val TARGET_AGGREGATE = "target_aggregate"
        const val TARGET_TENANT_ID = "target_tenant_id"
        const val TARGET_ID = "target_id"
        const val COMMAND_TYPE = "command_type"
        const val BODY = "body"
        const val CORRELATION_ID = "correlation_id"
        const val CAUSATION_ID = "causation_id"
        const val HEADERS = "headers"
        const val STATUS = "status"
        const val ATTEMPTS = "attempts"
        const val OUTCOME = "outcome"
        const val OPERATOR = "operator"
        const val OWNER_ID = "owner_id"
        const val SPACE_ID = "space_id"
        val DUE_ORDER: Bson = Sorts.ascending(DUE_AT, TIMER_ID)
    }
}
