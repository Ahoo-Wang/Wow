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

package me.ahoo.wow.mongo.query

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.access.AccessValues
import org.bson.Document
import org.slf4j.LoggerFactory

/**
 * A planned query index of [aggregate] in [context]: its [name], unique within its [collection], and its [keys] in
 * order, each ascending, over the documents its [partial] filter keeps: on the snapshot collection, the snapshots that
 * are not deleted; on the log collection ([MongoQueryIndexes.planLog]), the streams. A [sparse] one has no partial
 * filter and holds the documents that have its key: a built-in access attribute's tenant-scoped key on a log.
 */
@InternalWowApi
data class MongoQueryIndex(
    val context: String,
    val aggregate: String,
    val name: String,
    val keys: List<String>,
    /** The collection the index is on: the aggregate's snapshot collection by default. */
    val collection: String = MongoSnapshotStore.collectionName(context, aggregate),
    private val partial: Document? = MongoSnapshotStore.notDeleted(),
    /** Whether the index holds only the documents that have its key, with no partial filter. */
    val sparse: Boolean = false,
) {
    init {
        require(!sparse || partial == null) { "Index [$name] is sparse or partial, not both." }
    }

    /** The key document of the index: `{tenant_id: 1, <key>: 1, ...}`. */
    fun keyDocument(): Document = Document().also { document -> keys.forEach { document[it] = 1 } }

    /**
     * The partial filter of the index: `{deleted: false}` on the snapshots, `{version: {$exists: true}}` on a log; none
     * for a [sparse] one.
     */
    fun partialFilter(): Document? = partial?.let(::Document)

    /** The `createIndexes` specification of the index, as `listIndexes` reports it. */
    override fun toString(): String = "$collection.$name ${keyDocument().toJson()} " +
        (partialFilter()?.let { "partialFilterExpression ${it.toJson()}" } ?: "sparse")
}

/** What [MongoSnapshotQueryStorage.manageIndexes] did: the indexes it created, and the ones it found missing or different. */
@InternalWowApi
data class MongoQueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * The query indexes of an aggregate type's snapshot collection:
 *
 * ```
 * owner_ix: {tenant_id: 1, owner_id: 1}, partialFilterExpression: {deleted: false}
 * space_ix: {tenant_id: 1, space_id: 1}, partialFilterExpression: {deleted: false}
 * <name>:   {tenant_id: 1, <key>: 1, ..., _id: 1}, partialFilterExpression: {deleted: false}   (each x-wow-index entry)
 * ```
 *
 * Every index leads with the tenant, which admission adds to every query, and is partial on the snapshots that are not
 * deleted, its default scope: a query whose filter holds `deleted: false` can use it. A key is the document path the
 * filter, sort and aggregation compilers read for it ([MongoSnapshotDocuments]), so the planner matches the two.
 * A declared index ends with the tie-breakers every snapshot sort ends with ([MongoSnapshotDocuments.tieBreakers],
 * `_id`), so a sort by its keys under the tenant's equality whose tie-breaker runs in the same direction is the index's
 * order, forward or backward, with no sort stage: every sort ends with the tie-breaker in the direction of its last
 * field ([me.ahoo.wow.query.backend.tieBreakerDirection]): a sort by the keys in one direction is read in index order.
 * An existing index of another shape is drift, as any other ([manage]).
 *
 * MongoDB names indexes per collection, so a declared index keeps its own name. The store creates the two scope
 * indexes with the collection; planning them here lets `validate` report them too.
 */
@InternalWowApi
object MongoQueryIndexes {
    private val log = LoggerFactory.getLogger(MongoQueryIndexes::class.java)

    /** The index names the store keeps for itself. */
    private val RESERVED = setOf(MongoSnapshotDocuments.ID + "_")

    /** The paths of the built-in access attributes in a log stream, which a stream leaves out without a value. */
    private val BUILT_IN_ACCESS = listOf(AccessValues.OWNER, AccessValues.SPACE).map { "${MongoEventLog.ACCESS}.$it" }

    /**
     * The indexes of the snapshot collection of [aggregate] in [context]: the two scope indexes, then those of
     * [plan], keyed by its keys' document paths. Fails on a second multi-valued key (MongoDB cannot index parallel
     * arrays) and on a name the collection already uses.
     */
    fun plan(context: String, aggregate: String, plan: QueryIndexPlan): List<MongoQueryIndex> {
        val scope = MongoSnapshotStore.SCOPE_INDEXES.map { (name, field) ->
            MongoQueryIndex(context, aggregate, name, listOf(MongoSnapshotDocuments.TENANT_ID, field))
        }
        val names = scope.map { it.name }.toMutableSet()
        val declared = plan.indexes.map { index ->
            require(index.name !in RESERVED && names.add(index.name)) {
                "Index [${index.name}] of $context.$aggregate reuses a name the collection already has."
            }
            require(index.keys.count { it.cardinality != QueryCardinality.SINGLE } <= 1) {
                "Index [${index.name}] has more than one key with many values: MongoDB cannot index parallel arrays."
            }
            // Ended by the tie-breakers the snapshot documents end a sort with: a sort by the keys whose tie-breaker
            // runs in the same direction is the index's order, forward or backward.
            val paths = listOf(MongoSnapshotDocuments.TENANT_ID) + index.keys.map { key ->
                // A planned key, not an admitted query: no admission resolved it, so its physical field is read directly.
                val physical = key.physicalField
                MongoSnapshotDocuments.checked(physical.path)
            } + MongoSnapshotDocuments.tieBreakers
            MongoQueryIndex(context, aggregate, index.name, paths.distinct())
        }
        return scope + declared
    }

    /**
     * The indexes of [plan], the access attributes of [aggregate] in [context], on its log collection: led by the
     * tenant, each attribute's document path (multikey for a multiple one), partial on the streams
     * (`{version: {$exists: true}}`), which every `events` query asks for, so it can use them; the log's placeholders
     * hold no version. No tie-breaker ends them: they narrow the streams an access policy lets a reader see, and are not
     * read for an order, which for an `events` query ends with the stream key (tenant, aggregate, version).
     *
     * A built-in attribute's index (`owner`, `space`) is instead the sparse index of its tenant-scoped key
     * (`access_key.owner`, [me.ahoo.wow.mongo.MongoAccess.keys], decision D420): a stream without the attribute adds no
     * entry, an equality or an `$in` the compiler maps to the key reads the tenant's entries alone, and an `$or` branch
     * can use it, which a partial index whose filter only the branch implies it cannot. A negation and `isNull` read
     * the attribute itself, by no index of its own.
     */
    fun planLog(context: String, aggregate: String, plan: QueryIndexPlan): List<MongoQueryIndex> {
        val collection = MongoNames.of(MongoEventLog.PREFIX, context, aggregate)
        val streams = Document(MongoEventLog.VERSION, Document("\$exists", true))
        return plan.indexes.map { index ->
            require(index.keys.count { it.cardinality != QueryCardinality.SINGLE } <= 1) {
                "Index [${index.name}] has more than one key with many values: MongoDB cannot index parallel arrays."
            }
            val paths = listOf(MongoEventLog.TENANT_ID) + index.keys.map { key ->
                // A planned key, not an admitted query: no admission resolved it, so its physical field is read directly.
                val physical = key.physicalField
                MongoSnapshotDocuments.checked(physical.path)
            }
            // A built-in attribute's index is its tenant-scoped key's, sparse (D420): an `$or` branch can use it, its
            // bounds stay in the tenant, and a stream without the attribute adds no entry.
            paths.singleOrNull { it in BUILT_IN_ACCESS }?.let { path ->
                val key = MongoEventLog.ACCESS_KEY + "." + path.substringAfter('.')
                return@map MongoQueryIndex(context, aggregate, index.name, listOf(key), collection, null, sparse = true)
            }
            MongoQueryIndex(context, aggregate, index.name, paths.distinct(), collection, streams)
        }
    }

    /**
     * Manages [indexes] as [mode] says, collection by collection; see [Ddl]. [ensure] makes sure of a
     * collection before `auto` creates an index on it, as the snapshot store would, so the collection is never
     * created without its format record.
     */
    internal fun manage(
        database: MongoDatabase,
        mode: Ddl,
        indexes: List<MongoQueryIndex>,
        ensure: (index: MongoQueryIndex) -> Unit,
    ): MongoQueryIndexReport {
        if (!mode.checks) return MongoQueryIndexReport()
        val reports = indexes.groupBy { it.collection }.map { (collection, planned) ->
            manage(database, mode, collection, planned, ensure)
        }
        return MongoQueryIndexReport(
            created = reports.flatMap { it.created },
            missing = reports.flatMap { it.missing },
            invalid = reports.flatMap { it.invalid },
        )
    }

    private fun manage(
        database: MongoDatabase,
        mode: Ddl,
        collection: String,
        indexes: List<MongoQueryIndex>,
        ensure: (index: MongoQueryIndex) -> Unit,
    ): MongoQueryIndexReport {
        val exists = database.listCollectionNames().into(ArrayList()).contains(collection)
        val existing = if (exists) {
            database.getCollection(collection).listIndexes().into(ArrayList()).associateBy { it.getString("name") }
        } else {
            emptyMap()
        }
        val missing = indexes.filter { it.name !in existing }
        val invalid = indexes.filter { index -> existing[index.name]?.let { !index.matches(it) } == true }
        if (!mode.creates) {
            // Validating reports every missing or invalid index at once: the host refuses the startup naming them.
            return MongoQueryIndexReport(missing = missing.map { it.name }, invalid = invalid.map { it.name })
        }
        if (missing.isEmpty() && invalid.isEmpty()) return MongoQueryIndexReport()
        ensure(indexes.first())
        val snapshots = database.getCollection(collection)
        invalid.forEach { snapshots.dropIndex(it.name) }
        (invalid + missing).forEach { index ->
            log.info("Creating the query index {}.", index)
            // MongoDB builds an index without blocking the snapshot writes (hybrid builds, since 4.2).
            val options = IndexOptions().name(index.name).sparse(index.sparse)
            index.partialFilter()?.let(options::partialFilterExpression)
            snapshots.createIndex(index.keyDocument(), options)
        }
        return MongoQueryIndexReport(
            created = (invalid + missing).map { it.name },
            missing = missing.map { it.name },
            invalid = invalid.map { it.name },
        )
    }

    /**
     * Whether [existing], as `listIndexes` reports it, is this index: the same keys in order, the same partial filter,
     * sparse alike.
     */
    private fun MongoQueryIndex.matches(existing: Document): Boolean {
        val key = existing.get("key", Document::class.java) ?: return false
        val directions = key.values.map { (it as? Number)?.toInt() }
        return key.keys.toList() == keys && directions.all { it == 1 } &&
            existing.get("partialFilterExpression", Document::class.java) == partialFilter() &&
            existing.getBoolean("sparse", false) == sparse
    }
}
