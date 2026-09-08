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

package me.ahoo.wow.benchmark.query

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema

object BenchmarkQuerySchemas {
    @JvmStatic
    fun scalar(type: QueryValueType, semantic: QuerySemanticType?): QueryValueSchema =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type), semanticType = semantic)

    @JvmStatic
    fun create(
        model: QueryModel,
        fields: Map<QueryField, QueryValueSchema>,
        native: Map<QueryField, Map<QueryCapability, QueryField>>,
    ): QueryModelSchema {
        val values = fields + (QueryField("deleted") to scalar(QueryValueType.BOOLEAN, null))
        val bindings = native + (QueryField("deleted") to mapOf(QueryCapability.EXACT_MATCH to QueryField("deleted")))
        fun value(prefix: String): QueryValueSchema {
            val children = values.keys.map { it.path }.filter { it.startsWith(prefix) && it != prefix.removeSuffix(".") }
                .map { it.removePrefix(prefix).substringBefore('.') }.distinct()
            return if (children.isEmpty() && prefix.isNotEmpty()) values.getValue(QueryField(prefix.removeSuffix(".")))
            else QueryValueSchema(QueryValueKind.OBJECT, properties = children.associateWith { value("$prefix$it.") })
        }
        val definition = LogicalQuerySchema(value(""))
        return QueryModelSchema(model, emptySet(), definition, definition.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, _) ->
            QueryValueBindings(bindings[path.field(emptyList())].orEmpty().mapValues { (_, physical) ->
                QueryFieldBindingTemplate(BenchmarkQuerySchemas.path(physical.path), null)
            }, path, path)
        })
    }

    fun path(field: String): QueryPathTemplate = QueryPathTemplate(field.split('.').map(QueryPathSegment::Property))
}
