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
import me.ahoo.wow.tck.query.DatasetColumn
import me.ahoo.wow.tck.query.DatasetTable
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One ClickHouse server shared by the module's integration tests, and SQL helpers to load tables into it. */
object ClickHouseContainers {
    private const val IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
    const val DATABASE = "wow_tck"

    val container: ClickHouseContainer by lazy {
        ClickHouseContainer(DockerImageName.parse(IMAGE)).also {
            it.start()
            Runtime.getRuntime().addShutdownHook(Thread { it.stop() })
        }
    }

    val client: Client by lazy {
        Client.Builder()
            .addEndpoint(container.httpUrl)
            .setUsername(container.username)
            .setPassword(container.password)
            .useAsyncRequests(true)
            .build()
            .also { execute(it, "CREATE DATABASE IF NOT EXISTS $DATABASE") }
    }

    fun execute(sql: String) = execute(client, sql)

    private fun execute(client: Client, sql: String) {
        client.query(sql).get(1, TimeUnit.MINUTES).close()
    }

    /** Recreates [table] as a MergeTree and inserts its rows; test data only, so values are rendered as literals. */
    fun load(table: DatasetTable, engine: String = "MergeTree ORDER BY id") {
        execute("DROP TABLE IF EXISTS $DATABASE.${table.name}")
        val columns = table.columns.joinToString(", ") { "`${it.name}` ${it.nativeType()}" }
        execute("CREATE TABLE $DATABASE.${table.name} ($columns) ENGINE = $engine")
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
        }
        return if (nullable) "Nullable($base)" else base
    }

    fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        is Long, is Int -> value.toString()
        is BigDecimal -> value.toPlainString()
        is Instant -> "fromUnixTimestamp64Milli(${value.toEpochMilli()}, 'UTC')"
        else -> error("Unsupported test value [$value].")
    }
}
