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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.time.ZoneId

class AggregationQueryTest {
    @Test
    fun `should round trip portable aggregation model`() {
        val query = AggregationQuery(
            filter = EqualFilter(LogicalField("tenantId"), JsonNodeFactory.instance.stringNode("tenant")),
            elements = listOf(
                AggregationElement(
                    "state.orders",
                    EqualFilter(LogicalField("state.orders.status"), JsonNodeFactory.instance.stringNode("PAID")),
                ),
            ),
            groupBy = listOf(
                AggregationGroup.Terms("state.orders.status", "status"),
                AggregationGroup.Histogram("state.orders.amount", "amountBand", 10.0),
                AggregationGroup.DateHistogram(
                    "state.orders.createdAt",
                    "month",
                    AggregationDateUnit.MONTH,
                    "Asia/Shanghai",
                ),
            ),
            metrics = listOf(
                AggregationMetric.Count("count"),
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field("state.orders.amount"),
                    "amount",
                ),
            ),
            sort = listOf(Sort("amount", Sort.Direction.DESC)),
            limit = 20,
        )

        val replacement = EqualFilter(LogicalField("_id"), JsonNodeFactory.instance.stringNode("id"))
        query.withFilter(replacement).filter.assert().isEqualTo(replacement)
        query.assert().isInstanceOf(FilterCapable::class.java)
        query.assert().isInstanceOf(SortCapable::class.java)
    }

    @Test
    fun `should reject invalid shape aliases and sort`() {
        assertThrows<IllegalArgumentException> { AggregationQuery(metrics = emptyList()) }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "same")),
                metrics = listOf(AggregationMetric.Count("same")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort("count", Sort.Direction.ASC)),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort("missing", Sort.Direction.ASC)),
            )
        }
        listOf("__wow_value", "bad.alias", "\$value", "_id", "bad\u0000alias", " ").forEach { alias ->
            assertThrows<IllegalArgumentException> { AggregationMetric.Count(alias) }
        }
    }

    @Test
    fun `should append stable group sort`() {
        AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Terms("state.status", "status"),
                AggregationGroup.Terms("state.category", "category"),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort("category", Sort.Direction.DESC)),
        ).effectiveSort().assert().containsExactly(
            Sort("category", Sort.Direction.DESC),
            Sort("status", Sort.Direction.ASC),
        )
    }

    @Test
    fun `should keep effective sort within portable backend limit`() {
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = List(AggregationQuery.MAX_GROUPS) {
                    AggregationGroup.Terms("state.value$it", "value$it")
                },
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort("count", Sort.Direction.DESC)),
            )
        }
    }

    @Test
    fun `should enforce independent hard limits`() {
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                elements = List(AggregationQuery.MAX_ELEMENTS + 1) { AggregationElement("state.items$it") },
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(
                groupBy = List(AggregationQuery.MAX_GROUPS + 1) {
                    AggregationGroup.Terms("state.value$it", "value$it")
                },
                metrics = listOf(AggregationMetric.Count("count")),
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(metrics = List(AggregationQuery.MAX_METRICS + 1) { AggregationMetric.Count("count$it") })
        }
        assertThrows<IllegalArgumentException> {
            AggregationExpression.Field(
                (1..AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH + 1).joinToString(".") { "f$it" }
            )
        }
        assertThrows<IllegalArgumentException> {
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count")), limit = AggregationQuery.MAX_LIMIT + 1)
        }
    }

    @Test
    fun `should reject non portable histogram options`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { interval ->
            assertThrows<IllegalArgumentException> { AggregationGroup.Histogram("state.amount", "band", interval) }
        }
        AggregationGroup.DateHistogram(
            "snapshotTime",
            "day",
            AggregationDateUnit.DAY,
            "+08:00",
        ).timeZone.assert().isEqualTo("+08:00")
        listOf("Z", "UTC+08:00", "+19:00").forEach { timeZone ->
            assertThrows<IllegalArgumentException> {
                AggregationGroup.DateHistogram("snapshotTime", "day", AggregationDateUnit.DAY, timeZone)
            }
        }
    }

    @Test
    fun `portable time zones should be a stable runtime subset`() {
        AggregationTimeZones.ids.assert().contains("UTC", "Asia/Shanghai")
        AggregationTimeZones.ids.none { it.startsWith("SystemV/") }.assert().isTrue()
        ZoneId.getAvailableZoneIds().containsAll(AggregationTimeZones.ids).assert().isTrue()
    }
}
