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
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType

/**
 * Checks every declared [NumericFormat] of a model when its schema is built (design §6.6), so a wrong declaration is
 * a schema conflict instead of silently wrong formatting: the field must be numeric, and a `MONEY` [currencyField]
 * [NumericFormat.Money.currencyField] must be a single-valued string property of the same object or element.
 */
internal fun QueryValueSchema.requireNumericFormats() = visitNumericFormats(this, siblings = emptyMap(), path = "")

private fun visitNumericFormats(value: QueryValueSchema, siblings: Map<String, QueryValueSchema>, path: String) {
    (value.semanticType as? NumericFormat)?.let { format -> requireNumericFormat(value, format, siblings, path) }
    when (value.kind) {
        QueryValueKind.OBJECT -> {
            value.properties.forEach { (name, child) ->
                visitNumericFormats(child, value.properties, if (path.isEmpty()) name else "$path.$name")
            }
            value.additionalProperties?.let { visitNumericFormats(it, value.properties, "$path.{key}") }
        }
        // The items of an array share the siblings of the property that holds it; object items are their own scope.
        QueryValueKind.ARRAY -> value.items?.let { visitNumericFormats(it, siblings, path) }
        QueryValueKind.UNION -> value.alternatives.forEach { visitNumericFormats(it, siblings, path) }
        else -> Unit
    }
}

private fun requireNumericFormat(
    value: QueryValueSchema,
    format: NumericFormat,
    siblings: Map<String, QueryValueSchema>,
    path: String,
) {
    val numeric = value.kind == QueryValueKind.SCALAR && value.valueTypes.isNotEmpty() &&
        value.valueTypes.all { it == QueryValueType.INTEGER || it == QueryValueType.DECIMAL }
    if (!numeric) {
        throw QuerySchemaConflictException("Numeric format of [$path] requires a numeric field.")
    }
    val currencyField = (format as? NumericFormat.Money)?.currencyField ?: return
    val currency = siblings[currencyField]
    if (currency == null || !currency.isSingleString()) {
        throw QuerySchemaConflictException(
            "MONEY currencyField [$currencyField] of [$path] must be a single-valued string field of the same object.",
        )
    }
}

private fun QueryValueSchema.isSingleString(): Boolean {
    val values = operationValues().filter { it.kind != QueryValueKind.NULL }
    return cardinality == QueryCardinality.SINGLE && values.isNotEmpty() &&
        values.all { it.kind == QueryValueKind.SCALAR && it.valueTypes == setOf(QueryValueType.STRING) }
}
