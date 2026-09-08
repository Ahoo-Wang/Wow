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
import me.ahoo.wow.api.query.schema.QueryModelSchemaMetadata
import me.ahoo.wow.api.query.schema.QueryValueSchemaMetadata

fun QueryModelSchema.toMetadata(): QueryModelSchemaMetadata = QueryModelSchemaMetadata(
    model = model,
    capabilities = capabilities,
    root = root.metadata(this, QueryPathTemplate(emptyList())),
)

private fun QueryValueSchema.metadata(schema: QueryModelSchema, path: QueryPathTemplate): QueryValueSchemaMetadata {
    val native = schema.bindings[path] ?: QueryValueBindings()
    val protected = isFieldProtected(schema, path)
    val publicCapabilities = native.bindings.keys.filterTo(linkedSetOf()) { capability ->
        when (capability) {
            QueryCapability.CURSOR_SORT -> isCursorFieldAllowed(
                schema,
                path,
                checkNotNull(schema.definition.value(path)),
                native
            )
            QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC, QueryCapability.AGGREGATE_TEMPORAL -> !protected
            else -> true
        }
    }
    return QueryValueSchemaMetadata(
        kind = kind,
        title = title,
        description = description,
        enumValues = enumValues,
        valueTypes = valueTypes,
        nullable = nullable,
        required = required,
        semanticType = semanticType,
        properties = properties.toSortedMap().mapValues { (name, child) ->
            child.metadata(schema, QueryPathTemplate(path.segments + QueryPathSegment.Property(name)))
        },
        items = items?.metadata(schema, QueryPathTemplate(path.segments + QueryPathSegment.Item)),
        additionalProperties = additionalProperties?.metadata(
            schema,
            QueryPathTemplate(
                path.segments + QueryPathSegment.Key(path.segments.count { it is QueryPathSegment.Key }),
            )
        ),
        alternatives = alternatives.map { it.metadata(schema, path) },
        capabilities = publicCapabilities,
        masked = maskRule != null,
    )
}
