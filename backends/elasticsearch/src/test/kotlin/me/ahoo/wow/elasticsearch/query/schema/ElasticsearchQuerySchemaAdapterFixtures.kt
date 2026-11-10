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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType

/** What the tests of the schema adapter share: binding a logical schema to a mapping, the schema builders, a field's path. */
internal object ElasticsearchQuerySchemaAdapterFixtures {
    fun QueryModelSchema.path(field: String, capability: QueryCapability): String? =
        field(QueryField(field))?.binding(capability)?.physicalField?.path

    fun bind(definition: LogicalQuerySchema, mapping: TypeMapping) =
        ElasticsearchQuerySchemaAdapter.bind(definition, ElasticsearchIndexMapping.from("test", mapping))

    fun logical(vararg fields: Pair<String, QueryValueSchema>) = LogicalQuerySchema(objectValue(fields.toMap()))
    fun scalar(type: QueryValueType) = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type))
    fun array(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)
    fun union(vararg values: QueryValueSchema) = QueryValueSchema(
        QueryValueKind.UNION,
        alternatives = values.toList()
    )
    fun objectValue(
        properties: Map<String, QueryValueSchema> = emptyMap(),
        additional: QueryValueSchema? = null
    ) =
        QueryValueSchema(QueryValueKind.OBJECT, properties = properties, additionalProperties = additional)
}
