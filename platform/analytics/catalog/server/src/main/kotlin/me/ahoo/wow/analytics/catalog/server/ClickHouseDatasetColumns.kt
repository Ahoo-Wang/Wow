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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.schema.QueryFieldDeclarationBuilder
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal

/**
 * What a ClickHouse column of a dataset's view is to its queries, as the ClickHouse dataset backend reads
 * `system.columns`: text (`String`, `FixedString`, `UUID`, enums, dates and times, the latter a date's semantic), an
 * integer, a decimal (floats too), a boolean, or an array of text; `Nullable` and `LowCardinality` wrap any of them.
 * Any other type (a map, a tuple, a `JSON` column) is not one a dataset serves.
 */
@OptIn(ExperimentalWowApi::class)
internal object ClickHouseDatasetColumns {
    private const val NULLABLE = "Nullable("
    private const val LOW_CARDINALITY = "LowCardinality("
    private val INTEGER = Regex("U?Int(8|16|32|64|128|256)")
    private val TEXT_ARRAY = Regex("Array\\((LowCardinality\\()?(String|FixedString\\(\\d+\\))\\)?\\)")

    /** Whether a dataset's queries can serve a column of [type]. */
    fun servable(type: String): Boolean = valueOf(base(type)) != null || TEXT_ARRAY.matches(base(type))

    /** Declares the field of a column of [type] as the backend reads it; one [servable] says it does not serve, as unknown. */
    fun declare(field: QueryFieldDeclarationBuilder, type: String) {
        val base = base(type)
        if (TEXT_ARRAY.matches(base)) {
            field.kind(QueryValueKind.ARRAY)
            field.items {
                kind(QueryValueKind.SCALAR)
                types(QueryValueType.STRING)
            }
            return
        }
        val value = valueOf(base) ?: return
        field.kind(QueryValueKind.SCALAR)
        field.types(value)
        field.nullable(unwrap(type, LOW_CARDINALITY).startsWith(NULLABLE))
        if (base.startsWith("Date")) field.semantic(Temporal.Date)
    }

    private fun valueOf(base: String): QueryValueType? = when {
        base == "String" || base == "UUID" || base.startsWith("FixedString(") -> QueryValueType.STRING
        base.startsWith("Enum8(") || base.startsWith("Enum16(") -> QueryValueType.STRING
        base.startsWith("Date") -> QueryValueType.STRING
        INTEGER.matches(base) -> QueryValueType.INTEGER
        base == "Float32" || base == "Float64" || base.startsWith("Decimal") -> QueryValueType.DECIMAL
        base == "Bool" -> QueryValueType.BOOLEAN
        else -> null
    }

    private fun base(type: String): String = unwrap(unwrap(type, LOW_CARDINALITY), NULLABLE)

    private fun unwrap(type: String, wrapper: String): String =
        if (type.startsWith(wrapper) && type.endsWith(")")) type.substring(wrapper.length, type.length - 1) else type
}
