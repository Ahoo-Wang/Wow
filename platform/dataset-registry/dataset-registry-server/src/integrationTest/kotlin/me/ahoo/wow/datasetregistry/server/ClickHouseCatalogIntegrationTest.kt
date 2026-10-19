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

package me.ahoo.wow.datasetregistry.server

import com.clickhouse.client.api.Client
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.datasetregistry.api.source.FieldSensitivity
import me.ahoo.wow.datasetregistry.api.source.SourceColumn
import me.ahoo.wow.datasetregistry.api.source.SourceReference
import me.ahoo.wow.datasetregistry.domain.catalog.CatalogTrust
import me.ahoo.wow.datasetregistry.domain.catalog.TrustedWriters
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * The catalog on a ClickHouse server (decision D241): the tables and views of a database as sources (not its
 * materialized views nor `wow_catalog`), their columns, and the `wow_catalog` rows as the Sink writes them (design
 * 08 §8), its `writer` the inserting account; then the registry service adopting a Wow table from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseCatalogIntegrationTest {
    private val container = WowContainers.clickHouse
    private val client: Client = Client.Builder()
        .addEndpoint(container.httpUrl)
        .setUsername(container.username)
        .setPassword(container.password)
        .build()
    private val catalog = ClickHouseWarehouseCatalog(client, "warehouse", listOf(DATABASE))

    init {
        execute("DROP DATABASE IF EXISTS $DATABASE")
        execute("CREATE DATABASE $DATABASE")
        execute(
            "CREATE TABLE $DATABASE.shop_order_state (id String, tenant_id String, owner_id String, state String) " +
                "ENGINE = ReplacingMergeTree ORDER BY (tenant_id, id)",
        )
        execute("CREATE TABLE $DATABASE.orders (tenant String, phone Nullable(String)) ENGINE = MergeTree ORDER BY tenant")
        execute("CREATE VIEW $DATABASE.orders_view AS SELECT tenant FROM $DATABASE.orders")
        execute("CREATE MATERIALIZED VIEW $DATABASE.orders_mv TO $DATABASE.orders AS SELECT tenant, phone FROM $DATABASE.orders")
        // The table of design 08 §8, as the Sink creates it (ClickHouseCatalog.ddl).
        execute(
            "CREATE TABLE $DATABASE.wow_catalog (`format` UInt32, `database` String, `table` String, " +
                "`table_uuid` UUID, `role` LowCardinality(String), `service` String, `context` String, " +
                "`aggregate` String, `ownership` LowCardinality(String), `tenant_column` String, " +
                "`owner_column` String, `space_column` String, " +
                "`sensitive` Array(Tuple(path String, level String, mask String, event String, revision UInt32)), " +
                "`references` Array(Tuple(path String, context String, aggregate String)), " +
                "`event_revisions` Map(String, UInt32), `fingerprint` String, `columns_hash` String, " +
                "`written_at` DateTime64(3, 'UTC') DEFAULT now64(3), `writer` String MATERIALIZED currentUser()) " +
                "ENGINE = ReplacingMergeTree(written_at) ORDER BY (`database`, `table`, fingerprint)",
        )
    }

    @AfterAll
    fun close() {
        client.close()
    }

    private fun execute(sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    /** Appends the Sink's row of `shop_order_state`, with the table's own uuid and column hash. */
    private fun appendRow(fingerprint: String) {
        val table = runBlocking { catalog.table(DATABASE, STATE) }!!
        val hash = CatalogTrust.columnsHash(table.columns)
        execute(
            "INSERT INTO $DATABASE.wow_catalog (format, `database`, `table`, table_uuid, role, service, context, aggregate, " +
                "ownership, tenant_column, owner_column, space_column, sensitive, `references`, event_revisions, " +
                "fingerprint, columns_hash) VALUES (1, '$DATABASE', '$STATE', '${table.uuid}', 'state', 'shop-service', " +
                "'shop', 'order', 'OWNER', 'tenant_id', 'owner_id', '', " +
                "[('state.phone', 'DISPLAY', '{\"keepPrefix\":3,\"keepSuffix\":4}', '', 0), " +
                "('payload.card', 'SECRET', '', 'order_paid', 2)], [('state.memberId', 'crm', 'member')], " +
                "{'order_paid': 2}, '$fingerprint', '$hash')",
        )
    }

    @Test
    fun `the catalog should list the tables and views, read their columns and the Sink's rows`(): Unit = runBlocking {
        catalog.tables(DATABASE).map { it.name }.assert().containsExactly("orders", "orders_view", STATE)
        catalog.tables(DATABASE).first().uuid.assert().hasSize(UUID_LENGTH)
        val orders = catalog.table(DATABASE, "orders")!!
        orders.engine.assert().isEqualTo("MergeTree")
        orders.columns.assert().containsExactly(SourceColumn("tenant", "String"), SourceColumn("phone", "Nullable(String)"))
        catalog.table(DATABASE, "orders_mv").assert().isNull()
        catalog.table(DATABASE, "nothing").assert().isNull()

        appendRow("f-1")
        appendRow("f-1")
        val row = catalog.catalogRows(DATABASE, STATE).single { it.fingerprint == "f-1" }
        row.writer.assert().isEqualTo(container.username)
        row.context.assert().isEqualTo("shop")
        row.ownerColumn.assert().isEqualTo("owner_id")
        row.spaceColumn.assert().isEmpty()
        row.sensitive.assert().containsExactly(
            FieldSensitivity("state.phone", SensitivityLevel.DISPLAY, keepPrefix = 3, keepSuffix = 4),
            // A level this registry does not know is read as the highest.
            FieldSensitivity("payload.card", SensitivityLevel.CONFIDENTIAL, event = "order_paid", revision = 2),
        )
        row.references.assert().containsExactly(SourceReference("state.memberId", "crm", "member"))
        row.eventRevisions.assert().isEqualTo(mapOf("order_paid" to 2))
        catalog.catalogChanges(DATABASE).keys.assert().containsExactly(STATE)
        val table = catalog.table(DATABASE, STATE)!!
        CatalogTrust.evaluate(table, listOf(row), TrustedWriters.of(mapOf(container.username to listOf("shop"))))
            .assert().isEqualTo(CatalogTrust.Verdict.Trusted(row))
        catalog.catalogRows("default", STATE).assert().isEmpty()
        catalog.catalogChanges("default").assert().isEmpty()
        catalog.toString().assert().contains("warehouse")
    }

    @Test
    fun `the registry should adopt the Wow table its trusted writer describes`() {
        appendRow("f-registry")
        startRegistry(
            "wow.clickhouse.endpoint" to container.httpUrl,
            "wow.clickhouse.username" to container.username,
            "wow.clickhouse.password" to container.password,
            "wow.dataset-registry.databases" to DATABASE,
            "wow.dataset-registry.connection" to "warehouse",
            "wow.dataset-registry.trusted-writers.${container.username}" to "shop",
            "wow.dataset-registry.refresh-interval" to "200ms",
        ).use { registry ->
            val id = DatasetRegistryContext.sourceIdOf("warehouse", DATABASE, STATE)
            eventually("the Wow table adopted") {
                registry.get("/dataset-registry/source/$id/state", Who.VIEWER).status == OK
            }
            val state = registry.state(id)["state"]
            state["kind"].asString().assert().isEqualTo("WOW")
            state["enrichment"]["fingerprint"].asString().assert().isEqualTo("f-registry")
            // An analyst's table drafted from system.columns.
            val orders = DatasetRegistryContext.sourceIdOf("warehouse", DATABASE, "orders")
            registry.command(orders, "draft-source", """{"database":"$DATABASE","table":"orders"}""").assertOk()
            registry.state(orders)["state"]["columns"].values().map { it["type"].asString() }.assert()
                .containsExactly("String", "Nullable(String)")
        }
    }

    private companion object {
        const val DATABASE = "wow_registry_it"
        const val STATE = "shop_order_state"
        const val UUID_LENGTH = 36
        const val OK = 200
    }
}
