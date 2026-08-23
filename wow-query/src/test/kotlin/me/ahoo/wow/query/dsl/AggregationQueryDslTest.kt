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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AggregationQueryDslTest {
    @Test
    fun `should normalize recursive expand paths and leaf fields`() {
        val query = aggregationQuery {
            filter { "tenantId" eq "tenant" }
            expand("state.orders") {
                filter { "status" eq "PAID" }
                expand("lines") {
                    filter { "cancelled" eq false }
                    groupBy("sku", "sku")
                    histogram("amount", "amountBand", 10.0)
                    dateHistogram("createdAt", "createdDay", AggregationDateUnit.DAY)
                    sum("amount", "totalAmount")
                    avg("amount", "averageAmount")
                    min("amount", "minimumAmount")
                    max("amount", "maximumAmount")
                    count("lineCount")
                    sort { "totalAmount".desc() }
                    limit(100)
                }
            }
        }

        query.elements.map { it.path }.assert().containsExactly("state.orders", "state.orders.lines")
        (query.elements[0].filter as EqualFilter).field.value.assert().isEqualTo("state.orders.status")
        (query.elements[1].filter as EqualFilter).field.value.assert().isEqualTo("state.orders.lines.cancelled")
        query.filter.operator.assert().isEqualTo(FilterOperator.EQ)
        query.groupBy.map { it.field }.assert().containsExactly(
            "state.orders.lines.sku",
            "state.orders.lines.amount",
            "state.orders.lines.createdAt",
        )
        val numeric = query.metrics.filterIsInstance<AggregationMetric.Numeric>()
        numeric.assert().hasSize(4)
        (numeric.first().expression as AggregationExpression.Field).field.assert()
            .isEqualTo("state.orders.lines.amount")
        query.sort.assert().containsExactly(Sort("totalAmount", Sort.Direction.DESC))
        query.limit.assert().isEqualTo(100)
        query.toJsonString().toObject<AggregationQuery>().assert().isEqualTo(query)
    }

    @Test
    fun `should support root aggregation and already absolute fields`() {
        val query = aggregationQuery {
            groupBy("state.status", "status")
            count("count")
        }
        query.elements.assert().isEmpty()
        query.groupBy.single().field.assert().isEqualTo("state.status")

        val expanded = aggregationQuery {
            expand("state.orders") {
                groupBy("state.orders.status", "status")
                count("count")
            }
        }
        expanded.groupBy.single().field.assert().isEqualTo("state.orders.status")
    }

    @Test
    fun `should reject siblings and non leaf declarations`() {
        assertThrows<IllegalArgumentException> {
            aggregationQuery {
                expand("state.orders") { count("count") }
                expand("state.items") { count("count") }
            }
        }
        assertThrows<IllegalArgumentException> {
            aggregationQuery {
                count("rootCount")
                expand("state.orders") { count("count") }
            }
        }
    }

    @Test
    fun `should reject blank expand paths`() {
        listOf("", " ").forEach { path ->
            assertThrows<IllegalArgumentException> {
                aggregationQuery {
                    expand(path) { count("count") }
                }
            }
        }
    }
}
