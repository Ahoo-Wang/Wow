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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoFailures
import me.ahoo.wow.mongo.MongoFormat
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.MongoRules
import me.ahoo.wow.mongo.conditionalUpsert
import me.ahoo.wow.mongo.isDuplicateKey
import me.ahoo.wow.mongo.mongo
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.snapshot.SnapshotTable
import org.bson.BsonArray
import org.bson.BsonBinaryReader
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonNull
import org.bson.BsonReader
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.DecoderContext
import org.bson.codecs.DocumentCodec
import org.bson.conversions.Bson
import java.util.concurrent.ConcurrentHashMap

/**
 * The [SnapshotStore] of one bounded context on MongoDB (documentation/designs/03-log-and-snapshot.md §3).
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
@InternalWowApi
class MongoSnapshotStore(
    client: MongoClient,
    databaseName: String,
    private val context: String,
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the store's collections and indexes are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
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

    private fun ensure(aggregate: String): MongoCollection<Document> = ensure(database, context, aggregate, ddl)

    override suspend fun save(snapshot: SnapshotRecord): Boolean = dispatcher.mongo {
        require(snapshot.ref.type.context == context) { "${snapshot.ref} is not in context $context." }
        saveNow(collectionOf(snapshot.ref.type.name), snapshot)
    }

    /** The version-guarded save of [snapshot]; a refused one marks a higher state revision stale. */
    private fun saveNow(snapshots: MongoCollection<Document>, snapshot: SnapshotRecord): Boolean {
        val written = upsert(snapshots, guardOf(snapshot), snapshot)
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
        return written
    }

    /**
     * Saves [snapshots] (decision D248 ②): in the rounds [SnapshotSaveOutcome.rounds] gives, each round's snapshots of
     * one aggregate type with one unordered `bulkWrite` of conditional upserts, each guarded as [save] guards one. Each
     * update is a pipeline that clears a stale mark of a version up to its own in the same write, so a batch that all
     * lands is one round trip whatever marks it meets. A snapshot whose upsert met the key (the guard refused it, or
     * a concurrent insert won the key) is saved again alone, as [save] would ([conditionalUpsert]), which also marks
     * a higher state revision stale; one the server refused with another error, or whose state cannot be BSON, is
     * [SnapshotSaveOutcome.Failed] alone.
     */
    override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> = dispatcher.mongo {
        snapshots.forEach { require(it.ref.type.context == context) { "${it.ref} is not in context $context." } }
        val outcomes = arrayOfNulls<SnapshotSaveOutcome>(snapshots.size)
        SnapshotSaveOutcome.rounds(snapshots).forEach { round ->
            round.groupBy { snapshots[it].ref.type.name }.forEach { (aggregate, indexes) ->
                saveRound(collectionOf(aggregate), indexes, snapshots, outcomes)
            }
        }
        outcomes.map { checkNotNull(it) }
    }

    /** Saves the [indexes] of [batch], of distinct refs of the collection [snapshots], into [outcomes]. */
    private fun saveRound(
        snapshots: MongoCollection<Document>,
        indexes: List<Int>,
        batch: List<SnapshotRecord>,
        outcomes: Array<SnapshotSaveOutcome?>,
    ) {
        val written = ArrayList<Int>(indexes.size)
        val upserts = ArrayList<UpdateOneModel<Document>>(indexes.size)
        indexes.forEach { index ->
            val snapshot = batch[index]
            try {
                upserts += UpdateOneModel(guardOf(snapshot), pipelineOf(snapshot), UPSERT)
                written += index
            } catch (refused: IllegalArgumentException) {
                // The state cannot be BSON, as a save would refuse it (ExactBson).
                outcomes[index] = SnapshotSaveOutcome.Failed(refused)
            }
        }
        if (upserts.isEmpty()) {
            return
        }
        val again = try {
            snapshots.bulkWrite(upserts, UNORDERED)
            emptyList()
        } catch (failure: MongoBulkWriteException) {
            if (failure.writeConcernError != null) {
                throw failure
            }
            failure.writeErrors.mapNotNull { error ->
                val index = written[error.index]
                if (error.category == ErrorCategory.DUPLICATE_KEY) {
                    index
                } else {
                    outcomes[index] = SnapshotSaveOutcome.Failed(
                        MongoFailures.translate(MongoWriteException(error, failure.serverAddress, failure.errorLabels)),
                    )
                    null
                }
            }
        }
        written.forEach { index -> if (outcomes[index] == null && index !in again) outcomes[index] = SnapshotSaveOutcome.Written }
        again.forEach { index -> outcomes[index] = SnapshotSaveOutcome.of(saveNow(snapshots, batch[index])) }
    }

    /**
     * The filter of [snapshot]'s save, as BSON: its key, a lower stored version, of the same or a lower state
     * revision. A snapshot written before state revisions has none: it is of revision 1, which no revision is below.
     */
    private fun guardOf(snapshot: SnapshotRecord): Bson = BsonDocument(TENANT_ID, BsonString(snapshot.ref.tenantId))
        .append(AGGREGATE_ID, BsonString(snapshot.ref.id))
        .append(VERSION, BsonDocument("\$lt", BsonInt32(snapshot.version)))
        .append(
            "\$or",
            BsonArray(
                listOf(
                    BsonDocument(STATE_REVISION, BsonDocument("\$lte", BsonInt32(snapshot.stateRevision))),
                    BsonDocument(STATE_REVISION, BsonDocument("\$exists", BsonBoolean.FALSE)),
                ),
            ),
        )

    /**
     * The update of [snapshot]'s save as a pipeline: every field set to its value (`$literal`, so a value that reads
     * like an expression is written as it is), and a stale mark of a version up to [snapshot]'s removed.
     */
    private fun pipelineOf(snapshot: SnapshotRecord): List<Bson> {
        val fields = BsonDocument()
        for ((field, value) in fieldsOf(snapshot)) {
            fields[field] = BsonDocument(LITERAL, value)
        }
        val unmarked =
            BsonDocument("\$lte", BsonArray(listOf(BsonString("\$$STALE_VERSION"), BsonInt32(snapshot.version))))
        fields[STALE_VERSION] = BsonDocument(
            "\$cond",
            BsonArray(listOf(unmarked, BsonString("\$\$REMOVE"), BsonString("\$$STALE_VERSION"))),
        )
        return listOf(BsonDocument("\$set", fields))
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
        val update = BsonDocument("\$set", fieldsOf(snapshot))
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

    /**
     * The fields a save writes of [snapshot], as BSON: the driver writes them without rendering or looking a codec up
     * (the state included, [ExactBson.bson]).
     */
    private fun fieldsOf(snapshot: SnapshotRecord): BsonDocument = BsonDocument()
        .append(VERSION, BsonInt32(snapshot.version))
        .append(SCHEMA_FINGERPRINT, BsonString(snapshot.schemaFingerprint))
        .append(STATE, ExactBson.bson(snapshot.state))
        .append(OWNER_ID, stringOf(snapshot.scope.ownerId))
        .append(SPACE_ID, stringOf(snapshot.scope.spaceId))
        .append(DELETED, BsonBoolean.valueOf(snapshot.deleted))
        .append(FIRST_EVENT_TIME, BsonInt64(snapshot.firstEventTime))
        .append(LAST_EVENT_TIME, BsonInt64(snapshot.lastEventTime))
        .append(TAGS, BsonDocument().apply { snapshot.tags.forEach { (tag, values) -> put(tag, stringsOf(values)) } })
        .append(OPERATOR, MongoOperators.bson(snapshot.operator))
        .append(FIRST_OPERATOR, MongoOperators.bson(snapshot.firstOperator))
        .append(SCHEDULES, schedulesOf(snapshot.schedules))
        .append(STATE_REVISION, BsonInt32(snapshot.stateRevision))

    private fun stringOf(value: String?): BsonValue = value?.let(::BsonString) ?: BsonNull.VALUE

    private fun stringsOf(values: List<String>) = BsonArray(values.map(::BsonString))

    private fun schedulesOf(schedules: Map<String, ScheduleRule>) = BsonDocument().apply {
        schedules.forEach { (name, rule) -> put(name, MongoRules.bson(rule)) }
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

    /**
     * The stored document of [ref] as the server sent it ([RawBsonDocument]), read field by field in one pass: no
     * [Document] tree, and the state written to JSON text as it is read ([ExactBson.json]).
     */
    override suspend fun load(ref: AggregateRef): SnapshotRecord? = dispatcher.mongo {
        require(ref.type.context == context) { "$ref is not in context $context." }
        collectionOf(ref.type.name).withDocumentClass(RawBsonDocument::class.java).find(keyFilter(ref)).first()
            ?.let { BsonBinaryReader(it.byteBuffer.asNIO()).use { reader -> snapshotOf(ref.type, reader) } }
    }

    private fun keyFilter(ref: AggregateRef): Bson =
        BsonDocument(TENANT_ID, BsonString(ref.tenantId)).append(AGGREGATE_ID, BsonString(ref.id))

    @Suppress("CyclomaticComplexMethod") // One branch per stored field, in one pass.
    private fun snapshotOf(type: AggregateType, reader: BsonReader): SnapshotRecord {
        var tenantId: String? = null
        var aggregateId: String? = null
        var version: Int? = null
        var fingerprint: String? = null
        var state: String? = null
        var ownerId: String? = null
        var spaceId: String? = null
        var deleted: Boolean? = null
        var firstEventTime: Long? = null
        var lastEventTime: Long? = null
        var tags: Map<String, List<String>>? = null
        var operator: Operator? = null
        var firstOperator: Operator? = null
        var schedules: Map<String, ScheduleRule> = emptyMap()
        var stateRevision = 1
        reader.readStartDocument()
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            when (reader.readName()) {
                TENANT_ID -> tenantId = reader.readString()
                AGGREGATE_ID -> aggregateId = reader.readString()
                VERSION -> version = reader.readInt32()
                SCHEMA_FINGERPRINT -> fingerprint = reader.readString()
                STATE -> state = ExactBson.json(reader)
                OWNER_ID -> ownerId = stringOf(reader)
                SPACE_ID -> spaceId = stringOf(reader)
                DELETED -> deleted = reader.readBoolean()
                FIRST_EVENT_TIME -> firstEventTime = reader.readInt64()
                LAST_EVENT_TIME -> lastEventTime = reader.readInt64()
                TAGS -> tags = tagsOf(reader)
                OPERATOR -> operator = MongoOperators.of(reader)
                FIRST_OPERATOR -> firstOperator = MongoOperators.of(reader)
                SCHEDULES -> schedules = schedulesOf(reader)
                STATE_REVISION -> stateRevision = reader.readInt32()
                else -> reader.skipValue()
            }
        }
        reader.readEndDocument()
        fun <T : Any> stored(value: T?, field: String): T = checkNotNull(value) { "A stored snapshot has no $field." }
        return SnapshotRecord(
            ref = type.ref(stored(tenantId, TENANT_ID), stored(aggregateId, AGGREGATE_ID)),
            version = stored(version, VERSION),
            schemaFingerprint = stored(fingerprint, SCHEMA_FINGERPRINT),
            state = stored(state, STATE),
            scope = Scope(ownerId, spaceId),
            deleted = stored(deleted, DELETED),
            firstEventTime = stored(firstEventTime, FIRST_EVENT_TIME),
            lastEventTime = stored(lastEventTime, LAST_EVENT_TIME),
            tags = stored(tags, TAGS),
            operator = operator,
            firstOperator = firstOperator,
            schedules = schedules,
            stateRevision = stateRevision,
        )
    }

    /** The string [reader] is at; null for a BSON null. */
    private fun stringOf(reader: BsonReader): String? = if (reader.currentBsonType == BsonType.NULL) {
        reader.readNull()
        null
    } else {
        reader.readString()
    }

    private fun tagsOf(reader: BsonReader): Map<String, List<String>> = buildMap {
        reader.readStartDocument()
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            val tag = reader.readName()
            val values = ArrayList<String>()
            reader.readStartArray()
            while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
                values += reader.readString()
            }
            reader.readEndArray()
            put(tag, values)
        }
        reader.readEndDocument()
    }

    /** The schedules [reader] is at, each rule as [MongoRules.of] reads it; none for a BSON null. */
    private fun schedulesOf(reader: BsonReader): Map<String, ScheduleRule> {
        if (reader.currentBsonType == BsonType.NULL) {
            reader.readNull()
            return emptyMap()
        }
        val schedules = DOCUMENTS.decode(reader, DecoderContext.builder().build())
        return schedules.keys.associateWith { MongoRules.of(schedules.get(it, Document::class.java)) }
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
        fun ensure(
            database: MongoDatabase,
            context: String,
            aggregate: String,
            ddl: StoreDdl = StoreDdl.AUTO,
        ): MongoCollection<Document> {
            val name = collectionName(context, aggregate)
            MongoFormat.claim(database, name, FORMAT, ddl)
            return database.getCollection(name).also { collection ->
                MongoFormat.index(
                    collection,
                    Indexes.ascending(TENANT_ID, AGGREGATE_ID),
                    IndexOptions().unique(true).name(SNAPSHOT_KEY),
                    ddl,
                )
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
        const val TAGS = "tags"
        const val OPERATOR = "operator"
        const val FIRST_OPERATOR = "first_operator"
        const val SCHEDULES = "schedules"
        const val STATE_REVISION = "state_revision"
        const val STALE_VERSION = "stale_version"

        private const val LITERAL = "\$literal"

        /** Decodes the rare sub-documents read as documents (the schedules). */
        private val DOCUMENTS = DocumentCodec()

        /** The bulk write of a batch: unordered, so one refused upsert does not stop the others. */
        private val UNORDERED: BulkWriteOptions = BulkWriteOptions().ordered(false)

        private val UPSERT: UpdateOptions = UpdateOptions().upsert(true)

        /** What a save reads of the document it replaces: the stale mark alone. */
        private val STALE_PROJECTION: Bson = Projections.fields(
            Projections.include(STALE_VERSION),
            Projections.excludeId()
        )

        /** The records of the snapshot collections' claimed state revisions (decision D172). */
        const val TABLES = "wow_snapshot_table"
        private const val ID = "_id"
        private const val REVISION = "revision"
        private const val REBUILDING = "rebuilding"
        private const val BACKFILLED = "backfilled"
    }
}
