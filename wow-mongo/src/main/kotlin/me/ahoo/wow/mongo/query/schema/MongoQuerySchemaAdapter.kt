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
            val storageTypes = validatorSchema.storageTypes()
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
                    val binding = QueryFieldBinding(physicalPath, storageTypes[physicalPath])
                    logicalSchema.toFieldSchema(
                        logicalSchema.capabilities().associateWith { binding },
                    )
                },
            )
        }

        private fun LogicalQueryFieldSchema.capabilities(): Set<QueryCapability> = buildSet {
            add(QueryCapability.PRESENCE)
            val scalar = QueryValueType.OBJECT !in valueTypes
            if (scalar) {
                add(QueryCapability.EXACT_MATCH)
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

        private fun Document?.storageTypes(): Map<String, QueryStorageType> {
            if (this == null) return emptyMap()
            return buildMap { collectStorageTypes(this@storageTypes, path = null, includeType = false) }
        }

        private fun MutableMap<String, QueryStorageType>.collectStorageTypes(
            schema: Document,
            path: String?,
            includeType: Boolean,
        ) {
            if (includeType && path != null) {
                schema.storageType()?.let { put(path, it) }
            }
            schema.document("properties")?.forEach { (name, child) ->
                (child as? Document)?.let {
                    collectStorageTypes(it, path.child(name), includeType = true)
                }
            }
            schema.document("items")?.let {
                collectStorageTypes(it, path, includeType = false)
            }
        }

        private fun Document.storageType(): QueryStorageType? {
            val type = when (val declared = this["bsonType"]) {
                is String -> declared
                is Iterable<*> -> declared.filterIsInstance<String>().filter { it != "null" }.distinct().singleOrNull()
                else -> null
            }
            return type?.let(::QueryStorageType)
        }

        private fun String?.child(name: String): String = if (this == null) name else "$this.$name"

        private fun Document.document(key: String): Document? = this[key] as? Document

        private fun Document.validatorSchema(): Document? = document("options")
            ?.document("validator")
            ?.document("\$jsonSchema")
    }
}
