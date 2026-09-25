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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

class ResidualOperatorsTest {
    private val day = AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY)
    private val day1 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private val day4 = Instant.parse("2026-01-04T00:00:00Z").toEpochMilli()

    @Test
    fun `dense fill rows carry empty metric semantics between real buckets`() {
        val fill = DenseFill(
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
        fill.fill(Flux.just(bucket(day1, 2), bucket(day4, 1))).collectList().test()
            .assertNext { rows ->
                rows.map { it["day"].longValue() }.assert()
                    .containsExactly(day1, day1 + DAY_MILLIS, day1 + 2 * DAY_MILLIS, day4)
                rows.subList(1, 3).forEach { row ->
                    row["count"].longValue().assert().isZero()
                    row["total"].isNull.assert().isTrue()
                }
            }
            .verifyComplete()
    }

    @Test
    fun `dense fill follows a descending stream`() {
        DenseFill(day, listOf(AggregationMetric.Count("count")))
            .fill(Flux.just(bucket(day4, 1), bucket(day1, 2)))
            .map { it["day"].longValue() }
            .collectList().test()
            .assertNext { it.assert().containsExactly(day4, day4 - DAY_MILLIS, day4 - 2 * DAY_MILLIS, day1) }
            .verifyComplete()
    }

    @Test
    fun `dense fill generates gap rows on demand`() {
        val second = AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.SECOND)
        DenseFill(second, listOf(AggregationMetric.Count("count")))
            .fill(Flux.just(bucket(0, 1), bucket(365L * DAY_MILLIS, 1)))
            .take(3)
            .map { it["day"].longValue() }
            .collectList().test()
            .assertNext { it.assert().containsExactly(0L, 1_000L, 2_000L) }
            .verifyComplete()
    }

    @Test
    fun `fill rows take part in HAVING like real rows`() {
        val fill = DenseFill(day, listOf(AggregationMetric.Count("count")))
        val having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0)
        fill.fill(Flux.just(bucket(day1, 2), bucket(day4, 1)))
            .filter { it.matchesHaving(having) }
            .map { it["day"].longValue() }
            .collectList().test()
            .assertNext { it.assert().containsExactly(day1, day4) }
            .verifyComplete()
        fill.fill(Flux.just(bucket(day1, 2), bucket(day4, 1)))
            .filter { it.matchesHaving(HavingExpression.IsNull("missing")) }
            .count().test()
            .expectNext(4L)
            .verifyComplete()
    }

    @Test
    fun `HAVING fails every comparison on a null metric`() {
        val row = """{"count":null}""".toJsonNode<ObjectNode>()
        row.matchesHaving(HavingExpression.Condition("count", ComparisonOperator.NE, 1.0)).assert().isFalse()
        row.matchesHaving(HavingExpression.Between("count", 0.0, 2.0)).assert().isFalse()
        row.matchesHaving(HavingExpression.In("count", listOf(1.0))).assert().isFalse()
        row.matchesHaving(HavingExpression.IsNull("count")).assert().isTrue()
    }

    private fun bucket(key: Long, count: Long): ObjectNode = """{"day":$key,"count":$count}""".toJsonNode()

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
