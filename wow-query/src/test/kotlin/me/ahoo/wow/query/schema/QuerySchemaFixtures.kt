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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal

internal fun scalarFixture(type: QueryValueType = QueryValueType.STRING, temporal: Temporal? = null, mask: MaskRule? = null) =
    QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(type),
        nullable = false,
        semanticType = temporal,
        maskRule = mask
    )

internal fun objectFixture(vararg properties: Pair<String, QueryValueSchema>) =
    QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap())

internal fun arrayFixture(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

internal fun boundSchemaFixture(
    root: QueryValueSchema,
    model: QueryModel = QueryModel.SNAPSHOT,
    capabilities: Set<QueryCapability> = emptySet(),
    fieldCapabilities: Set<QueryCapability> = setOf(
        QueryCapability.PRESENCE, QueryCapability.EXACT_MATCH, QueryCapability.LITERAL_MATCH, QueryCapability.RANGE,
        QueryCapability.SORT, QueryCapability.CURSOR_SORT, QueryCapability.ELEMENT_SCOPE,
        QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC, QueryCapability.AGGREGATE_TEMPORAL,
        QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE,
    ),
): QueryModelSchema {
    val definition = LogicalQuerySchema(root)
    return QueryModelSchema(
        model,
        capabilities,
        definition,
        definition.values.keys.associateWith { path ->
            val physical = QueryPathTemplate(listOf(QueryPathSegment.Property("native")) + path.segments)
            QueryValueBindings(
                fieldCapabilities.associateWith { QueryFieldBindingTemplate(physical, null) },
                physical,
                path
            )
        }
    )
}
