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

package me.ahoo.wow.postgres.query

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

/**
 * One value bound to a `?` of a compiled statement. Every value of a query is bound, never written into the SQL
 * text; the text holds only the compiler's own words, the table's name and path segments checked against
 * [PostgresSnapshotQueryExpressions.SEGMENT].
 */
internal sealed interface SqlParameter {
    fun bind(statement: PreparedStatement, index: Int)

    data class Text(val value: String?) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) =
            if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
    }

    data class Numeric(val value: BigDecimal?) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) =
            if (value == null) statement.setNull(index, Types.NUMERIC) else statement.setBigDecimal(index, value)
    }

    data class Int8(val value: Long?) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) =
            if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
    }

    data class Bool(val value: Boolean?) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) =
            if (value == null) statement.setNull(index, Types.BOOLEAN) else statement.setBoolean(index, value)
    }

    /** A JSON text; the statement casts it (`?::jsonb`). */
    data class Json(val value: String) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) = statement.setString(index, value)
    }

    /** An SQL array of [elementType] (`text`, `numeric`, `int8`, `bool`); JSON texts are `text`, cast `?::jsonb[]`. */
    data class Array(val elementType: String, val values: List<Any>) : SqlParameter {
        override fun bind(statement: PreparedStatement, index: Int) =
            statement.setArray(index, statement.connection.createArrayOf(elementType, values.toTypedArray()))
    }
}

/** A compiled statement: its text and the values of its `?`s, in order. */
internal class SqlStatement(val sql: String, val parameters: List<SqlParameter>) {
    fun prepare(connection: Connection): PreparedStatement = connection.prepareStatement(sql).also { statement ->
        parameters.forEachIndexed { index, parameter -> parameter.bind(statement, index + 1) }
    }

    override fun toString(): String = sql
}

/** Collects the values of a statement as its text is written, left to right. */
internal class SqlParameters {
    val values = ArrayList<SqlParameter>()

    /** Binds [parameter] and returns its placeholder. */
    fun bind(parameter: SqlParameter): String {
        values += parameter
        return "?"
    }

    fun statement(sql: String): SqlStatement = SqlStatement(sql, values.toList())
}
