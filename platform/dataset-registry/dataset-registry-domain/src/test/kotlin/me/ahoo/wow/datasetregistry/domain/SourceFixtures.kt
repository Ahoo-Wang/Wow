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

package me.ahoo.wow.datasetregistry.domain

import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.datasetregistry.api.source.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.api.source.SourceReference
import me.ahoo.wow.datasetregistry.domain.catalog.CatalogRow
import me.ahoo.wow.datasetregistry.domain.catalog.CatalogTrust
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseCatalog
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseTable
import me.ahoo.wow.datasetregistry.domain.catalog.WarehouseTableInfo

/** A warehouse in memory: its [tables] and their `wow_catalog` [rows], as a test sets them. */
class FakeCatalog(override val connection: String = CONNECTION) : WarehouseCatalog {
    val tables = linkedMapOf<String, WarehouseTable>()
    val rows = mutableListOf<CatalogRow>()

    override val databases: List<String> = listOf(DATABASE)

    fun put(table: WarehouseTable) {
        tables["${table.database}.${table.name}"] = table
    }

    override suspend fun tables(database: String): List<WarehouseTableInfo> =
        tables.values.filter { it.database == database }.map { WarehouseTableInfo(it.name, it.uuid, it.engine, "") }

    override suspend fun table(database: String, table: String): WarehouseTable? = tables["$database.$table"]

    override suspend fun catalogRows(database: String, table: String): List<CatalogRow> =
        rows.filter { it.database == database && it.table == table }

    override suspend fun catalogChanges(database: String): Map<String, String> =
        rows.filter { it.database == database }.groupBy { it.table }.mapValues { (_, it) -> it.maxOf { row -> row.writtenAt } }

    companion object {
        const val CONNECTION = "warehouse"
        const val DATABASE = "analytics"
    }
}

object SourceFixtures {
    const val SINK = "wow_sink"
    const val ORDERS = "orders"
    const val STATE = "shop_order_state"

    val ORDERS_ID = DatasetRegistryContext.sourceIdOf(FakeCatalog.CONNECTION, FakeCatalog.DATABASE, ORDERS)
    val STATE_ID = DatasetRegistryContext.sourceIdOf(FakeCatalog.CONNECTION, FakeCatalog.DATABASE, STATE)

    /** An analyst's table: a tenant, an owner, a customer's phone, a member id and an amount. */
    val ORDERS_TABLE = WarehouseTable(
        database = FakeCatalog.DATABASE,
        name = ORDERS,
        uuid = "uuid-orders",
        engine = "MergeTree",
        columns = listOf(
            SourceColumn("tenant", "String"),
            SourceColumn("owner", "LowCardinality(String)"),
            SourceColumn("phone", "Nullable(String)"),
            SourceColumn("member_id", "String"),
            SourceColumn("amount", "Decimal(18, 2)"),
        ),
    )

    /** A Wow table: the current state of `shop.order`. */
    val STATE_TABLE = WarehouseTable(
        database = FakeCatalog.DATABASE,
        name = STATE,
        uuid = "uuid-state",
        engine = "ReplacingMergeTree",
        columns = listOf(
            SourceColumn("id", "String"),
            SourceColumn("tenant_id", "String"),
            SourceColumn("owner_id", "String"),
            SourceColumn("space_id", "String"),
            SourceColumn("state", "String"),
        ),
    )

    val PHONE = FieldSensitivity("state.customer.phone", SensitivityLevel.DISPLAY, keepPrefix = 3, keepSuffix = 4)
    val CARD = FieldSensitivity("state.card", SensitivityLevel.CONFIDENTIAL)

    /** The Sink's row of [STATE_TABLE], as [writer] wrote it. */
    fun stateRow(
        writer: String = SINK,
        sensitive: List<FieldSensitivity> = listOf(PHONE, CARD),
        fingerprint: String = "fingerprint-1",
        writtenAt: String = "2026-10-19 10:00:00.000",
        table: WarehouseTable = STATE_TABLE,
        tableUuid: String = table.uuid,
    ): CatalogRow = CatalogRow(
        database = table.database,
        table = table.name,
        tableUuid = tableUuid,
        role = "state",
        service = "shop-service",
        context = "shop",
        aggregate = "order",
        ownership = "OWNER",
        tenantColumn = "tenant_id",
        ownerColumn = "owner_id",
        spaceColumn = "space_id",
        sensitive = sensitive,
        references = listOf(SourceReference("state.memberId", "crm", "member")),
        eventRevisions = mapOf("order_placed" to 1),
        fingerprint = fingerprint,
        columnsHash = CatalogTrust.columnsHash(table.columns),
        writtenAt = writtenAt,
        writer = writer,
    )
}
