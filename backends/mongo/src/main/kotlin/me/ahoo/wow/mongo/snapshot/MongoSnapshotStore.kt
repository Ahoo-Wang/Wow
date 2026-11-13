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

package me.ahoo.wow.mongo.snapshot

import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoDatabases
import me.ahoo.wow.mongo.MongoFailures
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
import me.ahoo.wow.mongo.mongoRead
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.schema.EnsuredOnce
import me.ahoo.wow.spi.snapshot.SnapshotRebuildStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import me.ahoo.wow.spi.store.HeldStoreCallMarks
import me.ahoo.wow.spi.store.StoreCallMarks
import org.bson.BsonDocument
import org.bson.BsonMaxKey
import org.bson.BsonMaximumSizeExceededException
import org.bson.BsonMinKey
import org.bson.BsonString
import org.bson.BsonType
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration

/**
 * The [SnapshotStore] of one bounded context on MongoDB (documentation/designs/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has its own collection, `wow_snapshot__<context>__<aggregate>`, holding one
 * document per aggregate, whose `_id` is its key `{t: tenant, a: aggregate id}`: no index besides
 * `_id_` keeps it unique, and the key's order is the tenant's, then the aggregate id's. The document also holds the
 * two as `tenant_id` and `aggregate_id`, which the queries read, and every write's filter names them, the shard key of
 * a sharded collection; it has no aggregate name, the collection is the aggregate type. An aggregate type's collection is created, with its key and format record, the
 * first time this store touches the type, or up front by [createCollection]. Two partial indexes cover the predicates
 * the query side adds to every query: `{tenant_id, owner_id}` and `{tenant_id, space_id}`, over the
 * snapshots that are not deleted.
 * The state and the access attributes are BSON documents, so the snapshot can be queried in place as the default
 * read model; the
 * state's decimals are `Decimal128`, exact ([ExactBson]).
 * A save is one conditional upsert whose filter carries the version and state revision guard (`version < new`, the
 * stored `state_revision` not above the new one): no transaction, and concurrent saves leave the highest version (see
 * [conditionalUpsert]). A save refused for a higher state revision then marks the document stale (`stale_version`).
 * The claimed state revision of each collection is a document of [TABLES], keyed by the collection.
 *
 * Calls run on [dispatcher], virtual threads by default. A failure of the database is a defect and is
 * thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
@InternalWowApi
class MongoSnapshotStore(
    private val client: MongoClient,
    databaseName: String,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's collections and indexes are managed: created when missing by default. */
    private val ddl: Ddl = Ddl.AUTO,
    /** Whether the store shards its collections on a sharded cluster. */
    sharding: MongoSharding = MongoSharding.AUTO,
    /** How long each of its calls may take (`wow.mongo.operation-timeout`, [MongoTimeouts]). */
    operationTimeout: Duration = MongoTimeouts.OPERATION,
) : SnapshotStore, SnapshotRebuildStore {
    /** This store rewrites its snapshots in place: it is its own rebuild control plane. */
    override val rebuilds: SnapshotRebuildStore get() = this

    /**
     * [WRITES_IN_FLIGHT]: a write here is one unordered `bulkWrite` of at most the batch, and returns only once a
     * majority journaled it ([MongoDatabases]), so the subscription's throughput is these calls times their batch per
     * call, whatever the server has to spare. It is what the subscription had in flight before the calls were merged
     * across partitions (four lanes, each one call at a time).
     */
    override val writesInFlight: Int get() = WRITES_IN_FLIGHT

    private val database = MongoDatabases.of(client, databaseName, operationTimeout)

    /**
     * Whether this store shards its collections: asked of the server once, as the store is built when its port opens,
     * so a server that cannot answer fails the start, and no first write waits on the round trip under a lock.
     */
    private val sharded: Boolean = sharding.shards(client, ddl)

    /** The collections whose key and format this store has made sure of, by aggregate name, each once. */
    private val ensured = EnsuredOnce<String, MongoCollection<Document>>()

    /**
     * Nothing to create up front: each aggregate type's collection is created on its first use ([createCollection]),
     * since the store does not know the context's aggregate types. Kept for the host, which calls it at startup.
     */
    fun createSchema() = Unit

    /**
     * Creates the key index of [aggregateName]'s collection when missing, once per store; refuses snapshots recorded
     * in another format version ([MongoFormat]).
     */
    fun createCollection(aggregateName: String) {
        collectionOf(aggregateName)
    }

    private fun collectionOf(aggregate: String): MongoCollection<Document> =
        ensured.get(aggregate) { ensure(aggregate) }

    private fun ensure(aggregate: String): MongoCollection<Document> =
        ensure(database, context, aggregate, ddl).also { collection ->
            if (sharded) {
                // Sharded by tenant, then the aggregate id's hash, so new aggregates spread over the shards.
                MongoShards.shardSnapshots(client, database, collection)
            }
        }

    override suspend fun save(snapshot: SnapshotRecord): Boolean = dispatcher.mongo {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val snapshots = collectionOf(snapshot.ref.type.name)
        MongoFailures.refusing("the snapshot of ${snapshot.ref}") { saveNow(snapshots, snapshot) }
    }

    /** The version-guarded save of [snapshot]; a refused one marks a higher state revision stale. */
    private fun saveNow(snapshots: MongoCollection<Document>, snapshot: SnapshotRecord): Boolean {
        val written = upsert(snapshots, MongoSnapshotWrites.guardOf(snapshot), snapshot)
        if (!written) {
            // Refused: when the stored snapshot is of a higher state revision, the log has moved past it.
            snapshots.updateOne(
                Filters.and(
                    keyFilter(snapshot.ref),
                    Filters.lt(VERSION, snapshot.version),
                    Filters.gt(STATE_REVISION, snapshot.stateRevision),
                ),
                Updates.max(STALE_VERSION, snapshot.version),
            )
        }
        return written
    }

    /**
     * Saves [snapshots]: in the rounds [SnapshotSaveOutcome.rounds] gives, each round's snapshots of one aggregate type
     * with one unordered `bulkWrite` of conditional upserts ([MongoSnapshotWrites]), each the save's own modifier
     * `$set`, guarded as [save] guards one and over a document without a stale mark. Each operation either applied (it
     * matched and wrote the snapshot, or found no document and inserted it: written) or met the key with its insert
     * (the document exists and the filter did not match it: the guard refused the version, the document carries a stale
     * mark, or a concurrent insert won the key). Such a snapshot is saved again alone, as [save] would
     * ([conditionalUpsert]), which tells the three apart: it writes over a stale mark and clears one of a version up to
     * its own, and marks a higher state revision stale. So a batch that all lands is one round trip; a marked document,
     * during a rebuild, costs its save alone; one the server refused with another error, or whose state cannot be BSON,
     * is [SnapshotSaveOutcome.Failed] alone.
     *
     * A write the snapshot subscription clocks ([StoreCallMarks] in its context) is marked from its entry to its work
     * done: sent at its first `bulkWrite`, its documents built, and read after its last round. The driver checks a
     * connection out within each command, so the call has none of its own: it is marked acquired once on its thread.
     */
    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
        val marks = currentCoroutineContext()[StoreCallMarks]?.apply { entered() }
        return dispatcher.mongo {
            marks?.onThread()
            marks?.acquired()
            snapshots.forEach { require(it.ref.type.context == context) { "${it.ref} is not in context $context." } }
            val outcomes = arrayOfNulls<SnapshotSaveOutcome>(snapshots.size)
            HeldStoreCallMarks.hold(marks)
            try {
                SnapshotSaveOutcome.rounds(snapshots).forEach { round ->
                    round.groupBy { snapshots[it].ref.type.name }.forEach { (aggregate, indexes) ->
                        saveRound(collectionOf(aggregate), indexes, snapshots, outcomes, marks)
                    }
                }
                marks?.read()
            } finally {
                HeldStoreCallMarks.release(marks)
            }
            outcomes.map { checkNotNull(it) }.also { marks?.released() }
        }
    }

    /** Saves the [indexes] of [batch], of distinct refs of the collection [snapshots], into [outcomes]. */
    private fun saveRound(
        snapshots: MongoCollection<Document>,
        indexes: List<Int>,
        batch: List<SnapshotRecord>,
        outcomes: Array<SnapshotSaveOutcome?>,
        marks: StoreCallMarks?,
    ) {
        val written = ArrayList<Int>(indexes.size)
        val upserts = ArrayList<UpdateOneModel<Document>>(indexes.size)
        indexes.forEach { index ->
            val snapshot = batch[index]
            try {
                upserts += UpdateOneModel(
                    MongoSnapshotWrites.unmarkedGuardOf(snapshot),
                    MongoSnapshotWrites.updateOf(snapshot),
                    UPSERT,
                )
                written += index
            } catch (refused: IllegalArgumentException) {
                // The state cannot be BSON, as a save would refuse it (ExactBson).
                outcomes[index] =
                    SnapshotSaveOutcome.Failed(checkNotNull(MongoFailures.refusalOf("the snapshot of ${snapshot.ref}", refused)))
            }
        }
        if (upserts.isEmpty()) {
            return
        }
        val again = try {
            marks?.sent()
            snapshots.bulkWrite(upserts, UNORDERED)
            emptyList()
        } catch (tooLarge: BsonMaximumSizeExceededException) {
            // The driver refuses a document beyond the size limit as it reaches it, after the batches before it went:
            // each is saved again alone, guarded, so the one too large is refused alone.
            written
        } catch (failure: MongoBulkWriteException) {
            if (failure.writeConcernError != null) {
                throw failure
            }
            failure.writeErrors.mapNotNull { error ->
                val index = written[error.index]
                if (error.category == ErrorCategory.DUPLICATE_KEY) {
                    index
                } else {
                    val write = MongoWriteException(error, failure.serverAddress, failure.errorLabels)
                    outcomes[index] = SnapshotSaveOutcome.Failed(
                        MongoFailures.refusalOf("the snapshot of ${batch[index].ref}", write)
                            ?: MongoFailures.translate(write),
                    )
                    null
                }
            }
        }
        written.forEach { index -> if (outcomes[index] == null && index !in again) outcomes[index] = SnapshotSaveOutcome.Written }
        again.forEach { index -> outcomes[index] = alone(snapshots, batch[index]) }
    }

    /** [snapshot] saved alone, as [save] saves it: one the store can never take fails alone, refused. */
    private fun alone(snapshots: MongoCollection<Document>, snapshot: SnapshotRecord): SnapshotSaveOutcome = try {
        SnapshotSaveOutcome.of(
            MongoFailures.refusing("the snapshot of ${snapshot.ref}") { saveNow(snapshots, snapshot) }
        )
    } catch (refused: StorageRefusedException) {
        SnapshotSaveOutcome.Failed(refused)
    }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = dispatcher.mongo {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val snapshots = collectionOf(snapshot.ref.type.name)
        upsert(snapshots, Filters.and(keyFilter(snapshot.ref), Filters.lte(VERSION, snapshot.version)), snapshot)
    }

    /**
     * Writes [snapshot] over the document [guarded] matches, or inserts it when there is none; then clears a stale
     * mark of a version up to its own. Whether it was written.
     *
     * The write returns the stale mark the document had before it, so the clearing write runs only when there was a
     * mark to clear: a save is one round trip unless the snapshot was marked stale (B.D diagnostics). A mark set after
     * the write, of the version it wrote, stays.
     */
    private fun upsert(snapshots: MongoCollection<Document>, guarded: Bson, snapshot: SnapshotRecord): Boolean {
        val update = MongoSnapshotWrites.updateOf(snapshot)
        // Before the write: the stored document's stale mark, null when it has none or there was no document.
        var marked: Number? = null
        val written = conditionalUpsert { upsert ->
            val before = snapshots.findOneAndUpdate(
                guarded,
                update,
                FindOneAndUpdateOptions().upsert(upsert).projection(STALE_PROJECTION)
                    .returnDocument(ReturnDocument.BEFORE),
            )
            marked = before?.get(STALE_VERSION) as Number?
            // With upsert, no document before means it inserted one; without, that it matched none.
            before != null || upsert
        }
        if (written && marked.let { it != null && it.toLong() <= snapshot.version }) {
            snapshots.updateOne(
                Filters.and(keyFilter(snapshot.ref), Filters.lte(STALE_VERSION, snapshot.version)),
                Updates.unset(STALE_VERSION),
            )
        }
        return written
    }

    override suspend fun markStale(ref: AggregateRef): Boolean = dispatcher.mongo {
        require(ref.type.context == context) { "$ref is not in context $context." }
        val stale = Document("\$max", listOf("\$$STALE_VERSION", "\$$VERSION"))
        collectionOf(ref.type.name).updateOne(
            keyFilter(ref),
            listOf(Document("\$set", Document(STALE_VERSION, stale))),
        ).matchedCount > 0
    }

    override suspend fun pending(
        type: AggregateType,
        revision: Int,
        after: AggregateRef?,
        limit: Int,
    ): List<AggregateRef> = dispatcher.mongo {
        require(type.context == context) { "$type is not in context $context." }
        require(limit >= 1) { "A page holds at least one ref." }
        val pending = Filters.or(Filters.ne(STATE_REVISION, revision), Filters.exists(STALE_VERSION))
        val filter = if (after == null) {
            pending
        } else {
            Filters.and(pending, Filters.gt(ID, keyOf(after)))
        }
        collectionOf(type.name).find(filter)
            .projection(Projections.include(TENANT_ID, AGGREGATE_ID))
            .sort(Sorts.ascending(ID))
            .limit(limit)
            .map { type.ref(it.getString(TENANT_ID), it.getString(AGGREGATE_ID)) }
            .toList()
    }

    override suspend fun table(type: AggregateType): SnapshotTable? = dispatcher.mongo {
        require(type.context == context) { "$type is not in context $context." }
        tables.find(Filters.eq(ID, collectionName(context, type.name))).first()?.let(::tableOf)
    }

    override suspend fun claim(type: AggregateType, revision: Int, lower: Boolean): SnapshotTable = dispatcher.mongo {
        require(type.context == context) { "$type is not in context $context." }
        require(revision >= 1) { "A state revision starts at 1." }
        val structure = collectionName(context, type.name)
        try {
            tables.insertOne(
                Document(ID, structure).append(REVISION, revision).append(REBUILDING, revision != 1)
                    .append(BACKFILLED, false),
            )
        } catch (failure: MongoWriteException) {
            if (!failure.isDuplicateKey()) {
                throw failure
            }
            val changes = if (lower) Filters.ne(REVISION, revision) else Filters.lt(REVISION, revision)
            tables.updateOne(
                Filters.and(Filters.eq(ID, structure), changes),
                Updates.combine(Updates.set(REVISION, revision), Updates.set(REBUILDING, true)),
            )
        }
        tableOf(checkNotNull(tables.find(Filters.eq(ID, structure)).first()))
    }

    override suspend fun mark(
        type: AggregateType,
        revision: Int,
        rebuilding: Boolean?,
        backfilled: Boolean?,
    ): SnapshotTable? = dispatcher.mongo {
        require(type.context == context) { "$type is not in context $context." }
        val filter = Filters.and(Filters.eq(ID, collectionName(context, type.name)), Filters.eq(REVISION, revision))
        val changes = listOfNotNull(
            rebuilding?.let { Updates.set(REBUILDING, it) },
            backfilled?.let { Updates.set(BACKFILLED, it) },
        )
        val marked = if (changes.isEmpty()) {
            tables.find(filter).first()
        } else {
            tables.findOneAndUpdate(
                filter,
                Updates.combine(changes),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )
        }
        marked?.let(::tableOf)
    }

    private val tables: MongoCollection<Document> get() = database.getCollection(TABLES)

    private fun tableOf(document: Document) = SnapshotTable(
        revision = document.getInteger(REVISION),
        rebuilding = document.getBoolean(REBUILDING),
        backfilled = document.getBoolean(BACKFILLED),
    )

    // Interruptible: the kernel gives up on a load past its timeout, and the read ends with it.
    override suspend fun load(ref: AggregateRef): SnapshotRecord? = dispatcher.mongoRead {
        require(ref.type.context == context) { "$ref is not in context $context." }
        collectionOf(ref.type.name).find(keyFilter(ref)).first()?.let { snapshotOf(ref.type, it) }
    }

    private fun keyFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(ID, keyOf(ref)),
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.eq(AGGREGATE_ID, ref.id),
    )

    private fun snapshotOf(type: AggregateType, document: Document): SnapshotRecord {
        return SnapshotRecord(
            ref = type.ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
            version = document.getInteger(VERSION),
            schemaFingerprint = document.getString(SCHEMA_FINGERPRINT),
            state = ExactBson.json(document.get(STATE, Document::class.java)),
            scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
            deleted = document.getBoolean(DELETED),
            firstEventTime = document.getLong(FIRST_EVENT_TIME),
            lastEventTime = document.getLong(LAST_EVENT_TIME),
            access = AccessValues.normalized(document.get(ACCESS, Document::class.java)),
            operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
            firstOperator = MongoOperators.of(document.get(FIRST_OPERATOR, Document::class.java)),
            schedules = document.get(SCHEDULES, Document::class.java)?.let { schedules ->
                schedules.keys.associateWith { MongoRules.of(schedules.get(it, Document::class.java)) }
            }.orEmpty(),
            stateRevision = document.getInteger(STATE_REVISION),
        )
    }

    internal companion object {
        /** The version of this layout. */
        const val FORMAT = 1

        /** [writesInFlight]. */
        const val WRITES_IN_FLIGHT = 4

        /** The partial index of the owner scope. */
        const val OWNER_INDEX = "owner_ix"

        /** The partial index of the space scope. */
        const val SPACE_INDEX = "space_ix"

        /**
         * The indexes that cover the predicates admission adds to every query: the tenant and the
         * caller's owner or space, by index name and the scope field after the tenant.
         */
        val SCOPE_INDEXES: Map<String, String> = linkedMapOf(OWNER_INDEX to "owner_id", SPACE_INDEX to "space_id")

        /** The partial filter of every query index: the snapshots that are not deleted, the default scope. */
        fun notDeleted(): Document = Document(DELETED, false)

        /** The options of a query index of this collection: [name], partial on the snapshots that are not deleted. */
        fun scopeIndexOptions(name: String): IndexOptions =
            IndexOptions().name(name).partialFilterExpression(notDeleted())

        /** The collection of [aggregate]'s snapshots in [context]. */
        fun collectionName(context: String, aggregate: String): String = MongoNames.of(PREFIX, context, aggregate)

        /** The `_id` of [ref]'s snapshot: its tenant, then its aggregate id. */
        fun keyOf(ref: AggregateRef): BsonDocument = keyOf(ref.tenantId, ref.id)

        /** The `_id` of the snapshot of [tenantId]'s aggregate [aggregateId]. */
        fun keyOf(tenantId: String, aggregateId: String): BsonDocument =
            BsonDocument(KEY_TENANT, BsonString(tenantId)).append(KEY_AGGREGATE, BsonString(aggregateId))

        /** The keys of [tenantId]'s snapshots: one range of `_id`. */
        fun tenantKeys(tenantId: String): Bson = Filters.and(
            Filters.gt(ID, BsonDocument(KEY_TENANT, BsonString(tenantId)).append(KEY_AGGREGATE, BsonMinKey())),
            Filters.lt(ID, BsonDocument(KEY_TENANT, BsonString(tenantId)).append(KEY_AGGREGATE, BsonMaxKey())),
        )

        /**
         * Makes sure of the snapshot collection of [aggregate] in [context]: claims its format record ([MongoFormat]),
         * refuses snapshots an alpha wrote, then creates its scope indexes when missing, which creates the collection
         * itself.
         */
        fun ensure(
            database: MongoDatabase,
            context: String,
            aggregate: String,
            ddl: Ddl = Ddl.AUTO,
        ): MongoCollection<Document> {
            val name = collectionName(context, aggregate)
            MongoFormat.claim(database, name, FORMAT, ddl)
            return database.getCollection(name).also { collection ->
                // A snapshot's key is its `_id`: an alpha's have the driver's ObjectIds, and a save
                // would add a second document for the aggregate beside it.
                check(
                    collection.find(Filters.type(ID, BsonType.OBJECT_ID)).projection(Projections.include(ID)).first() == null
                ) {
                    "$name was written by an earlier 10.0 alpha, whose snapshots have no key as their _id: rebuild " +
                        "the snapshots from the log, an alpha's data is not migrated."
                }
                SCOPE_INDEXES.forEach { (index, field) ->
                    MongoFormat.index(collection, Indexes.ascending(TENANT_ID, field), scopeIndexOptions(index), ddl)
                }
            }
        }

        const val TENANT_ID = "tenant_id"
        const val PREFIX = "wow_snapshot"
        const val AGGREGATE_ID = "aggregate_id"
        const val VERSION = "version"
        const val SCHEMA_FINGERPRINT = "schema_fingerprint"
        const val STATE = "state"
        const val OWNER_ID = "owner_id"
        const val SPACE_ID = "space_id"
        const val DELETED = "deleted"
        const val FIRST_EVENT_TIME = "first_event_time"
        const val LAST_EVENT_TIME = "last_event_time"
        const val ACCESS = "access"
        const val OPERATOR = "operator"
        const val FIRST_OPERATOR = "first_operator"
        const val SCHEDULES = "schedules"
        const val STATE_REVISION = "state_revision"
        const val STALE_VERSION = "stale_version"

        /** The bulk write of a batch: unordered, so one refused upsert does not stop the others. */
        private val UNORDERED: BulkWriteOptions = BulkWriteOptions().ordered(false)

        private val UPSERT: UpdateOptions = UpdateOptions().upsert(true)

        /** What a save reads of the document it replaces: the stale mark alone. */
        private val STALE_PROJECTION: Bson = Projections.fields(
            Projections.include(STALE_VERSION),
            Projections.excludeId()
        )

        /** The records of the snapshot collections' claimed state revisions. */
        const val TABLES = "wow_snapshot_table"
        const val ID = "_id"
        private const val KEY_TENANT = "t"
        private const val KEY_AGGREGATE = "a"
        private const val REVISION = "revision"
        private const val REBUILDING = "rebuilding"
        private const val BACKFILLED = "backfilled"
    }
}
