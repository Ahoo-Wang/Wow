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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class FilterComplexityTest {
    private val field = QueryField("name")
    private val value = JsonNodeFactory.instance.stringNode("value")
    private val values = listOf(value, JsonNodeFactory.instance.stringNode("other"))

    @Test
    fun `isExpensive should flag negations emptiness checks and substring matching`() {
        listOf(
            NotEqualFilter(field, value),
            NotInFilter(field, values),
            NorFilter(listOf(EqualFilter(field, value))),
            IsNullFilter(field),
            IsNotNullFilter(field),
            NotExistsFilter(field),
            IsEmptyFilter(field),
            IsNotEmptyStringFilter(field),
            ContainsFilter(field, "v"),
            EndsWithFilter(field, "v"),
            StartsWithFilter(field, ""),
            StartsWithFilter(field, "v", StringComparison.CASE_INSENSITIVE),
        ).forEach { it.isExpensive().assert().describedAs(it.toString()).isTrue() }
    }

    @Test
    fun `isExpensive should accept indexable filters and inspect only the node itself`() {
        listOf(
            MatchAllFilter,
            MatchNoneFilter,
            IdFilter("id"),
            TenantIdFilter("tenant"),
            EqualFilter(field, value),
            InFilter(field, values),
            BetweenFilter(field, JsonNodeFactory.instance.numberNode(1), JsonNodeFactory.instance.numberNode(2)),
            ContainsAllFilter(field, values),
            IsEmptyStringFilter(field),
            ExistsFilter(field),
            StartsWithFilter(field, "v"),
            DeletionFilter(DeletionState.ALL),
            SearchFilter("text"),
            TodayFilter(field),
            AndFilter(listOf(NotEqualFilter(field, value))),
            OrFilter(listOf(NotEqualFilter(field, value))),
            ElementMatchFilter(field, NotEqualFilter(field, value)),
        ).forEach { it.isExpensive().assert().describedAs(it.toString()).isFalse() }
    }

    @Test
    fun `isMatchAll should recognize unrestricted filters`() {
        MatchAllFilter.isMatchAll().assert().isTrue()
        DeletionFilter(DeletionState.ALL).isMatchAll().assert().isTrue()
        DeletionFilter(DeletionState.ACTIVE).isMatchAll().assert().isFalse()
        AndFilter(listOf(MatchAllFilter, DeletionFilter(DeletionState.ALL))).isMatchAll().assert().isTrue()
        AndFilter(listOf(MatchAllFilter, IdFilter("id"))).isMatchAll().assert().isFalse()
        OrFilter(listOf(IdFilter("id"), MatchAllFilter)).isMatchAll().assert().isTrue()
        OrFilter(listOf(IdFilter("id"))).isMatchAll().assert().isFalse()
        NorFilter(listOf(MatchNoneFilter)).isMatchAll().assert().isFalse()
        MatchNoneFilter.isMatchAll().assert().isFalse()
        ElementMatchFilter(field, EqualFilter(field, value)).isMatchAll().assert().isFalse()
    }

    @Test
    fun `valueCount should count only multi-value nodes`() {
        InFilter(field, values).valueCount().assert().isEqualTo(2)
        NotInFilter(field, values).valueCount().assert().isEqualTo(2)
        ContainsAllFilter(field, values).valueCount().assert().isEqualTo(2)
        IdsFilter(listOf("a", "b", "c")).valueCount().assert().isEqualTo(3)
        AggregateIdsFilter(listOf("a")).valueCount().assert().isEqualTo(1)
        EqualFilter(field, value).valueCount().assert().isNull()
        IdFilter("id").valueCount().assert().isNull()
        AndFilter(listOf(InFilter(field, values))).valueCount().assert().isNull()
    }

    @Test
    fun `childFilters should expose logical operands and element predicates`() {
        val leaf = EqualFilter(field, value)
        AndFilter(listOf(leaf, MatchAllFilter)).childFilters().assert().containsExactly(leaf, MatchAllFilter)
        OrFilter(listOf(leaf)).childFilters().assert().containsExactly(leaf)
        NorFilter(listOf(leaf)).childFilters().assert().containsExactly(leaf)
        ElementMatchFilter(field, leaf).childFilters().assert().containsExactly(leaf)
        leaf.childFilters().assert().isEmpty()
    }

    @Test
    fun `walkFilterNodes should visit every node last-in-first-out`() {
        val first = EqualFilter(field, value)
        val second = IdFilter("id")
        val nested = ElementMatchFilter(QueryField("items"), InFilter(field, values))
        val tree = AndFilter(listOf(first, OrFilter(listOf(second, nested))))
        val scope = TenantIdFilter("tenant")

        val visited = listOf<FilterExpression>(tree, scope).walkFilterNodes().toList()

        visited.assert().containsExactly(
            scope,
            tree,
            tree.operands[1],
            nested,
            nested.predicate,
            second,
            first,
        )
        tree.filterNodeCount().assert().isEqualTo(6)
        MatchAllFilter.filterNodeCount().assert().isEqualTo(1)
    }

    @Test
    fun `walkFilterNodes should be lazy and stack safe`() {
        var deep: FilterExpression = IdFilter("leaf")
        repeat(100_000) { deep = AndFilter(listOf(deep)) }

        deep.filterNodeCount().assert().isEqualTo(100_001)
        deep.walkFilterNodes().take(2).toList().assert().hasSize(2)
    }

    @Test
    fun `having analysis should count values children and nodes`() {
        val condition = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0)
        val between = HavingExpression.Between("count", 1.0, 2.0)
        val inValues = HavingExpression.In("count", listOf(1.0, 2.0, 3.0))
        val isNull = HavingExpression.IsNull("count")
        val or = HavingExpression.Or(listOf(inValues, isNull))
        val and = HavingExpression.And(listOf(condition, between, or))

        condition.valueCount().assert().isEqualTo(1)
        between.valueCount().assert().isEqualTo(2)
        inValues.valueCount().assert().isEqualTo(3)
        isNull.valueCount().assert().isEqualTo(0)
        and.valueCount().assert().isEqualTo(0)
        or.valueCount().assert().isEqualTo(0)

        and.childExpressions().assert().containsExactly(condition, between, or)
        or.childExpressions().assert().containsExactly(inValues, isNull)
        condition.childExpressions().assert().isEmpty()

        and.walkHavingNodes().toList().assert().containsExactly(and, or, isNull, inValues, between, condition)
    }

    @Test
    fun `hasArithmeticExpression should flag non-field metric inputs`() {
        val fieldExpression = AggregationExpression.Field(field)
        val binary = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            fieldExpression,
            AggregationExpression.Constant(1.0),
        )

        AggregationMetric.Count("count").hasArithmeticExpression().assert().isFalse()
        AggregationMetric.Any(field, "any").hasArithmeticExpression().assert().isFalse()
        AggregationMetric.Numeric(AggregationFunction.SUM, fieldExpression, "sum")
            .hasArithmeticExpression().assert().isFalse()
        AggregationMetric.Numeric(AggregationFunction.SUM, binary, "sum")
            .hasArithmeticExpression().assert().isTrue()
        AggregationMetric.Numeric(AggregationFunction.SUM, AggregationExpression.Constant(1.0), "sum")
            .hasArithmeticExpression().assert().isTrue()
        AggregationMetric.DistinctCount(fieldExpression, "distinct").hasArithmeticExpression().assert().isFalse()
        AggregationMetric.DistinctCount(binary, "distinct").hasArithmeticExpression().assert().isTrue()
        AggregationMetric.Percentile(fieldExpression, 50.0, "p50").hasArithmeticExpression().assert().isFalse()
        AggregationMetric.Percentile(binary, 50.0, "p50").hasArithmeticExpression().assert().isTrue()
        AggregationMetric.Derived("ratio", DerivedExpression.MetricRef("count"))
            .hasArithmeticExpression().assert().isTrue()
    }
}
