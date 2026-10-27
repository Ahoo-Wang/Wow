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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkIntegrationTest
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinks
import me.ahoo.wow.clickhouse.sink.SinkFixtures
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * A `_state_flat` view the 6.4b Sink writes, declared as a dataset (decision D145): the system columns are its scope,
 * identity and event time, the state's top-level scalars its fields, and its decimals stay exact (D122).
 */
class StateFlatDatasetTest {
    private val database = "wow_flat"
    private val factory = ClickHouseDatasetQueryBackendFactory(
        ClickHouseContainers.client,
        ClickHouseDatasetOptions(database = database),
    )

    private val dataset = QueryDataset.of(AggregateType("shop", "orders")) {
        tenant("tenant_id")
        owner("owner_id")
        space("space_id")
        identity("id")
        eventTime("commit_time")
        storage(WowJson.mapper.createObjectNode().put("table", "shop_order_state_flat"))
        listOf("id", "tenant_id", "aggregate_id", "owner_id", "space_id", "status").forEach { name ->
            field(name) {
                types(QueryValueType.STRING)
                nullable(false)
            }
        }
        field("note") { types(QueryValueType.STRING) }
        listOf("version", "quantity").forEach { name ->
            field(name) {
                types(QueryValueType.INTEGER)
                nullable(false)
            }
        }
        listOf("total", "price", "rate").forEach { name ->
            field(name) {
                types(QueryValueType.DECIMAL)
                nullable(false)
            }
        }
        field("discount") { types(QueryValueType.DECIMAL) }
        listOf("deleted", "paid").forEach { name ->
            field(name) {
                types(QueryValueType.BOOLEAN)
                nullable(false)
            }
        }
        field("commit_time") {
            types(QueryValueType.STRING)
            semantic(Temporal.Date)
            nullable(false)
        }
    }

    private suspend fun target(): DatasetTarget {
        ClickHouseContainers.execute("DROP DATABASE IF EXISTS $database")
        ClickHouseContainers.execute("CREATE DATABASE $database")
        val sink = ClickHouseSinks(ClickHouseSinkIntegrationTest.client, SinkFixtures.MANIFEST, ClickHouseSinkOptions(database = database))
            .sinkOf("shop", "order")
        sink.write(listOf(SinkFixtures.recordOf(version = 1)))
        sink.write(listOf(SinkFixtures.recordOf(version = 2, state = SinkFixtures.stateOf(2, total = "99.90"))))
        sink.write(
            listOf(
                SinkFixtures.recordOf(id = "order-2", state = SinkFixtures.stateOf(1, total = "0.10"))
                    .copyWith(scope = Scope("owner-2", "space-1")),
            ),
        )
        sink.write(
            listOf(SinkFixtures.recordOf(id = "order-3", tenantId = "tenant-2", state = SinkFixtures.stateOf(1, total = "5.55"))),
        )
        return DatasetTarget(dataset, factory)
    }

    @Test
    fun `the flat view should be served as a dataset with exact decimals`(): Unit = runBlocking {
        val target = target()
        val schema = target.schema()
        val rows = target.backend.list(
            QueryAdmission.Trusted.list(
                ListQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("aggregate_id"), QueryField("total"), QueryField("version"))),
                    sort = listOf(Sort(QueryField("total"), Sort.Direction.DESC)),
                ),
                schema,
            ),
        ).toList()
        rows.map { it.get("aggregate_id").stringValue() }.assert().containsExactly("order-1", "order-3", "order-2")
        rows.map { it.get("total").decimalValue() }.assert()
            .containsExactly(BigDecimal("99.90"), BigDecimal("5.55"), BigDecimal("0.10"))
        rows.first().get("version").longValue().assert().isEqualTo(2L)
        rows.first().propertyNames().assert().containsExactlyInAnyOrder("aggregate_id", "total", "version")

        target.backend.countBounded(QueryAdmission.Trusted.count(CountQuery(TenantIdFilter("tenant-1")), schema))
            .count.assert().isEqualTo(2L)
        val since = Instant.ofEpochMilli(1_760_000_000_125L).toString()
        target.backend.list(
            QueryAdmission.Trusted.list(ListQuery(GreaterThanOrEqualFilter(QueryField("commit_time"), since)), schema),
        ).toList().map { it.get("id").stringValue() }.assert().containsExactly("shop.order/tenant-1/order-1@2")

        val sums = target.backend.aggregate(
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(QueryField("tenant_id"), "tenant")),
                    metrics = listOf(
                        AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField("total")), "gmv"),
                        AggregationMetric.Count("orders"),
                    ),
                ),
                schema,
            ),
        ).toList()
        sums.map { it.get("tenant").stringValue() }.assert().containsExactly("tenant-1", "tenant-2")
        sums.map { it.get("gmv").decimalValue() }.assert().containsExactly(BigDecimal("100.00"), BigDecimal("5.55"))
    }

    @Test
    fun `the gateway should restrict the flat view to the caller's tenant, owner and space`(): Unit = runBlocking {
        val target = target()
        val gateways = DatasetQueryGateways.of(target.datasets, target.catalog, factory, policies = listOf(ScopeQueryPolicy()))
        val gateway = checkNotNull(gateways[dataset.name])
        val owner2 = QueryCaller(Caller(tenantId = "tenant-1", scope = Scope("owner-2", "space-1")), entry = QueryEntry.HTTP)
        gateway.list(ListQuery(MatchAllFilter, limit = 10), owner2).toList().map { it.get("aggregate_id").stringValue() }
            .assert().containsExactly("order-2")
        gateway.count(CountQuery(MatchAllFilter), QueryCaller(Caller(tenantId = "tenant-1"), entry = QueryEntry.HTTP)).count
            .assert().isEqualTo(2L)
        gateway.count(CountQuery(MatchAllFilter), QueryCaller(Caller(tenantId = "tenant-2", scope = Scope("owner-2")), entry = QueryEntry.HTTP))
            .count.assert().isEqualTo(0L)
    }

    @Test
    fun `a cursor should walk the flat view by its event time`(): Unit = runBlocking {
        val target = target()
        val schema = target.schema()
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = CursorQuery(
                MatchAllFilter,
                sort = listOf(Sort(QueryField("commit_time"), Sort.Direction.DESC), Sort(QueryField("id"), Sort.Direction.ASC)),
                size = 2,
                cursor = cursor,
            )
            val page = target.backend.cursor(QueryAdmission.Trusted.cursor(query, schema))
            ids += page.list.map { it.get("aggregate_id").stringValue() }
            cursor = page.nextCursor
        } while (cursor != null && ids.size < 10)
        ids.assert().containsExactly("order-1", "order-2", "order-3")
    }
}
