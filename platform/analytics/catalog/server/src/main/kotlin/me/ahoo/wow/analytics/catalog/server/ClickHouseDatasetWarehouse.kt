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
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouse
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouseException
import me.ahoo.wow.spi.json.WowJson

/**
 * The datasets' views on a ClickHouse server (decision D251 ④), over the host's client-v2 [client]: a publication
 * creates its database if it is not there, runs its DDL and reads the view's columns back (a view whose column a
 * dataset cannot serve, such as a map, is dropped again and refused); the end of a retirement drops the views. The account
 * needs `CREATE DATABASE`, `CREATE VIEW` and `DROP VIEW` on the datasets' database, and `SELECT` on the sources the
 * views read.
 */
class ClickHouseDatasetWarehouse(private val client: Client) : DatasetWarehouse {
    override suspend fun create(artifact: DatasetArtifact): List<SourceColumn> {
        execute("CREATE DATABASE IF NOT EXISTS ${quote(artifact.database)}")
        execute(artifact.ddl)
        val columns = columns(artifact.database, artifact.view)
        val unserved = columns.filterNot { ClickHouseDatasetColumns.servable(it.type) }
        if (unserved.isNotEmpty()) {
            drop(artifact.database, listOf(artifact.view))
            throw DatasetWarehouseException(
                "a dataset serves no column of the types of ${unserved.map { "${it.name} ${it.type}" }}.",
            )
        }
        return columns
    }

    override suspend fun drop(database: String, views: List<String>) {
        views.forEach { execute("DROP VIEW IF EXISTS ${quote(database)}.${quote(it)}") }
    }

    private suspend fun columns(database: String, view: String): List<SourceColumn> = withContext(Dispatchers.IO) {
        val sql = "SELECT name, type FROM system.columns WHERE database = {database:String} AND table = {view:String} " +
            "ORDER BY position"
        val settings = QuerySettings().setFormat(ClickHouseFormat.JSONEachRow)
        client.query(sql, mapOf("database" to database, "view" to view), settings).await().use { response ->
            response.inputStream.bufferedReader().lineSequence().filter(String::isNotBlank)
                .map { WowJson.mapper.readTree(it) }
                .map { SourceColumn(it["name"].asString(), it["type"].asString()) }
                .toList()
        }
    }

    private suspend fun execute(sql: String) = withContext(Dispatchers.IO) {
        try {
            client.execute(sql).await().close()
        } catch (
            @Suppress("TooGenericExceptionCaught") // The client raises the server's refusal as any exception.
            failed: Exception,
        ) {
            throw DatasetWarehouseException(failed.message ?: failed.javaClass.name, failed)
        }
    }

    private fun quote(name: String): String = "`${name.replace("\\", "\\\\").replace("`", "\\`")}`"
}
