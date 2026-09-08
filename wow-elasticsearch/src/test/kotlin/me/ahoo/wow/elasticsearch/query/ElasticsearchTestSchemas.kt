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

package me.ahoo.wow.elasticsearch.query

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
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema

internal fun String.testPath() = QueryPathTemplate(split('.').map(QueryPathSegment::Property))

internal fun nativeSchema(
    model: QueryModel = QueryModel.SNAPSHOT,
    capabilities: Set<QueryCapability> = emptySet(),
    fields: Map<QueryField, QueryValueBindings> = emptyMap(),
): QueryModelSchema {
    fun value(prefix: String): QueryValueSchema {
        val children = fields.keys.map { it.path }.filter { it.startsWith(prefix) && it != prefix.removeSuffix(".") }
            .map { it.removePrefix(prefix).substringBefore('.') }.distinct()
        val objectValue =
            QueryValueSchema(QueryValueKind.OBJECT, properties = children.associateWith { value("$prefix$it.") })
        val native = if (prefix.isEmpty()) null else fields[QueryField(prefix.removeSuffix("."))]
        return when {
            native?.bindings?.containsKey(QueryCapability.ELEMENT_SCOPE) == true -> QueryValueSchema(
                QueryValueKind.ARRAY,
                items = objectValue
            )
            prefix.isEmpty() || children.isNotEmpty() -> objectValue
            else -> QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(if (prefix == "deleted.") QueryValueType.BOOLEAN else QueryValueType.STRING)
            )
        }
    }
    val arrays = fields.filterValues { QueryCapability.ELEMENT_SCOPE in it.bindings }.keys.map { it.path }.toSet()
    val bindings = fields.mapKeys { (field, _) ->
        val parts = field.path.split('.')
        QueryPathTemplate(
            buildList {
                parts.forEachIndexed { index, part ->
                    add(QueryPathSegment.Property(part))
                    if (index < parts.lastIndex && parts.take(index + 1).joinToString(".") in arrays) {
                        add(
                            QueryPathSegment.Item
                        )
                    }
                }
            }
        )
    }
    return QueryModelSchema(model, capabilities, LogicalQuerySchema(value("")), bindings)
}

internal fun nativeBindings(
    physical: QueryField,
    vararg capabilities: QueryCapability,
    projection: QueryField? = physical,
    response: QueryField? = projection,
): QueryValueBindings = QueryValueBindings(
    capabilities.associateWith { QueryFieldBindingTemplate(physical.path.testPath(), null) },
    projection?.path?.testPath(),
    response?.path?.testPath(),
)

internal fun aggregationTestSchema(): QueryModelSchema = nativeSchema(
    fields = buildMap {
        listOf(
            "state.product", "state.amount", "state.address", "state.total", "state.active", "state.value",
            "state.productId", "state.productName", "state.category", "state.items.amount"
        ).forEach { path ->
            put(
                QueryField(path),
                nativeBindings(QueryField(path), QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC)
            )
        }
        put(QueryField("deleted"), nativeBindings(QueryField("deleted"), QueryCapability.EXACT_MATCH))
        put(QueryField("state.items"), nativeBindings(QueryField("state.items"), QueryCapability.ELEMENT_SCOPE))
    }
)
