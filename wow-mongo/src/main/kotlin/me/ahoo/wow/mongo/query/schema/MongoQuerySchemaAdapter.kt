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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.LogicalQueryFieldSchema
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
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
            val capabilities = if (indexes.hasTextIndex()) {
                setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
            } else {
                emptySet()
            }
            val invalidContainers = logicalSchema.fields.mapNotNullTo(linkedSetOf()) { (logicalField, fieldSchema) ->
                val storageSchema = storageSchemas[logicalField.physicalPath(model)]
                logicalField.path.takeIf { fieldSchema.invalidContainer(storageSchema) }
            }
            val elementFields = logicalSchema.fields.mapNotNullTo(linkedSetOf()) { (logicalField, fieldSchema) ->
                if (invalidContainers.any { logicalField.path == it || logicalField.path.startsWith("$it.") }) {
                    return@mapNotNullTo null
                }
                val storageSchema = storageSchemas[logicalField.physicalPath(model)]
                logicalField.takeIf {
                    fieldSchema.cardinality == QueryCardinality.MANY &&
                        QueryValueType.OBJECT in fieldSchema.valueTypes &&
                        fieldSchema.supports(QueryCapability.ELEMENT_SCOPE, storageSchema)
                }
            }
            return QueryModelSchema(
                model = model,
                capabilities = capabilities,
                fields = logicalSchema.fields.mapValues { (logicalField, logicalSchema) ->
                    val physicalPath = logicalField.physicalPath(model)
                    val physicalField = QueryField(physicalPath)
                    val storageSchema = storageSchemas[physicalPath]
                    val binding = QueryFieldBinding(logicalField, physicalField, storageSchema?.types?.singleOrNull())
                    if (invalidContainers.any { logicalField.path == it || logicalField.path.startsWith("$it.") }) {
                        return@mapValues logicalSchema.toFieldSchema(
                            source = logicalField,
                            projectionField = null,
                            bindings = emptyMap(),
                        )
                    }
                    logicalSchema.toFieldSchema(
                        source = logicalField,
                        projectionField = physicalField,
                        bindings = logicalSchema.capabilities()
                            .filter { logicalSchema.supports(it, storageSchema) }
                            .associateWith { binding },
                        elementChild = elementFields.any { logicalField.relativeTo(it) != null },
                    )
                },
            )
        }

        private fun QueryField.physicalPath(model: QueryModel): String = when {
            model == QueryModel.SNAPSHOT && path == "aggregateId" -> "_id"
            model == QueryModel.EVENT_STREAM && path == "id" -> "_id"
            else -> path
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
            source: QueryField,
            projectionField: QueryField?,
            bindings: Map<QueryCapability, QueryFieldBinding>,
            elementChild: Boolean = false,
        ): QueryFieldSchema {
            val rewrites = bindings.values.map { it.resolvedField != source }.distinct()
            val rewriteMode = when {
                (elementChild && bindings.isNotEmpty()) ||
                    semanticType is Temporal || QueryCapability.ELEMENT_SCOPE in bindings ->
                    QueryRewriteMode.INFER
                bindings.isEmpty() || rewrites == listOf(false) -> QueryRewriteMode.NONE
                rewrites == listOf(true) -> QueryRewriteMode.REQUIRED
                else -> QueryRewriteMode.INFER
            }
            return QueryFieldSchema(
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
                projectionField = projectionField,
                rewriteMode = rewriteMode,
                maskRule = maskRule,
            )
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
    }
}
