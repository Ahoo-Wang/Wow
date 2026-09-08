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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema

internal data class MongoTestField(
    val value: QueryValueSchema,
    val capabilities: Set<QueryCapability>,
    val physical: String,
    val storageTypes: Set<QueryStorageType>? = null,
)

internal fun mongoLogicalSchema(fields: Map<QueryField, QueryValueSchema>): LogicalQuerySchema {
    fun objectAt(prefix: String, base: QueryValueSchema? = null): QueryValueSchema {
        val children = fields.keys.mapNotNull { field ->
            field.path.removePrefix(prefix).takeIf { prefix.isEmpty() || field.path.startsWith(prefix) }
                ?.substringBefore('.')?.takeIf { it.isNotEmpty() }
        }.distinct().associateWith { name ->
            val path = prefix + name
            val value = fields[QueryField(path)]
            when {
                value?.kind == QueryValueKind.ARRAY -> QueryValueSchema(
                    kind = QueryValueKind.ARRAY,
                    items = objectAt("$path.", value.items),
                    nullable = value.nullable,
                )
                value == null || value.kind == QueryValueKind.OBJECT -> objectAt("$path.", value)
                else -> value
            }
        }
        if (base != null && base.kind != QueryValueKind.OBJECT) return base
        return QueryValueSchema(
            kind = QueryValueKind.OBJECT,
            properties = base?.properties.orEmpty() + children,
            additionalProperties = base?.additionalProperties,
            maskRule = base?.maskRule,
        )
    }
    return LogicalQuerySchema(objectAt(""))
}

internal fun mongoTestSchema(
    model: QueryModel = QueryModel.SNAPSHOT,
    capabilities: Set<QueryCapability> = emptySet(),
    fields: Map<QueryField, MongoTestField>,
): QueryModelSchema {
    val definition = mongoLogicalSchema(fields.mapValues { it.value.value })
    val bindings = definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, _) ->
        val names = path.segments.filterIsInstance<QueryPathSegment.Property>().joinToString(".") { it.name }
        val entry = fields.entries.filter { (field, _) -> names == field.path || names.startsWith("${field.path}.") }
            .maxByOrNull { it.key.path.length } ?: return@mapValues QueryValueBindings()
        val fixture = entry.value
        var remaining = entry.key.path.count { it == '.' } + 1
        val offset = path.segments.indexOfFirst { segment ->
            if (segment is QueryPathSegment.Property) remaining--
            remaining == 0
        } + 1
        val suffix = path.segments.drop(offset)
        val physical = QueryPathTemplate(fixture.physical.split('.').map(QueryPathSegment::Property) + suffix)
        QueryValueBindings(
            fixture.capabilities.associateWith { QueryFieldBindingTemplate(physical, fixture.storageTypes) },
            physical,
            path,
        )
    }.filterKeys { it.segments.isNotEmpty() }
    return QueryModelSchema(model, capabilities, definition, bindings)
}

internal fun mongoScalar(type: QueryValueType = QueryValueType.STRING): QueryValueSchema =
    QueryValueSchema(kind = QueryValueKind.SCALAR, valueTypes = setOf(type))
