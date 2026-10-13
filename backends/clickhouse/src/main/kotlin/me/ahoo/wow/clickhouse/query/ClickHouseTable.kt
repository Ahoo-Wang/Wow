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

import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.clickhouse.sink.ClickHouseSql
import me.ahoo.wow.query.QueryExecutionException

/** The family of a column's base type: what decides its capabilities and how its operands are bound. */
internal enum class ClickHouseTypeFamily {
    STRING,
    ENUM,
    INTEGER,
    FLOAT,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATE_TIME,

    /** `Array(String)`, the one array a dataset column may be: several strings per record, such as ABAC tags. */
    STRING_ARRAY,

    /** Other arrays, maps, tuples and the other types a dataset column cannot have. */
    UNSUPPORTED,
}

/** A column of a dataset's table, with what its `system.columns` [type] says about it. */
internal class TypedColumn(val column: ClickHouseColumn) {
    val name: String
        get() = column.name

    val type: String
        get() = column.type

    /** Whether the column is `Nullable(T)`: the only scalar columns that can tell a missing value from a default. */
    val nullable: Boolean = unwrap(type, LOW_CARDINALITY).startsWith(NULLABLE)

    /** The type without `LowCardinality(...)` and `Nullable(...)`. */
    val baseType: String = unwrap(unwrap(type, LOW_CARDINALITY), NULLABLE)

    val family: ClickHouseTypeFamily = familyOf(baseType)

    /** Whether the column holds several values per record: an empty array is a record without any. */
    val array: Boolean
        get() = family == ClickHouseTypeFamily.STRING_ARRAY

    /** Whether the column stores instants natively; its operands and positions travel as epoch milliseconds. */
    val temporal: Boolean
        get() = family == ClickHouseTypeFamily.DATE || family == ClickHouseTypeFamily.DATE_TIME

    /** The type an operand of this column is bound as: its base type, except where the text form differs. */
    val parameterType: String
        get() = when (family) {
            ClickHouseTypeFamily.STRING, ClickHouseTypeFamily.ENUM, ClickHouseTypeFamily.STRING_ARRAY -> "String"
            ClickHouseTypeFamily.DATE, ClickHouseTypeFamily.DATE_TIME -> "Int64"
            else -> baseType
        }

    override fun toString(): String = "TypedColumn($name $type)"

    private companion object {
        const val LOW_CARDINALITY = "LowCardinality("
        const val NULLABLE = "Nullable("
        const val ARRAY = "Array("
        val INTEGER_TYPES = Regex("U?Int(8|16|32|64|128|256)")

        val STRING_TYPES = setOf("String", "UUID")
        val FLOAT_TYPES = setOf("Float32", "Float64")
        val DATE_TYPES = setOf("Date", "Date32")

        fun familyOf(baseType: String): ClickHouseTypeFamily = when {
            baseType in STRING_TYPES || baseType.startsWith("FixedString(") -> ClickHouseTypeFamily.STRING
            baseType.startsWith("Enum8(") || baseType.startsWith("Enum16(") -> ClickHouseTypeFamily.ENUM
            INTEGER_TYPES.matches(baseType) -> ClickHouseTypeFamily.INTEGER
            baseType in FLOAT_TYPES -> ClickHouseTypeFamily.FLOAT
            baseType.startsWith("Decimal") -> ClickHouseTypeFamily.DECIMAL
            baseType == "Bool" -> ClickHouseTypeFamily.BOOLEAN
            baseType in DATE_TYPES -> ClickHouseTypeFamily.DATE
            baseType.startsWith("DateTime") -> ClickHouseTypeFamily.DATE_TIME
            stringArray(baseType) -> ClickHouseTypeFamily.STRING_ARRAY
            else -> ClickHouseTypeFamily.UNSUPPORTED
        }

        /** `Array(String)` or `Array(FixedString(n))`, optionally of `LowCardinality`; never of `Nullable`. */
        fun stringArray(type: String): Boolean {
            if (!type.startsWith(ARRAY) || !type.endsWith(")")) return false
            val element = unwrap(unwrap(type, ARRAY), LOW_CARDINALITY)
            return element == "String" || element.startsWith("FixedString(")
        }

        fun unwrap(type: String, wrapper: String): String {
            val wrapped = type.startsWith(wrapper) && type.endsWith(")")
            return if (wrapped) type.substring(wrapper.length, type.length - 1) else type
        }
    }
}

/** A table or view as `system.tables` and `system.columns` describe it: its [engine] and its columns by name. */
internal class ClickHouseTable(
    val database: String,
    val name: String,
    val engine: String,
    columns: List<ClickHouseColumn>,
) {
    val columns: Map<String, TypedColumn> = columns.associate { it.name to TypedColumn(it) }

    /** `database`.`name`, quoted. */
    val qualifiedName: String
        get() = "${quoteIdentifier(database)}.${quoteIdentifier(name)}"

    /** Whether `FINAL` merges rows at read time: the MergeTree engines that collapse or replace rows. */
    val supportsFinal: Boolean
        get() = FINAL_ENGINES.any { engine.removePrefix("Replicated").removePrefix("Shared") == it }

    /** The column [name], which the compiled schema bound; a missing one means the table changed under it. */
    fun column(name: String): TypedColumn =
        columns[name] ?: throw QueryExecutionException("Column [$name] is not in table [$database.${this.name}].")

    override fun toString(): String = "ClickHouseTable($database.$name, $engine)"

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
internal fun quoteIdentifier(identifier: String): String = ClickHouseSql.identifier(identifier)
