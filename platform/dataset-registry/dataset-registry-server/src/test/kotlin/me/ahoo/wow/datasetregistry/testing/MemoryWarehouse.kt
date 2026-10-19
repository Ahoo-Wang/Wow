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

package me.ahoo.wow.datasetregistry.testing

import me.ahoo.wow.datasetregistry.domain.catalog.CatalogRow
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseCatalog
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseTable
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseTableInfo
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A warehouse in memory, which a test fills: its tables, by `<database>.<name>`, and their `wow_catalog` rows. */
class MemoryWarehouse(override val connection: String = "warehouse") : WarehouseCatalog {
    val tables = ConcurrentHashMap<String, WarehouseTable>()
    val rows = CopyOnWriteArrayList<CatalogRow>()

    override val databases: List<String> = listOf(DATABASE)

    fun put(table: WarehouseTable) {
        tables["${table.database}.${table.name}"] = table
    }

    override suspend fun tables(database: String): List<WarehouseTableInfo> = tables.values
        .filter { it.database == database }
        .map { WarehouseTableInfo(it.name, it.uuid, it.engine, it.columns.size.toString()) }

    override suspend fun table(database: String, table: String): WarehouseTable? = tables["$database.$table"]

    override suspend fun catalogRows(database: String, table: String): List<CatalogRow> =
        rows.filter { it.database == database && it.table == table }

    override suspend fun catalogChanges(database: String): Map<String, String> =
        rows.filter { it.database == database }.groupBy { it.table }.mapValues { (_, it) -> it.maxOf { row -> row.writtenAt } }

    companion object {
        const val DATABASE: String = "analytics"
    }
}

/** Puts a [MemoryWarehouse] in place of the ClickHouse catalog. */
@Configuration(proxyBeanMethods = false)
class MemoryWarehouseConfiguration {
    @Bean
    @Primary
    fun memoryWarehouse(): MemoryWarehouse = MemoryWarehouse()
}
