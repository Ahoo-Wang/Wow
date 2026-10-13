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

import com.clickhouse.client.api.Client
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkIntegrationTest
import me.ahoo.wow.query.tck.DatasetColumn
import me.ahoo.wow.query.tck.DatasetTable
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The ClickHouse server of the Sink's tests, shared by the dataset tests, and SQL helpers to load tables into it. */
object ClickHouseContainers {
    const val DATABASE = "wow_tck"

    val client: Client by lazy {
        ClickHouseSinkIntegrationTest.client.also { execute(it, "CREATE DATABASE IF NOT EXISTS $DATABASE") }
    }

    fun execute(sql: String) = execute(client, sql)

    private fun execute(client: Client, sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    /**
     * Recreates [table] as a MergeTree and inserts its rows; test data only, so values are rendered as literals. A
     * missing or null value is a `NULL`, which a non-`Nullable` column (an array) stores as its default.
     */
    fun load(table: DatasetTable) {
        execute("DROP TABLE IF EXISTS $DATABASE.${table.name}")
        val columns = table.columns.joinToString(", ") { "`${it.name}` ${it.nativeType()}" }
        execute("CREATE TABLE $DATABASE.${table.name} ($columns) ENGINE = MergeTree ORDER BY id")
        if (table.rows.isEmpty()) return
        val values = table.rows.joinToString(", ") { row ->
            table.columns.joinToString(", ", "(", ")") { literal(row[it.name]) }
        }
        execute("INSERT INTO $DATABASE.${table.name} VALUES $values")
    }

    private fun DatasetColumn.nativeType(): String {
        val base = when (type) {
            DatasetColumn.Type.STRING -> "String"
            DatasetColumn.Type.INTEGER -> "Int64"
            DatasetColumn.Type.DECIMAL -> "Decimal(18, 2)"
            DatasetColumn.Type.DATE -> "DateTime64(3, 'UTC')"
            // An array cannot be Nullable: a missing or null one is empty.
            DatasetColumn.Type.STRING_ARRAY -> return "Array(String)"
        }
        return if (nullable) "Nullable($base)" else base
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        is Long, is Int -> value.toString()
        is BigDecimal -> value.toPlainString()
        is Instant -> "fromUnixTimestamp64Milli(${value.toEpochMilli()}, 'UTC')"
        is List<*> -> value.joinToString(", ", "[", "]") { literal(it) }
        else -> error("Unsupported test value [$value].")
    }
}
