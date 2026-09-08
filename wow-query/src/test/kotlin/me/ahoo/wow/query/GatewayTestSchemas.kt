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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema

/** A permissive native stub over explicitly typed test model values. */
internal fun gatewaySchema(
    model: QueryModel = QueryModel.SNAPSHOT,
    capabilities: Set<QueryCapability> = emptySet(),
    fields: Map<QueryField, QueryValueSchema> = emptyMap(),
): QueryModelSchema {
    val values = buildMap {
        listOf("id", "aggregateId", "tenantId", "ownerId", "spaceId").forEach { name ->
            put(QueryField(name), QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)))
        }
        put(QueryField("deleted"), QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.BOOLEAN)))
        putAll(fields)
    }
    fun value(prefix: String): QueryValueSchema {
        val children = values.keys.map { it.path }.filter { it.startsWith(prefix) && it != prefix.removeSuffix(".") }
            .map { it.removePrefix(prefix).substringBefore('.') }.distinct()
        if (children.isEmpty() && prefix.isNotEmpty()) return values.getValue(QueryField(prefix.removeSuffix(".")))
        val objectValue = QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = children.associateWith { value("$prefix$it.") }
        )
        return if (prefix == "body.") {
            QueryValueSchema(QueryValueKind.ARRAY, items = objectValue)
        } else {
            objectValue
        }
    }
    val definition = LogicalQuerySchema(value(""))
    return QueryModelSchema(
        model,
        capabilities,
        definition,
        definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            val native = when (value.kind) {
                QueryValueKind.SCALAR -> setOf(
                    QueryCapability.PRESENCE,
                    QueryCapability.EXACT_MATCH,
                    QueryCapability.SORT,
                    QueryCapability.CURSOR_SORT,
                    QueryCapability.AGGREGATE_TERMS
                )
                QueryValueKind.ARRAY -> setOf(QueryCapability.ELEMENT_SCOPE)
                else -> emptySet()
            }
            QueryValueBindings(native.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
        }
    )
}
