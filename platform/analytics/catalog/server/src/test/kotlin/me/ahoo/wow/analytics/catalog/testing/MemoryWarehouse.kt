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

package me.ahoo.wow.analytics.catalog.testing

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouse
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogRow
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTable
import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseTableInfo
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A warehouse in memory, which a test fills: its tables, by `<database>.<name>`, and their `wow_catalog` rows. */
class MemoryWarehouse(override val connection: String = "warehouse") : WarehouseCatalog {
    override val engine: String = "clickhouse"
    val tables = ConcurrentHashMap<String, WarehouseTable>()
    val rows = CopyOnWriteArrayList<CatalogRow>()

    override val databases: List<String> = listOf(DATABASE)

    /** How many times the tables were listed: the watcher lists them once a look. */
    val listings = AtomicInteger()

    fun put(table: WarehouseTable) {
        tables["${table.database}.${table.name}"] = table
    }

    override suspend fun tables(database: String): List<WarehouseTableInfo> = tables.values
        .also { listings.incrementAndGet() }
        .filter { it.database == database }
        // A metadata time that never changes: the watcher tells a change by the columns' fingerprint.
        .map { WarehouseTableInfo(it.name, it.uuid, it.tableEngine, "0", it.columns.hashCode().toString()) }

    override suspend fun table(database: String, table: String): WarehouseTable? = tables["$database.$table"]

    override suspend fun catalogRows(database: String, table: String): List<CatalogRow> =
        rows.filter { it.database == database && it.table == table }

    override suspend fun catalogChanges(database: String): Map<String, String> =
        rows.filter { it.database == database }.groupBy { it.table }.mapValues { (_, it) -> it.maxOf { row -> row.writtenAt } }

    companion object {
        const val DATABASE: String = "analytics"
    }
}

/** The datasets' views in memory: those created, by `<database>.<view>`, and those dropped. */
class MemoryDatasetWarehouse : DatasetWarehouse {
    val created = ConcurrentHashMap<String, DatasetArtifact>()
    val dropped = CopyOnWriteArrayList<String>()

    override suspend fun create(artifact: DatasetArtifact): List<SourceColumn> {
        created["${artifact.database}.${artifact.view}"] = artifact
        return columnsOf(artifact)
    }

    private fun columnsOf(artifact: DatasetArtifact): List<SourceColumn> {
        val scope = listOfNotNull(artifact.scope.tenant, artifact.scope.owner, artifact.scope.space)
        return (scope + artifact.fields.map { it.name }).map { SourceColumn(it, "String") }
    }

    /** The columns of a view created, or that the published artifact's select gives: text, by the artifact's names. */
    override suspend fun describe(select: String): List<SourceColumn>? {
        val artifact = created.values.firstOrNull {
            select == it.select || select.replace("`", "") == "SELECT * FROM ${it.database}.${it.view}"
        }
            ?: created.values.firstOrNull { it.select == select }
        return artifact?.let { columnsOf(it) }
    }

    override suspend fun drop(database: String, views: List<String>) {
        views.forEach {
            created.remove("$database.$it")
            dropped += "$database.$it"
        }
    }
}

/** Puts a [MemoryWarehouse] in place of the ClickHouse catalog, and a [MemoryDatasetWarehouse] of the views. */
@Configuration(proxyBeanMethods = false)
class MemoryWarehouseConfiguration {
    @Bean
    @Primary
    fun memoryWarehouse(): MemoryWarehouse = MemoryWarehouse()

    @Bean
    @Primary
    fun memoryDatasetWarehouse(): MemoryDatasetWarehouse = MemoryDatasetWarehouse()
}
