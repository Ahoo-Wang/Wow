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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.aggregation.PreviousStep
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.resolve.integralLong
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

class PreviousWindowsTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "province" to scalarFixture(),
            "amount" to scalarFixture(QueryValueType.INTEGER),
            "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
            "paidAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.SECONDS)),
        ),
    )
    private val createdAt = QueryField("createdAt")
    private val province = AggregationGroup.Terms(QueryField("province"), "province")
    private val month = AggregationGroup.DateHistogram(createdAt, "month", AggregationDateUnit.MONTH)
    private val gmv = AggregationMetric.Numeric(
        AggregationFunction.SUM,
        AggregationExpression.Field(QueryField("amount")),
        "gmv",
    )

    private fun yoy(filter: FilterExpression, groupBy: List<AggregationGroup> = listOf(province, month)) =
        AggregationQuery(
            filter = filter,
            groupBy = groupBy,
            metrics = listOf(
                gmv,
                AggregationMetric.Derived(
                    "yoy",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("gmv"),
                        DerivedExpression.Previous("gmv", "month", period = AggregationDateUnit.YEAR),
                    ),
                ),
            ),
        )

    @Test
    fun `year over year reads the shifted range once more and joins it on the shifted bucket`() {
        runTest {
            val thisYear = listOf(row("zj", JAN_2026, 120), row("zj", FEB_2026, 90), row("js", JAN_2026, 50))
            val lastYear = listOf(row("zj", JAN_2025, 100), row("zj", FEB_2025, 0), row("sh", JAN_2025, 10))
            val backend = RecordingBackend { query ->
                val lower = ((query.filter as AndFilter).operands[0] as GreaterThanOrEqualFilter).value.integralLong()
                (if (lower == JAN_2026) thisYear else lastYear).map { it.deepCopy() }.asFlow()
            }
            val filter = AndFilter(
                listOf(
                    GreaterThanOrEqualFilter(createdAt, JAN_2026),
                    LessThanFilter(createdAt, MAR_2026),
                    EqualFilter(QueryField("province"), "zj"),
                ),
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(yoy(filter), schema)).toList()

            rows.map { it["yoy"].takeUnless(JsonNode::isNull)?.doubleValue() }.assert().containsExactly(1.2, null, null)
            backend.aggregations.assert().hasSize(2)
            val shifted = backend.aggregations[1].filter as AndFilter
            (shifted.operands[0] as GreaterThanOrEqualFilter).value.integralLong().assert().isEqualTo(JAN_2025)
            (shifted.operands[1] as LessThanFilter).value.integralLong().assert().isEqualTo(MAR_2025)
            shifted.operands[2].assert().isEqualTo(backend.aggregations[0].filter.let { it as AndFilter }.operands[2])
            backend.windows.assert().containsExactly(GroupWindow.All, GroupWindow.All)
        }
    }

    @Test
    fun `without a range on the date field the earlier buckets come from the same groups`() {
        runTest {
            val backend = RecordingBackend {
                flowOf(row("zj", DEC_2025, 10), row("zj", JAN_2026, 30), row("zj", MAR_2026, 60))
            }
            val query = AggregationQuery(
                groupBy = listOf(province, month),
                metrics = listOf(gmv, AggregationMetric.Derived("mom", DerivedExpression.Previous("gmv", "month"))),
            )
            val rows = backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            rows.map {
                it["mom"].takeUnless(
                    JsonNode::isNull
                )?.doubleValue()
            }.assert().containsExactly(null, 10.0, null)
            backend.aggregations.assert().hasSize(1)
        }
    }

    @Test
    fun `a range the filter conditions outside its top-level AND is rejected at admission`() {
        val filter = OrFilter(listOf(GreaterThanFilter(createdAt, JAN_2026), IsNullFilter(createdAt)))
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.aggregate(yoy(filter), schema) }
            .violation.assert().isEqualTo(QueryViolation.PreviousUnshiftable(createdAt))
        val step = PreviousStep(month, 1, null)
        step.shift(ContainsFilter(createdAt, "2026"), null).assert().isNull()
        step.shift(GreaterThanFilter(createdAt, 1.5), null).assert().isNull()
        QueryViolation.PreviousUnshiftable(createdAt).message.assert().contains("[createdAt]")
    }

    @Test
    fun `every comparison on the date field moves back and presence stays`() {
        val step = PreviousStep(month, 1, AggregationDateUnit.YEAR)
        val millis = Temporal.Epoch(TimeUnit.MILLISECONDS)
        val moved = listOf(
            EqualFilter(createdAt, JAN_2026),
            NotEqualFilter(createdAt, JAN_2026),
            GreaterThanFilter(createdAt, JAN_2026),
            GreaterThanOrEqualFilter(createdAt, JAN_2026),
            LessThanFilter(createdAt, JAN_2026),
            LessThanOrEqualFilter(createdAt, JAN_2026),
            InFilter(createdAt, listOf(JAN_2026)),
            NotInFilter(createdAt, listOf(JAN_2026)),
            BetweenFilter(createdAt, JAN_2026, MAR_2026),
        ).map { step.shift(it, millis).toString() }
        moved.forEach { it.assert().contains("$JAN_2025").doesNotContain("$JAN_2026") }
        listOf(ExistsFilter(createdAt), NotExistsFilter(createdAt), IsNullFilter(createdAt), IsNotNullFilter(createdAt))
            .forEach { step.shift(it, millis).assert().isEqualTo(it) }
        step.shift(InFilter(createdAt, listOf("soon")), millis).assert().isNull()
        step.shift(BetweenFilter(createdAt, "x", 1), millis).assert().isNull()
        step.shift(BetweenFilter(createdAt, 1, "x"), millis).assert().isNull()
        step.shift(EqualFilter(createdAt, true), millis).assert().isNull()
        // An integral JSON node is an instant too.
        (step.shift(EqualFilter(createdAt, WowJson.mapper.readTree("$JAN_2026")), millis) as EqualFilter)
            .value.integralLong().assert().isEqualTo(JAN_2025)
    }

    @Test
    fun `the date field may not hide in a nested filter`() {
        val step = PreviousStep(month, 1, null)
        listOf(
            ElementMatchFilter(QueryField("createdAt"), ExistsFilter(QueryField("x"))),
            ExpressionFilter(AggregationExpression.Field(createdAt), ComparisonOperator.GT, 1.0),
            SearchFilter("2026", setOf(createdAt)),
            AndFilter(listOf(ExistsFilter(QueryField("amount")), OrFilter(listOf(ExistsFilter(createdAt))))),
        ).forEach { step.shift(it, null).assert().isNull() }
        val other = ExistsFilter(QueryField("amount"))
        step.shift(other, null).assert().isEqualTo(other)
    }

    @Test
    fun `instants move back in the encoding they came in`() {
        val step = PreviousStep(month, 1, AggregationDateUnit.YEAR)
        fun text(value: String) = (step.shift(EqualFilter(createdAt, value), Temporal.Date) as? EqualFilter)?.value
        text("2026-01-01T00:00:00Z").assert().isEqualTo("2025-01-01T00:00:00Z")
        text("2026-01-01T08:00:00+08:00").assert().isEqualTo("2025-01-01T08:00:00+08:00")
        text("2026-01-01T08:00:00-05:00").assert().isEqualTo("2025-01-01T08:00:00-05:00")
        text("2026-01-01T08:00:00").assert().isEqualTo("2025-01-01T08:00:00")
        text("2024-02-29").assert().isEqualTo("2023-02-28")
        text("yesterday").assert().isNull()
        val seconds = step.shift(EqualFilter(createdAt, JAN_2026 / 1000), Temporal.Epoch(TimeUnit.SECONDS))
        (seconds as EqualFilter).value.integralLong().assert().isEqualTo(JAN_2025 / 1000)
    }

    @Test
    fun `a bucket step moves the key by buckets and a period step by the calendar`() {
        PreviousStep(month, 1, null).previousKey(JAN_2026).assert().isEqualTo(DEC_2025)
        PreviousStep(month, 2, AggregationDateUnit.QUARTER).previousKey(MAR_2026).assert().isEqualTo(
            PreviousStep(month, 6, null).previousKey(MAR_2026),
        )
        val day = AggregationGroup.DateHistogram(createdAt, "day", AggregationDateUnit.DAY)
        // 2026-03-31 one month back is 2026-02-28.
        PreviousStep(day, 1, AggregationDateUnit.MONTH).previousKey(MAR_31_2026).assert().isEqualTo(FEB_28_2026)
        AggregationDateUnit.entries.forEach { unit ->
            val along = AggregationGroup.DateHistogram(createdAt, "bucket", unit)
            PreviousStep(along, 1, unit).field.assert().isEqualTo(createdAt)
            (PreviousStep(along, 1, unit).previousKey(JAN_2026) < JAN_2026).assert().isTrue()
        }
    }

    private fun row(province: String, month: Long, gmv: Long): ObjectNode =
        WowJson.mapper.readTree("""{"province":"$province","month":$month,"gmv":$gmv}""") as ObjectNode

    private class RecordingBackend(private val groups: (AggregationQuery) -> Flow<ObjectNode>) : QueryBackend {
        override val aggregateType: AggregateType = AggregateType("context", "aggregate")
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON
        val windows = mutableListOf<GroupWindow>()
        val aggregations = mutableListOf<AggregationQuery>()

        override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = emptyFlow()

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage =
            BackendPage(emptyList())

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long = 0

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            aggregations += query.query
            windows += window
            emitAll(groups(query.query))
        }
    }

    private companion object {
        const val JAN_2025 = 1735689600000L
        const val MAR_2025 = 1740787200000L
        const val DEC_2025 = 1764547200000L
        const val JAN_2026 = 1767225600000L
        const val FEB_2025 = 1738368000000L
        const val FEB_2026 = 1769904000000L
        const val MAR_2026 = 1772323200000L
        const val MAR_31_2026 = MAR_2026 + 30 * 86_400_000L
        const val FEB_28_2026 = FEB_2026 + 27 * 86_400_000L
    }
}
