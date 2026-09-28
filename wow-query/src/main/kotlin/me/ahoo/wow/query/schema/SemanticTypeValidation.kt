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

import me.ahoo.wow.api.query.schema.NumericFormat
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Reference
import me.ahoo.wow.api.query.schema.TimeSpan

/**
 * Checks every declared semantic type of a model when its schema is built, so a wrong declaration is a schema
 * conflict instead of silently wrong formatting (design §6.6):
 *
 * - a [NumericFormat] or a [TimeSpan] is on a numeric field;
 * - a [Reference] is on a string or integer field;
 * - a sibling field a semantic type names ([NumericFormat.Money.currencyField], [Reference.contextNameField],
 *   [Reference.aggregateNameField]) is a single-valued string property of the same object or element;
 * - nothing declares [QuerySemanticType.Unknown], which only a client reading a newer server's descriptor sees.
 */
internal fun QueryValueSchema.requireSemanticTypes() = visitSemanticTypes(this, siblings = emptyMap(), path = "")

private fun visitSemanticTypes(value: QueryValueSchema, siblings: Map<String, QueryValueSchema>, path: String) {
    value.semanticType?.let { semantic -> requireSemanticType(value, semantic, siblings, path) }
    when (value.kind) {
        QueryValueKind.OBJECT -> {
            value.properties.forEach { (name, child) ->
                visitSemanticTypes(child, value.properties, if (path.isEmpty()) name else "$path.$name")
            }
            value.additionalProperties?.let { visitSemanticTypes(it, value.properties, "$path.{key}") }
        }
        // The items of an array share the siblings of the property that holds it; object items are their own scope.
        QueryValueKind.ARRAY -> value.items?.let { visitSemanticTypes(it, siblings, path) }
        QueryValueKind.UNION -> value.alternatives.forEach { visitSemanticTypes(it, siblings, path) }
        else -> Unit
    }
}

private val NUMERIC_TYPES = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL)
private val ID_TYPES = setOf(QueryValueType.STRING, QueryValueType.INTEGER)

private fun requireSemanticType(
    value: QueryValueSchema,
    semantic: QuerySemanticType,
    siblings: Map<String, QueryValueSchema>,
    path: String,
) {
    when (semantic) {
        is NumericFormat -> {
            value.requireScalarOf(NUMERIC_TYPES) { "Numeric format of [$path] requires a numeric field." }
            val currencyField = (semantic as? NumericFormat.Money)?.currencyField
            currencyField?.let { siblings.requireSingleString(it, "MONEY currencyField", path) }
        }
        is TimeSpan -> value.requireScalarOf(NUMERIC_TYPES) { "DURATION of [$path] requires a numeric field." }
        is Reference -> {
            value.requireScalarOf(ID_TYPES) { "REFERENCE of [$path] requires a string or integer field." }
            semantic.contextNameField?.let { siblings.requireSingleString(it, "REFERENCE contextNameField", path) }
            semantic.aggregateNameField?.let { siblings.requireSingleString(it, "REFERENCE aggregateNameField", path) }
        }
        QuerySemanticType.Unknown -> throw QuerySchemaConflictException("Semantic type of [$path] is unknown.")
        else -> Unit
    }
}

private inline fun QueryValueSchema.requireScalarOf(types: Set<QueryValueType>, message: () -> String) {
    if (kind != QueryValueKind.SCALAR || valueTypes.isEmpty() || !types.containsAll(valueTypes)) {
        throw QuerySchemaConflictException(message())
    }
}

private fun Map<String, QueryValueSchema>.requireSingleString(field: String, member: String, path: String) {
    val sibling = this[field]
    if (sibling == null || !sibling.isSingleString()) {
        throw QuerySchemaConflictException(
            "$member [$field] of [$path] must be a single-valued string field of the same object.",
        )
    }
}

private fun QueryValueSchema.isSingleString(): Boolean {
    val values = operationValues().filter { it.kind != QueryValueKind.NULL }
    return cardinality == QueryCardinality.SINGLE && values.isNotEmpty() &&
        values.all { it.kind == QueryValueKind.SCALAR && it.valueTypes == setOf(QueryValueType.STRING) }
}
