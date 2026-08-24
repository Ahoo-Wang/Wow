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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.query.dsl.aggregation
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ElasticsearchAggregationCompilerTest {
    @Test
    fun `computed metric should compile a parameterized double runtime field`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping = null).compile(
            aggregation {
                expand("state.items")
                sum(field("price") * field("quantity") - constant(10.0), "total")
            },
        )

        plan.metrics.single().field.assert().isEqualTo("__wow_expression_0")
        val runtime = plan.runtimeMappings.getValue("__wow_expression_0")
        runtime.type().assert().isEqualTo(RuntimeFieldType.Double)
        val script = requireNotNull(runtime.script())
        val source = requireNotNull(script.source()).scriptString()
        source.assert()
            .contains("doc.containsKey")
            .contains("instanceof Number")
            .contains("Double.isFinite")
            .contains("emit")
            .doesNotContain("state.items.price")
            .doesNotContain("10.0")
        script.params().values.map { it.to(Any::class.java) }.assert()
            .contains("state.items.price", "state.items.quantity", 10.0)
    }

    @Test
    fun `plain field metric should not create a runtime field`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping = null).compile(
            aggregation { sum("state.amount", "total") },
        )

        plan.runtimeMappings.assert().isEmpty()
        plan.metrics.single().field.assert().isEqualTo("state.amount")
    }

    @Test
    fun `compiler should nest relative elements in order and resolve exact terms`() {
        val mapping = mockk<ElasticsearchIndexMapping> {
            every { requireNested(any()) } answers { firstArg() }
            every { resolve(any<String>(), any()) } answers {
                val field = firstArg<String>()
                if (secondArg<ElasticsearchFieldUsage>() == ElasticsearchFieldUsage.EXACT) "$field.keyword" else field
            }
            every { resolve(any<FilterExpression>(), any()) } answers { firstArg() }
        }
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping).compile(
            aggregation {
                expand("state.orders") { "status" eq "PAID" }
                expand("lines") { "quantity" gt 0 }
                terms("productId", "product")
                histogram("amount", 10.0, "amountRange")
                dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
                sum("amount", "total")
            },
        )

        plan.elements.map { it.path }.assert().containsExactly("state.orders", "state.orders.lines")
        plan.groupSources[0].value().terms().field().assert()
            .isEqualTo("state.orders.lines.productId.keyword")
        plan.groupSources[1].value().histogram().field().assert().isEqualTo("state.orders.lines.amount")
        plan.groupSources[2].value().dateHistogram().field().assert().isEqualTo("state.orders.lines.createdAt")
        plan.metrics.single().field.assert().isEqualTo("state.orders.lines.amount")
        verifyOrder {
            mapping.requireNested("state.orders")
            mapping.requireNested("state.orders.lines")
        }
        verify {
            mapping.resolve("state.orders.lines.productId", ElasticsearchFieldUsage.EXACT)
            mapping.resolve("state.orders.lines.amount", ElasticsearchFieldUsage.RANGE)
            mapping.resolve("state.orders.lines.createdAt", ElasticsearchFieldUsage.RANGE)
        }
    }

    @Test
    fun `compiler should order composite sources by effective group sort`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping = null).compile(
            aggregation {
                terms("state.productId", "product")
                histogram("state.amount", 10.0, "amountRange")
                dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day", ZoneId.of("Asia/Shanghai"))
                count("count")
                sum("state.amount", "total")
                avg("state.amount", "average")
                min("state.amount", "minimum")
                max("state.amount", "maximum")
                sort {
                    "amountRange".desc()
                    "product".asc()
                }
                limit(7)
            },
        )

        plan.groupSources.map { it.name() }.assert().containsExactly("amountRange", "product", "day")
        plan.groupSources[0].value().histogram().apply {
            field().assert().isEqualTo("state.amount")
            interval().assert().isEqualTo(10.0)
            order().assert().isEqualTo(SortOrder.Desc)
        }
        plan.groupSources[2].value().dateHistogram().apply {
            field().assert().isEqualTo("state.createdAt")
            calendarInterval()!!.time().assert().isEqualTo("day")
            timeZone().assert().isEqualTo("Asia/Shanghai")
        }
        plan.metrics.map { it.alias }.assert()
            .containsExactly("count", "total", "average", "minimum", "maximum")
        plan.effectiveSort.assert().isEqualTo(
            listOf(
                Sort("amountRange", Sort.Direction.DESC),
                Sort("product", Sort.Direction.ASC),
                Sort("day", Sort.Direction.ASC),
            ),
        )
        plan.limit.assert().isEqualTo(7)
        plan.metricSorted.assert().isFalse()
    }

    @Test
    fun `second date histogram should use a fixed interval`() {
        val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping = null).compile(
            aggregation {
                dateHistogram("state.createdAt", AggregationDateUnit.SECOND, "second")
                count("count")
            },
        )

        plan.groupSources.single().value().dateHistogram().apply {
            fixedInterval()!!.time().assert().isEqualTo("1s")
            calendarInterval().assert().isNull()
        }
    }

    @Test
    fun `custom converter should receive caller paths without mapping resolution`() {
        val convertedParents = mutableListOf<String?>()
        val converter = mockk<me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter> {
            every { convert(any(), captureNullable(convertedParents)) } answers {
                co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll { it }
            }
        }

        val plan = ElasticsearchAggregationCompiler(converter, mapping = null).compile(
            aggregation {
                filter { "physical.root" eq "ACTIVE" }
                expand("physical.items") { "physical.quantity" gt 0 }
                terms("physical.product", "product")
                count("count")
            },
        )

        plan.elements.single().path.assert().isEqualTo("physical.items")
        plan.groupSources.single().value().terms().field().assert().isEqualTo("physical.items.physical.product")
        convertedParents.assert().containsExactly(null, "physical.items")
    }
}
