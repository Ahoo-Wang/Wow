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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import java.util.Collections

internal class MongoQueryFieldBinding private constructor(
    private val fields: Map<LogicalField, BoundField>
) {
    fun physical(logicalField: LogicalField, usage: QueryFieldUsage = QueryFieldUsage.EXACT): String {
        val bound = fields[logicalField] ?: throw IllegalArgumentException("Logical query field is not bound.")
        return bound.physical[usage] ?: bound.physical.getValue(QueryFieldUsage.EXACT)
    }

    fun schema(logicalField: LogicalField): QueryFieldSchema =
        fields[logicalField]?.schema ?: throw IllegalArgumentException("Logical query field is not bound.")

    fun contains(logicalField: LogicalField): Boolean = fields.containsKey(logicalField)

    fun projection(fields: Set<LogicalField>): Map<LogicalField, String> {
        val snapshot = LinkedHashMap<LogicalField, String>(fields.size)
        fields.forEach { logical -> snapshot[logical] = physical(logical) }
        return Collections.unmodifiableMap(snapshot)
    }

    private class BoundField(
        val schema: QueryFieldSchema,
        physical: Map<QueryFieldUsage, String>
    ) {
        val physical: Map<QueryFieldUsage, String> = Collections.unmodifiableMap(LinkedHashMap(physical))
    }

    companion object {
        private val MONGO_BACKEND_ID = QueryBackendId("mongo")

        fun bind(schema: QuerySchema): MongoQueryFieldBinding {
            val fields = LinkedHashMap<LogicalField, BoundField>(schema.fields.size)
            schema.fields.forEach { (logical, fieldSchema) ->
                val defaultPath = when {
                    schema.target.documentKind == QueryDocumentKind.SNAPSHOT && logical.value == "aggregateId" ->
                        Documents.ID_FIELD

                    schema.target.documentKind == QueryDocumentKind.EVENT_STREAM && logical.value == "id" ->
                        Documents.ID_FIELD

                    else -> logical.value
                }
                val physical = LinkedHashMap<QueryFieldUsage, String>()
                physical[QueryFieldUsage.EXACT] = fieldSchema.bindings
                    .singleOrNull { it.backendId == MONGO_BACKEND_ID && it.usage == QueryFieldUsage.EXACT }
                    ?.field?.value ?: defaultPath
                fieldSchema.bindings.filter { it.backendId == MONGO_BACKEND_ID }.forEach { binding ->
                    physical[binding.usage] = binding.field.value
                }
                fields[logical] = BoundField(fieldSchema, physical)
            }
            return MongoQueryFieldBinding(Collections.unmodifiableMap(fields))
        }
    }
}
