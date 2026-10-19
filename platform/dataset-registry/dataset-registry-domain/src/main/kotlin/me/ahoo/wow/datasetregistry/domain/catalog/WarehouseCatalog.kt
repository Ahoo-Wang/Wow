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

package me.ahoo.wow.datasetregistry.domain.catalog

import me.ahoo.wow.datasetregistry.api.source.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.api.source.SourceReference

/**
 * The warehouse's metadata, the base of the data-source catalog (decision D241 ①): the tables and views of the
 * configured databases ([databases]) of one warehouse [connection], each a source identified by (connection, database,
 * table), and the `wow_catalog` rows the Wow Sink appended of them (D241 ②). 10.0 has the ClickHouse implementation
 * (`system.tables`, `system.columns`, `<database>.wow_catalog`); the service binds it.
 */
interface WarehouseCatalog {
    /** The name of the warehouse connection, the first part of each source's identity. */
    val connection: String

    /** The databases whose tables are sources. */
    val databases: List<String>

    /** The tables and views of [database] that may be sources, without their columns. */
    suspend fun tables(database: String): List<WarehouseTableInfo>

    /** [database]'s [table] with its columns, or null when the warehouse has none of that name. */
    suspend fun table(database: String, table: String): WarehouseTable?

    /** Every `wow_catalog` row of [database]'s [table], whoever wrote it; none without `wow_catalog`. */
    suspend fun catalogRows(database: String, table: String): List<CatalogRow>

    /** When each table of [database] last got a `wow_catalog` row, by its name (decision D241 ④). */
    suspend fun catalogChanges(database: String): Map<String, String>

    companion object {
        /** A warehouse that has no table: the service's catalog without a warehouse connection. */
        @JvmField
        val NONE: WarehouseCatalog = object : WarehouseCatalog {
            override val connection: String = "none"
            override val databases: List<String> = emptyList()
            override suspend fun tables(database: String): List<WarehouseTableInfo> = emptyList()
            override suspend fun table(database: String, table: String): WarehouseTable? = null
            override suspend fun catalogRows(database: String, table: String): List<CatalogRow> = emptyList()
            override suspend fun catalogChanges(database: String): Map<String, String> = emptyMap()
        }
    }
}

/** A table or view of the warehouse as its list says: its [name], [uuid], [engine], and when its metadata last changed. */
data class WarehouseTableInfo(val name: String, val uuid: String, val engine: String, val modifiedAt: String)

/** [database]'s table [name] as the warehouse has it now: its [uuid], its [engine] and its [columns], in order. */
data class WarehouseTable(
    val database: String,
    val name: String,
    val uuid: String,
    val engine: String,
    val columns: List<SourceColumn>,
)

/**
 * One `wow_catalog` row (design 08 §8, decision D241 ②): what the Wow Sink says of [database]'s [table] (whose uuid it
 * read as [tableUuid] and whose columns it expects to hash to [columnsHash]), and the account that wrote it
 * ([writer], the server's `currentUser()`, which no insert can set).
 */
data class CatalogRow(
    val database: String,
    val table: String,
    val tableUuid: String,
    val role: String,
    val service: String,
    val context: String,
    val aggregate: String,
    val ownership: String,
    val tenantColumn: String,
    val ownerColumn: String,
    val spaceColumn: String,
    val sensitive: List<FieldSensitivity>,
    val references: List<SourceReference>,
    val eventRevisions: Map<String, Int>,
    val fingerprint: String,
    val columnsHash: String,
    val writtenAt: String,
    val writer: String,
)
