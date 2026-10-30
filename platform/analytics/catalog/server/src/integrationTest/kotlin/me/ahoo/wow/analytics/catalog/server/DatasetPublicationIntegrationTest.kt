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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetField
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetScope
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetWarehouseException
import me.ahoo.wow.analytics.catalog.domain.warehouse.CatalogTrust
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.TENANT
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver.Companion.USER
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * A wide table end to end on a ClickHouse server (roadmap 9.3b): a Wow table the catalog adopts from its trusted
 * `wow_catalog` row and an analyst's table, joined, published into the datasets' database and queried through the
 * dataset route, each sensitive value masked as its source says, each tenant reading its own rows; then retired.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetPublicationIntegrationTest {
    private val container = WowContainers.clickHouse
    private val client: Client = Client.Builder()
        .addEndpoint(container.httpUrl)
        .setUsername(container.username)
        .setPassword(container.password)
        .build()

    init {
        listOf(DATABASE, VIEWS).forEach { execute("DROP DATABASE IF EXISTS $it") }
        execute("CREATE DATABASE $DATABASE")
        execute(
            "CREATE TABLE $DATABASE.shop_order_state (id String, tenant_id String, owner_id String, version UInt32, " +
                "state String) ENGINE = ReplacingMergeTree(version) ORDER BY (tenant_id, id)",
        )
        execute(
            "CREATE TABLE $DATABASE.customers (tenant String, id String, name String, phone Nullable(String)) " +
                "ENGINE = MergeTree ORDER BY (tenant, id)",
        )
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
        execute(
            "INSERT INTO $DATABASE.shop_order_state VALUES " +
                "('o1', 't1', 'u1', 1, '{\"customerId\":\"c1\",\"phone\":\"13800000000\"}'), " +
                "('o1', 't1', 'u1', 2, '{\"customerId\":\"c1\",\"phone\":\"13912345678\"}'), " +
                "('o2', 't2', 'u2', 1, '{\"customerId\":\"c1\",\"phone\":\"13700000000\"}')",
        )
        execute(
            "INSERT INTO $DATABASE.customers VALUES ('t1', 'u1', 'Ann', '15011112222'), " +
                "('t2', 'u2', 'Bob', '15033334444')",
        )
    }

    @AfterAll
    fun close() {
        client.close()
    }

    private fun execute(sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    private fun appendRow() {
        val uuid = client.queryAll(
            "SELECT toString(uuid) AS uuid FROM system.tables WHERE database = '$DATABASE' AND name = 'shop_order_state'",
        ).single().getString("uuid")
        val columns = runBlocking {
            ClickHouseWarehouseCatalog(client, "warehouse", listOf(DATABASE)).table(DATABASE, "shop_order_state")!!.columns
        }
        execute(
            "INSERT INTO $DATABASE.wow_catalog (format, `database`, `table`, table_uuid, role, service, context, " +
                "aggregate, ownership, tenant_column, owner_column, space_column, sensitive, `references`, " +
                "event_revisions, fingerprint, columns_hash) VALUES (1, '$DATABASE', 'shop_order_state', '$uuid', " +
                "'state', 'shop-service', 'shop', 'order', 'OWNER', 'tenant_id', 'owner_id', '', " +
                "[('state.phone', 'DISPLAY', '{\"keepPrefix\":3,\"keepSuffix\":4}', '', 0)], [], {}, 'f-1', " +
                "'${CatalogTrust.columnsHash(columns)}')",
        )
    }

    @Test
    fun `a wide table over a Wow table and an analyst's table should be published, queried masked, and retired`() {
        appendRow()
        startCatalog(
            "wow.clickhouse.endpoint" to container.httpUrl,
            "wow.clickhouse.username" to container.username,
            "wow.clickhouse.password" to container.password,
            "wow.clickhouse.database" to DATABASE,
            "wow.analytics.catalog.databases" to DATABASE,
            "wow.analytics.catalog.connection" to "warehouse",
            "wow.analytics.catalog.trusted-writers.${container.username}" to "shop",
            "wow.analytics.catalog.refresh-interval" to "200ms",
            "wow.analytics.catalog.datasets.database" to VIEWS,
            "wow.analytics.catalog.datasets.refresh-interval" to "100ms",
        ).use { node ->
            val orders = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "shop_order_state")
            eventually("the Wow table adopted") {
                node.get("/analytics-catalog/source/$orders/state", Who.VIEWER).status == OK
            }
            val customers = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "customers")
            node.command(customers, "draft-source", """{"database":"$DATABASE","table":"customers"}""").assertOk()
            node.command(customers, "revise-source", """{"tenantColumn":"tenant"}""").assertOk()
            node.command(
                customers,
                "review-source-sensitivity",
                """{"fields":[{"path":"tenant"},{"path":"id"},{"path":"name"},""" +
                    """{"path":"phone","level":"display","keepPrefix":3}]}""",
            ).assertOk()
            node.command(customers, "mark-source-reviewed", "{}", Who.APPROVER).assertOk()

            val id = AnalyticsCatalogContext.datasetIdOf("shop", "order_customers")
            val definition = """{"sources":[{"alias":"o","sourceId":"$orders"},{"alias":"c","sourceId":"$customers"}],""" +
                """"subject":"o","joins":[{"alias":"c","kind":"LEFT","on":[{"left":"o.tenant_id","right":"c.tenant"},""" +
                """{"left":"o.state.customerId","right":"c.id"}]}],"columns":[{"name":"order_id","from":"o.id"},""" +
                """{"name":"order_phone","from":"o.state.phone"},{"name":"customer","from":"c.name"},""" +
                """{"name":"customer_phone","from":"c.phone"}],"identity":"order_id"}"""
            // A join key is a whole column: a value inside the state is refused.
            node.datasetCommand(
                id,
                "draft-dataset",
                """{"context":"shop","name":"order_customers","definition":$definition}""",
                Who.EDITOR,
            ).assertProblem(BAD_REQUEST, "analytics.catalog.definition-invalid")
            val keyed = definition.replace(
                """{"left":"o.state.customerId","right":"c.id"}""",
                """{"left":"o.owner_id","right":"c.id"}""",
            )
            node.datasetCommand(
                id,
                "draft-dataset",
                """{"context":"shop","name":"order_customers","definition":$keyed}""",
                Who.EDITOR,
            ).assertOk()
            node.datasetCommand(id, "publish-dataset", "{}", Who.EDITOR).assertOk()
            client.queryAll("SELECT name FROM system.tables WHERE database = '$VIEWS'").map { it.getString("name") }
                .assert().containsExactly("ds_shop_order_customers_v1")

            val query = """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"order_id","direction":"asc"}]}"""
            // A reader of tenant t1 with no role of the catalog: the datasets are the data plane's.
            val t1 = Who(mapOf(USER to "reader", TENANT to "t1"))
            eventually("the dataset served") { node.post(QUERY, query, t1).status == OK }
            val rows = node.post(QUERY, query, t1).assertOk().json.values().toList()
            rows.map { it["order_id"].asString() }.assert().containsExactly("o1")
            val row = rows.single()
            row["customer"].asString().assert().isEqualTo("Ann")
            // Masked as their sources say: the Wow value by its path's mask, the analyst's by its column's.
            row["order_phone"].asString().assert().startsWith("139").endsWith("5678").isNotEqualTo("13912345678")
            row["customer_phone"].asString().assert().startsWith("150").isNotEqualTo("15011112222")
            val t2 = Who(mapOf(USER to "reader", TENANT to "t2"))
            // The catalog itself stays the catalog's roles': anyone else is refused it (UI-35).
            node.sources(t1).assertProblem(FORBIDDEN, "wow.auth.forbidden")
            node.post(QUERY, query, t2).assertOk().json.values().map { it["order_id"].asString() }.toList()
                .assert().containsExactly("o2")

            // A source's schema changes under the published view: its queries are refused, plainly, not failed.
            execute("ALTER TABLE $DATABASE.customers DROP COLUMN phone")
            eventually("the dataset needing revision") { node.post(QUERY, query, t1).status == CONFLICT }
            node.post(QUERY, query, t1).assertProblem(CONFLICT, "wow.query.dataset-unavailable").json["detail"]
                .asString().assert().contains("needs revision").doesNotContain("phone")
            node.dataset(id)["state"]["status"].asString().assert().isEqualTo("NEEDS_REVISION")
            // Revised without the column and published: served again.
            node.datasetCommand(
                id,
                "revise-dataset",
                """{"definition":${keyed.replace(""",{"name":"customer_phone","from":"c.phone"}""", "")}}""",
                Who.EDITOR,
            ).assertOk()
            node.datasetCommand(id, "publish-dataset", "{}", Who.EDITOR).assertOk()
            eventually("the revision served") { node.post(QUERY, query, t1).status == OK }
            node.post(QUERY, query, t1).assertOk().json.values().single().has("customer_phone").assert().isFalse()

            node.datasetCommand(id, "retire-dataset", "{}", Who.EDITOR).assertOk()
            eventually("the dataset no longer served") { node.post(QUERY, query, t1).status == NOT_FOUND }
            // The views stay for the retention.
            client.queryAll("SELECT name FROM system.tables WHERE database = '$VIEWS'").assert().hasSize(2)
        }
    }

    @Test
    fun `a view with a column a dataset cannot serve should be refused and dropped`(): Unit = runBlocking {
        val warehouse = ClickHouseDatasetWarehouse(client)
        val artifact = DatasetArtifact(
            context = "shop",
            name = "maps",
            version = 1,
            database = VIEWS,
            view = "ds_shop_maps_v1",
            ddl = "CREATE OR REPLACE VIEW `$VIEWS`.`ds_shop_maps_v1` AS SELECT 't' AS tenant_id, map('a', 1) AS m",
            scope = DatasetScope(tenant = "tenant_id"),
            fields = listOf(DatasetField("m")),
            sources = emptyList(),
        )
        assertThrows<DatasetWarehouseException> { warehouse.create(artifact) }.message.assert().contains("m Map(String, UInt8)")
        client.queryAll("SELECT name FROM system.tables WHERE database = '$VIEWS' AND name = 'ds_shop_maps_v1'")
            .assert().isEmpty()
        assertThrows<DatasetWarehouseException> { warehouse.create(artifact.copy(ddl = "CREATE VIEW nonsense")) }
        val created = warehouse.create(
            artifact.copy(ddl = "CREATE OR REPLACE VIEW `$VIEWS`.`ds_shop_maps_v1` AS SELECT 't' AS tenant_id, ['a'] AS m"),
        )
        created.map { it.type }.assert().containsExactly("String", "Array(String)")
        warehouse.describe("SELECT * FROM `$VIEWS`.`ds_shop_maps_v1`")!!.map { it.name }.assert().containsExactly("tenant_id", "m")
        warehouse.describe("SELECT 1 AS one, toString(now()) AS at")!!.map { it.type }.assert().containsExactly("UInt8", "String")
        warehouse.describe("SELECT nothing FROM `$VIEWS`.`ds_shop_maps_v1`").assert().isNull()
        warehouse.drop(VIEWS, listOf("ds_shop_maps_v1", "ds_shop_none_v1"))
    }

    private companion object {
        const val DATABASE = "wow_dataset_publication_it"
        const val VIEWS = "wow_datasets_it"
        const val QUERY = "/wow/dataset/shop/order_customers/query"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
    }
}
