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
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate
import co.elastic.clients.elasticsearch._types.aggregations.NestedAggregate
import co.elastic.clients.elasticsearch._types.aggregations.SumAggregate
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchAggregationCompilerTest {
    @Test
    fun `should compile every portable group and metric`() {
        val query = AggregationQuery(
            condition = Condition.eq("state.name", "Wow"),
            groupBy = listOf(
                AggregationGroup.Terms("state.name", "name"),
                AggregationGroup.Histogram("state.amount", "amountBand", 10.0),
                AggregationGroup.DateHistogram("state.createdAt", "createdAt", AggregationDateUnit.DAY),
            ),
            metrics = AggregationFunction.entries.map { function ->
                AggregationMetric.Numeric(
                    function,
                    AggregationExpression.Field("state.amount"),
                    function.name.lowercase(),
                )
            } + AggregationMetric.Count("count"),
        )

        val plan = ElasticsearchAggregationCompiler.compile(query, mapping(), SnapshotConditionConverter)
        plan.query.toString().assert().contains("state.name.keyword")
        plan.aggregationQuery.groupBy.map(AggregationGroup::field).assert()
            .containsExactly("state.name", "state.amount", "state.createdAt")

        val sources = plan.aggregationQuery.groupBy.map { plan.compositeSource(it, Sort.Direction.DESC).value() }
        sources[0].terms().order().assert().isEqualTo(SortOrder.Desc)
        sources[1].histogram().interval().assert().isEqualTo(10.0)
        sources[2].dateHistogram().calendarInterval()!!.time().assert().isEqualTo("1d")

        val metrics = plan.metricAggregations()
        metrics.keys.assert().containsExactlyInAnyOrder(
            "__wow_metric_0",
            "__wow_metric_1",
            "__wow_metric_2",
            "__wow_metric_3",
        )
        metrics["__wow_metric_0"]!!.sum().field().assert().isEqualTo("state.amount")
        metrics["__wow_metric_1"]!!.avg().field().assert().isEqualTo("state.amount")
        metrics["__wow_metric_2"]!!.min().field().assert().isEqualTo("state.amount")
        metrics["__wow_metric_3"]!!.max().field().assert().isEqualTo("state.amount")
    }

    @Test
    fun `should isolate metric aliases from Elasticsearch response fields`() {
        val query = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Field("state.amount"),
                    "key",
                ),
                AggregationMetric.Numeric(
                    AggregationFunction.AVG,
                    AggregationExpression.Field("state.amount"),
                    "doc_count",
                ),
            ),
        )

        val plan = ElasticsearchAggregationCompiler.compile(query, mapping(), SnapshotConditionConverter)

        plan.metricAggregations().keys.assert().containsExactlyInAnyOrder("__wow_metric_0", "__wow_metric_1")
        plan.metricName(query.metrics[0] as AggregationMetric.Numeric).assert().isEqualTo("__wow_metric_0")
        plan.metricName(query.metrics[1] as AggregationMetric.Numeric).assert().isEqualTo("__wow_metric_1")
    }

    @Test
    fun `should compile every date interval`() {
        AggregationDateUnit.entries.forEach { unit ->
            val source = AggregationGroup.DateHistogram("createdAt", "createdAt", unit)
                .toCompositeSource(Sort.Direction.ASC).value().dateHistogram()
            val interval = source.fixedInterval() ?: source.calendarInterval()
            interval!!.time().assert().isEqualTo(
                when (unit) {
                    AggregationDateUnit.YEAR -> "1y"
                    AggregationDateUnit.QUARTER -> "1q"
                    AggregationDateUnit.MONTH -> "1M"
                    AggregationDateUnit.WEEK -> "1w"
                    AggregationDateUnit.DAY -> "1d"
                    AggregationDateUnit.HOUR -> "1h"
                    AggregationDateUnit.MINUTE -> "1m"
                    AggregationDateUnit.SECOND -> "1s"
                },
            )
            (source.fixedInterval() != null).assert().isEqualTo(unit == AggregationDateUnit.SECOND)
        }
    }

    @Test
    fun `should keep resolved multi-field paths outside public depth validation`() {
        val segments = (1..AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH).map { "level$it" }
        val field = segments.joinToString(".")
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(field, "value")),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val plan = ElasticsearchAggregationCompiler.compile(
            query,
            ElasticsearchIndexMapping.from("index", deepTextMapping(segments)),
            SnapshotConditionConverter,
        )

        plan.aggregationQuery.groupBy.single().field.assert().isEqualTo(field)
        plan.compositeSource(query.groupBy.single(), Sort.Direction.ASC)
            .value().terms().field().assert().isEqualTo("$field.keyword")
    }

    @Test
    fun `should use one wrapper path and reject malformed responses`() {
        val plan = ElasticsearchAggregationPlan(
            query = matchAll { it },
            aggregationQuery = AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
            elements = listOf(ResolvedElement("state.items", matchAll { it }, 0)),
        )
        val wrapped = plan.wrap(emptyMap())
        wrapped["__wow_element_0"]!!.nested().path().assert().isEqualTo("state.items")

        val leafMetric = Aggregate(SumAggregate.of { it.value(3.0) })
        val filter = Aggregate(
            FilterAggregate.of {
                it.docCount(2).aggregations("sum", leafMetric)
            },
        )
        val nested = Aggregate(
            NestedAggregate.of {
                it.docCount(2).aggregations("__wow_filter_0", filter)
            },
        )
        val leaf = plan.leaf(mapOf("__wow_element_0" to nested))
        leaf.documentCount.assert().isEqualTo(2L)
        leaf.aggregations["sum"]!!.sum().value().assert().isEqualTo(3.0)

        assertThrows<IllegalStateException> { plan.leaf(emptyMap()) }
        assertThrows<IllegalStateException> { plan.leaf(mapOf("__wow_element_0" to leafMetric)) }
        val wrongFilter = Aggregate(
            NestedAggregate.of {
                it.docCount(2).aggregations("__wow_filter_0", leafMetric)
            },
        )
        assertThrows<IllegalStateException> { plan.leaf(mapOf("__wow_element_0" to wrongFilter)) }
    }

    @Test
    fun `should compile nested element conditions`() {
        val query = AggregationQuery(
            elements = listOf(AggregationElement("state.items", Condition.eq("state.items.status", "PAID"))),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        val plan = ElasticsearchAggregationCompiler.compile(query, mapping(), SnapshotConditionConverter)
        val wrapped = plan.wrap(emptyMap())["__wow_element_0"]!!
        wrapped.nested().path().assert().isEqualTo("state.items")
        wrapped.aggregations()["__wow_filter_0"]!!.filter().bool().filter().last().term().field().assert()
            .isEqualTo("state.items.status")
    }

    private fun mapping(): ElasticsearchIndexMapping = ElasticsearchIndexMapping.from(
        "index",
        TypeMapping.of { mapping ->
            mapping.properties("state") { state ->
                state.`object` { objectField ->
                    objectField
                        .properties("name") { name ->
                            name.text { text ->
                                text.fields("keyword") { keyword -> keyword.keyword { it } }
                            }
                        }.properties("amount") { amount -> amount.double_ { it } }
                        .properties("createdAt") { createdAt -> createdAt.date { it } }
                        .properties("items") { items ->
                            items.nested { nested ->
                                nested.properties("status") { status -> status.keyword { it } }
                            }
                        }
                }
            }
        },
    )

    private fun deepTextMapping(segments: List<String>): TypeMapping {
        fun property(index: Int): Property = if (index == segments.lastIndex) {
            Property.of { property ->
                property.text { text -> text.fields("keyword") { keyword -> keyword.keyword { it } } }
            }
        } else {
            Property.of { property ->
                property.`object` { objectField ->
                    objectField.properties(segments[index + 1], property(index + 1))
                }
            }
        }
        return TypeMapping.of { mapping -> mapping.properties(segments.first(), property(0)) }
    }
}
