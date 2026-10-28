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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind

internal fun scalarFixture(type: QueryValueType = QueryValueType.STRING, temporal: Temporal? = null) =
    QueryValueSchema(
        QueryValueKind.SCALAR,
        valueTypes = setOf(type),
        nullable = false,
        semanticType = temporal,
    )

internal fun objectFixture(vararg properties: Pair<String, QueryValueSchema>) =
    QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap())

internal fun arrayFixture(items: QueryValueSchema) = QueryValueSchema(QueryValueKind.ARRAY, items = items)

/** An object whose keys are dynamic, each holding [values]. */
internal fun mapFixture(
    values: QueryValueSchema
) = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = values)

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

/**
 * The physical field [field] is bound to for [capability], for tests that inspect a schema. Backends never look a
 * field up: admission resolves every reference once and hands them [me.ahoo.wow.query.resolve.ResolvedField]s.
 */
internal fun QueryModelSchema.physicalField(
    field: QueryField,
    capability: QueryCapability,
    logicalParent: QueryField? = null,
): QueryField {
    val logical = absoluteLogicalField(field, logicalParent)
    val definition = this.field(logical) ?: throw QueryViolation.UnknownField(logical).rejection()
    val ancestors = definition.elementAncestors
    requireValid(ancestors != null && ancestors == requiredElementAncestors(logicalParent)) {
        QueryViolation.ElementScopeRequired(logical)
    }
    return definition.binding(capability)?.physicalField
        ?: throw QueryViolation.UnsupportedCapability(logical, setOf(capability)).rejection()
}

private fun QueryModelSchema.requiredElementAncestors(parent: QueryField?): List<QueryField>? =
    if (parent == null) emptyList() else field(parent)?.elementAncestors?.plus(parent)
