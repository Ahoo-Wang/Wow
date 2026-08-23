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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.modeling.annotation.stateAggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.LocalTime

class AggregationQueryValidatorTest {
    private val namedAggregate = AggregateMetadata(
        namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate,
        staticTenantId = MOCK_AGGREGATE_METADATA.staticTenantId,
        state = TestState::class.java.stateAggregateMetadata(),
        command = MOCK_AGGREGATE_METADATA.command,
    )

    @Test
    fun `should validate root and recursive elements aggregation`() {
        AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Terms("state.status", "status"),
                AggregationGroup.Histogram("state.amount", "band", 10.0),
                AggregationGroup.DateHistogram("state.createdAt", "day", AggregationDateUnit.DAY),
            ),
            metrics = listOf(numeric("state.amount"), AggregationMetric.Count("count")),
        ).validate()

        AggregationQuery(
            elements = listOf(
                AggregationElement("state.orders", filter { "state.orders.status" eq "PAID" }),
                AggregationElement("state.orders.lines", filter { "state.orders.lines.cancelled" eq false }),
            ),
            groupBy = listOf(AggregationGroup.Terms("state.orders.lines.sku", "sku")),
            metrics = listOf(numeric("state.orders.lines.amount"), AggregationMetric.Count("count")),
        ).validate()
    }

    @Test
    fun `should reject invalid element chains and collections`() {
        listOf(
            listOf(AggregationElement("state.tags")),
            listOf(AggregationElement("state.attributes")),
            listOf(AggregationElement("state.objects")),
            listOf(AggregationElement("state.nestedItems")),
            listOf(AggregationElement("state.orders.lines")),
            listOf(AggregationElement("state.orders"), AggregationElement("state.items")),
        ).forEach { elements ->
            assertThrows<IllegalArgumentException> {
                AggregationQuery(elements = elements, metrics = listOf(AggregationMetric.Count("count")))
                    .validate()
            }
        }
    }

    @Test
    fun `should keep element filters inside their own row scope`() {
        listOf(
            filter { "state.status" eq "PAID" },
            filter { "state.orders.lines.sku" eq "sku" },
            filter { "state.orders.tags" eq "tag" },
            filter { "state.orders.lines".elementMatch { "sku" eq "sku" } },
            filter { search("sku") },
            filter { deletion(DeletionState.ACTIVE) },
        ).forEach { elementFilter ->
            assertThrows<IllegalArgumentException> {
                AggregationQuery(
                    elements = listOf(AggregationElement("state.orders", elementFilter)),
                    metrics = listOf(AggregationMetric.Count("count")),
                ).validate()
            }
        }
    }

    @Test
    fun `should require fields from innermost source with portable types`() {
        val invalid = listOf(
            AggregationQuery(
                elements = listOf(AggregationElement("state.orders")),
                groupBy = listOf(AggregationGroup.Terms("state.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                elements = listOf(AggregationElement("state.orders")),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.sku", "sku")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.createdAt", "time")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram("state.status", "band", 1.0)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                groupBy = listOf(AggregationGroup.DateHistogram("state.status", "day", AggregationDateUnit.DAY)),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.orders.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                elements = listOf(AggregationElement("state.orders")),
                groupBy = listOf(AggregationGroup.Terms("state.orders.shipping", "shipping")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
            AggregationQuery(
                metrics = listOf(numeric("state.status")),
            ),
        )
        invalid.forEach { query -> assertThrows<IllegalArgumentException> { query.validate() } }
    }

    @Test
    fun `should validate element filter operators against field types`() {
        listOf(
            filter { "state.orders.status".contains("PAID") },
            filter { "state.orders.amount" gt 0.0 },
            filter { "state.orders.createdAt".today() },
            filter { "state.orders.status".exists() },
        ).forEach { elementFilter -> aggregation(elementFilter).validate() }

        listOf(
            filter { "state.orders.amount".contains("1") },
            filter { "state.orders.cancelled".startsWith("false") },
            filter { "state.orders.cancelled" gt false },
            filter { "state.orders.amount" gt false },
            filter { "state.orders.amount".between(1, "100") },
            filter { "state.orders.status" gt 1 },
            filter { "state.orders.createdAt" gt 1 },
            filter { "state.orders.amount" eq false },
            filter { "state.orders.amount" isIn listOf(false) },
            filter { "state.orders.cancelled" eq 1 },
            filter { "state.orders.createdAt" notIn listOf(1) },
            EqualFilter(LogicalField("state.orders.status"), JsonNodeFactory.instance.nullNode()),
            filter { "state.orders.shipping" eq "address" },
            filter { "state.orders.shipping" eq null },
            filter { "state.orders.shipping".exists() },
            filter { "state.orders.shipping".isEmptyCollection() },
            filter { "state.orders.status".today() },
            filter { "state.orders.createdAt".today(datePattern = "yyyy-MM-dd") },
        ).forEach { elementFilter ->
            assertThrows<IllegalArgumentException> { aggregation(elementFilter).validate() }
        }
    }

    @Test
    fun `should validate complete portable element filter surface`() {
        listOf(
            filter { matchNone() },
            filter {
                and {
                    "state.orders.status" eq "PAID"
                    "state.orders.amount" gt 0
                }
            },
            filter {
                or {
                    "state.orders.status" eq "PAID"
                    "state.orders.status" eq "CREATED"
                }
            },
            filter { nor { "state.orders.status" eq "CANCELLED" } },
            filter { "state.orders.status" ne "CANCELLED" },
            filter { "state.orders.amount" gte 1 },
            filter { "state.orders.amount" lt 100 },
            filter { "state.orders.amount" lte 100 },
            filter { "state.orders.status".endsWith("ID") },
            filter { "state.orders.status" isIn listOf("PAID") },
            filter { "state.orders.status" notIn listOf("CANCELLED") },
            filter { "state.orders.amount".between(1, 100) },
            filter { "state.orders.status".isNull() },
            filter { "state.orders.status".isNotNull() },
            filter { "state.orders.status".notExists() },
            filter { "state.orders.createdAt".beforeToday(LocalTime.NOON) },
            filter { "state.orders.createdAt".tomorrow() },
            filter { "state.orders.createdAt".thisWeek() },
            filter { "state.orders.createdAt".nextWeek() },
            filter { "state.orders.createdAt".lastWeek() },
            filter { "state.orders.createdAt".thisMonth() },
            filter { "state.orders.createdAt".lastMonth() },
            filter { "state.orders.createdAt".recentDays(1) },
            filter { "state.orders.createdAt".earlierDays(1) },
        ).forEach { elementFilter -> aggregation(elementFilter).validate() }

        listOf(
            filter { "state.orders.tags" containsAll listOf("tag") },
            filter { "state.orders.tags".isEmptyCollection() },
        ).forEach { elementFilter ->
            assertThrows<IllegalArgumentException> { aggregation(elementFilter).validate() }
        }
    }

    private fun aggregation(elementFilter: me.ahoo.wow.api.query.FilterExpression) = AggregationQuery(
        elements = listOf(AggregationElement("state.orders", elementFilter)),
        metrics = listOf(AggregationMetric.Count("count")),
    )

    private fun AggregationQuery.validate() = AggregationQueryValidator.validate(this, namedAggregate)

    private fun numeric(field: String) = AggregationMetric.Numeric(
        AggregationFunction.SUM,
        AggregationExpression.Field(field),
        "amount",
    )

    private class TestState(val id: String) {
        val status: String = ""
        val amount: Double = 0.0
        val createdAt: Instant = Instant.EPOCH
        val orders: List<Order> = emptyList()
        val items: List<Item> = emptyList()
        val tags: List<String> = emptyList()
        val attributes: Map<String, String> = emptyMap()
        val objects: List<Any> = emptyList()
        val nestedItems: List<List<Item>> = emptyList()
    }

    private data class Order(
        val status: String,
        val amount: Double,
        val lines: List<Line>,
        val createdAt: Instant = Instant.EPOCH,
        val cancelled: Boolean = false,
        val tags: List<String> = emptyList(),
        val shipping: Shipping = Shipping(""),
    )

    private data class Line(
        val sku: String,
        val amount: Double,
        val cancelled: Boolean,
    )

    private data class Item(val sku: String)
    private data class Shipping(val address: String)
}
