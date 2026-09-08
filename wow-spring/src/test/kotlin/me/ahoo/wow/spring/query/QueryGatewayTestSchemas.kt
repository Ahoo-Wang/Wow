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

package me.ahoo.wow.spring.query

import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema

internal fun testQuerySchema(model: QueryModel): QueryModelSchema {
    val properties = listOf("id", "aggregateId", "ownerId", "tenantId", "spaceId", "deleted").associateWith {
        QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(if (it == "deleted") QueryValueType.BOOLEAN else QueryValueType.STRING)
        )
    }
    val definition = LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties))
    val capabilities = setOf(QueryCapability.EXACT_MATCH, QueryCapability.SORT, QueryCapability.CURSOR_SORT)
    return QueryModelSchema(
        model,
        emptySet(),
        definition,
        definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, _) ->
            QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
        }
    )
}
