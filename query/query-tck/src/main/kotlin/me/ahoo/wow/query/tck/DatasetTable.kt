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

package me.ahoo.wow.query.tck
import me.ahoo.wow.api.annotation.InternalWowApi

/** A column of a TCK dataset table, in storage-neutral terms; each backend maps it to a native type. */
class DatasetColumn @InternalWowApi constructor(val name: String, val type: Type, val nullable: Boolean = false) {
    /**
     * A date column stores instants and is declared with the `TEMPORAL_DATE` semantic (a string at the query
     * interface). A backend whose arrays cannot be null stores a null string array as an empty array.
     *
     * Cases may be added in a minor release, so a `when` over it keeps an `else` branch.
     */
    enum class Type { STRING, INTEGER, DECIMAL, DATE, STRING_ARRAY }

    override fun equals(other: Any?): Boolean =
        this === other || other is DatasetColumn && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "DatasetColumn(name=$name, type=$type, nullable=$nullable)"

    private fun valueFields(): List<Any?> = listOf(name, type, nullable)

    companion object {
        /** A [DatasetColumn] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(name: String, type: Type, nullable: Boolean = false): DatasetColumn = DatasetColumn(name, type, nullable)
    }
}

/**
 * A table the spec loads: its columns and rows, each value a `String`, `Long`, `BigDecimal`, `Instant`, a `List` of
 * strings or `null` as its column's type says. A row without a column's key leaves the value missing; a table stores
 * it as its storage stores a missing value (NULL, or an empty array).
 */
class DatasetTable @InternalWowApi constructor(
    val name: String,
    val columns: List<DatasetColumn>,
    val rows: List<Map<String, Any?>>
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is DatasetTable && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "DatasetTable(name=$name, columns=$columns, rows=$rows)"

    private fun valueFields(): List<Any?> = listOf(name, columns, rows)

    companion object {
        /** A [DatasetTable] of the given values. */
        @JvmStatic
        fun of(
            name: String,
            columns: List<DatasetColumn>,
            rows: List<Map<String, Any?>>,
        ): DatasetTable = DatasetTable(name, columns, rows)
    }
}
