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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The names an aggregation gives its own columns stand apart from the table's columns and from the query's aliases:
 * a column or an alias named like one of them reads its own values.
 */
class ClickHouseAggregationNamesTest {
    @Test
    fun `a group's own name should not replace a column of that name in a metric`(): Unit = runBlocking {
        val row = row(
            "agg_names_group",
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(CATEGORY, "category")),
                metrics = listOf(sum("__g0", "total")),
            ),
        )
        row.path("total").longValue().assert().isEqualTo(30L)
    }

    @Test
    fun `a metric's own name should not replace a column of that name in another metric`(): Unit = runBlocking {
        val row = row(
            "agg_names_metric",
            AggregationQuery(metrics = listOf(sum("amount", "amountTotal"), sum("__m0", "nativeTotal"))),
        )
        row.path("amountTotal").longValue().assert().isEqualTo(18L)
        row.path("nativeTotal").longValue().assert().isEqualTo(300L)
    }

    @Test
    fun `an alias named like a metric's own name should not replace that metric`(): Unit = runBlocking {
        val row = row(
            "agg_names_alias",
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(CATEGORY, "__m0")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        row.path("__m0").longValue().assert().isEqualTo(1L)
        row.path("count").longValue().assert().isEqualTo(2L)
    }

    @Test
    fun `a metric alias named like a column should still leave the column to the filter`(): Unit = runBlocking {
        val row = row(
            "agg_names_column_alias",
            AggregationQuery(
                filter = GreaterThanFilter(QueryField("amount"), 7),
                groupBy = listOf(AggregationGroup.Terms(CATEGORY, "category")),
                metrics = listOf(sum("amount", "amount"), AggregationMetric.Count("count")),
            ),
        )
        row.path("amount").longValue().assert().isEqualTo(11L)
        row.path("count").longValue().assert().isEqualTo(1L)
    }

    private suspend fun row(table: String, query: AggregationQuery): ObjectNode {
        val target = target(table)
        return target.backend.aggregate(QueryAdmission.Trusted.aggregate(query, target.schema())).toList().single()
    }

    /** Two rows of category 1, with columns named like the aggregation's own names. */
    private fun target(table: String): DatasetTarget {
        val database = ClickHouseContainers.DATABASE
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, category Int64, amount Int64, " +
                "__g0 Int64, __m0 Int64) ENGINE = MergeTree ORDER BY id",
        )
        ClickHouseContainers.execute(
            "INSERT INTO $database.$table VALUES ('a', 't1', 1, 7, 10, 100), ('b', 't1', 1, 11, 20, 200)",
        )
        val dataset = QueryDataset.of(AggregateType("names", table)) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            listOf("id", "tenant_id").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            listOf("category", "amount", "__g0", "__m0").forEach {
                field(it) {
                    types(QueryValueType.INTEGER)
                    nullable(false)
                }
            }
        }
        return DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
    }

    private fun sum(field: String, alias: String) =
        AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField(field)), alias)

    private companion object {
        val CATEGORY = QueryField("category")
    }
}
