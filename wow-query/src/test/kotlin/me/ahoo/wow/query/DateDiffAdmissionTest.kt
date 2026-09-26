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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class DateDiffAdmissionTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "state" to objectFixture(
                "paidAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
                "shippedAt" to scalarFixture(QueryValueType.STRING, Temporal.Date),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "lines" to arrayFixture(objectFixture("sku" to scalarFixture())),
            ),
        ),
    )
    private val hours = AggregationExpression.DateDiff(
        QueryField("state.paidAt"),
        QueryField("state.shippedAt"),
        DateDiffUnit.HOUR,
    )

    @Test
    fun `date difference operands resolve with the temporal capability`() {
        val admitted = QueryAdmission.Trusted.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(alias = "bucket", interval = 24.0, expression = hours)),
                metrics = listOf(AggregationMetric.Numeric(AggregationFunction.AVG, hours, "avgHours")),
            ),
            schema,
        )
        val metric = admitted.query.metrics.single() as AggregationMetric.Numeric
        val diff = metric.expression as AggregationExpression.DateDiff
        listOf(diff.from, diff.to).forEach {
            admitted.field(it).capability.assert().isEqualTo(QueryCapability.AGGREGATE_TEMPORAL)
        }
        val group = admitted.query.groupBy.single() as AggregationGroup.Histogram
        group.field.assert().isNull()
        admitted.field((group.expression as AggregationExpression.DateDiff).to).logicalField.assert()
            .isEqualTo(QueryField("state.shippedAt"))
    }

    @Test
    fun `a date difference needs temporal operands`() {
        val amount = AggregationExpression.DateDiff(
            QueryField("state.paidAt"),
            QueryField("state.amount"),
            DateDiffUnit.DAY
        )
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(metrics = listOf(AggregationMetric.Numeric(AggregationFunction.SUM, amount, "days"))),
                boundSchemaFixture(
                    objectFixture(
                        "aggregateId" to scalarFixture(),
                        "state" to objectFixture(
                            "paidAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
                            "amount" to scalarFixture(QueryValueType.DECIMAL),
                        ),
                    ),
                    fieldCapabilities = setOf(QueryCapability.AGGREGATE_NUMERIC, QueryCapability.EXACT_MATCH),
                ),
            )
        }.violation.assert().isInstanceOf(QueryViolation.UnsupportedCapability::class.java)
    }

    @Test
    fun `an expression filter is a root filter that reads its fields for aggregation`() {
        val late = ExpressionFilter(hours, ComparisonOperator.GT, 48.0)
        val admitted = QueryAdmission.Trusted.list(ListQuery(late, limit = 10), schema)
        val filter = admitted.query.filter as ExpressionFilter
        admitted.field((filter.expression as AggregationExpression.DateDiff).from).capability.assert()
            .isEqualTo(QueryCapability.AGGREGATE_TEMPORAL)

        assertThrows<IllegalArgumentException> { ElementMatchFilter(QueryField("state.lines"), late) }
        assertThrows<IllegalArgumentException> {
            ExpressionFilter(AggregationExpression.Constant(1.0), ComparisonOperator.GT, 0.0)
        }
        assertThrows<IllegalArgumentException> { ExpressionFilter(hours, ComparisonOperator.GT, Double.NaN) }
    }

    @Test
    fun `a TERMS or HISTOGRAM group reads exactly one of a field and an expression`() {
        assertThrows<IllegalArgumentException> { AggregationGroup.Histogram(alias = "none", interval = 1.0) }
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(QueryField("state.amount"), "both", expression = hours)
        }
        assertThrows<IllegalArgumentException> {
            AggregationGroup.Terms(alias = "missing", missingKey = "none", expression = hours)
        }
    }

    @Test
    fun `computed expressions are expensive for the entry budget`() {
        val strict = QueryBudget(QueryBudget.HTTP_LABEL, allowExpensiveOperators = false)
        assertThrows<IllegalArgumentException> {
            strict.check(ListQuery(ExpressionFilter(hours, ComparisonOperator.GT, 48.0), limit = 10))
        }
        assertThrows<IllegalArgumentException> {
            strict.check(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(alias = "hours", expression = hours)),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
            )
        }
        QueryBudget.HTTP_DEFAULT.check(ListQuery(ExpressionFilter(hours, ComparisonOperator.GT, 48.0), limit = 10))
    }

    @Test
    fun `the descriptor offers the units and the expression filter only where expensive operators are allowed`() {
        val open = schema.describe(QueryBudget.HTTP_DEFAULT, 100)
        open.analysis.dateDiffUnits.assert().isEqualTo(DateDiffUnit.entries)
        open.record.rootOperators.assert().contains(FilterOperator.EXPRESSION)

        val strict = schema.describe(QueryBudget(QueryBudget.HTTP_LABEL, allowExpensiveOperators = false), 100)
        strict.analysis.dateDiffUnits.assert().isEmpty()
        strict.record.rootOperators.assert().doesNotContain(FilterOperator.EXPRESSION)
    }
}
