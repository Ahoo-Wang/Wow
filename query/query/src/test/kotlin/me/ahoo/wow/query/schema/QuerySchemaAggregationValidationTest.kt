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

@file:Suppress("LongMethod")

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.schema.QuerySchemaValidationFixtures.validateQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How validation admits an aggregation: relative inputs, groups and their missing key, metrics and metric filters. */
class QuerySchemaAggregationValidationTest {
    @Test
    fun `aggregation consumes logical relative inputs without rewriting nested filters`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "orders" to arrayFixture(
                    objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL))
                )
            )
        )
        val query = AggregationQuery(
            elements = listOf(AggregationElement(QueryField("orders"), EqualFilter(QueryField("status"), "PAID"))),
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(
                AggregationMetric.Numeric(
                    AggregationFunction.SUM,
                    AggregationExpression.Binary(
                        AggregationExpressionOperator.ADD,
                        AggregationExpression.Field(QueryField("amount")),
                        AggregationExpression.Constant(1.0),
                    ),
                    "total",
                ),
            ),
        )
        validateQuery(query, schema).assert().isSameAs(query)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(expandedSum("orders", "orders.amount"), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(expandedSum("orders", "missing"), schema)
        }
    }

    @Test
    fun `terms missing key requires a single valued string field`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "name" to scalarFixture(QueryValueType.STRING),
                "nickname" to QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(QueryValueType.STRING), QueryValueSchema(QueryValueKind.NULL))
                ),
                "label" to QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(QueryValueType.STRING), scalarFixture(QueryValueType.INTEGER))
                ),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "names" to arrayFixture(scalarFixture(QueryValueType.STRING)),
            )
        )
        fun termsWithMissingKey(field: String) = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField(field), field, missingKey = "UNKNOWN")),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        validateQuery(termsWithMissingKey("name"), schema).assert().isNotNull()
        validateQuery(termsWithMissingKey("nickname"), schema).assert().isNotNull()
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("amount"), schema)
        }.message.assert().isEqualTo("Field [amount] must be a single-valued string field to declare missingKey.")
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("names"), schema)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(termsWithMissingKey("label"), schema)
        }
    }

    @Test
    fun `histogram and date histogram groups skip the terms missing key predicate`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "amount" to scalarFixture(QueryValueType.DECIMAL),
                "createdAt" to scalarFixture(QueryValueType.STRING, temporal = Temporal.Date),
            )
        )
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.Histogram(QueryField("amount"), "amountRange", 10.0),
                AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
        )
        // Neither grouped field is a single-valued string: the missingKey predicate applies to
        // TERMS groups only, so both groups must validate on their own capabilities.
        validateQuery(query, schema).assert().isSameAs(query)
    }

    @Test
    fun `metric filters follow the current scope for root and element contexts`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "tenantId" to scalarFixture(),
                "orders" to arrayFixture(
                    objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL))
                )
            )
        )
        val paid = EqualFilter(QueryField("status"), "PAID")
        // Root scope: metric filters may use root-level filters (tenantId is declared at the root).
        validateQuery(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("tenantOrders", TenantIdFilter("tenant")))),
            schema,
        ).assert().isNotNull()
        // Element scope: fields of the current scope are legal; unknown fields are rejected.
        validateQuery(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("orders"))),
                metrics = listOf(AggregationMetric.Count("paid", paid)),
            ),
            schema,
        ).assert().isNotNull()
        assertThrows<QuerySchemaValidationException> {
            validateQuery(
                AggregationQuery(
                    elements = listOf(AggregationElement(QueryField("orders"))),
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("missing")),
                            "total",
                            paid,
                        ),
                    ),
                ),
                schema,
            )
        }
        // Unfiltered metrics (MatchAllFilter) skip metric filter validation inside element scope.
        validateQuery(
            AggregationQuery(
                elements = listOf(AggregationElement(QueryField("orders"))),
                metrics = listOf(
                    AggregationMetric.Count("all"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    ),
                ),
            ),
            schema,
        ).assert().isNotNull()
    }

    @Test
    fun `distinct count accepts terms or numeric fields and rejects others while percentile follows numeric rules`() {
        val both = boundSchemaFixture(
            objectFixture(
                "customerId" to scalarFixture(),
                "amount" to scalarFixture(QueryValueType.DECIMAL),
            )
        )
        val amount = AggregationExpression.Field(QueryField("amount"))
        val query = AggregationQuery(
            metrics = listOf(
                AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField("customerId")), "customers"),
                AggregationMetric.DistinctCount(amount, "amounts"),
                AggregationMetric.Numeric(AggregationFunction.STDDEV, amount, "stddev"),
                AggregationMetric.Numeric(AggregationFunction.VARIANCE, amount, "variance"),
                AggregationMetric.Percentile(amount, 95.0, "p95"),
                AggregationMetric.Percentile(amount, 50.0, "median"),
            ),
        )
        validateQuery(query, both).assert().isSameAs(query)

        val presenceOnly = boundSchemaFixture(
            objectFixture("code" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.PRESENCE),
        )
        val code = AggregationExpression.Field(QueryField("code"))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.DistinctCount(code, "codes")), presenceOnly)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Percentile(code, 95.0, "p")), presenceOnly)
        }
    }

    @Test
    fun `distinct count accepts terms-only fields while percentile and stddev require numeric`() {
        val termsOnly = boundSchemaFixture(
            objectFixture("customerId" to scalarFixture()),
            fieldCapabilities = setOf(QueryCapability.AGGREGATE_TERMS),
        )
        val customer = AggregationExpression.Field(QueryField("customerId"))
        val query = metric(AggregationMetric.DistinctCount(customer, "customers"))
        validateQuery(query, termsOnly).assert().isSameAs(query)
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Percentile(customer, 95.0, "p95")), termsOnly)
        }
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Numeric(AggregationFunction.STDDEV, customer, "std")), termsOnly)
        }
    }

    @Test
    fun `derived metrics need no schema capability`() {
        val schema = boundSchemaFixture(objectFixture("status" to scalarFixture()))
        val query = AggregationQuery(
            metrics = listOf(
                AggregationMetric.Count("total"),
                AggregationMetric.Derived(
                    "half",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("total"),
                        DerivedExpression.Constant(2.0),
                    ),
                ),
            ),
        )
        validateQuery(query, schema).assert().isSameAs(query)
    }

    @Test
    fun `any metrics require scalar values`() {
        val array = boundSchemaFixture(objectFixture("tags" to arrayFixture(scalarFixture())))
        assertThrows<QuerySchemaValidationException> {
            validateQuery(metric(AggregationMetric.Any(QueryField("tags"), "tag")), array)
        }
    }

    private fun metric(metric: AggregationMetric) = AggregationQuery(metrics = listOf(metric))

    private fun expandedSum(path: String, field: String) = AggregationQuery(
        elements = listOf(AggregationElement(QueryField(path))),
        metrics = listOf(
            AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(QueryField(field)), "total"),
        ),
    )
}
