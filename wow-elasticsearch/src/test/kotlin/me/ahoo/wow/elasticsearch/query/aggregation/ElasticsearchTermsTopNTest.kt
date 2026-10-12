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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.query.aggregationTestSchema
import me.ahoo.wow.elasticsearch.query.compile
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.dsl.aggregation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchTermsTopNTest {
    private val schema = aggregationTestSchema()

    private fun order(metric: me.ahoo.wow.query.dsl.AggregationQueryDsl.() -> Unit) =
        ElasticsearchAggregationCompiler.compile(
            aggregation {
                terms("state.product", "product")
                metric()
                sort { "m".desc() }
                limit(3)
            },
            schema,
        ).metricOrder.map { it.name() to it.value() }

    @Test
    fun `a native top-N orders by the metric's bucket path, then by the key`() {
        order { count("m") }.assert().containsExactly("_count" to SortOrder.Desc, "_key" to SortOrder.Asc)
        // A filtered count orders by its filter aggregation's documents, under the metric's own name.
        order { count("m") { "deleted" eq false } }.assert()
            .containsExactly("m" to SortOrder.Desc, "_key" to SortOrder.Asc)
        order { distinctCount("state.value", "m") }.assert()
            .containsExactly("m" to SortOrder.Desc, "_key" to SortOrder.Asc)
        order { distinctCount("state.value", "m") { "deleted" eq false } }.assert()
            .containsExactly("${metricFilterAggregationName("m")}>m" to SortOrder.Desc, "_key" to SortOrder.Asc)
    }

    @Test
    fun `a plan without a metric sort has no metric order`() {
        ElasticsearchAggregationCompiler.compile(
            aggregation {
                terms("state.product", "product")
                count("m")
            },
            schema,
        ).metricOrder.assert().isEmpty()
    }

    @Test
    fun `a metric the storage does not order by is refused, as is a metric sort over two groups`() {
        assertThrows<QueryExecutionException> { order { sum("state.amount", "m") } }
        assertThrows<QueryExecutionException> {
            ElasticsearchAggregationCompiler.compile(
                aggregation {
                    terms("state.product", "product")
                    terms("state.category", "category")
                    count("m")
                    sort { "m".desc() }
                },
                schema,
            )
        }
    }
}
