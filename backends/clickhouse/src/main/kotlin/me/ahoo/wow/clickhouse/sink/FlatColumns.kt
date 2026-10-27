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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.Currency

/**
 * A column of a view: its [name], its ClickHouse [type] as `system.columns` reads it, the [expression] it is, and the
 * state's [property] it is read from.
 */
@InternalWowApi
data class FlatColumn(val name: String, val type: String, val expression: String, val property: String = name)

/**
 * The typed columns of `<ctx>_<agg>_state_flat`: one per top-level scalar property of the state
 * schema, a JSON Schema of the Manifest's `$defs`, in the schema's order. A property that is an object, an array, a
 * reference or a composition is left out: nested expansion is deferred.
 *
 * | Schema | Column | Read from |
 * | --- | --- | --- |
 * | `string` (an enum too) | `String` | `JSONExtractString(state, …)` |
 * | `integer` | `Int64` | `JSONExtract(state, …, 'Int64')` |
 * | `number` with `x-wow-decimal` or `x-wow-money` | `Decimal(38, scale)` | `toDecimal128OrZero(state_numbers[…], scale)` |
 * | other `number` | `Float64` | `JSONExtract(state, …, 'Float64')` |
 * | `boolean` | `Bool` | `JSONExtract(state, …, 'Bool')` |
 *
 * A type that admits `null` (`["string", "null"]`) makes the column `Nullable`. A decimal's scale is the keyword's
 * `scale`, else the currency's default digits for a money, else [DEFAULT_SCALE].
 *
 * Decimals are read from the record's `state_numbers`, the exact text of each top-level number:
 * ClickHouse's JSON functions parse numbers as `Float64` (`12.30` becomes `12.3`, 27 digits become `1.23e26`).
 *
 * A property named as a system column of the view gets the prefix `state_`.
 */
internal object FlatColumns {
    /** The scale of a decimal that declares none and whose currency, if it is a money, is another field. */
    const val DEFAULT_SCALE: Int = 18
    private const val DECIMAL_PRECISION = 38

    fun of(schema: Map<String, Any?>?, reserved: Set<String>): List<FlatColumn> {
        val properties = schema?.get("properties") as? Map<*, *> ?: return emptyList()
        return properties.mapNotNull { (name, property) ->
            if (name !is String || property !is Map<*, *>) null else columnOf(name, property, reserved)
        }
    }

    private fun columnOf(name: String, property: Map<*, *>, reserved: Set<String>): FlatColumn? {
        val types = typesOf(property["type"]) ?: return null
        val nullable = "null" in types
        val scalar = (types - "null").singleOrNull() ?: return null
        val key = ClickHouseSql.literal(name)
        val (base, expression) = when (scalar) {
            "number" -> scaleOf(property)?.let { scale -> decimalOf(key, scale, nullable) }
                ?: extracted(key, "Float64", nullable)

            "string" -> if (nullable) extracted(key, "String", true) else "String" to "JSONExtractString(state, $key)"
            "integer" -> extracted(key, "Int64", nullable)
            "boolean" -> extracted(key, "Bool", nullable)
            else -> return null
        }
        return FlatColumn(if (name in reserved) "state_$name" else name, nullableOf(base, nullable), expression, name)
    }

    private fun typesOf(type: Any?): List<String>? = when (type) {
        is String -> listOf(type)
        is List<*> -> type.filterIsInstance<String>()
        else -> null
    }

    private fun decimalOf(key: String, scale: Int, nullable: Boolean): Pair<String, String> {
        val function = if (nullable) "toDecimal128OrNull" else "toDecimal128OrZero"
        return "Decimal($DECIMAL_PRECISION, $scale)" to "$function(state_numbers[$key], $scale)"
    }

    private fun extracted(key: String, type: String, nullable: Boolean): Pair<String, String> =
        type to "JSONExtract(state, $key, '${nullableOf(type, nullable)}')"

    private fun nullableOf(type: String, nullable: Boolean): String = if (nullable) "Nullable($type)" else type

    private fun scaleOf(property: Map<*, *>): Int? {
        (property["x-wow-decimal"] as? Map<*, *>)?.let { decimal ->
            return (decimal["scale"] as? Number)?.toInt() ?: DEFAULT_SCALE
        }
        val money = property["x-wow-money"] as? Map<*, *> ?: return null
        (money["scale"] as? Number)?.let { return it.toInt() }
        val currency = money["currency"] as? String ?: return DEFAULT_SCALE
        return Currency.getInstance(currency).defaultFractionDigits.takeIf { it >= 0 } ?: DEFAULT_SCALE
    }
}
