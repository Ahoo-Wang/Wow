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

import com.mongodb.MongoWriteException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.MongoRules
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.ConcurrentHashMap

/**
 * The [SnapshotStore] of one bounded context on MongoDB (documentation/designs/v10/03-log-and-snapshot.md §3).
 *
 * Each aggregate type has its own collection, `wow_snapshot__<context>__<aggregate>` (decision D161), holding one
 * document per aggregate, keyed by a unique index on (tenant, aggregate id); the document has no aggregate name, the
 * collection is the aggregate type. An aggregate type's collection is created, with its key and format record, the
 * first time this store touches the type, or up front by [createCollection]. Two partial indexes cover the predicates
 * the query side adds to every query (decision D151): `{tenant_id, owner_id}` and `{tenant_id, space_id}`, over the
 * snapshots that are not deleted.
 * The state and the tags are BSON documents, so the snapshot can be queried in place as the default read model; the
 * state's decimals are `Decimal128`, exact ([ExactBson]).
 * A save is one conditional upsert whose filter carries the version and state revision guard (`version < new`, the
 * stored `state_revision` not above the new one): no transaction, and concurrent saves leave the highest version (see
 * [conditionalUpsert]). A save refused for a higher state revision then marks the document stale (`stale_version`,
 * decision D172). The claimed state revision of each collection is a document of [TABLES], keyed by the collection.

 *
 * Calls run on [dispatcher], virtual threads by default (decision D29). A failure of the database is a defect and is
 * thrown: a snapshot is a cache, so the caller may drop it and rebuild later.
 */
class MongoSnapshotStore(
    client: MongoClient,
    databaseName: String,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : SnapshotStore {
    private val database = client.getDatabase(databaseName)

    /** The collections whose key and format this store has made sure of, by aggregate name. */
    private val ensured = ConcurrentHashMap<String, MongoCollection<Document>>()

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
        ensured[aggregate] ?: ensure(aggregate).also { ensured[aggregate] = it }

    private fun ensure(aggregate: String): MongoCollection<Document> = ensure(database, context, aggregate)

    override suspend fun save(snapshot: SnapshotRecord): Boolean = dispatcher.mongo {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val snapshots = collectionOf(snapshot.ref.type.name)
        // A snapshot written before state revisions has none: it is of revision 1, which no revision is below.
        val guarded = Filters.and(
            keyFilter(snapshot.ref),
            Filters.lt(VERSION, snapshot.version),
            Filters.or(Filters.lte(STATE_REVISION, snapshot.stateRevision), Filters.exists(STATE_REVISION, false)),
        )
        val written = upsert(snapshots, guarded, snapshot)
        if (!written) {
            // Refused: when the stored snapshot is of a higher state revision, the log has moved past it (D172).
            snapshots.updateOne(
                Filters.and(
                    keyFilter(snapshot.ref),
                    Filters.lt(VERSION, snapshot.version),
                    Filters.gt(STATE_REVISION, snapshot.stateRevision),
                ),
                Updates.max(STALE_VERSION, snapshot.version),
            )
        }
        written
    }

    override suspend fun rewrite(snapshot: SnapshotRecord): Boolean = dispatcher.mongo {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        val snapshots = collectionOf(snapshot.ref.type.name)
        upsert(snapshots, Filters.and(keyFilter(snapshot.ref), Filters.lte(VERSION, snapshot.version)), snapshot)
    }

    /**
     * Writes [snapshot] over the document [guarded] matches, or inserts it when there is none; then clears a stale
     * mark of a version up to its own. Whether it was written.
     */
    private fun upsert(snapshots: MongoCollection<Document>, guarded: Bson, snapshot: SnapshotRecord): Boolean {
        val update = Updates.combine(
            Updates.set(VERSION, snapshot.version),
            Updates.set(SCHEMA_FINGERPRINT, snapshot.schemaFingerprint),
            Updates.set(STATE, ExactBson.document(snapshot.state)),
            Updates.set(OWNER_ID, snapshot.scope.ownerId),
            Updates.set(SPACE_ID, snapshot.scope.spaceId),
            Updates.set(DELETED, snapshot.deleted),
            Updates.set(FIRST_EVENT_TIME, snapshot.firstEventTime),
            Updates.set(LAST_EVENT_TIME, snapshot.lastEventTime),
            Updates.set(TAGS, Document(snapshot.tags)),
            Updates.set(OPERATOR, MongoOperators.document(snapshot.operator)),
            Updates.set(FIRST_OPERATOR, MongoOperators.document(snapshot.firstOperator)),
            Updates.set(SCHEDULES, Document(snapshot.schedules.mapValues { MongoRules.document(it.value) })),
            Updates.set(STATE_REVISION, snapshot.stateRevision),
        )
        val written = conditionalUpsert { upsert ->
            val result = snapshots.updateOne(guarded, update, UpdateOptions().upsert(upsert))
            result.matchedCount > 0 || result.upsertedId != null
        }
        if (written) {
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
        // A document without a state revision is of revision 1; `$ne` matches a missing field.
        val otherRevision = if (revision == 1) {
            Filters.and(Filters.exists(STATE_REVISION), Filters.ne(STATE_REVISION, revision))
        } else {
            Filters.ne(STATE_REVISION, revision)
        }
        val pending = Filters.or(otherRevision, Filters.exists(STALE_VERSION))
        val filter = if (after == null) {
            pending
        } else {
            Filters.and(
                pending,
                Filters.or(
                    Filters.gt(TENANT_ID, after.tenantId),
                    Filters.and(Filters.eq(TENANT_ID, after.tenantId), Filters.gt(AGGREGATE_ID, after.id)),
                ),
            )
        }
        collectionOf(type.name).find(filter)
            .projection(Projections.include(TENANT_ID, AGGREGATE_ID))
            .sort(Sorts.ascending(TENANT_ID, AGGREGATE_ID))
            .limit(limit)
            .map { type.ref(it.getString(TENANT_ID), it.getString(AGGREGATE_ID)) }
            .toList()
    }

    override suspend fun table(type: AggregateType): SnapshotTable? = dispatcher.mongo {
        require(type.context == context) { "$type is not in context $context." }
        tables.find(Filters.eq(ID, collectionName(context, type.name))).firstOrNull()?.let(::tableOf)
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
            tables.find(filter).firstOrNull()
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

    override suspend fun load(ref: AggregateRef): SnapshotRecord? = dispatcher.mongo {
        require(ref.type.context == context) { "$ref is not in context $context." }
        collectionOf(ref.type.name).find(keyFilter(ref)).firstOrNull()?.let { snapshotOf(ref.type, it) }
    }

    private fun keyFilter(ref: AggregateRef): Bson = Filters.and(
        Filters.eq(TENANT_ID, ref.tenantId),
        Filters.eq(AGGREGATE_ID, ref.id),
    )

    private fun snapshotOf(type: AggregateType, document: Document): SnapshotRecord {
        val tags = document.get(TAGS, Document::class.java)
        return SnapshotRecord(
            ref = type.ref(document.getString(TENANT_ID), document.getString(AGGREGATE_ID)),
            version = document.getInteger(VERSION),
            schemaFingerprint = document.getString(SCHEMA_FINGERPRINT),
            state = ExactBson.json(document.get(STATE, Document::class.java)),
            scope = Scope(document.getString(OWNER_ID), document.getString(SPACE_ID)),
            deleted = document.getBoolean(DELETED),
            firstEventTime = document.getLong(FIRST_EVENT_TIME),
            lastEventTime = document.getLong(LAST_EVENT_TIME),
            tags = tags.keys.associateWith { tags.getList(it, String::class.java).toList() },
            operator = MongoOperators.of(document.get(OPERATOR, Document::class.java)),
            firstOperator = MongoOperators.of(document.get(FIRST_OPERATOR, Document::class.java)),
            schedules = document.get(SCHEDULES, Document::class.java)?.let { schedules ->
                schedules.keys.associateWith { MongoRules.of(schedules.get(it, Document::class.java)) }
            }.orEmpty(),
            stateRevision = document.getInteger(STATE_REVISION) ?: 1,
        )
    }

    internal companion object {
        /** The version of this layout (decision D59). */
        const val FORMAT = 1

        const val SNAPSHOT_KEY = "snapshot_key"

        /** The partial index of the owner scope (decision D151). */
        const val OWNER_INDEX = "owner_ix"

        /** The partial index of the space scope (decision D151). */
        const val SPACE_INDEX = "space_ix"

        /**
         * The indexes that cover the predicates admission adds to every query (decision D151): the tenant and the
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

        /**
         * Makes sure of the snapshot collection of [aggregate] in [context]: claims its format record ([MongoFormat]),
         * then creates its key and scope indexes when missing, which creates the collection itself.
         */
        fun ensure(database: MongoDatabase, context: String, aggregate: String): MongoCollection<Document> {
            val name = collectionName(context, aggregate)
            MongoFormat.claim(database, name, FORMAT)
            return database.getCollection(name).also { collection ->
                collection.createIndex(
                    Indexes.ascending(TENANT_ID, AGGREGATE_ID),
                    IndexOptions().unique(true).name(SNAPSHOT_KEY),
                )
                SCOPE_INDEXES.forEach { (index, field) ->
                    collection.createIndex(Indexes.ascending(TENANT_ID, field), scopeIndexOptions(index))
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
        const val TAGS = "tags"
        const val OPERATOR = "operator"
        const val FIRST_OPERATOR = "first_operator"
        const val SCHEDULES = "schedules"
        const val STATE_REVISION = "state_revision"
        const val STALE_VERSION = "stale_version"

        /** The records of the snapshot collections' claimed state revisions (decision D172). */
        const val TABLES = "wow_snapshot_table"
        private const val ID = "_id"
        private const val REVISION = "revision"
        private const val REBUILDING = "rebuilding"
        private const val BACKFILLED = "backfilled"
    }
}
