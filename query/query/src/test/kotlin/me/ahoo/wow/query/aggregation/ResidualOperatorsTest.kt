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

package me.ahoo.wow.query.aggregation

import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

class ResidualOperatorsTest {
    private val day = AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY)
    private val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()

    @Test
    fun `dense fill rows carry empty metric semantics between real buckets`() {
        runTest {
            val fill = DateHistogramFill(
                day,
                listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("n")),
                        "total"
                    ),
                ),
            )
            val rows = fill.fill(flowOf(bucket(day1, 2), bucket(day4, 1))).toList()
            rows.map { it["day"].longValue() }.assert()
                .containsExactly(day1, day1 + DAY_MILLIS, day1 + 2 * DAY_MILLIS, day4)
            rows.subList(1, 3).forEach { row ->
                row["count"].longValue().assert().isZero()
                row["total"].isNull.assert().isTrue()
            }
        }
    }

    @Test
    fun `dense fill follows a descending stream`() {
        runTest {
            DateHistogramFill(day, listOf(AggregationMetric.Count("count")))
                .fill(flowOf(bucket(day4, 1), bucket(day1, 2)))
                .map { it["day"].longValue() }
                .toList()
                .assert().containsExactly(day4, day4 - DAY_MILLIS, day4 - 2 * DAY_MILLIS, day1)
        }
    }

    @Test
    fun `dense fill passes a row without a key through and keeps the previous key`() {
        runTest {
            val keyless = json("""{"day":null,"count":5}""")
            DateHistogramFill(day, listOf(AggregationMetric.Count("count")))
                .fill(flowOf(bucket(day1, 2), keyless, bucket(day1 + 2 * DAY_MILLIS, 1)))
                .map { it["count"].longValue() }
                .toList()
                .assert().containsExactly(2L, 5L, 0L, 1L)
        }
    }

    @Test
    fun `dense fill generates gap rows on demand`() {
        runTest {
            val second = AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.SECOND)
            DateHistogramFill(second, listOf(AggregationMetric.Count("count")))
                .fill(flowOf(bucket(0, 1), bucket(365L * DAY_MILLIS, 1)))
                .take(3)
                .map { it["day"].longValue() }
                .toList()
                .assert().containsExactly(0L, 1_000L, 2_000L)
        }
    }

    @Test
    fun `a dense date part is completed to its domain in either direction`() {
        runTest {
            val weekday = AggregationGroup.DatePart(QueryField("createdAt"), "weekday", AggregationDatePart.DAY_OF_WEEK)
            val rows = flowOf(json("""{"weekday":3,"count":2}"""))
            DatePartFill(weekday, Sort.Direction.ASC, listOf(AggregationMetric.Count("count"))).fill(rows)
                .map { it["weekday"].intValue() to it["count"].longValue() }.toList()
                .assert().containsExactly(1 to 0L, 2 to 0L, 3 to 2L, 4 to 0L, 5 to 0L, 6 to 0L, 7 to 0L)
            DatePartFill(weekday, Sort.Direction.DESC, listOf(AggregationMetric.Count("count"))).fill(rows)
                .map { it["weekday"].intValue() }.toList()
                .assert().containsExactly(7, 6, 5, 4, 3, 2, 1)
        }
    }

    @Test
    fun `fill rows take part in HAVING like real rows`() {
        runTest {
            val fill = DateHistogramFill(day, listOf(AggregationMetric.Count("count")))
            val having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0)
            fill.fill(flowOf(bucket(day1, 2), bucket(day4, 1)))
                .filter { it.matchesHaving(having) }
                .map { it["day"].longValue() }
                .toList()
                .assert().containsExactly(day1, day4)
            fill.fill(flowOf(bucket(day1, 2), bucket(day4, 1)))
                .filter { it.matchesHaving(HavingExpression.IsNull("missing")) }
                .count()
                .assert().isEqualTo(4)
        }
    }

    @Test
    fun `HAVING fails every comparison on a null metric`() {
        val row = json("""{"count":null}""")
        row.matchesHaving(HavingExpression.Condition("count", ComparisonOperator.NE, 1.0)).assert().isFalse()
        row.matchesHaving(HavingExpression.Between("count", 0.0, 2.0)).assert().isFalse()
        row.matchesHaving(HavingExpression.In("count", listOf(1.0))).assert().isFalse()
        row.matchesHaving(HavingExpression.IsNull("count")).assert().isTrue()
        row.matchesHaving(HavingExpression.IsNull("count", negated = true)).assert().isFalse()
        json("{}").matchesHaving(HavingExpression.Condition("count", ComparisonOperator.EQ, 1.0)).assert().isFalse()
    }

    @Test
    fun `HAVING compares, ranges and combines a metric`() {
        val row = json("""{"count":3}""")
        listOf(
            ComparisonOperator.EQ to 3.0,
            ComparisonOperator.NE to 2.0,
            ComparisonOperator.GT to 2.0,
            ComparisonOperator.GTE to 3.0,
            ComparisonOperator.LT to 4.0,
            ComparisonOperator.LTE to 3.0,
        ).forEach { (operator, value) ->
            row.matchesHaving(HavingExpression.Condition("count", operator, value)).assert().isTrue()
            row.matchesHaving(HavingExpression.Condition("count", operator, value + 10)).assert()
                .isEqualTo(operator in setOf(ComparisonOperator.NE, ComparisonOperator.LT, ComparisonOperator.LTE))
        }
        row.matchesHaving(HavingExpression.Between("count", 3.0, 4.0)).assert().isTrue()
        row.matchesHaving(HavingExpression.Between("count", 4.0, 5.0)).assert().isFalse()
        row.matchesHaving(HavingExpression.In("count", listOf(1.0, 3.0))).assert().isTrue()
        val matches = HavingExpression.IsNull("count", negated = true)
        val fails = HavingExpression.IsNull("count")
        row.matchesHaving(HavingExpression.And(listOf(matches, fails))).assert().isFalse()
        row.matchesHaving(HavingExpression.Or(listOf(fails, matches))).assert().isTrue()
    }

    @Test
    fun `metric sort should retain exact bounded top N with complete tie sort`() {
        val rows = listOf(
            mapOf("product" to "c", "total" to 7.0).toObjectNode(),
            mapOf("product" to "a", "total" to 7.0).toObjectNode(),
            mapOf("product" to "b", "total" to 7.0).toObjectNode(),
            mapOf("product" to "d", "total" to 3.0).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort(QueryField("total"), Sort.Direction.DESC), Sort(QueryField("product"), Sort.Direction.ASC)),
            limit = 2,
        ).map { it.path("product").asString() }.assert().containsExactly("a", "b")
    }

    @Test
    fun `long sort above double precision should not fall through to tie sort`() {
        val rows = listOf(
            mapOf("product" to "z", "count" to 9_007_199_254_740_993L).toObjectNode(),
            mapOf("product" to "a", "count" to 9_007_199_254_740_992L).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort(QueryField("count"), Sort.Direction.DESC), Sort(QueryField("product"), Sort.Direction.ASC)),
            limit = 1,
        ).single().path("product").asString().assert().isEqualTo("z")
    }

    @Test
    fun `top rows should sort boolean and null values`() {
        val rows = listOf(
            mapOf("active" to true).toObjectNode(),
            mapOf("active" to null).toObjectNode(),
            mapOf("active" to false).toObjectNode(),
        )

        selectTopRows(rows, listOf(Sort(QueryField("active"), Sort.Direction.ASC)), limit = 3)
            .map { if (it.path("active").isNull) null else it.path("active").booleanValue() }
            .assert().containsExactly(null, false, true)
    }

    @Test
    fun `top rows should reject incomparable values`() {
        val rows = listOf(
            mapOf("value" to 1).toObjectNode(),
            mapOf("value" to "1").toObjectNode(),
        )

        assertThrows<IllegalStateException> {
            selectTopRows(rows, listOf(Sort(QueryField("value"), Sort.Direction.ASC)), limit = 2)
        }.message.assert().contains("Aggregation sort values must have comparable types")
    }

    @Test
    fun `top rows should continue to tie breaker when primary values are both null`() {
        val rows = listOf(
            mapOf("id" to "a", "value" to null).toObjectNode(),
            mapOf("id" to "b", "value" to null).toObjectNode(),
        )

        selectTopRows(
            rows,
            listOf(Sort(QueryField("value"), Sort.Direction.ASC), Sort(QueryField("id"), Sort.Direction.DESC)),
            limit = 2,
        ).map { it.path("id").asString() }.assert().containsExactly("b", "a")
    }

    @Test
    fun `top rows should reject object and array sort values`() {
        val rows = listOf(
            mapOf("value" to mapOf("nested" to 1)).toObjectNode(),
            mapOf("value" to listOf(1)).toObjectNode(),
        )

        assertThrows<IllegalStateException> {
            selectTopRows(rows, listOf(Sort(QueryField("value"), Sort.Direction.ASC)), limit = 2)
        }.message.assert().contains("Aggregation sort values must have comparable types")
    }

    @Test
    fun `group sort fields keep the order the backend emitted the groups in`() {
        val rows = listOf(
            mapOf("month" to "b", "total" to 1).toObjectNode(),
            mapOf("month" to "b", "total" to 5).toObjectNode(),
            mapOf("month" to "a", "total" to 3).toObjectNode(),
        )
        BoundedTopRows(
            listOf(Sort(QueryField("month"), Sort.Direction.ASC), Sort(QueryField("total"), Sort.Direction.DESC)),
            limit = 2,
            groupAliases = listOf("month"),
        ).apply { rows.forEach(::add) }.result()
            .map { it.path("month").asString() to it.path("total").intValue() }
            .assert().containsExactly("b" to 5, "b" to 1)
    }

    private fun json(text: String): ObjectNode = WowJson.mapper.readTree(text) as ObjectNode

    private fun bucket(key: Long, count: Long): ObjectNode = json("""{"day":$key,"count":$count}""")

    private fun Map<String, Any?>.toObjectNode(): ObjectNode = WowJson.mapper.valueToTree(this)

    private fun selectTopRows(rows: Iterable<ObjectNode>, sort: List<Sort>, limit: Int): List<ObjectNode> =
        BoundedTopRows(sort, limit).apply { rows.forEach(::add) }.result()

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
