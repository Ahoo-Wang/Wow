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
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.snapshot.SnapshotFieldConverter
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import java.util.Optional

class MongoQuerySchemaAdapter(
    private val collection: MongoCollection<Document>,
    private val database: MongoDatabase? = null,
) : QuerySchemaBackendAdapter {
    override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = loadFacts(logicalSchema)

    override fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = loadFacts(logicalSchema)

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
            bind(logicalSchema, facts.t1, facts.t2.orElse(null))
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
        ): QueryModelSchema {
            val storageSchemas = validatorSchema.storageSchemas()
            val capabilities = if (indexes.hasTextIndex()) {
                setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
            } else {
                emptySet()
            }
            val invalidContainers = logicalSchema.fields.mapNotNullTo(linkedSetOf()) { (logicalField, fieldSchema) ->
                val storageSchema = storageSchemas[SnapshotFieldConverter.convert(logicalField.value)]
                logicalField.value.takeIf { fieldSchema.invalidContainer(storageSchema) }
            }
            return QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = capabilities,
                fields = logicalSchema.fields.mapValues { (logicalField, logicalSchema) ->
                    val physicalPath = SnapshotFieldConverter.convert(logicalField.value)
                    val storageSchema = storageSchemas[physicalPath]
                    val binding = QueryFieldBinding(physicalPath, storageSchema?.types?.singleOrNull())
                    if (invalidContainers.any { logicalField.value == it || logicalField.value.startsWith("$it.") }) {
                        return@mapValues logicalSchema.toFieldSchema(projectionPath = null, bindings = emptyMap())
                    }
                    logicalSchema.toFieldSchema(
                        physicalPath,
                        logicalSchema.capabilities()
                            .filter { logicalSchema.supports(it, storageSchema) }
                            .associateWith { binding },
                    )
                },
            )
        }

        private fun LogicalQueryFieldSchema.invalidContainer(storageSchema: MongoStorageSchema?): Boolean {
            if (QueryValueType.OBJECT !in valueTypes || storageSchema == null) return false
            return if (cardinality == QueryCardinality.MANY) {
                !storageSchema.types.proves(listOf(ARRAY_TYPES)) ||
                    !storageSchema.itemTypes.proves(listOf(OBJECT_TYPES))
            } else {
                !storageSchema.types.proves(listOf(OBJECT_TYPES))
            }
        }

        private fun LogicalQueryFieldSchema.capabilities(): Set<QueryCapability> = buildSet {
            add(QueryCapability.PRESENCE)
            if (valueTypes.isEmpty()) return@buildSet
            val scalar = QueryValueType.OBJECT !in valueTypes
            if (scalar || dynamicChildren) {
                add(QueryCapability.EXACT_MATCH)
            }
            if (scalar) {
                add(QueryCapability.SORT)
                add(QueryCapability.AGGREGATE_TERMS)
            }
            if (QueryValueType.STRING in valueTypes) {
                add(QueryCapability.LITERAL_MATCH)
                add(QueryCapability.RANGE)
            }
            if (QueryValueType.INTEGER in valueTypes || QueryValueType.DECIMAL in valueTypes) {
                add(QueryCapability.RANGE)
                add(QueryCapability.AGGREGATE_NUMERIC)
            }
            if (semanticType is Temporal.Date || semanticType is Temporal.Epoch) {
                add(QueryCapability.RANGE)
                add(QueryCapability.AGGREGATE_TEMPORAL)
            }
            if (cardinality == QueryCardinality.MANY && QueryValueType.OBJECT in valueTypes) {
                add(QueryCapability.ELEMENT_SCOPE)
            }
        }

        private fun LogicalQueryFieldSchema.supports(
            capability: QueryCapability,
            storageSchema: MongoStorageSchema?,
        ): Boolean {
            if (capability == QueryCapability.PRESENCE) return true
            if (storageSchema == null) {
                return semanticType != Temporal.Date ||
                    capability != QueryCapability.RANGE && capability != QueryCapability.AGGREGATE_TEMPORAL
            }
            val requirements = storageRequirements(capability)
            val storageTypes = if (cardinality == QueryCardinality.MANY) {
                if (!storageSchema.types.proves(listOf(ARRAY_TYPES))) return false
                storageSchema.itemTypes
            } else {
                storageSchema.types
            }
            return storageTypes.proves(requirements)
        }

        private fun LogicalQueryFieldSchema.storageRequirements(capability: QueryCapability): List<Set<String>> =
            when (capability) {
                QueryCapability.LITERAL_MATCH -> when (semanticType) {
                    Temporal.Date,
                    is Temporal.Epoch,
                    -> emptyList()
                    else -> valueTypes.filter { it == QueryValueType.STRING }.map { STRING_TYPES }
                }
                QueryCapability.EXACT_MATCH -> if (semanticType == Temporal.Date) {
                    emptyList()
                } else {
                    temporalRequirements().ifEmpty { valueTypes.map { it.storageTypes() } }
                }
                QueryCapability.RANGE -> rangeRequirements()
                QueryCapability.AGGREGATE_NUMERIC -> numericRequirements()
                QueryCapability.AGGREGATE_TEMPORAL -> temporalRequirements()
                QueryCapability.ELEMENT_SCOPE -> valueTypes.filter { it == QueryValueType.OBJECT }.map { OBJECT_TYPES }
                else -> temporalRequirements().ifEmpty { valueTypes.map { it.storageTypes() } }
            }

        private fun LogicalQueryFieldSchema.numericRequirements(): List<Set<String>> = when (semanticType) {
            Temporal.Date -> emptyList()
            is Temporal.Epoch -> listOf(INTEGRAL_TYPES)
            else -> valueTypes.mapNotNull {
                when (it) {
                    QueryValueType.INTEGER -> INTEGRAL_TYPES
                    QueryValueType.DECIMAL -> NUMERIC_TYPES
                    else -> null
                }
            }
        }

        private fun LogicalQueryFieldSchema.rangeRequirements(): List<Set<String>> = when (semanticType) {
            Temporal.Date -> emptyList()
            is Temporal.Formatted -> {
                if (valueTypes == setOf(QueryValueType.STRING)) listOf(STRING_TYPES) else emptyList()
            }
            else -> temporalRequirements().ifEmpty {
                numericRequirements().ifEmpty {
                    valueTypes.filter { it == QueryValueType.STRING }.map { STRING_TYPES }
                }
            }
        }

        private fun LogicalQueryFieldSchema.temporalRequirements(): List<Set<String>> = when (semanticType) {
            Temporal.Date -> listOf(DATE_TYPES)
            is Temporal.Epoch -> listOf(INTEGRAL_TYPES)
            else -> emptyList()
        }

        private fun QueryValueType.storageTypes(): Set<String> = when (this) {
            QueryValueType.STRING -> STRING_TYPES
            QueryValueType.BOOLEAN -> BOOLEAN_TYPES
            QueryValueType.OBJECT -> OBJECT_TYPES
            QueryValueType.INTEGER -> INTEGRAL_TYPES
            QueryValueType.DECIMAL -> NUMERIC_TYPES
            else -> emptySet()
        }

        private fun LogicalQueryFieldSchema.toFieldSchema(
            projectionPath: String?,
            bindings: Map<QueryCapability, QueryFieldBinding>,
        ) = QueryFieldSchema(
            title = title,
            description = description,
            enumValues = enumValues,
            valueTypes = valueTypes,
            nullable = nullable,
            required = required,
            cardinality = cardinality,
            semanticType = semanticType,
            dynamicChildren = dynamicChildren && bindings.keys.any { it != QueryCapability.ELEMENT_SCOPE },
            bindings = bindings,
            projectionPath = projectionPath,
        )

        private fun List<Document>.hasTextIndex(): Boolean = any { index ->
            index["hidden"] != true && !index.containsKey("partialFilterExpression") &&
                (index["key"] as? Document)?.values?.any { it == "text" } == true
        }

        private fun Document?.storageSchemas(): Map<String, MongoStorageSchema> {
            if (this == null) return emptyMap()
            return collectStorageSchemas(path = null, includeType = false)
        }

        private fun Document.collectStorageSchemas(
            path: String?,
            includeType: Boolean,
        ): Map<String, MongoStorageSchema> = buildMap {
            if (includeType && path != null) {
                val types = storageTypes()
                val itemTypes = itemStorageTypes()
                if (types != null || itemTypes != null) {
                    put(path, MongoStorageSchema(types, itemTypes))
                }
            }
            document("properties")?.forEach { (name, child) ->
                (child as? Document)?.let {
                    mergeConjunctive(it.collectStorageSchemas(path.child(name), includeType = true))
                }
            }
            document("items")?.let {
                mergeConjunctive(it.collectStorageSchemas(path, includeType = false))
            }
            listOf("anyOf", "oneOf").forEach { key ->
                mergeConjunctive(compositionSchemas(key).mergeAlternatives(path))
            }
            compositionSchemas("allOf").forEach {
                mergeConjunctive(it.collectStorageSchemas(path, includeType = false))
            }
        }

        private fun Document.compositionSchemas(key: String): List<Document> =
            (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()

        private fun List<Document>.mergeAlternatives(path: String?): Map<String, MongoStorageSchema> =
            buildMap {
                val alternatives = this@mergeAlternatives
                    .filter { it.canContainChildren() }
                    .map { it.collectStorageSchemas(path, includeType = false) }
                alternatives.flatMapTo(linkedSetOf()) { it.keys }.forEach { field ->
                    val storage = alternatives.map { it[field] }
                    put(
                        field,
                        if (storage.any { it == null }) {
                            MongoStorageSchema(types = null, itemTypes = null)
                        } else {
                            storage.filterNotNull().reduce(MongoStorageSchema::union)
                        },
                    )
                }
            }

        private fun Document.canContainChildren(): Boolean = storageTypes()?.any {
            it.value in OBJECT_TYPES || it.value in ARRAY_TYPES
        } != false

        private fun MutableMap<String, MongoStorageSchema>.mergeConjunctive(
            other: Map<String, MongoStorageSchema>,
        ) {
            other.forEach { (field, storage) -> merge(field, storage, MongoStorageSchema::intersect) }
        }

        private fun Document.storageTypes(): Set<QueryStorageType>? {
            val constraints = buildList {
                directStorageTypes()?.let(::add)
                listOf("anyOf", "oneOf").forEach { key ->
                    unionStorageTypes(key)?.let(::add)
                }
                intersectionStorageTypes("allOf")?.let(::add)
            }
            return constraints.reduceOrNull(Set<QueryStorageType>::intersect)
        }

        private fun Document.directStorageTypes(): Set<QueryStorageType>? =
            when (val declared = this["bsonType"]) {
                is String -> setOfNotNull(declared.takeUnless { it == "null" }?.let(::QueryStorageType))
                is Iterable<*> -> declared.filterIsInstance<String>()
                    .filter { it != "null" }
                    .mapTo(linkedSetOf(), ::QueryStorageType)
                else -> null
            }

        private fun Document.unionStorageTypes(key: String): Set<QueryStorageType>? {
            if (!containsKey(key)) return null
            val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
            if (schemas.isEmpty()) return emptySet()
            val types = schemas.map { it.storageTypes() }
            if (types.any { it == null }) return emptySet()
            return types.filterNotNull().flatten().toSet()
        }

        private fun Document.intersectionStorageTypes(key: String): Set<QueryStorageType>? {
            if (!containsKey(key)) return null
            val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
            if (schemas.isEmpty()) return emptySet()
            return schemas.mapNotNull { it.storageTypes() }
                .reduceOrNull(Set<QueryStorageType>::intersect)
        }

        private fun Document.itemStorageTypes(): Set<QueryStorageType>? {
            val constraints = buildList {
                document("items")?.storageTypes()?.let(::add)
                listOf("anyOf", "oneOf").forEach { key ->
                    unionItemStorageTypes(key)?.let(::add)
                }
                intersectionItemStorageTypes("allOf")?.let(::add)
            }
            return constraints.reduceOrNull(Set<QueryStorageType>::intersect)
        }

        private fun Document.unionItemStorageTypes(key: String): Set<QueryStorageType>? {
            if (!containsKey(key)) return null
            val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
            val types = mutableListOf<Set<QueryStorageType>>()
            for (schema in schemas) {
                val storageTypes = schema.storageTypes()
                if (storageTypes == null || storageTypes.any { it.value == "array" }) {
                    types += schema.itemStorageTypes() ?: return null
                }
            }
            return types.takeIf { it.isNotEmpty() }?.flatten()?.toSet()
        }

        private fun Document.intersectionItemStorageTypes(key: String): Set<QueryStorageType>? {
            if (!containsKey(key)) return null
            val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
            return schemas.mapNotNull { it.itemStorageTypes() }
                .reduceOrNull(Set<QueryStorageType>::intersect)
        }

        private fun Set<QueryStorageType>?.proves(requirements: List<Set<String>>): Boolean {
            if (this == null) return true
            if (isEmpty() || requirements.isEmpty()) return false
            return all { physical -> requirements.any { physical.value in it } } &&
                requirements.all { expected -> any { physical -> physical.value in expected } }
        }

        private fun String?.child(name: String): String = if (this == null) name else "$this.$name"

        private fun Document.document(key: String): Document? = this[key] as? Document

        private fun Document.validatorSchema(): Document? = document("options")
            ?.document("validator")
            ?.document("\$jsonSchema")

        private data class MongoStorageSchema(
            val types: Set<QueryStorageType>?,
            val itemTypes: Set<QueryStorageType>?,
        ) {
            fun union(other: MongoStorageSchema) = MongoStorageSchema(
                types = types.unionConstraint(other.types),
                itemTypes = itemTypes.unionConstraint(other.itemTypes),
            )

            fun intersect(other: MongoStorageSchema) = MongoStorageSchema(
                types = types.intersectConstraint(other.types),
                itemTypes = itemTypes.intersectConstraint(other.itemTypes),
            )
        }

        private fun <T> Set<T>?.unionConstraint(other: Set<T>?): Set<T>? = when {
            this == null || other == null -> null
            else -> this + other
        }

        private fun <T> Set<T>?.intersectConstraint(other: Set<T>?): Set<T>? = when {
            this == null -> other
            other == null -> this
            else -> intersect(other)
        }

        private val INTEGRAL_TYPES = setOf("int", "long")
        private val NUMERIC_TYPES = INTEGRAL_TYPES + setOf("double", "decimal", "number")
        private val DATE_TYPES = setOf("date", "timestamp")
        private val STRING_TYPES = setOf("string")
        private val BOOLEAN_TYPES = setOf("bool")
        private val OBJECT_TYPES = setOf("object")
        private val ARRAY_TYPES = setOf("array")
    }
}
