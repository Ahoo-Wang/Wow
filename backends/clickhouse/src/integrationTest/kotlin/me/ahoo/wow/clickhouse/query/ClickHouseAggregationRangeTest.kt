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
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/** A metric is its exact value or the query fails: no sum wraps, and none past the double range reads as no rows. */
class ClickHouseAggregationRangeTest {
    @Test
    fun `the sum and the mean of Int64 values past its range should be exact`(): Unit = runBlocking {
        val row = aggregate("range_int64", "Int64", listOf("9223372036854775807", "1")).getOrThrow()
        row.path("count").longValue().assert().isEqualTo(2L)
        row.path("total").decimalValue().assert().isEqualByComparingTo(BigDecimal("9223372036854775808"))
        row.path("mean").doubleValue().assert().isEqualTo(4.611686018427387904E18)
    }

    @Test
    fun `the sum and the mean of UInt64 values past its range should be exact`(): Unit = runBlocking {
        val row = aggregate("range_uint64", "UInt64", listOf("18446744073709551615", "1")).getOrThrow()
        row.path("total").decimalValue().assert().isEqualByComparingTo(BigDecimal("18446744073709551616"))
        row.path("mean").doubleValue().assert().isEqualTo(9.223372036854775808E18)
    }

    @Test
    fun `the sum of UInt128 values past its range should be exact`(): Unit = runBlocking {
        val max = "340282366920938463463374607431768211455"
        val row = aggregate("range_uint128", "UInt128", listOf(max, "1")).getOrThrow()
        row.path("total").decimalValue().assert().isEqualByComparingTo(BigDecimal(max) + BigDecimal.ONE)
    }

    @Test
    fun `the sum of Int256 values should keep its own type`(): Unit = runBlocking {
        val row = aggregate("range_int256", "Int256", listOf("-3", "1")).getOrThrow()
        row.path("total").longValue().assert().isEqualTo(-2L)
    }

    @Test
    fun `a Float64 sum past the double range should fail the query`(): Unit = runBlocking {
        aggregate("range_float64", "Float64", listOf("1e308", "1e308")).exceptionOrNull().assert()
            .isInstanceOf(QueryExecutionException::class.java)
    }

    @Test
    fun `a sum of an expression past the double range should fail the query`(): Unit = runBlocking {
        val doubled = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(AMOUNT),
            AggregationExpression.Constant(1.0),
        )
        val metric = AggregationMetric.Numeric(AggregationFunction.SUM, doubled, "total")
        aggregate("range_expression", "Float64", listOf("1e308", "1e308"), listOf(metric)).exceptionOrNull().assert()
            .isInstanceOf(QueryExecutionException::class.java)
    }

    @Test
    fun `a finite Float64 sum should keep its value and no rows should sum to null`(): Unit = runBlocking {
        val row = aggregate("range_finite", "Float64", listOf("1.25", "2.5")).getOrThrow()
        row.path("total").decimalValue().assert().isEqualByComparingTo(BigDecimal("3.75"))
        val empty = aggregate("range_empty", "Float64", emptyList()).getOrThrow()
        empty.path("total").isNull.assert().isTrue()
        empty.path("count").longValue().assert().isEqualTo(0L)
    }

    /** SUM, AVG and COUNT of an `amount` column of the [native] type holding [values]. */
    private suspend fun aggregate(
        table: String,
        native: String,
        values: List<String>,
        metrics: List<AggregationMetric> = listOf(
            AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(AMOUNT), "total"),
            AggregationMetric.Numeric(AggregationFunction.AVG, AggregationExpression.Field(AMOUNT), "mean"),
            AggregationMetric.Count("count"),
        ),
    ): Result<ObjectNode> {
        val database = ClickHouseContainers.DATABASE
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.$table")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.$table (id String, tenant_id String, amount $native) ENGINE = MergeTree ORDER BY id",
        )
        if (values.isNotEmpty()) {
            val rows = values.mapIndexed { index, value -> "('$index', 't1', $value)" }.joinToString(", ")
            ClickHouseContainers.execute("INSERT INTO $database.$table VALUES $rows")
        }
        val type = if (native.startsWith("Float")) QueryValueType.DECIMAL else QueryValueType.INTEGER
        val dataset = QueryDataset.of(AggregateType("range", table)) {
            tenant("tenant_id")
            identity("id")
            storage(WowJson.mapper.createObjectNode().put("table", "$database.$table"))
            listOf("id", "tenant_id").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("amount") {
                types(type)
                nullable(false)
            }
        }
        val target = DatasetTarget(dataset, ClickHouseDatasetBindings(ClickHouseContainers.client))
        val query = AggregationQuery(metrics = metrics)
        return runCatching {
            target.backend.aggregate(QueryAdmission.Trusted.aggregate(query, target.schema())).toList().single()
        }
    }

    private companion object {
        val AMOUNT = QueryField("amount")
    }
}
