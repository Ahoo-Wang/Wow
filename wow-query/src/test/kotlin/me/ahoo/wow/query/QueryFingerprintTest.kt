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
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.filter.QueryType
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.StringNode

class QueryFingerprintTest {
    private val amount = QueryField("state.amount")
    private val createTime = QueryField("createTime")

    private fun count(filter: FilterExpression) = fingerprintOf(QueryType.COUNT, filter)

    private fun between(lower: Int, upper: Int) = BetweenFilter(amount, IntNode.valueOf(lower), IntNode.valueOf(upper))

    @Test
    fun `filter values never reach the shape and never change the fingerprint`() {
        val sameShapes = listOf(
            between(1234, 5678) to between(1, 2),
            RecentDaysFilter(createTime, 7, zoneId = "Asia/Shanghai") to RecentDaysFilter(createTime, 30),
            BeforeTodayFilter(createTime, "18:45") to BeforeTodayFilter(createTime, "09:00"),
            BeforeNowFilter(createTime, "-PT45M") to BeforeNowFilter(createTime),
            ContainsFilter(amount, "secret-text") to ContainsFilter(amount, "other"),
            SearchFilter("secret phrase") to SearchFilter("other"),
            TenantIdFilter("secret-tenant") to TenantIdFilter("other"),
            InFilter(amount, listOf(IntNode.valueOf(4321), IntNode.valueOf(8765))) to
                InFilter(amount, listOf(IntNode.valueOf(1), IntNode.valueOf(2))),
            ExpressionFilter(AggregationExpression.Field(amount), ComparisonOperator.GT, 2468.0) to
                ExpressionFilter(AggregationExpression.Field(amount), ComparisonOperator.GT, 1.0),
        )
        sameShapes.forEach { (left, right) -> count(left).assert().isEqualTo(count(right)) }
        val shape = queryShapeOf(QueryType.COUNT, AndFilter(sameShapes.map { it.first }))
        shape.assert().doesNotContain(
            "1234", "5678", "18:45", "PT45M", "Asia", "secret", "4321", "8765", "2468",
        )
    }

    @Test
    fun `a different structure has a different fingerprint`() {
        count(between(1, 2)).assert()
            .isNotEqualTo(count(GreaterThanFilter(amount, IntNode.valueOf(1))))
            .isNotEqualTo(count(BetweenFilter(createTime, IntNode.valueOf(1), IntNode.valueOf(2))))
        count(InFilter(amount, listOf(IntNode.valueOf(1)))).assert()
            .isNotEqualTo(count(InFilter(amount, listOf(IntNode.valueOf(1), IntNode.valueOf(2)))))
        count(RecentDaysFilter(createTime, 7)).assert()
            .isNotEqualTo(count(RecentDaysFilter(amount, 7)))
        fingerprintOf(QueryType.LIST, ListQuery(MatchAllFilter)).assert()
            .isNotEqualTo(
                fingerprintOf(
                    QueryType.LIST,
                    ListQuery(MatchAllFilter, sort = listOf(Sort(amount, Sort.Direction.ASC)))
                )
            )
            .isNotEqualTo(count(MatchAllFilter))
    }

    @Test
    fun `the page index and cursor are values while the paging kind and size are shape`() {
        fun paged(index: Int, size: Int) =
            fingerprintOf(QueryType.PAGED, PagedQuery(MatchAllFilter, pagination = Pagination(index, size)))
        paged(1, 10).assert().isEqualTo(paged(7, 10)).isNotEqualTo(paged(1, 20))
        fun cursor(value: String?) = fingerprintOf(QueryType.CURSOR, CursorQuery(MatchAllFilter, cursor = value))
        cursor("secret-cursor").assert().isEqualTo(cursor("other")).isNotEqualTo(cursor(null))
        queryShapeOf(QueryType.CURSOR, CursorQuery(MatchAllFilter, cursor = "secret-cursor")).assert()
            .doesNotContain("secret")
    }

    @Test
    fun `having bounds, constants and aliases never change an aggregation fingerprint`() {
        fun aggregation(alias: String, lower: Double, upper: Double, constant: Double, percentile: Double) =
            AggregationQuery(
                filter = BetweenFilter(amount, IntNode.valueOf(lower.toInt()), IntNode.valueOf(upper.toInt())),
                groupBy = listOf(
                    AggregationGroup.Terms(QueryField("state.region"), "${alias}Region", missingKey = "secret-key"),
                    AggregationGroup.Histogram(amount, "${alias}Bucket", interval = constant),
                ),
                metrics = listOf(
                    AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Field(amount), alias),
                    AggregationMetric.Percentile(AggregationExpression.Field(amount), percentile, "${alias}P"),
                    AggregationMetric.Derived(
                        "${alias}Scaled",
                        DerivedExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            DerivedExpression.MetricRef(alias),
                            DerivedExpression.Constant(constant),
                        ),
                    ),
                ),
                sort = listOf(Sort(QueryField(alias), Sort.Direction.DESC)),
                having = HavingExpression.And(
                    listOf(
                        HavingExpression.Between(alias, lower, upper),
                        HavingExpression.Condition("${alias}P", ComparisonOperator.GT, constant),
                        HavingExpression.In(alias, listOf(lower, upper)),
                    ),
                ),
            )
        val secret = aggregation("secretAlias", 1357.0, 9753.0, 8642.0, 95.0)
        fingerprintOf(QueryType.AGGREGATION, secret).assert()
            .isEqualTo(fingerprintOf(QueryType.AGGREGATION, aggregation("total", 1.0, 2.0, 3.0, 50.0)))
        queryShapeOf(QueryType.AGGREGATION, secret).assert()
            .doesNotContain("secret", "1357", "9753", "8642", "95")
        val otherHaving = secret.copy(having = HavingExpression.Condition("secretAlias", ComparisonOperator.LT, 1.0))
        fingerprintOf(QueryType.AGGREGATION, otherHaving).assert()
            .isNotEqualTo(fingerprintOf(QueryType.AGGREGATION, secret))
    }

    @Test
    fun `equality arity is shape, its value is not`() {
        fun eq(value: JsonNode) = count(EqualFilter(amount, value))
        eq(StringNode.valueOf("a")).assert().isEqualTo(eq(StringNode.valueOf("b")))
            .isNotEqualTo(eq(JsonNodeFactory.instance.arrayNode().add("a").add("b")))
    }
}
