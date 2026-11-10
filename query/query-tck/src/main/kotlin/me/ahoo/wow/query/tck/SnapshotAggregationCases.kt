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

package me.ahoo.wow.query.tck
import kotlinx.coroutines.flow.map
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DAY
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * The aggregation cases of the snapshot contract. They read only [SnapshotQueryFixtures]'s fixtures and hooks; see
 * [SnapshotQueryFixtures] for the chain of families it is part of.
 */
abstract class SnapshotAggregationCases internal constructor() : SnapshotKeysetCases() {
    // Aggregation

    @Test
    fun `aggregation summarizes every record with count, sum, avg, min and max`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = DeletionFilter(DeletionState.ACTIVE),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    numeric(AggregationFunction.SUM, "total"),
                    numeric(AggregationFunction.AVG, "average"),
                    numeric(AggregationFunction.MIN, "minimum"),
                    numeric(AggregationFunction.MAX, "maximum"),
                ),
            ),
        ).single()
        row.path("count").longValue().assert().isEqualTo(4L)
        row.path("total").doubleValue().assert().isEqualTo(110.5)
        row.path("average").doubleValue().assert().isEqualTo(27.625)
        row.path("minimum").doubleValue().assert().isEqualTo(10.5)
        row.path("maximum").doubleValue().assert().isEqualTo(50.0)
        Unit
    }

    @Test
    fun `aggregation returns one empty summary row`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = AggregateIdFilter("none"),
                metrics = listOf(AggregationMetric.Count("count"), numeric(AggregationFunction.SUM, "total")),
            ),
        ).single()
        row.path("count").longValue().assert().isEqualTo(0L)
        row.path("total").isNull.assert().isTrue()
        Unit
    }

    @Test
    fun `aggregation groups by terms in group order`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count"), numeric(AggregationFunction.SUM, "total")),
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("CANCELLED", "NEW", "PAID")
        rows.map { it.path("count").longValue() }.assert().containsExactly(1L, 2L, 2L)
        rows.map { it.path("total").doubleValue() }.assert().containsExactly(50.0, 70.0, 30.5)
        Unit
    }

    /**
     * The orders and one more whose keywords repeat: `a` on o1 and o6, `b` on o1 and o2, `c` (twice) on o6 only; o3's
     * are empty, o4 and o5 have none.
     */
    private fun keywordOrders(): List<SnapshotRecord> = orders() + snapshot(
        "o6",
        """{"name":"Zeta","status":"NEW","amount":1,"quantity":1,"createdAt":$T0,"keywords":["c","a","c"],""" +
            """"lines":[]}""",
    )

    @Test
    fun `aggregation groups numbers by value, whatever their JSON form`() = blocking {
        val fixture = given(
            listOf(
                snapshot("q1", """{"quantity":1}"""),
                snapshot("q2", """{"quantity":1.0}"""),
                snapshot("q3", """{"quantity":2}"""),
            ),
        )
        val rows = fixture.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.quantity"), "quantity")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("quantity"), Sort.Direction.ASC)),
            ),
        )
        rows.map { it.path("quantity").doubleValue() to it.path("count").longValue() }
            .assert().containsExactly(1.0 to 2L, 2.0 to 1L)
        Unit
    }

    @Test
    fun `aggregation groups a string array by each of its values, or refuses it where storage cannot`() = blocking {
        val fixture = given(keywordOrders())
        val byKeyword = AggregationQuery(
            filter = MatchAllFilter,
            groupBy = listOf(AggregationGroup.Terms(KEYWORDS, "keyword")),
            metrics = listOf(AggregationMetric.Count("count"), numeric(AggregationFunction.SUM, "total")),
        )
        if (fixture.schema.field(KEYWORDS)?.binding(QueryCapability.AGGREGATE_TERMS) == null) {
            val refused = runCatching { fixture.aggregate(byKeyword) }.exceptionOrNull()
            (refused as QuerySchemaValidationException).violation.assert()
                .isEqualTo(QueryViolation.UnsupportedCapability(KEYWORDS, setOf(QueryCapability.AGGREGATE_TERMS)))
            return@blocking
        }
        val rows = fixture.aggregate(byKeyword).associateBy { it.path("keyword").stringValue(null) }
        // A record joins the group of each distinct value; one without a value (none, or empty) joins the null group.
        rows.mapValues { it.value.path("count").longValue() }.assert()
            .isEqualTo(mapOf("a" to 2L, "b" to 2L, "c" to 1L, null to 3L))
        rows.mapValues { it.value.path("total").doubleValue() }.assert()
            .isEqualTo(mapOf("a" to 11.5, "b" to 30.5, "c" to 1.0, null to 120.0))
        Unit
    }

    @Test
    fun `a string array sorts by its least value ascending, or refuses the sort where storage cannot`() = blocking {
        val fixture = given(keywordOrders())
        val withKeywords = AggregateIdsFilter(listOf("o1", "o2", "o6"))
        val ascending = listOf(Sort(KEYWORDS, Sort.Direction.ASC), Sort(QueryField("aggregateId"), Sort.Direction.ASC))
        if (fixture.schema.field(KEYWORDS)?.binding(QueryCapability.SORT) == null) {
            val refused = runCatching { fixture.ids(withKeywords, ascending) }.exceptionOrNull()
            (refused as QuerySchemaValidationException).violation.assert()
                .isEqualTo(QueryViolation.UnsupportedCapability(KEYWORDS, setOf(QueryCapability.SORT)))
            return@blocking
        }
        // o1 [a, b] and o6 [c, a, c] lead with `a`, o2 [b] with `b`.
        fixture.ids(withKeywords, ascending).assert().containsExactly("o1", "o6", "o2")
        Unit
    }

    @Test
    fun `an array holding a null sorts first ascending, or the sort is refused where storage cannot`() = blocking {
        val fixture = given(
            listOf(snapshot("n1", """{"probeTags":[null,"z"]}"""), snapshot("n2", """{"probeTags":["a"]}""")),
        )
        val tags = QueryField("state.probeTags")
        fun sorted(direction: Sort.Direction) = blocking {
            fixture.ids(AggregateIdsFilter(listOf("n1", "n2")), listOf(Sort(tags, direction)))
        }
        val field = checkNotNull(fixture.schema.field(tags))
        if (field.binding(QueryCapability.SORT) == null || !fixture.schema.storage.sorts(field.value)) {
            // A storage that cannot sort arrays, or cannot see a null element, refuses: it never orders otherwise.
            val refused = runCatching { sorted(Sort.Direction.ASC) }.exceptionOrNull()
            val violation = checkNotNull((refused as QuerySchemaValidationException).violation)
            (violation is QueryViolation.UnsupportedCapability || violation is QueryViolation.StorageUnsupported)
                .assert().describedAs("$violation").isTrue()
            violation.field.assert().isEqualTo(tags)
            return@blocking
        }
        // n1's least element is its null, below n2's "a"; its greatest, "z", is above it.
        sorted(Sort.Direction.ASC).assert().containsExactly("n1", "n2")
        sorted(Sort.Direction.DESC).assert().containsExactly("n1", "n2")
        Unit
    }

    @Test
    fun `aggregation sorts groups by a metric and takes the top N`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(numeric(AggregationFunction.SUM, "total")),
                sort = listOf(Sort(QueryField("total"), Sort.Direction.DESC)),
                limit = 2,
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("NEW", "CANCELLED")
        Unit
    }

    @Test
    fun `aggregation without a sort refuses more groups than its limit, and returns every group within it`() =
        blocking {
            val fixture = given(orders())
            fun byStatus(limit: Int) = AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                limit = limit,
            )
            val refused = runCatching { fixture.aggregate(byStatus(2)) }.exceptionOrNull()
            refused.assert().isInstanceOf(QueryRequestException::class.java)
            (refused as QueryRequestException).violation.assert().isEqualTo(QueryViolation.TooManyGroups(2))
            fixture.aggregate(byStatus(3)).map { it.path("status").stringValue() }
                .assert().containsExactly("CANCELLED", "NEW", "PAID")
            Unit
        }

    @Test
    fun `aggregation HAVING keeps the matching groups`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 2.0),
            ),
        )
        rows.map { it.path("status").stringValue() }.assert().containsExactly("NEW", "PAID")
        Unit
    }

    @Test
    fun `aggregation HAVING that matches no group returns no row`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.aggregation.having, "storage.aggregation.having", "HAVING")
        val byStatus = AggregationQuery(
            filter = MatchAllFilter,
            groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
            metrics = listOf(AggregationMetric.Count("count")),
            having = HavingExpression.Condition("count", ComparisonOperator.GTE, 10.0),
        )
        // Groups exist, but HAVING removes all of them: no row, and no empty summary in their place.
        fixture.aggregate(byStatus).assert().isEmpty()
        // No record matches: no group, so no row either.
        fixture.aggregate(byStatus.copy(filter = AggregateIdFilter("none"))).assert().isEmpty()
        Unit
    }

    @Test
    fun `aggregation metric filters count a subset of each group`() = blocking {
        val fixture = given(orders())
        val row = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                metrics = listOf(
                    AggregationMetric.Count("all"),
                    AggregationMetric.Count("paid", EqualFilter(QueryField("state.status"), "PAID")),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(AMOUNT),
                        "paidTotal",
                        InFilter(QueryField("state.status"), listOf("PAID")),
                    ),
                ),
            ),
        ).single()
        row.path("all").longValue().assert().isEqualTo(5L)
        row.path("paid").longValue().assert().isEqualTo(2L)
        row.path("paidTotal").doubleValue().assert().isEqualTo(30.5)
        Unit
    }

    @Test
    fun `aggregation expands elements and sums each element group`() = blocking {
        val fixture = given(orders())
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                elements = listOf(AggregationElement(QueryField("state.lines"))),
                groupBy = listOf(AggregationGroup.Terms(QueryField("sku"), "sku")),
                metrics = listOf(
                    AggregationMetric.Count("lines"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("quantity")),
                        "quantity"
                    ),
                ),
            ),
        )
        rows.map { it.path("sku").stringValue() }.assert().containsExactly("A", "B", "C")
        rows.map { it.path("lines").longValue() }.assert().containsExactly(2L, 2L, 1L)
        rows.map { it.path("quantity").doubleValue() }.assert().containsExactly(2.0, 7.0, 4.0)
        Unit
    }

    @Test
    fun `aggregation groups the elements by a field of their root record`() = blocking {
        val fixture = given(orders())
        assumeSupported(
            fixture.schema.storage.aggregation.rootGroups,
            "storage.aggregation.rootGroups",
            "groups by a root field"
        )
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                elements = listOf(AggregationElement(QueryField("state.lines"))),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("state.status"), "status", scope = AggregationGroupScope.ROOT),
                ),
                metrics = listOf(AggregationMetric.Count("lines")),
            ),
        )
        rows.map { it.path("status").stringValue() to it.path("lines").longValue() }
            .assert().containsExactly("CANCELLED" to 1L, "NEW" to 1L, "PAID" to 3L)
        Unit
    }

    @Test
    fun `a histogram and an expression group put the records without a value in the null group`() = blocking {
        val fixture = given(
            listOf(
                snapshot("h1", """{"amount":12,"quantity":2}"""),
                snapshot("h2", """{"amount":null,"quantity":1}"""),
                snapshot("h3", """{"quantity":1}"""),
                // A division by zero has no value.
                snapshot("h4", """{"amount":4,"quantity":0}"""),
            ),
        )
        val amount = QueryField("state.amount")
        val perUnit = AggregationExpression.Binary(
            me.ahoo.wow.api.query.AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Field(amount),
            AggregationExpression.Field(QueryField("state.quantity")),
        )
        fun groups(group: AggregationGroup): List<Pair<Double?, Long>> = blocking {
            fixture.aggregate(
                AggregationQuery(
                    groupBy = listOf(group),
                    metrics = listOf(AggregationMetric.Count("count")),
                    sort = listOf(Sort(QueryField("key"), Sort.Direction.ASC)),
                ),
            ).map { row -> row.path("key").takeUnless { it.isNull }?.doubleValue() to row.path("count").longValue() }
        }
        groups(AggregationGroup.Histogram(amount, "key", 10.0)).assert()
            .containsExactly(null to 2L, 0.0 to 1L, 10.0 to 1L)
        groups(AggregationGroup.Histogram(alias = "key", interval = 5.0, expression = perUnit)).assert()
            .containsExactly(null to 3L, 5.0 to 1L)
        groups(AggregationGroup.Terms(alias = "key", expression = perUnit)).assert()
            .containsExactly(null to 3L, 6.0 to 1L)
        Unit
    }

    @Test
    fun `aggregation counts distinct values`() = blocking {
        val fixture = given(orders())
        assumeSupported(
            fixture.schema.storage.aggregation.distinctCount,
            "storage.aggregation.distinctCount",
            "distinct counts"
        )
        val row = fixture.aggregate(
            AggregationQuery(
                filter = MatchAllFilter,
                metrics = listOf(
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("state.status")), "statuses")
                ),
            ),
        ).single()
        row.path("statuses").longValue().assert().isEqualTo(3L)
        Unit
    }

    @Test
    fun `aggregation FIRST and LAST pick among the records that hold a value`() = blocking {
        val fixture = given(
            listOf(
                snapshot("e1", """{"name":null,"status":"PAID","createdAt":$T0}"""),
                snapshot("e2", """{"name":"Beta","status":"PAID","createdAt":${T0 + DAY}}"""),
                snapshot("e3", """{"status":"PAID","createdAt":${T0 + 2 * DAY}}"""),
                snapshot("e4", """{"name":null,"status":"NEW","createdAt":$T0}"""),
                snapshot("e5", """{"status":"NEW","createdAt":${T0 + DAY}}"""),
            ),
        )
        assumeSupported(fixture.schema.storage.aggregation.firstLast, "storage.aggregation.firstLast", "FIRST / LAST")
        val createdAt = QueryField("state.createdAt")
        val rows = fixture.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("state.status"), "status")),
                metrics = listOf(
                    AggregationMetric.First(QueryField("state.name"), "first", createdAt),
                    AggregationMetric.Last(QueryField("state.name"), "last", createdAt),
                ),
                sort = listOf(Sort(QueryField("status"), Sort.Direction.ASC)),
            ),
        ).associateBy { it.path("status").stringValue() }
        rows.getValue("PAID").path("first").stringValue().assert().isEqualTo("Beta")
        rows.getValue("PAID").path("last").stringValue().assert().isEqualTo("Beta")
        rows.getValue("NEW").path("first").isNull.assert().isTrue()
        rows.getValue("NEW").path("last").isNull.assert().isTrue()
        Unit
    }

    @Test
    fun `aggregation groups an epoch field by day`() = blocking {
        val fixture = given(orders())
        val createdAt = fixture.schema.field(QueryField("state.createdAt"))
        assumeBound(
            createdAt?.binding(QueryCapability.AGGREGATE_TEMPORAL),
            "state.createdAt",
            QueryCapability.AGGREGATE_TEMPORAL,
            "temporal grouping",
        )
        val rows = fixture.aggregate(
            AggregationQuery(
                filter = GreaterThanOrEqualFilter(QueryField("state.createdAt"), T0 + DAY),
                groupBy = listOf(
                    AggregationGroup.DateHistogram(QueryField("state.createdAt"), "day", AggregationDateUnit.DAY)
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        rows.map {
            it.path(
                "day"
            ).longValue()
        }.assert().containsExactly(T0 + DAY, T0 + 2 * DAY, T0 + 3 * DAY, T0 + 4 * DAY)
        Unit
    }
}
