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

package me.ahoo.wow.clickhouse.query

/** The family of a column's base type: what decides its capabilities and how operands are bound. */
enum class ClickHouseTypeFamily {
    STRING,
    ENUM,
    INTEGER,
    FLOAT,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATE_TIME,

    /** Arrays, maps, tuples and other types a dataset column cannot have yet. */
    UNSUPPORTED,
}

/** One column as `system.columns` reports it. */
data class ClickHouseColumn(val name: String, val type: String) {
    /** Whether the column is `Nullable(T)`: the only columns that can tell a missing value from a default. */
    val nullable: Boolean = unwrap(type, LOW_CARDINALITY).startsWith(NULLABLE)

    /** The type without `LowCardinality(...)` and `Nullable(...)`. */
    val baseType: String = unwrap(unwrap(type, LOW_CARDINALITY), NULLABLE)

    val family: ClickHouseTypeFamily = when {
        baseType == "String" || baseType.startsWith("FixedString(") || baseType == "UUID" -> ClickHouseTypeFamily.STRING
        baseType.startsWith("Enum8(") || baseType.startsWith("Enum16(") -> ClickHouseTypeFamily.ENUM
        INTEGER_TYPES.matches(baseType) -> ClickHouseTypeFamily.INTEGER
        baseType == "Float32" || baseType == "Float64" -> ClickHouseTypeFamily.FLOAT
        baseType.startsWith("Decimal") -> ClickHouseTypeFamily.DECIMAL
        baseType == "Bool" -> ClickHouseTypeFamily.BOOLEAN
        baseType == "Date" || baseType == "Date32" -> ClickHouseTypeFamily.DATE
        baseType.startsWith("DateTime") -> ClickHouseTypeFamily.DATE_TIME
        else -> ClickHouseTypeFamily.UNSUPPORTED
    }

    /** Whether the column stores instants natively; its operands and positions travel as epoch milliseconds. */
    val temporal: Boolean
        get() = family == ClickHouseTypeFamily.DATE || family == ClickHouseTypeFamily.DATE_TIME

    /** The type an operand of this column is bound as: its base type, except where the text form differs. */
    val parameterType: String
        get() = when (family) {
            ClickHouseTypeFamily.STRING, ClickHouseTypeFamily.ENUM -> "String"
            ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME -> "Int64"
            else -> baseType
        }

    companion object {
        private const val LOW_CARDINALITY = "LowCardinality("
        private const val NULLABLE = "Nullable("
        private val INTEGER_TYPES = Regex("U?Int(8|16|32|64|128|256)")

        private fun unwrap(type: String, wrapper: String): String =
            if (type.startsWith(wrapper) && type.endsWith(")")) {
                type.substring(
                    wrapper.length,
                    type.length - 1
                )
            } else {
                type
            }
    }
}

/** A table or view, its engine and its columns by name. */
data class ClickHouseTable(
    val database: String,
    val name: String,
    val engine: String,
    val columns: Map<String, ClickHouseColumn>,
) {
    val qualifiedName: String
        get() = "${quoteIdentifier(database)}.${quoteIdentifier(name)}"

    /** Whether `FINAL` merges rows at read time: the MergeTree engines that collapse or replace rows. */
    val supportsFinal: Boolean
        get() = FINAL_ENGINES.any { engine.removePrefix("Replicated").removePrefix("Shared") == it }

    fun column(name: String): ClickHouseColumn =
        columns[name] ?: throw IllegalStateException("Column [$name] is not in table [${this.name}].")

    private companion object {
        val FINAL_ENGINES = setOf(
            "ReplacingMergeTree",
            "CollapsingMergeTree",
            "VersionedCollapsingMergeTree",
            "SummingMergeTree",
            "AggregatingMergeTree",
            "CoalescingMergeTree",
        )
    }
}

/** Quotes an identifier that comes from the compiled schema, never from a caller. */
fun quoteIdentifier(identifier: String): String = "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`"
