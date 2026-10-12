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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.util.NamedValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchRootGroupsTest {

    private val matchAll = Query.of { it.matchAll { all -> all } }

    private fun plan(vararg sources: Pair<String, CompositeAggregationSource>) = ElasticsearchAggregationPlan(
        rootQuery = matchAll,
        elements = listOf(ElasticsearchAggregationElement("body", matchAll)),
        groupSources = sources.map { (alias, source) -> NamedValue.of(alias, source) },
        metrics = listOf(ElasticsearchAggregationMetric.Count("count")),
        runtimeMappings = emptyMap(),
        effectiveSort = emptyList(),
        rootSources = listOf(NamedValue.of("aggregate", terms("aggregateId"))),
    )

    private fun terms(field: String) = CompositeAggregationSource.of {
        it.terms { terms -> terms.field(field).order(SortOrder.Asc) }
    }

    @Test
    fun `element groups nest as bucket aggregations built from their sources`() {
        val day = CompositeAggregationSource.of {
            it.dateHistogram { histogram ->
                histogram.field("body.at").calendarInterval { interval -> interval.time("day") }
                    .timeZone("Asia/Shanghai").order(SortOrder.Desc)
            }
        }
        val second = CompositeAggregationSource.of {
            it.dateHistogram { histogram ->
                histogram.field("body.at").fixedInterval { interval -> interval.time("1s") }.order(SortOrder.Asc)
            }
        }
        val band = CompositeAggregationSource.of {
            it.histogram { histogram -> histogram.field("body.amount").interval(5.0).order(SortOrder.Asc) }
        }
        val aggregations = plan("day" to day, "second" to second, "band" to band, "name" to terms("body.name"))
            .innerGroupAggregations(7, emptyMap())

        val dayAggregation = aggregations.getValue(innerGroupAggregationName(0))
        dayAggregation.dateHistogram().calendarInterval().assert().isEqualTo(CalendarInterval.Day)
        dayAggregation.dateHistogram().timeZone().assert().isEqualTo("Asia/Shanghai")
        dayAggregation.dateHistogram().minDocCount().assert().isEqualTo(1)
        val secondAggregation = dayAggregation.aggregations().getValue(innerGroupAggregationName(1))
        secondAggregation.dateHistogram().fixedInterval()!!.time().assert().isEqualTo("1s")
        val bandAggregation = secondAggregation.aggregations().getValue(innerGroupAggregationName(2))
        bandAggregation.histogram().interval().assert().isEqualTo(5.0)
        val nameAggregation = bandAggregation.aggregations().getValue(innerGroupAggregationName(3))
        nameAggregation.terms().size().assert().isEqualTo(7)
        nameAggregation.terms().field().assert().isEqualTo("body.name")
    }

    @Test
    fun `a source with no bucket aggregation is refused`() {
        val tiles = CompositeAggregationSource.of {
            it.geotileGrid { grid -> grid.field("body.location").precision(3) }
        }
        assertThrows<IllegalStateException> { plan("tile" to tiles).innerGroupAggregations(7, emptyMap()) }
    }

    private fun bucket(inner: Aggregate, docCount: Long = 2): CompositeBucket = CompositeBucket.of { bucket ->
        bucket.key("aggregate", FieldValue.of("a")).docCount(docCount).aggregations(
            nestedAggregationName(0),
            Aggregate.of {
                it.nested { nested ->
                    nested.docCount(docCount).aggregations(
                        filterAggregationName(0),
                        Aggregate.of { filter ->
                            filter.filter { scope ->
                                scope.docCount(docCount).aggregations(innerGroupAggregationName(0), inner)
                            }
                        },
                    )
                }
            },
        )
    }

    private fun rows(inner: Aggregate, size: Int = 7, docCount: Long = 2) =
        bucket(inner, docCount).rootRows(plan("key" to terms("body.key")), size)
            .map { it.path("aggregate").asString() to it.get("key") }

    @Test
    fun `each bucket kind keys its rows as the composite source would`() {
        rows(
            Aggregate.of {
                it.dterms { terms ->
                    terms.buckets { buckets -> buckets.array(listOf(dtermsBucket(1.5))) }.sumOtherDocCount(0)
                }
            },
        ).map { it.second.asDouble() }.assert().containsExactly(1.5)
        rows(
            Aggregate.of {
                it.lterms { terms ->
                    terms.buckets { buckets ->
                        buckets.array(
                            listOf(
                                co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket.of { bucket ->
                                    bucket.key(1).keyAsString("true").docCount(1)
                                },
                            ),
                        )
                    }.sumOtherDocCount(0)
                }
            },
        ).map { it.second.asBoolean() }.assert().containsExactly(true)
        rows(
            Aggregate.of {
                it.dateHistogram { histogram ->
                    histogram.buckets { buckets ->
                        buckets.array(
                            listOf(
                                co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket.of { bucket ->
                                    bucket.key(86_400_000).docCount(1)
                                },
                            ),
                        )
                    }
                }
            },
        ).map { it.second.asLong() }.assert().containsExactly(86_400_000L)
        rows(Aggregate.of { it.umterms { terms -> terms.buckets { buckets -> buckets.array(emptyList()) } } })
            .assert().isEmpty()
        // A root bucket whose elements the element filters all dropped has no row.
        rows(
            Aggregate.of { it.umterms { terms -> terms.buckets { buckets -> buckets.array(emptyList()) } } },
            docCount = 0
        )
            .assert().isEmpty()
    }

    private fun dtermsBucket(key: Double) =
        co.elastic.clients.elasticsearch._types.aggregations.DoubleTermsBucket.of { bucket ->
            bucket.key(key).docCount(1)
        }

    @Test
    fun `more keys than a page holds fail the query rather than truncate it`() {
        // Elasticsearch left keys out of the terms buckets.
        assertThrows<IllegalStateException> {
            rows(
                Aggregate.of {
                    it.dterms { terms ->
                        terms.buckets { buckets -> buckets.array(listOf(dtermsBucket(1.0))) }.sumOtherDocCount(3)
                    }
                },
            )
        }
        // More buckets than the page size.
        assertThrows<IllegalStateException> {
            rows(
                Aggregate.of {
                    it.dterms { terms ->
                        terms.buckets { buckets -> buckets.array(listOf(dtermsBucket(1.0), dtermsBucket(2.0))) }
                            .sumOtherDocCount(0)
                    }
                },
                size = 1,
            )
        }
        // An aggregate kind the plan never asks for.
        assertThrows<IllegalStateException> {
            rows(Aggregate.of { it.sum { sum -> sum.value(1.0) } })
        }
    }
}
