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

package me.ahoo.wow.query

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.spec.MetricSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `aggregate`: what it sends a backend and what the core computes after it (HAVING, top-N, dense fill, the summary). */
internal class BackendAggregationQueriesTest : BackendQueriesFixtures() {
    @Test
    fun `native aggregation sends the query unchanged with its limit`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(row("a")) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 5,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
            )
            val admitted = QueryAdmission.Trusted.aggregate(query, schema)
            backend.aggregate(admitted).toList().assert().hasSize(1)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(5))
            backend.aggregations.single().assert().isSameAs(admitted.query)
        }
    }

    @Test
    fun `a HAVING that removes every group leaves no row, never the empty summary`() {
        runTest {
            // A native HAVING removed every group: the core adds nothing in their place.
            val backend = RecordingBackend(groups = { emptyFlow() })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 1.0),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().assert().isEmpty()
            // A summary cannot carry a HAVING, so the empty summary is never a row a HAVING removed.
            assertThrows<IllegalArgumentException> { query.copy(groupBy = emptyList()) }
        }
    }

    @Test
    fun `groups without a sort ask the storage for one more, to refuse a cut no one asked for`() {
        runTest {
            val groups = flowOf(group("a", 1), group("b", 3), group("c", 5))
            val unsorted = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                limit = 2,
            )
            val backend = RecordingBackend(groups = { groups })
            assertThrows<QueryRequestException> {
                backend.aggregate(QueryAdmission.Trusted.aggregate(unsorted, schema)).toList()
            }.violation.assert().isEqualTo(QueryViolation.TooManyGroups(2))
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(3))
            // A sort makes the cut the query's own: a top-N, asked for exactly.
            val sorted = RecordingBackend(groups = { groups })
            val top = AggregationQuery(
                groupBy = unsorted.groupBy,
                metrics = unsorted.metrics,
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 2,
            )
            sorted.aggregate(QueryAdmission.Trusted.aggregate(top, schema)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("a", "b")
            sorted.groupWindows.single().assert().isEqualTo(GroupWindow.First(2))
            // A residual HAVING reads every group: what passes it is counted against the limit the same way.
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
            )
            val having = AggregationQuery(
                groupBy = unsorted.groupBy,
                metrics = unsorted.metrics,
                limit = 1,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
            )
            val all = RecordingBackend(groups = { groups })
            assertThrows<QueryRequestException> {
                all.aggregate(QueryAdmission.Trusted.aggregate(having, residual)).toList()
            }.violation.assert().isEqualTo(QueryViolation.TooManyGroups(1))
            all.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        }
    }

    @Test
    fun `residual HAVING asks for every group and filters them in the core`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 3), group("c", 5)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 1,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("b")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            backend.aggregations.single().having.assert().isNull()
        }
    }

    @Test
    fun `the entry budget bounds the groups a residual operator reads`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 1), group("c", 5)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 1,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
            )
            val admitted = QueryAdmission.Trusted.aggregate(query, residual)
            assertThrows<QueryRequestException> {
                backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 2)).toList()
            }.message.assert().isEqualTo(
                "HTTP aggregation processes more than [2] groups, dense fill included, to compute HAVING, a " +
                    "metric sort or a window in the query service; narrow the filter or the period."
            )
            backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 3)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("c")
            backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 0)).toList()
                .assert().hasSize(1)
            // A natively computed aggregation reads only its limit, so the bound does not apply.
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, schema),
                QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1)
            ).toList().assert().hasSize(1)
        }
    }

    @Test
    fun `residual top-N keeps the group order sent down and ranks by the metric in the core`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 3), group("c", 2)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 2,
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(topN = SupportMode.RESIDUAL))
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("b", "c")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            backend.aggregations.single().sort.assert().isEmpty()
        }
    }

    @Test
    fun `a storage that orders one TERMS group by the metric takes the top N itself`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("b", 3), group("c", 2)) })
            val count = AggregationMetric.Count("count")
            val byCount = listOf(Sort(QueryField("count"), Sort.Direction.DESC))
            val name = AggregationGroup.Terms(QueryField("name"), "name")
            val native = schema.withStorage(
                StorageSupport(
                    aggregation = AggregationSupport(topN = SupportMode.RESIDUAL, termsTopN = setOf(MetricSpec.COUNT))
                )
            )
            val query = AggregationQuery(groupBy = listOf(name), metrics = listOf(count), sort = byCount, limit = 2)
            // The bound on residual groups does not apply: the storage reads only its limit.
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, native),
                QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1),
            ).toList().map { it["name"].stringValue() }.assert().containsExactly("b", "c")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(2))
            backend.aggregations.single().sort.assert().isEqualTo(byCount)

            // Two groups, a HAVING, or a metric type the storage does not order by stay residual.
            val residual = listOf(
                query.copy(groupBy = listOf(name, AggregationGroup.Terms(QueryField("aggregateId"), "aggregate"))),
                query.copy(having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0)),
                query.copy(
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("amount")),
                            "count",
                        )
                    ),
                ),
                query.copy(groupBy = listOf(AggregationGroup.Histogram(QueryField("amount"), "name", 10.0))),
            )
            residual.forEach { shape ->
                val recording = RecordingBackend(groups = { emptyFlow() })
                recording.aggregate(QueryAdmission.Trusted.aggregate(shape, native)).toList()
                recording.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            }
        }
    }

    @Test
    fun `residual dense fill sends real buckets only and fills the gaps before the limit`() {
        runTest {
            val day = 86_400_000L
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(3 * day, 1)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("day"), Sort.Direction.ASC)),
                limit = 3,
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(0L to 2L, day to 0L, 2 * day to 0L)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(3))
            (backend.aggregations.single().groupBy.single() as AggregationGroup.DateHistogram).dense.assert().isFalse()
        }
    }

    @Test
    fun `the residual budget counts dense fill rows, so a sparse fine-grained histogram under HAVING stays bounded`() {
        runTest {
            val year = 365L * 86_400_000L
            // Two real buckets a year apart at SECOND granularity: ~3·10^7 fill rows, all discarded by HAVING.
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 1), bucket(year, 1)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.SECOND,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 5.0),
                limit = 10,
            )
            val residual = schema.withStorage(
                StorageSupport(
                    aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL, having = SupportMode.RESIDUAL)
                ),
            )
            assertThrows<QueryRequestException> {
                backend.aggregate(
                    QueryAdmission.Trusted.aggregate(query, residual),
                    QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1000)
                ).toList()
            }.message!!.assert().startsWith("HTTP aggregation processes more than [1000] groups")
        }
    }

    @Test
    fun `a dense date part is filled by the core to its whole domain on every storage`() {
        runTest {
            val backend = RecordingBackend(
                groups = { flowOf(json("""{"weekday":2,"count":3}"""), json("""{"weekday":5,"count":1}""")) },
            )
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DatePart(
                        QueryField("createdAt"),
                        "weekday",
                        AggregationDatePart.DAY_OF_WEEK,
                        timeZone = "Asia/Shanghai",
                        dense = true,
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("weekday"), Sort.Direction.DESC)),
            )

            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
                .map { it["weekday"].intValue() to it["count"].longValue() }
                .assert().containsExactly(7 to 0L, 6 to 0L, 5 to 1L, 4 to 0L, 3 to 0L, 2 to 3L, 1 to 0L)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            val native = backend.aggregations.single()
            (native.groupBy.single() as AggregationGroup.DatePart).dense.assert().isFalse()
            native.sort.assert().containsExactly(Sort(QueryField("weekday"), Sort.Direction.DESC))
        }
    }

    @Test
    fun `HAVING and a metric sort see the fill rows of a dense date part`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(json("""{"hour":9,"count":4}""")) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DatePart(
                        QueryField("createdAt"),
                        "hour",
                        AggregationDatePart.HOUR_OF_DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 3,
                having = HavingExpression.Condition("count", ComparisonOperator.LT, 5.0),
            )

            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
                .map { it["hour"].intValue() to it["count"].longValue() }
                .assert().containsExactly(9 to 4L, 0 to 0L, 1 to 0L)
            backend.aggregations.single().having.assert().isNull()
            backend.aggregations.single().sort.assert().isEmpty()
        }
    }

    @Test
    fun `residual dense fill makes a native HAVING residual so it sees the fill rows`() {
        runTest {
            val day = 86_400_000L
            // A native HAVING would drop the day-1 bucket before the fill, which would then refill it with count 0.
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(day, 1), bucket(2 * day, 3)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 2.0),
                limit = 10,
            )
            val residualDense = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residualDense)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(0L to 2L, 2 * day to 3L)
            backend.aggregations.single().having.assert().isNull()
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        }
    }

    @Test
    fun `residual dense fill makes a native top-N residual so the fill walks key order`() {
        runTest {
            val day = 86_400_000L
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(2 * day, 5), bucket(3 * day, 1)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 3,
            )
            val residualDense = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residualDense)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(2 * day to 5L, 0L to 2L, 3 * day to 1L)
            val native = backend.aggregations.single()
            native.sort.map { it.field.path }.assert().doesNotContain("count")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        }
    }

    @Test
    fun `the core emits the empty summary when the backend emits no row without groups`() {
        runTest {
            val backend = RecordingBackend()
            val query = AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    ),
                ),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().single().apply {
                this["count"].longValue().assert().isZero()
                this["total"].isNull.assert().isTrue()
            }
        }
    }

    @Test
    fun `a summary the backend emits is never duplicated`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(json("""{"count":0}""")) })
            val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().assert().hasSize(1)
        }
    }
}
