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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetColumn
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoin
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKey
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKind
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSource
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceColumn
import me.ahoo.wow.analytics.catalog.api.source.SourceDrafted
import me.ahoo.wow.analytics.catalog.api.source.SourceReviewed
import me.ahoo.wow.analytics.catalog.api.source.SourceRevised
import me.ahoo.wow.analytics.catalog.api.source.SourceSensitivityReviewed
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichment
import me.ahoo.wow.analytics.catalog.api.source.WowEnrichmentAdopted
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetCompilation
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetCompiler
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * The compiled views on a ClickHouse server (decision D259 7.2b-2): a wide table over a Wow table that merges its
 * rows, an analyst's table and a platform dimension creates as one view per version, reads each row's latest
 * state, keeps the left join's empty side `NULL`, reads values inside the JSON state, and applies its filters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetViewIntegrationTest {
    private val container = WowContainers.clickHouse
    private val client: Client = Client.Builder()
        .addEndpoint(container.httpUrl)
        .setUsername(container.username)
        .setPassword(container.password)
        .build()

    init {
        listOf(DATABASE, DatasetCompiler.DEFAULT_DATABASE).forEach {
            execute("DROP DATABASE IF EXISTS $it")
            execute("CREATE DATABASE $it")
        }
        execute(
            "CREATE TABLE $DATABASE.shop_member_state (id String, tenant_id String, owner_id String, version UInt32, " +
                "state String) ENGINE = ReplacingMergeTree(version) ORDER BY (tenant_id, id)",
        )
        execute(
            "CREATE TABLE $DATABASE.orders (tenant String, id String, member_id String, region String, " +
                "amount Decimal(18, 2), placed_at DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY (tenant, id)",
        )
        execute("CREATE TABLE $DATABASE.regions (code String, name String) ENGINE = MergeTree ORDER BY code")
        execute(
            "INSERT INTO $DATABASE.shop_member_state VALUES " +
                "('m1', 't1', 'u1', 1, '{\"customer\":{\"phone\":\"13800000000\",\"name\":\"Old\"}}'), " +
                "('m1', 't1', 'u1', 2, '{\"customer\":{\"phone\":\"13900000000\",\"name\":\"Ann\"}}'), " +
                "('m1', 't2', 'u2', 1, '{\"customer\":{\"phone\":\"13700000000\",\"name\":\"Other tenant\"}}')",
        )
        execute(
            "INSERT INTO $DATABASE.orders VALUES ('t1', 'o1', 'm1', 'n', 10.50, '2026-10-03 10:00:00'), " +
                "('t1', 'o2', 'nobody', 's', 3.00, '2026-09-30 23:00:00'), ('t1', 'o3', 'm1', 'n', 0, '2026-10-01 00:00:00')",
        )
        execute("INSERT INTO $DATABASE.regions VALUES ('n', 'North'), ('s', 'South')")
    }

    @AfterAll
    fun close() {
        client.close()
    }

    private fun execute(sql: String) {
        client.execute(sql).get(1, TimeUnit.MINUTES).close()
    }

    @Test
    fun `a compiled view should serve the wide table's rows`() {
        val compiled = DatasetCompiler.compile(DEFINITION, SOURCES, "shop", "orders", 1)
        val artifact = (compiled as DatasetCompilation.Compiled).artifact
        execute(artifact.ddl)
        // A view per version: a second one leaves the first in place, and a replaced one is the new definition.
        execute((DatasetCompiler.compile(DEFINITION, SOURCES, "shop", "orders", 2) as DatasetCompilation.Compiled).artifact.ddl)
        execute(artifact.ddl)
        client.queryAll("SELECT name FROM system.tables WHERE database = '${artifact.database}' ORDER BY name")
            .map { it.getString("name") }.assert().containsExactly("ds_shop_orders_v1", "ds_shop_orders_v2")

        val rows = client.queryAll("SELECT * FROM ${artifact.database}.${artifact.view} ORDER BY order_id")
        rows.map { it.getString("order_id") }.assert().containsExactly("o1", "o2")
        val first = rows[0]
        first.getString("tenant_id").assert().isEqualTo("t1")
        first.getString("member_name").assert().isEqualTo("Ann")
        first.getString("member_phone").assert().isEqualTo("13900000000")
        first.getString("region").assert().isEqualTo("North")
        first.getString("month").assert().startsWith("2026-10-01")
        first.getString("big").assert().isEqualTo("1")
        // The left join's empty side is NULL, not the type's default.
        rows[1].hasValue("member_name").assert().isFalse()
        rows[1].getString("region").assert().isEqualTo("South")
    }

    private companion object {
        const val DATABASE = "wow_dataset_it"
        val MEMBERS_ID = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "shop_member_state")
        val ORDERS_ID = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "orders")
        val REGIONS_ID = AnalyticsCatalogContext.sourceIdOf("warehouse", DATABASE, "regions")

        val MEMBERS = SourceState().apply {
            onSourcing(
                WowEnrichmentAdopted(
                    connection = "warehouse",
                    engine = "clickhouse",
                    database = DATABASE,
                    table = "shop_member_state",
                    tableUuid = "u",
                    tableEngine = "ReplacingMergeTree",
                    columns = listOf(
                        SourceColumn("id", "String"),
                        SourceColumn("tenant_id", "String"),
                        SourceColumn("owner_id", "String"),
                        SourceColumn("version", "UInt32"),
                        SourceColumn("state", "String"),
                    ),
                    enrichment = WowEnrichment("shop-service", "shop", "member", "state", "TENANT", "f", "w", "t"),
                    tenantColumn = "tenant_id",
                    ownerColumn = "owner_id",
                    sensitivity = listOf(
                        FieldSensitivity("state.customer.phone", SensitivityLevel.DISPLAY, keepPrefix = 3),
                        FieldSensitivity("state", SensitivityLevel.DISPLAY),
                    ),
                ),
            )
        }
        val ORDERS = analyst(
            "orders",
            listOf("tenant", "id", "member_id", "region").map { SourceColumn(it, "String") } +
                SourceColumn("amount", "Decimal(18, 2)") + SourceColumn("placed_at", "DateTime64(3, 'UTC')"),
            tenant = "tenant",
        )
        val REGIONS = analyst("regions", listOf(SourceColumn("code", "String"), SourceColumn("name", "String")))
        val SOURCES = mapOf(MEMBERS_ID to MEMBERS, ORDERS_ID to ORDERS, REGIONS_ID to REGIONS)

        val DEFINITION = DatasetDefinition(
            sources = listOf(DatasetSource("o", ORDERS_ID), DatasetSource("m", MEMBERS_ID), DatasetSource("r", REGIONS_ID)),
            subject = "o",
            joins = listOf(
                DatasetJoin(
                    "m",
                    listOf(DatasetJoinKey("o.tenant", "m.tenant_id"), DatasetJoinKey("o.member_id", "m.id")),
                    DatasetJoinKind.LEFT,
                ),
                DatasetJoin("r", listOf(DatasetJoinKey("o.region", "r.code"))),
            ),
            columns = listOf(
                DatasetColumn("order_id", from = "o.id"),
                DatasetColumn("member_name", expression = "nullIf(m.state.customer.name, '')"),
                DatasetColumn("member_phone", from = "m.state.customer.phone"),
                DatasetColumn("region", from = "r.name"),
                DatasetColumn("month", expression = "toStartOfMonth(o.placed_at)"),
                DatasetColumn("big", expression = "o.amount >= 10 and o.region in ('n', 'e')"),
            ),
            filters = listOf(
                "o.amount > 0",
                "o.region != 'x' and containsIgnoreCase(r.name, 'O') and o.amount between 0.01 and 1000",
                "startsWithIgnoreCase(o.id, 'O') and m.state.customer.name not in ('Old')",
            ),
            identity = "order_id",
        )

        fun analyst(table: String, columns: List<SourceColumn>, tenant: String? = null): SourceState = SourceState().apply {
            onSourcing(SourceDrafted("warehouse", "clickhouse", DATABASE, table, "u", "MergeTree", columns))
            onSourcing(SourceRevised(tenant, platform = tenant == null))
            onSourcing(SourceSensitivityReviewed(columns.map { FieldSensitivity(it.name) }))
            onSourcing(SourceReviewed)
        }
    }
}
