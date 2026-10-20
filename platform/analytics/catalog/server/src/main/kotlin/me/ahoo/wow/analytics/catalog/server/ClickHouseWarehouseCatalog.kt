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

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceReference
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTableInfo
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The [WarehouseCatalog] of a ClickHouse server (decision D241 ①), over the host's client-v2 [client]:
 *
 * - the sources are the tables and views of [databases] that `system.tables` lists, but for the materialized views
 *   (their data is in the tables they write `TO`), their inner tables and `wow_catalog` itself;
 * - a table's columns are `system.columns`', in order;
 * - the `wow_catalog` rows of a table are those of format 1 in its database's `wow_catalog` (design 08 §8), read
 *   with `FINAL`: a row of the same content written again is one row. A database without `wow_catalog` has none.
 *
 * Each statement's result is read as `JSONEachRow` on [Dispatchers.IO].
 */
class ClickHouseWarehouseCatalog(
    private val client: Client,
    override val connection: String,
    override val databases: List<String>,
) : WarehouseCatalog {
    override val engine: String = ENGINE

    override suspend fun tables(database: String): List<WarehouseTableInfo> = rows(
        "SELECT name, toString(uuid) AS uuid, engine, toString(metadata_modification_time) AS modified " +
            "FROM system.tables WHERE database = {database:String} AND $SOURCE_TABLES ORDER BY name",
        mapOf("database" to database),
    ).map { WarehouseTableInfo(it.text("name"), it.text("uuid"), it.text("engine"), it.text("modified")) }

    override suspend fun table(database: String, table: String): WarehouseTable? {
        val parameters = mapOf("database" to database, "table" to table)
        val info = rows(
            "SELECT toString(uuid) AS uuid, engine FROM system.tables " +
                "WHERE database = {database:String} AND name = {table:String} AND $SOURCE_TABLES",
            parameters,
        ).firstOrNull() ?: return null
        val columns = rows(
            "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {table:String} " +
                "ORDER BY position",
            parameters,
        ).map { SourceColumn(it.text("name"), it.text("type")) }
        return WarehouseTable(database, table, info.text("uuid"), info.text("engine"), columns)
    }

    override suspend fun catalogRows(database: String, table: String): List<CatalogRow> {
        if (!hasCatalog(database)) {
            return emptyList()
        }
        return rows(
            "SELECT `database`, `table`, toString(table_uuid) AS table_uuid, role, service, context, aggregate, " +
                "ownership, tenant_column, owner_column, space_column, sensitive, `references`, event_revisions, " +
                "fingerprint, columns_hash, toString(written_at) AS written_at, writer " +
                "FROM ${identifier(database)}.$CATALOG FINAL " +
                "WHERE format = $FORMAT AND `database` = {database:String} AND `table` = {table:String}",
            mapOf("database" to database, "table" to table),
        ).map(::catalogRowOf)
    }

    override suspend fun catalogChanges(database: String): Map<String, String> {
        if (!hasCatalog(database)) {
            return emptyMap()
        }
        return rows(
            "SELECT `table`, toString(max(written_at)) AS written_at FROM ${identifier(database)}.$CATALOG " +
                "WHERE `database` = {database:String} GROUP BY `table`",
            mapOf("database" to database),
        ).associate { it.text("table") to it.text("written_at") }
    }

    private suspend fun hasCatalog(database: String): Boolean = rows(
        "SELECT name FROM system.tables WHERE database = {database:String} AND name = '$CATALOG'",
        mapOf("database" to database),
    ).isNotEmpty()

    private suspend fun rows(sql: String, parameters: Map<String, Any>): List<JsonNode> = withContext(Dispatchers.IO) {
        client.query(sql, parameters, QuerySettings().setFormat(ClickHouseFormat.JSONEachRow)).await().use { response ->
            response.inputStream.bufferedReader().lineSequence().filter(String::isNotBlank).map(json::readTree).toList()
        }
    }

    override fun toString(): String = "ClickHouseWarehouseCatalog($connection, $databases)"

    internal companion object {
        /** The engine type of a ClickHouse connection (decision D251 ⑤). */
        const val ENGINE: String = "clickhouse"
        const val CATALOG: String = "wow_catalog"
        const val FORMAT: Int = 1
        private const val SOURCE_TABLES =
            "engine != 'MaterializedView' AND name != '$CATALOG' AND NOT startsWith(name, '.inner')"
        private val json: JsonMapper = JsonMapper.builder().build()

        fun identifier(name: String): String = "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"

        private fun JsonNode.text(name: String): String = get(name)?.asString().orEmpty()

        fun catalogRowOf(row: JsonNode): CatalogRow = CatalogRow(
            database = row.text("database"),
            table = row.text("table"),
            tableUuid = row.text("table_uuid"),
            role = row.text("role"),
            service = row.text("service"),
            context = row.text("context"),
            aggregate = row.text("aggregate"),
            ownership = row.text("ownership"),
            tenantColumn = row.text("tenant_column"),
            ownerColumn = row.text("owner_column"),
            spaceColumn = row.text("space_column"),
            sensitive = row["sensitive"]?.values()?.map(::sensitivityOf).orEmpty(),
            references = row["references"]?.values()?.map {
                SourceReference(it.text("path"), it.text("context"), it.text("aggregate"))
            }.orEmpty(),
            eventRevisions = row["event_revisions"]?.properties()?.associate { (name, revision) ->
                name to revision.asString().toInt()
            }.orEmpty(),
            fingerprint = row.text("fingerprint"),
            columnsHash = row.text("columns_hash"),
            writtenAt = row.text("written_at"),
            writer = row.text("writer"),
        )

        /**
         * One `sensitive` entry: its level as the Sink wrote it, a level this catalog does not know read as the
         * highest, and its `x-wow-mask` (`{"keepPrefix", "keepSuffix"}` or `{"strategy"}`) as the mask.
         */
        private fun sensitivityOf(entry: JsonNode): FieldSensitivity {
            val mask = entry.text("mask").takeIf { it.isNotBlank() }?.let(json::readTree)
            return FieldSensitivity(
                path = entry.text("path"),
                level = SensitivityLevel.entries.firstOrNull { it.name == entry.text("level") }
                    ?: SensitivityLevel.CONFIDENTIAL,
                keepPrefix = mask?.get("keepPrefix")?.asInt() ?: 0,
                keepSuffix = mask?.get("keepSuffix")?.asInt() ?: 0,
                strategy = mask?.get("strategy")?.asString(),
                event = entry.text("event"),
                revision = entry["revision"]?.asString()?.toIntOrNull() ?: 0,
            )
        }
    }
}
