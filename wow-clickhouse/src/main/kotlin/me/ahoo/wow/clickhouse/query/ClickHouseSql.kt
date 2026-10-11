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

import java.time.ZoneId

/** The parameters of one statement, named `p0`, `p1`, … in binding order. */
internal class SqlParameters {
    private val values = linkedMapOf<String, Any>()

    fun bind(value: Any, type: String): String {
        val name = "p${values.size}"
        values[name] = value
        return "{$name:$type}"
    }

    fun statement(sql: String): ClickHouseStatement = ClickHouseStatement(sql, values.toMap())
}

/** A finite double as SQL text; the AST already rejects NaN and infinities. */
internal fun Double.sqlLiteral(): String {
    check(isFinite()) { "SQL constant must be finite." }
    return "toFloat64(${toBigDecimal().toPlainString()})"
}

/** A time zone the AST already validated as a `ZoneId`, as a quoted SQL string. */
internal fun String.sqlTimeZone(): String = "'" + ZoneId.of(this).id.replace("'", "\\'") + "'"
