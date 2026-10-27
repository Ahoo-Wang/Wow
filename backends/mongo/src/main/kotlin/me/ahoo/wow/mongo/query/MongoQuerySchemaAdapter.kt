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

import com.mongodb.MongoException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryStorageFamilyRules
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.storageFamilies
import org.bson.Document

/**
 * The storage adapter of one aggregate type's snapshot collection, the 9.x adapter: it reads the
 * collection's `listIndexes()` and its `$jsonSchema` validator, and compiles what they prove ([facts]). A collection
 * that does not exist yet, before the aggregate type's first snapshot, yields provisional facts of the collection it
 * will be created as, so the Catalog asks again on the next load.
 */
internal class MongoQuerySchemaAdapter internal constructor(
    private val database: MongoDatabase,
    private val collection: String,
    private val dispatcher: CoroutineDispatcher,
) : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = withContext(dispatcher) {
        val (info, indexes) = try {
            val info = database.listCollections().filter(Filters.eq("name", collection)).first()
            val indexes = if (info == null) {
                emptyList()
            } else {
                database.getCollection(
                    collection
                ).listIndexes().into(ArrayList())
            }
            info to indexes
        } catch (error: MongoException) {
            throw QuerySchemaUnavailableException("Failed to read the MongoDB snapshot collection $collection.", error)
        }
        facts(logicalSchema, indexes, info?.validatorSchema(), provisional = info == null)
    }

    companion object {
        /**
         * MongoDB cannot sort by two arrays on independent paths ("cannot sort with keys that are parallel arrays").
         * TOTAL, RUNNING and RANK run natively with `$setWindowFields`; PREVIOUS needs the core's shifted second read.
         * HAVING, top-N, dense fill (`$densify`), percentiles, distinct counts, FIRST / LAST and root groups run in the
         * pipeline.
         */
        val SUPPORT: StorageSupport = StorageSupport(
            aggregation = AggregationSupport(
                windows = mapOf(
                    WindowFunction.TOTAL to SupportMode.NATIVE,
                    WindowFunction.RUNNING to SupportMode.NATIVE,
                    WindowFunction.RANK to SupportMode.NATIVE,
                    WindowFunction.PREVIOUS to SupportMode.RESIDUAL,
                ),
            ),
            parallelArraySort = SupportMode.NONE,
        )

        /**
         * MongoDB reads each declared value type on its own ([QueryStorageFamilyRules.strictValueTypes] off). A date
         * string is no operand ([QueryStorageFamilyRules.dateOperands] off): the snapshot document stores it as text
         * ([me.ahoo.wow.mongo.ExactBson]), and ISO-8601 text does not order as the instants it names (its fraction
         * and offset vary), so a [Temporal.Date] value only exists, as on PostgreSQL. An epoch is a number and
         * supports everything.
         */
        val RULES: QueryStorageFamilyRules = QueryStorageFamilyRules(dateOperands = false, strictValueTypes = false)

        /**
         * What the collection's indexes and validator prove about [logicalSchema]: per path, the capabilities its
         * BSON types can execute (the Catalog applies the storage-independent rules), and model-wide text search
         * where a text index covers the collection. Every logical path binds to its document path
         * ([MongoQueryDocuments.physical] of [documents], a snapshot's by default); a record names it by its logical
         * path.
         */
        internal fun facts(
            logicalSchema: LogicalQuerySchema,
            indexes: List<Document>,
            validatorSchema: Document?,
            provisional: Boolean = false,
            documents: MongoQueryDocuments = MongoSnapshotDocuments,
        ): QueryStorageFacts {
            val storageSchemas = documents.systemTypes.entries.associate { (field, types) ->
                QueryPathTemplate(listOf(QueryPathSegment.Property(field))) to
                    MongoStorageSchema(types.mapTo(linkedSetOf(), ::QueryStorageType))
            }.toMutableMap()
            validatorSchema.storageSchemas().forEach { (path, schema) ->
                storageSchemas.merge(path, schema, MongoStorageSchema::intersect)
            }
            val nativeArrays = storageSchemas.filterValues { facts ->
                facts.types?.any { it.value in ARRAY_TYPES } == true
            }.keys
            val nativePaths = storageSchemas.keys.map(documents::logical)
            val paths = (
                logicalSchema.values.keys + nativePaths +
                    nativePaths.filter { it !in logicalSchema.values }.flatMap { native ->
                        logicalSchema.values.keys.mapNotNull { logical -> logical.specialize(native) }
                    }
                ).filter { it.segments.isNotEmpty() && logicalSchema.value(it) != null }.toSet()
            val bindings = paths.associateWith { path ->
                val value = checkNotNull(logicalSchema.value(path))
                val physical = documents.physical(path)
                val storage = storageSchemas.storageAt(physical)
                val invalidAncestor = (1..path.segments.size).any { size ->
                    val ancestor = QueryPathTemplate(path.segments.take(size))
                    val logical = logicalSchema.value(ancestor)
                    logical != null &&
                        !logical.containerSupported(storageSchemas.storageAt(documents.physical(ancestor)))
                }
                if (invalidAncestor) return@associateWith QueryValueBindings()
                // A validator may declare an array the logical model does not know of (the Catalog rules out logical ones).
                val nativeArrayAncestor = nativeArrays.any { it.isPhysicalAncestorOf(physical) }
                // Pending 6.1c (#4344): set the binding's `indexed` fact from `indexes` once the core has it on v10.
                val native = QueryFieldBindingTemplate(physical, storage?.types?.takeIf { it.isNotEmpty() })
                QueryValueBindings(
                    bindings = FIELD_CAPABILITIES.filter { capability ->
                        value.supports(capability, physical, storageSchemas) &&
                            (capability != QueryCapability.CURSOR_SORT || !nativeArrayAncestor)
                    }.associateWith { native },
                    projectionPath = physical,
                    responsePath = path,
                )
            }
            return QueryStorageFacts(
                bindings = bindings,
                capabilities = if (indexes.hasTextIndex()) {
                    setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
                } else {
                    emptySet()
                },
                // `$percentile` runs with method "approximate"; distinct counts are exact set sizes.
                approximateMetrics = setOf("PERCENTILE"),
                storage = SUPPORT,
                provisional = provisional,
            )
        }

        private fun QueryPathTemplate.specialize(prefix: QueryPathTemplate): QueryPathTemplate? {
            if (prefix.segments.size > segments.size) return null
            val compatible = segments.zip(prefix.segments).all { (logical, native) ->
                if (logical is QueryPathSegment.Key) native != QueryPathSegment.Item else logical == native
            }
            if (!compatible) return null
            var slot = 0
            return QueryPathTemplate(
                (prefix.segments + segments.drop(prefix.segments.size)).map {
                    if (it is QueryPathSegment.Key) QueryPathSegment.Key(slot++) else it
                },
            )
        }

        private fun QueryPathTemplate.isPhysicalAncestorOf(other: QueryPathTemplate): Boolean {
            val native = segments.filter { it != QueryPathSegment.Item }
            val logical = other.segments.filter { it != QueryPathSegment.Item }
            return native.size <= logical.size && native.zip(logical).all { (left, right) ->
                left == right ||
                    left is QueryPathSegment.Key && (right is QueryPathSegment.Property || right is QueryPathSegment.Key)
            }
        }

        private fun QueryValueSchema.containerSupported(storage: MongoStorageSchema?): Boolean = when {
            storage?.uncertain == true && (kind == QueryValueKind.OBJECT || kind == QueryValueKind.ARRAY) -> false
            else -> when (kind) {
                QueryValueKind.OBJECT -> storage?.types.proves(listOf(OBJECT_TYPES))
                QueryValueKind.ARRAY -> storage?.types.proves(listOf(ARRAY_TYPES))
                QueryValueKind.UNION -> {
                    val types = alternatives.filter { it.kind != QueryValueKind.NULL }.mapNotNull {
                        when (it.kind) {
                            QueryValueKind.OBJECT -> OBJECT_TYPES
                            QueryValueKind.ARRAY -> ARRAY_TYPES
                            QueryValueKind.SCALAR -> it.storageRequirements(QueryCapability.SORT).flatten().toSet()
                            else -> null
                        }
                    }
                    types.isEmpty() || storage?.types.proves(types)
                }
                else -> true
            }
        }

        // One native capability gate preserves shape, representation and union checks.
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        private fun QueryValueSchema.supports(
            capability: QueryCapability,
            path: QueryPathTemplate,
            native: Map<QueryPathTemplate, MongoStorageSchema>,
        ): Boolean {
            if (capability == QueryCapability.PRESENCE) return true
            if (kind == QueryValueKind.UNION) return alternatives.supportsUnion(capability, path, native)

            val storage = native.storageAt(path)
            if (storage?.uncertain == true) return false
            if (kind == QueryValueKind.ARRAY) return supportsArray(capability, path, native)
            if (kind != QueryValueKind.SCALAR) return false
            if (capability == QueryCapability.ELEMENT_SCOPE) return false
            // A date string is stored as text: it only exists (see RULES).
            if (semanticType == Temporal.Date) return false
            val requirements = storageRequirements(capability)
            if (requirements.isEmpty() || requirements.any { it.isEmpty() }) return false
            if (!storage?.types.proves(requirements)) return false
            return capability != QueryCapability.CURSOR_SORT || supportsCursorSort(storage?.types, requirements)
        }

        @Suppress("ReturnCount")
        private fun QueryValueSchema.supportsArray(
            capability: QueryCapability,
            path: QueryPathTemplate,
            native: Map<QueryPathTemplate, MongoStorageSchema>,
        ): Boolean {
            if (!native.storageAt(path)?.types.proves(listOf(ARRAY_TYPES))) return false
            val item = checkNotNull(items)
            val itemPath = QueryPathTemplate(path.segments + QueryPathSegment.Item)
            if (capability == QueryCapability.ELEMENT_SCOPE) {
                // `$elemMatch` reads any item; storage need only not contradict object items. Whether the logical
                // items are objects (a union of objects included) is the Catalog's rule (QueryStorageFacts.compile).
                val itemStorage = native.storageAt(itemPath)
                return itemStorage?.uncertain != true && itemStorage?.types.proves(listOf(OBJECT_TYPES))
            }
            // Whether a logical array may order a cursor is the Catalog's rule (QueryStorageFacts.compile).
            if (item.kind == QueryValueKind.ARRAY) return false
            return item.supports(capability, itemPath, native)
        }

        private fun supportsCursorSort(types: Set<QueryStorageType>?, requirements: List<Set<String>>): Boolean {
            if (types != null) {
                return types.isNotEmpty() && CURSOR_STORAGE_FAMILIES.any { family -> types.all { it.value in family } }
            }
            return CURSOR_STORAGE_FAMILIES.any { family -> requirements.all { it.all(family::contains) } }
        }

        private fun QueryValueSchema.nonNullBranches(): List<QueryValueSchema> = when (kind) {
            QueryValueKind.NULL -> emptyList()
            QueryValueKind.UNION -> alternatives.flatMap { it.nonNullBranches() }
            else -> listOf(this)
        }

        @Suppress("ReturnCount")
        private fun List<QueryValueSchema>.supportsUnion(
            capability: QueryCapability,
            path: QueryPathTemplate,
            native: Map<QueryPathTemplate, MongoStorageSchema>,
        ): Boolean {
            val values = flatMap { it.nonNullBranches() }
            val declarationFacts = if (capability == QueryCapability.AGGREGATE_TEMPORAL) native else emptyMap()
            if (values.isEmpty() || !values.all { it.supports(capability, path, declarationFacts) }) return false
            val storage = native.storageAt(path)
            if (storage?.uncertain == true) return false
            val arrays = values.filter { it.kind == QueryValueKind.ARRAY }
            if (arrays.isNotEmpty()) {
                if (capability == QueryCapability.ELEMENT_SCOPE) {
                    return values.all { it.supports(capability, path, native) }
                }
                val scalars = values - arrays.toSet()
                val rootRequirements = listOf(ARRAY_TYPES) + scalars.flatMap { it.storageRequirements(capability) }
                if (!storage?.types.proves(rootRequirements)) return false
                if (scalars.isNotEmpty()) {
                    val scalarStorage = storage?.copy(
                        types = storage.types?.filterTo(linkedSetOf()) { it.value !in ARRAY_TYPES },
                    )
                    val scalarFacts = if (scalarStorage == null) native else native + (path to scalarStorage)
                    if (!scalars.supportsUnion(capability, path, scalarFacts)) return false
                }
                return arrays.map { checkNotNull(it.items) }.supportsUnion(
                    capability,
                    QueryPathTemplate(path.segments + QueryPathSegment.Item),
                    native,
                )
            }
            val requirements = values.flatMap { it.storageRequirements(capability) }
            if (!storage?.types.proves(requirements)) return false
            return capability != QueryCapability.CURSOR_SORT || supportsCursorSort(storage?.types, requirements)
        }

        /** The BSON types [capability] needs: the Catalog's families ([storageFamilies]), each as its BSON types. */
        internal fun QueryValueSchema.storageRequirements(capability: QueryCapability): List<Set<String>> =
            storageFamilies(capability, RULES).map { families -> families.flatMapTo(linkedSetOf()) { it.bsonTypes() } }

        /** The BSON types of [this] family. */
        internal fun QueryStorageFamily.bsonTypes(): Set<String> = when (this) {
            QueryStorageFamily.STRING, QueryStorageFamily.EXACT_STRING -> STRING_TYPES
            QueryStorageFamily.INTEGRAL, QueryStorageFamily.SIGNED_INTEGRAL -> INTEGRAL_TYPES
            QueryStorageFamily.NUMERIC -> NUMERIC_TYPES
            QueryStorageFamily.BOOLEAN -> BOOLEAN_TYPES
            QueryStorageFamily.DATE -> DATE_TYPES
        }

        private fun List<Document>.hasTextIndex(): Boolean = any { index ->
            index["hidden"] != true && !index.containsKey("partialFilterExpression") &&
                (index["key"] as? Document)?.values?.any { it == "text" } == true
        }

        private fun Set<QueryStorageType>?.proves(requirements: List<Set<String>>): Boolean {
            if (this == null) return true
            if (isEmpty() || requirements.isEmpty()) return false
            return all { physical -> requirements.any { physical.value in it } } &&
                requirements.all { expected -> any { physical -> physical.value in expected } }
        }

        private val FIELD_CAPABILITIES = setOf(
            QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH, QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE, QueryCapability.SORT, QueryCapability.CURSOR_SORT,
            QueryCapability.ELEMENT_SCOPE, QueryCapability.AGGREGATE_TERMS,
            QueryCapability.AGGREGATE_NUMERIC, QueryCapability.AGGREGATE_TEMPORAL,
        )

        private val CURSOR_STORAGE_FAMILIES = listOf(
            NUMERIC_TYPES,
            STRING_TYPES,
            BOOLEAN_TYPES,
            setOf("date"),
            setOf("timestamp"),
        )
    }
}
