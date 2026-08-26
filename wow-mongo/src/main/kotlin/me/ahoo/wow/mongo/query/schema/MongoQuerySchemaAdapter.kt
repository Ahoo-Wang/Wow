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
        val validator = database?.listCollections()?.toFlux()
            ?.filter { it.getString("name") == collection.namespace.collectionName }
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
            return QueryModelSchema(
                model = QueryModel.SNAPSHOT,
                capabilities = capabilities,
                fields = logicalSchema.fields.mapValues { (logicalField, logicalSchema) ->
                    val physicalPath = SnapshotFieldConverter.convert(logicalField.value)
                    val storageSchema = storageSchemas[physicalPath]
                    val binding = QueryFieldBinding(physicalPath, storageSchema?.types?.singleOrNull())
                    logicalSchema.toFieldSchema(
                        logicalSchema.capabilities()
                            .filter { logicalSchema.supports(it, storageSchema) }
                            .associateWith { binding },
                    )
                },
            )
        }

        private fun LogicalQueryFieldSchema.capabilities(): Set<QueryCapability> = buildSet {
            add(QueryCapability.PRESENCE)
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
            if (capability == QueryCapability.PRESENCE || storageSchema == null) return true
            val valueTypes = if (cardinality == QueryCardinality.MANY) {
                if (!storageSchema.types.allKnown { it.value == "array" }) return false
                storageSchema.itemTypes
            } else {
                storageSchema.types
            }
            return valueTypes.allKnown { supports(capability, it) }
        }

        private fun LogicalQueryFieldSchema.supports(
            capability: QueryCapability,
            storageType: QueryStorageType,
        ): Boolean = when (capability) {
            QueryCapability.LITERAL_MATCH ->
                storageType.value == "string" && isValueCompatible(storageType)

            QueryCapability.AGGREGATE_NUMERIC ->
                storageType.value in NUMERIC_TYPES && isValueCompatible(storageType)

            QueryCapability.AGGREGATE_TEMPORAL -> isTemporalCompatible(storageType)
            QueryCapability.ELEMENT_SCOPE ->
                QueryValueType.OBJECT in valueTypes && storageType.value == "object"

            else -> isValueCompatible(storageType)
        }

        private fun LogicalQueryFieldSchema.isValueCompatible(storageType: QueryStorageType): Boolean =
            when (semanticType) {
                Temporal.Date -> storageType.value in DATE_TYPES
                is Temporal.Epoch -> storageType.value in INTEGRAL_TYPES
                else -> valueTypes.any { logicalType -> logicalType.supports(storageType) }
            }

        private fun LogicalQueryFieldSchema.isTemporalCompatible(storageType: QueryStorageType): Boolean =
            when (semanticType) {
                Temporal.Date -> storageType.value in DATE_TYPES
                is Temporal.Epoch -> storageType.value in INTEGRAL_TYPES
                else -> false
            }

        private fun QueryValueType.supports(storageType: QueryStorageType): Boolean = when (this) {
            QueryValueType.STRING -> storageType.value == "string"
            QueryValueType.BOOLEAN -> storageType.value == "bool"
            QueryValueType.OBJECT -> storageType.value == "object"
            QueryValueType.INTEGER -> storageType.value in INTEGRAL_TYPES
            QueryValueType.DECIMAL -> storageType.value in NUMERIC_TYPES
            else -> false
        }

        private fun LogicalQueryFieldSchema.toFieldSchema(
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
            dynamicChildren = dynamicChildren,
            bindings = bindings,
        )

        private fun List<Document>.hasTextIndex(): Boolean = any { index ->
            (index["key"] as? Document)?.values?.any { it == "text" } == true
        }

        private fun Document?.storageSchemas(): Map<String, MongoStorageSchema> {
            if (this == null) return emptyMap()
            return buildMap { collectStorageSchemas(this@storageSchemas, path = null, includeType = false) }
        }

        private fun MutableMap<String, MongoStorageSchema>.collectStorageSchemas(
            schema: Document,
            path: String?,
            includeType: Boolean,
        ) {
            if (includeType && path != null) {
                val types = schema.storageTypes()
                val itemTypes = schema.document("items")?.storageTypes()
                if (types != null || itemTypes != null) {
                    put(path, MongoStorageSchema(types, itemTypes))
                }
            }
            schema.document("properties")?.forEach { (name, child) ->
                (child as? Document)?.let {
                    collectStorageSchemas(it, path.child(name), includeType = true)
                }
            }
            schema.document("items")?.let {
                collectStorageSchemas(it, path, includeType = false)
            }
        }

        private fun Document.storageTypes(): Set<QueryStorageType>? =
            when (val declared = this["bsonType"]) {
                is String -> setOfNotNull(declared.takeUnless { it == "null" }?.let(::QueryStorageType))
                is Iterable<*> -> declared.filterIsInstance<String>()
                    .filter { it != "null" }
                    .mapTo(linkedSetOf(), ::QueryStorageType)
                else -> null
            }

        private fun Set<QueryStorageType>?.allKnown(predicate: (QueryStorageType) -> Boolean): Boolean =
            this == null || isNotEmpty() && all(predicate)

        private fun String?.child(name: String): String = if (this == null) name else "$this.$name"

        private fun Document.document(key: String): Document? = this[key] as? Document

        private fun Document.validatorSchema(): Document? = document("options")
            ?.document("validator")
            ?.document("\$jsonSchema")

        private data class MongoStorageSchema(
            val types: Set<QueryStorageType>?,
            val itemTypes: Set<QueryStorageType>?,
        )

        private val INTEGRAL_TYPES = setOf("int", "long")
        private val NUMERIC_TYPES = INTEGRAL_TYPES + setOf("double", "decimal", "number")
        private val DATE_TYPES = setOf("date", "timestamp")
    }
}
