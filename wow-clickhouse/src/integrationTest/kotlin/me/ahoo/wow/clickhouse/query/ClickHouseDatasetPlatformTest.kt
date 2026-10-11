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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dataset.DefaultDatasetQueryGateway
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.query.target
import org.junit.jupiter.api.Test

/** A table without a tenant column, served as a platform-level dataset. */
class ClickHouseDatasetPlatformTest {
    private val database = ClickHouseContainers.DATABASE

    @Test
    fun `the platform tenant reads every row and any other tenant reads none`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.skus")
        ClickHouseContainers.execute("CREATE TABLE $database.skus (sku String, price Int64) ENGINE = MergeTree ORDER BY sku")
        ClickHouseContainers.execute("INSERT INTO $database.skus VALUES ('a', 1), ('b', 2), ('c', 3)")
        val dataset = QueryDataset.of(MaterializedNamedAggregate("erp", "skus")) {
            platform()
            identity("sku")
            storage(JsonSerializer.createObjectNode().put("table", "$database.skus"))
            field("sku") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("price") {
                types(QueryValueType.INTEGER)
                nullable(false)
            }
        }
        val target = ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client)).target(dataset)
        val gateway = DefaultDatasetQueryGateway(dataset, target.backend, target.schemaProvider)
        fun count(tenant: String): Long = gateway.count(MatchAllFilter)
            .contextWrite { it.withQueryScope(TenantIdFilter(tenant)) }
            .block()!!
        count(TenantId.DEFAULT_TENANT_ID).assert().isEqualTo(3L)
        count("t1").assert().isEqualTo(0L)
    }

    @Test
    fun `a dataset naming a source is read from that source, not the default one`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.prices")
        ClickHouseContainers.execute("CREATE TABLE $database.prices (sku String, price Int64) ENGINE = MergeTree ORDER BY sku")
        ClickHouseContainers.execute("INSERT INTO $database.prices VALUES ('a', 1), ('b', 2)")
        val unreachable = com.clickhouse.client.api.Client.Builder().addEndpoint("http://localhost:1")
            .setUsername("default").setPassword("").build()
        val factory = ClickHouseDatasetQueryBackendFactory(
            ClickHouseExecutor(unreachable),
            ClickHouseDatasetOptions(defaultDatabase = "nowhere"),
            mapOf("warehouse" to ClickHouseSource(ClickHouseExecutor(ClickHouseContainers.client), database)),
        )
        val dataset = QueryDataset.of(MaterializedNamedAggregate("erp", "prices")) {
            platform()
            storage(JsonSerializer.createObjectNode().put("table", "prices").put("source", "warehouse"))
            field("sku") {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        val target = factory.target(dataset)
        DefaultDatasetQueryGateway(dataset, target.backend, target.schemaProvider).count(MatchAllFilter)
            .contextWrite { it.withQueryScope(TenantIdFilter(TenantId.DEFAULT_TENANT_ID)) }
            .block().assert().isEqualTo(2L)
        unreachable.close()
    }
}
