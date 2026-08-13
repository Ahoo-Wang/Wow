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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import java.util.Collections

internal class ElasticsearchQueryFieldBinding private constructor(
    private val fields: Map<LogicalField, BoundField>,
) {
    fun physical(logical: LogicalField, usage: QueryFieldUsage = QueryFieldUsage.EXACT): String {
        val field = fields[logical] ?: throw IllegalArgumentException("Logical query field is not bound.")
        return field.physical[usage] ?: field.physical.getValue(QueryFieldUsage.EXACT)
    }

    fun source(logical: LogicalField): String =
        fields[logical]?.source ?: throw IllegalArgumentException("Logical query field is not bound.")

    fun schema(logical: LogicalField): QueryFieldSchema =
        fields[logical]?.schema ?: throw IllegalArgumentException("Logical query field is not bound.")

    fun contains(logical: LogicalField): Boolean = fields.containsKey(logical)

    fun schemas(): Map<LogicalField, QueryFieldSchema> =
        Collections.unmodifiableMap(fields.mapValuesTo(LinkedHashMap()) { (_, bound) -> bound.schema })

    fun presenceField(logical: LogicalField): String {
        val parent = source(logical).substringBeforeLast('.', missingDelimiterValue = "")
        return if (parent.isEmpty()) "__wow_query.present" else "$parent.__wow_query.present"
    }

    fun fieldValue(logical: LogicalField, value: QueryValue): FieldValue = when (val encoded = encode(logical, value)) {
        null -> FieldValue.NULL
        is Boolean -> FieldValue.of(encoded)
        is Long -> FieldValue.of(encoded)
        is Double -> FieldValue.of(encoded)
        is String -> FieldValue.of(encoded)
        else -> FieldValue.of(JsonData.of(encoded))
    }

    fun jsonValue(logical: LogicalField, value: QueryValue): JsonData = JsonData.of(encode(logical, value))

    fun projection(fields: Set<LogicalField>): Map<LogicalField, String> {
        val result = LinkedHashMap<LogicalField, String>(fields.size)
        fields.forEach { logical -> result[logical] = source(logical) }
        return Collections.unmodifiableMap(result)
    }

    fun hasAuthoritativeSystemFields(documentKind: QueryDocumentKind): Boolean =
        QuerySystemFields.fields(documentKind).all { authoritative ->
            val bound = fields[authoritative.path] ?: return@all false
            bound.schema.system &&
                bound.schema.valueKind == authoritative.valueKind &&
                bound.schema.collectionKind == authoritative.collectionKind &&
                bound.source == authoritative.path.value &&
                bound.physical.values.all { path -> path == authoritative.path.value }
        }

    private fun encode(logical: LogicalField, value: QueryValue): Any? {
        val schema = schema(logical)
        return when (value) {
            is QueryValue.BooleanValue -> value.value
            is QueryValue.IntegerValue -> value.value
            is QueryValue.FloatingValue -> value.value
            is QueryValue.DecimalValue -> value.value
            is QueryValue.StringValue -> value.value
            is QueryValue.InstantValue -> if (schema.system) value.value.toEpochMilli() else value.value.toString()
            is QueryValue.EnumValue -> value.value
            is QueryValue.ListValue -> value.values.map { nested -> encode(logical, nested) }
            is QueryValue.ObjectValue -> value.values.mapValues { (_, nested) -> encode(logical, nested) }
            is QueryValue.BinaryValue -> value.value
            QueryValue.NullValue -> null
        }
    }

    private class BoundField(
        val schema: QueryFieldSchema,
        val source: String,
        physical: Map<QueryFieldUsage, String>,
    ) {
        val physical: Map<QueryFieldUsage, String> = Collections.unmodifiableMap(LinkedHashMap(physical))
    }

    companion object {
        private val BACKEND_ID = QueryBackendId("elasticsearch")

        fun bind(schema: QuerySchema): ElasticsearchQueryFieldBinding {
            val fields = LinkedHashMap<LogicalField, BoundField>(schema.fields.size)
            schema.fields.forEach { (logical, fieldSchema) ->
                val source = logical.value
                val fullText = me.ahoo.wow.api.query.expression.QueryCapabilityId("full-text") in fieldSchema.capabilities
                val exact = if (fullText) "$source.exact" else source
                val physical = linkedMapOf<QueryFieldUsage, String>(QueryFieldUsage.EXACT to exact)
                if (fullText) {
                    physical[QueryFieldUsage.SEARCH] = source
                }
                if (fieldSchema.sortable) {
                    physical[QueryFieldUsage.SORT] = exact
                }
                if (fieldSchema.nested || fieldSchema.elementMatchEnabled) {
                    physical[QueryFieldUsage.NESTED] = source
                }
                fieldSchema.bindings.filter { it.backendId == BACKEND_ID }.forEach { binding ->
                    physical[binding.usage] = binding.field.value
                }
                fields[logical] = BoundField(fieldSchema, source, physical)
            }
            return ElasticsearchQueryFieldBinding(Collections.unmodifiableMap(fields))
        }
    }
}
