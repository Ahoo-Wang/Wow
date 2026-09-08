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

package me.ahoo.wow.mongo.query.schema

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.operationValues
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import java.util.Optional

class MongoQuerySchemaAdapter(
    private val collection: MongoCollection<Document>,
    private val database: MongoDatabase? = null,
    private val model: QueryModel = QueryModel.SNAPSHOT,
) : QuerySchemaBackendAdapter {
    override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = loadFacts(logicalSchema)

    private fun loadFacts(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.defer {
        val indexes = collection.listIndexes().toFlux().collectList()
        val validator = database?.listCollections()
            ?.filter(Filters.eq("name", collection.namespace.collectionName))
            ?.toFlux()
            ?.next()
            ?.map { Optional.ofNullable(it.validatorSchema()) }
            ?.defaultIfEmpty(Optional.empty())
            ?: Mono.just(Optional.empty())
        Mono.zip(indexes, validator).map { facts ->
            bind(logicalSchema, facts.t1, facts.t2.orElse(null), model)
        }
    }.onErrorMap { error ->
        if (error is QuerySchemaUnavailableException) {
            error
        } else {
            QuerySchemaUnavailableException("Failed to resolve MongoDB query schema.", error)
        }
    }

    companion object {
        internal fun bind(
            logicalSchema: LogicalQuerySchema,
            indexes: List<Document>,
            validatorSchema: Document?,
        ): QueryModelSchema = bind(
            logicalSchema,
            indexes,
            validatorSchema,
            QueryModel.SNAPSHOT,
        )

        internal fun bind(
            logicalSchema: LogicalQuerySchema,
            indexes: List<Document>,
            validatorSchema: Document?,
            model: QueryModel,
        ): QueryModelSchema {
            val storageSchemas = validatorSchema.storageSchemas()
            val nativeArrays = storageSchemas.filterValues { facts -> facts.types?.any { it.value in ARRAY_TYPES } == true }.keys
            val nativePaths = storageSchemas.keys.map { it.logicalPath(model) }
            val paths = (
                logicalSchema.values.keys + nativePaths + nativePaths.filter { it !in logicalSchema.values }.flatMap { native ->
                    logicalSchema.values.keys.mapNotNull { logical -> logical.specialize(native) }
                }
                ).filter { it.segments.isNotEmpty() && logicalSchema.value(it) != null }.toSet()
            val bindings = paths.associateWith { path ->
                val value = checkNotNull(logicalSchema.value(path))
                val physical = path.physicalPath(model)
                val storage = storageSchemas.storageAt(physical)
                val invalidAncestor = (1..path.segments.size).any { size ->
                    val ancestor = QueryPathTemplate(path.segments.take(size))
                    val logical = logicalSchema.value(ancestor)
                    logical != null && !logical.containerSupported(storageSchemas.storageAt(ancestor.physicalPath(model)))
                }
                if (invalidAncestor) return@associateWith QueryValueBindings()
                val arrayAncestor = path.segments.any { it == QueryPathSegment.Item } || value.hasArray() ||
                    nativeArrays.any { it.isPhysicalAncestorOf(physical) }
                val native = QueryFieldBindingTemplate(physical, storage?.types?.takeIf { it.isNotEmpty() })
                QueryValueBindings(
                    bindings = FIELD_CAPABILITIES.filter { capability ->
                        value.supports(capability, physical, storageSchemas) &&
                            (capability != QueryCapability.CURSOR_SORT || !arrayAncestor)
                    }.associateWith { native },
                    projectionPath = physical,
                    responsePath = path,
                )
            }
            return QueryModelSchema(
                model,
                if (indexes.hasTextIndex()) {
                    setOf(
                        QueryCapability.FULL_TEXT_TERMS,
                        QueryCapability.FULL_TEXT_PHRASE
                    )
                } else {
                    emptySet()
                },
                logicalSchema,
                bindings,
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
                }
            )
        }

        private fun QueryPathTemplate.physicalPath(model: QueryModel): QueryPathTemplate =
            renameRoot(if (model == QueryModel.SNAPSHOT) "aggregateId" else "id", "_id")

        private fun QueryPathTemplate.logicalPath(model: QueryModel): QueryPathTemplate =
            renameRoot("_id", if (model == QueryModel.SNAPSHOT) "aggregateId" else "id")

        private fun QueryPathTemplate.renameRoot(from: String, to: String): QueryPathTemplate = QueryPathTemplate(
            segments.mapIndexed { index, segment ->
                if (index == 0 && segment == QueryPathSegment.Property(from)) QueryPathSegment.Property(to) else segment
            },
        )

        private fun QueryPathTemplate.isPhysicalAncestorOf(other: QueryPathTemplate): Boolean {
            val native = segments.filter { it != QueryPathSegment.Item }
            val logical = other.segments.filter { it != QueryPathSegment.Item }
            return native.size <= logical.size && native.zip(logical).all { (left, right) ->
                left == right || left is QueryPathSegment.Key && (right is QueryPathSegment.Property || right is QueryPathSegment.Key)
            }
        }

        private fun QueryValueSchema.hasArray(): Boolean = kind == QueryValueKind.ARRAY ||
            kind == QueryValueKind.UNION && alternatives.any { it.hasArray() }

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

        @Suppress(
            "CyclomaticComplexMethod"
        ) // One native capability gate preserves shape, representation and union checks.
        private fun QueryValueSchema.supports(
            capability: QueryCapability,
            path: QueryPathTemplate,
            native: Map<QueryPathTemplate, MongoStorageSchema>,
        ): Boolean {
            if (capability == QueryCapability.PRESENCE) return true
            if (capability == QueryCapability.AGGREGATE_TEMPORAL &&
                operationValues().filter { it.kind != QueryValueKind.NULL }.map { it.semanticType }
                    .distinct().singleOrNull() == null
            ) {
                return false
            }
            if (kind == QueryValueKind.UNION) return alternatives.supportsUnion(capability, path, native)

            val storage = native.storageAt(path)
            if (storage?.uncertain == true) return false
            if (kind == QueryValueKind.ARRAY) return supportsArray(capability, path, native)
            if (kind != QueryValueKind.SCALAR) return false
            if (capability == QueryCapability.ELEMENT_SCOPE) return false
            if (semanticType == Temporal.Date && capability == QueryCapability.AGGREGATE_TEMPORAL && storage?.types == null) return false
            val requirements = storageRequirements(capability)
            if (requirements.isEmpty() || requirements.any { it.isEmpty() }) return false
            if (!storage?.types.proves(requirements)) return false
            return capability != QueryCapability.CURSOR_SORT || supportsCursorSort(storage?.types, requirements)
        }

        private fun QueryValueSchema.supportsArray(
            capability: QueryCapability,
            path: QueryPathTemplate,
            native: Map<QueryPathTemplate, MongoStorageSchema>,
        ): Boolean {
            if (!native.storageAt(path)?.types.proves(listOf(ARRAY_TYPES))) return false
            val item = checkNotNull(items)
            val itemPath = QueryPathTemplate(path.segments + QueryPathSegment.Item)
            if (capability == QueryCapability.ELEMENT_SCOPE) {
                return item.kind == QueryValueKind.OBJECT && item.containerSupported(native.storageAt(itemPath))
            }
            if (capability == QueryCapability.CURSOR_SORT || item.kind == QueryValueKind.ARRAY) return false
            return item.supports(capability, itemPath, native)
        }

        private fun supportsCursorSort(types: Set<QueryStorageType>?, requirements: List<Set<String>>): Boolean {
            if (types != null) {
                return types.isNotEmpty() && CURSOR_STORAGE_FAMILIES.any { family ->
                    types.all {
                        it.value in family
                    }
                }
            }
            return CURSOR_STORAGE_FAMILIES.any { family -> requirements.all { it.all(family::contains) } }
        }

        private fun QueryValueSchema.nonNullBranches(): List<QueryValueSchema> = when (kind) {
            QueryValueKind.NULL -> emptyList()
            QueryValueKind.UNION -> alternatives.flatMap { it.nonNullBranches() }
            else -> listOf(this)
        }

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
                    return values.all {
                        it.supports(
                            capability,
                            path,
                            native
                        )
                    }
                }
                val scalars = values - arrays.toSet()
                val rootRequirements = listOf(ARRAY_TYPES) + scalars.flatMap { it.storageRequirements(capability) }
                if (!storage?.types.proves(rootRequirements)) return false
                if (scalars.isNotEmpty()) {
                    val scalarStorage = storage?.copy(
                        types = storage.types?.filterTo(linkedSetOf()) { it.value !in ARRAY_TYPES }
                    )
                    if (!scalars.supportsUnion(
                            capability,
                            path,
                            if (scalarStorage == null) native else native + (path to scalarStorage)
                        )
                    ) {
                        return false
                    }
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

        private fun QueryValueSchema.storageRequirements(capability: QueryCapability): List<Set<String>> {
            if (semanticType == Temporal.Date && capability in DATE_OPERAND_CAPABILITIES) return emptyList()
            return when (capability) {
                QueryCapability.EXACT_MATCH, QueryCapability.SORT, QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS ->
                    temporalRequirements().ifEmpty { valueTypes.map { it.storageTypes() } }
                QueryCapability.LITERAL_MATCH -> if (semanticType is Temporal.Epoch) {
                    emptyList()
                } else {
                    valueTypes.map { if (it == QueryValueType.STRING) STRING_TYPES else emptySet() }
                }
                QueryCapability.RANGE -> temporalRequirements().ifEmpty {
                    valueTypes.map { if (it == QueryValueType.STRING) STRING_TYPES else it.numericTypes() }
                }
                QueryCapability.AGGREGATE_NUMERIC -> valueTypes.map { it.numericTypes() }
                QueryCapability.AGGREGATE_TEMPORAL -> temporalRequirements()
                else -> emptyList()
            }
        }

        private fun QueryValueType.numericTypes(): Set<String> = when (this) {
            QueryValueType.INTEGER -> INTEGRAL_TYPES
            QueryValueType.DECIMAL -> NUMERIC_TYPES
            else -> emptySet()
        }

        private fun QueryValueSchema.temporalRequirements(): List<Set<String>> = when (semanticType) {
            Temporal.Date -> listOf(DATE_TYPES)
            is Temporal.Epoch -> listOf(INTEGRAL_TYPES)
            else -> emptyList()
        }

        private fun QueryValueType.storageTypes(): Set<String> = when (this) {
            QueryValueType.STRING -> STRING_TYPES
            QueryValueType.BOOLEAN -> BOOLEAN_TYPES
            QueryValueType.INTEGER -> INTEGRAL_TYPES
            QueryValueType.DECIMAL -> NUMERIC_TYPES
            else -> emptySet()
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

        private val DATE_OPERAND_CAPABILITIES = setOf(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        )

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
