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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryModelSchema
import org.bson.Document
import org.slf4j.LoggerFactory

/**
 * One `x-wow-index` entry of an aggregate's state schema (decision D151): its name and its keys, logical paths. The
 * same reading as the PostgreSQL backend's, whose type lives in `:postgres` (a backend may not depend on another).
 */
data class QueryIndexDeclaration(val name: String, val keys: List<String>) {
    init {
        require(name.isNotEmpty()) { "An index needs a name." }
        require(keys.isNotEmpty()) { "Index [$name] needs at least one key." }
    }

    companion object {
        /** The keyword `manifest-tools` writes the indexes under, at the root of the state schema. */
        const val KEYWORD: String = "x-wow-index"

        /** The indexes the state of [aggregate] in [context] declares in [manifest]; none when it declares none. */
        fun of(manifest: Manifest, context: String, aggregate: String): List<QueryIndexDeclaration> {
            val state = manifest.contexts.firstOrNull { it.name == context }
                ?.aggregates?.firstOrNull { it.name == aggregate }?.state ?: return emptyList()
            val schema = manifest.defs[Manifest.schemaKey(state.schema)] as? Map<*, *> ?: return emptyList()
            val entries = schema[KEYWORD] ?: return emptyList()
            require(entries is List<*>) { "$KEYWORD of $context.$aggregate must be an array." }
            return entries.map { entry ->
                require(entry is Map<*, *>) { "An entry of $KEYWORD of $context.$aggregate must be an object." }
                val name = entry["name"]
                val keys = entry["keys"]
                require(name is String && keys is List<*> && keys.all { it is String }) {
                    "An entry of $KEYWORD of $context.$aggregate needs a name and string keys."
                }
                QueryIndexDeclaration(name, keys.map { it as String })
            }
        }
    }
}

/**
 * A planned query index of the snapshot collection of [aggregate] in [context]: its [name], unique within its
 * [collection], and its [keys] in order, each ascending, over the snapshots that are not deleted.
 */
data class MongoQueryIndex(val context: String, val aggregate: String, val name: String, val keys: List<String>) {
    /** The snapshot collection the index is on. */
    val collection: String
        get() = MongoSnapshotStore.collectionName(context, aggregate)

    /** The key document of the index: `{tenant_id: 1, <key>: 1, ...}`. */
    fun keyDocument(): Document = Document().also { document -> keys.forEach { document[it] = 1 } }

    /** The partial filter of the index: `{deleted: false}`. */
    fun partialFilter(): Document = MongoSnapshotStore.notDeleted()

    /** The `createIndexes` specification of the index, as `listIndexes` reports it. */
    override fun toString(): String =
        "$collection.$name ${keyDocument().toJson()} partialFilterExpression ${partialFilter().toJson()}"
}

/** What [MongoSnapshotQueryStorage.manageIndexes] did: the indexes it created, and the ones it found missing or different. */
data class MongoQueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * The query indexes of an aggregate type's snapshot collection (decisions D151, D161):
 *
 * ```
 * owner_ix: {tenant_id: 1, owner_id: 1}, partialFilterExpression: {deleted: false}
 * space_ix: {tenant_id: 1, space_id: 1}, partialFilterExpression: {deleted: false}
 * <name>:   {tenant_id: 1, <key>: 1, ...}, partialFilterExpression: {deleted: false}   (each x-wow-index entry)
 * ```
 *
 * Every index leads with the tenant, which admission adds to every query, and is partial on the snapshots that are not
 * deleted, its default scope: a query whose filter holds `deleted: false` can use it. A key is the document path the
 * filter, sort and aggregation compilers read for it ([MongoSnapshotDocuments]), so the planner matches the two.
 * MongoDB names indexes per collection, so a declared index keeps its own name. The store creates the two scope
 * indexes with the collection; planning them here lets `validate` report them too.
 */
object MongoQueryIndexes {
    private val log = LoggerFactory.getLogger(MongoQueryIndexes::class.java)

    /** The capabilities whose binding an index key takes its path from: those an index serves. */
    private val INDEXED = listOf(QueryCapability.SORT, QueryCapability.RANGE, QueryCapability.EXACT_MATCH)

    /** The index names the store keeps for itself. */
    private val RESERVED = setOf(MongoSnapshotStore.SNAPSHOT_KEY, MongoSnapshotDocuments.ID + "_")

    /**
     * The indexes of the snapshot collection of [aggregate] in [context]: the two scope indexes, then those of
     * [declarations], keyed by the paths of [schema], the aggregate's snapshot query model on this backend. Fails on a
     * key the model does not know or cannot index (one without an exact match, a range or a sort), on a second
     * multi-valued key (MongoDB cannot index parallel arrays) and on a name the collection already uses.
     */
    fun plan(
        context: String,
        aggregate: String,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): List<MongoQueryIndex> {
        val scope = MongoSnapshotStore.SCOPE_INDEXES.map { (name, field) ->
            MongoQueryIndex(context, aggregate, name, listOf(MongoSnapshotDocuments.TENANT_ID, field))
        }
        val names = scope.map { it.name }.toMutableSet()
        val declared = declarations.map { declaration ->
            require(declaration.name !in RESERVED && names.add(declaration.name)) {
                "Index [${declaration.name}] of $context.$aggregate reuses a name the collection already has."
            }
            val keys = declaration.keys.map { key(schema, declaration, it) }
            require(keys.count { it.second } <= 1) {
                "Index [${declaration.name}] has more than one key with many values: MongoDB cannot index parallel arrays."
            }
            val paths = (listOf(MongoSnapshotDocuments.TENANT_ID) + keys.map { it.first }).distinct()
            MongoQueryIndex(context, aggregate, declaration.name, paths)
        }
        return scope + declared
    }

    /** The document path of [key] and whether it holds many values. */
    private fun key(schema: QueryModelSchema, declaration: QueryIndexDeclaration, key: String): Pair<String, Boolean> {
        val field = requireNotNull(schema.field(QueryField(key))) {
            "Index [${declaration.name}] has the key [$key], which the query model does not know."
        }
        val binding = requireNotNull(INDEXED.firstNotNullOfOrNull(field::binding)) {
            "Index [${declaration.name}] has the key [$key], which MongoDB neither matches, ranges nor sorts."
        }
        // A schema binding, not an admitted query: no admission resolved it, so its physical field is read directly.
        val physical = binding.physicalField
        val path = MongoSnapshotDocuments.checked(physical.path)
        return path to (field.value.cardinality != QueryCardinality.SINGLE)
    }

    /**
     * Manages [indexes] as [mode] says, collection by collection; see [QueryIndexDdl]. [ensure] makes sure of a
     * collection before `auto` creates an index on it, as the snapshot store would, so the collection is never
     * created without its format record.
     */
    internal fun manage(
        database: MongoDatabase,
        mode: QueryIndexDdl,
        indexes: List<MongoQueryIndex>,
        ensure: (index: MongoQueryIndex) -> Unit,
    ): MongoQueryIndexReport {
        if (mode == QueryIndexDdl.NONE) return MongoQueryIndexReport()
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
        mode: QueryIndexDdl,
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
        if (mode == QueryIndexDdl.VALIDATE) {
            if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                log.warn(
                    "Query indexes of {} are missing {} and differ {} ({}=validate).",
                    collection,
                    missing.map { it.name },
                    invalid.map { it.name },
                    QueryIndexDdl.PROPERTY,
                )
            }
            return MongoQueryIndexReport(missing = missing.map { it.name }, invalid = invalid.map { it.name })
        }
        if (missing.isEmpty() && invalid.isEmpty()) return MongoQueryIndexReport()
        ensure(indexes.first())
        val snapshots = database.getCollection(collection)
        invalid.forEach { snapshots.dropIndex(it.name) }
        (invalid + missing).forEach { index ->
            log.info("Creating the query index {}.", index)
            // MongoDB builds an index without blocking the snapshot writes (hybrid builds, since 4.2).
            snapshots.createIndex(
                index.keyDocument(),
                IndexOptions().name(index.name).partialFilterExpression(index.partialFilter()),
            )
        }
        return MongoQueryIndexReport(
            created = (invalid + missing).map { it.name },
            missing = missing.map { it.name },
            invalid = invalid.map { it.name },
        )
    }

    /** Whether [existing], as `listIndexes` reports it, is this index: the same keys in order, the same partial filter. */
    private fun MongoQueryIndex.matches(existing: Document): Boolean {
        val key = existing.get("key", Document::class.java) ?: return false
        val directions = key.values.map { (it as? Number)?.toInt() }
        return key.keys.toList() == keys && directions.all { it == 1 } &&
            existing.get("partialFilterExpression", Document::class.java) == partialFilter()
    }
}
