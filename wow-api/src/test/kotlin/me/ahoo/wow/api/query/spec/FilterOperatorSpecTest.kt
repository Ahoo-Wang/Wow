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

package me.ahoo.wow.api.query.spec

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory

/**
 * The operator table of the step 2 plan (documentation/designs/2026-09-25-query-step2-operator-spec-plan.md), row by
 * row. A change here is a change to what every operator requires.
 */
class FilterOperatorSpecTest {
    private data class Row(
        val target: OperatorTarget,
        val valueRule: ValueRule,
        val capability: QueryCapability?,
        val cost: OperatorCost = OperatorCost.NORMAL,
        val systemField: SystemField? = null,
    )

    @Test
    fun `every operator has the tabled target, value rule, capability and cost`() {
        FilterOperator.entries.forEach { operator ->
            val spec = operator.spec
            val row = TABLE.getValue(operator)
            spec.operator.assert().isEqualTo(operator)
            spec.target.assert().describedAs("$operator target").isEqualTo(row.target)
            spec.valueRule.assert().describedAs("$operator value rule").isEqualTo(row.valueRule)
            spec.systemField.assert().describedAs("$operator system field").isEqualTo(row.systemField)
        }
        TABLE.keys.assert().isEqualTo(FilterOperator.entries.toSet())
    }

    @Test
    fun `capability and cost follow the table for nodes whose requirement does not depend on the value`() {
        FIXED.forEach { (operator, capability, cost) ->
            val node = sample(operator)
            operator.spec.requiredCapability(node).assert().describedAs("$operator capability").isEqualTo(capability)
            operator.spec.cost(node).assert().describedAs("$operator cost").isEqualTo(cost)
        }
    }

    @Test
    fun `equality with null asks for presence`() {
        val field = QueryField("state.name")
        val nullValue = JsonNodeFactory.instance.nullNode()
        val value = JsonNodeFactory.instance.stringNode("a")
        EqualFilter(field, nullValue).spec.requiredCapability(EqualFilter(field, nullValue))
            .assert().isEqualTo(QueryCapability.PRESENCE)
        EqualFilter(field, value).spec.requiredCapability(EqualFilter(field, value))
            .assert().isEqualTo(QueryCapability.EXACT_MATCH)
        NotEqualFilter(field, nullValue).let { it.spec.requiredCapability(it) }
            .assert().isEqualTo(QueryCapability.PRESENCE)
        NotEqualFilter(field, value).let { it.spec.cost(it) }.assert().isEqualTo(OperatorCost.EXPENSIVE)
    }

    @Test
    fun `starts with is expensive for an empty prefix or case-insensitive matching`() {
        val field = QueryField("state.name")
        listOf(
            StartsWithFilter(field, "a") to OperatorCost.NORMAL,
            StartsWithFilter(field, "") to OperatorCost.EXPENSIVE,
            StartsWithFilter(field, "a", StringComparison.CASE_INSENSITIVE) to OperatorCost.EXPENSIVE,
        ).forEach { (node, cost) -> node.spec.cost(node).assert().describedAs(node.toString()).isEqualTo(cost) }
    }

    @Test
    fun `search requires the capability of its mode`() {
        SearchFilter("a").let { it.spec.requiredCapability(it) }.assert().isEqualTo(QueryCapability.FULL_TEXT_TERMS)
        SearchFilter("a", mode = SearchMode.PHRASE).let { it.spec.requiredCapability(it) }
            .assert().isEqualTo(QueryCapability.FULL_TEXT_PHRASE)
    }

    @Test
    fun `a spec rejects a node of another operator`() {
        assertThrows<IllegalArgumentException> {
            FilterOperator.EQ.spec.requiredCapability(ExistsFilter(QueryField("state.name")))
        }
    }

    private companion object {
        val F = QueryField("state.name")
        val V = JsonNodeFactory.instance.stringNode("a")

        /** One valid node per operator; exhaustive, so a new operator must be added here and in [TABLE]. */
        @Suppress("CyclomaticComplexMethod")
        fun sample(operator: FilterOperator): FilterExpression = when (operator) {
            FilterOperator.MATCH_ALL -> MatchAllFilter
            FilterOperator.MATCH_NONE -> MatchNoneFilter
            FilterOperator.ID -> IdFilter("id")
            FilterOperator.IDS -> IdsFilter(listOf("id"))
            FilterOperator.AGGREGATE_ID -> AggregateIdFilter("id")
            FilterOperator.AGGREGATE_IDS -> AggregateIdsFilter(listOf("id"))
            FilterOperator.TENANT_ID -> TenantIdFilter("t")
            FilterOperator.OWNER_ID -> OwnerIdFilter("o")
            FilterOperator.SPACE_ID -> SpaceIdFilter("s")
            FilterOperator.AND -> AndFilter(listOf(MatchAllFilter))
            FilterOperator.OR -> OrFilter(listOf(MatchAllFilter))
            FilterOperator.NOR -> NorFilter(listOf(MatchAllFilter))
            FilterOperator.EQ -> EqualFilter(F, V)
            FilterOperator.NE -> NotEqualFilter(F, V)
            FilterOperator.GT -> GreaterThanFilter(F, V)
            FilterOperator.GTE -> GreaterThanOrEqualFilter(F, V)
            FilterOperator.LT -> LessThanFilter(F, V)
            FilterOperator.LTE -> LessThanOrEqualFilter(F, V)
            FilterOperator.CONTAINS -> ContainsFilter(F, "a")
            FilterOperator.STARTS_WITH -> StartsWithFilter(F, "a")
            FilterOperator.ENDS_WITH -> EndsWithFilter(F, "a")
            FilterOperator.IN -> InFilter(F, listOf(V))
            FilterOperator.NOT_IN -> NotInFilter(F, listOf(V))
            FilterOperator.BETWEEN -> BetweenFilter(F, V, V)
            FilterOperator.CONTAINS_ALL -> ContainsAllFilter(F, listOf(V))
            FilterOperator.IS_EMPTY -> IsEmptyFilter(F)
            FilterOperator.IS_EMPTY_STRING -> IsEmptyStringFilter(F)
            FilterOperator.IS_NOT_EMPTY_STRING -> IsNotEmptyStringFilter(F)
            FilterOperator.IS_NULL -> IsNullFilter(F)
            FilterOperator.IS_NOT_NULL -> IsNotNullFilter(F)
            FilterOperator.EXISTS -> ExistsFilter(F)
            FilterOperator.NOT_EXISTS -> NotExistsFilter(F)
            FilterOperator.DELETION -> DeletionFilter(DeletionState.ACTIVE)
            FilterOperator.ELEMENT_MATCH -> ElementMatchFilter(QueryField("state.items"), MatchAllFilter)
            FilterOperator.SEARCH -> SearchFilter("a")
            FilterOperator.TODAY -> TodayFilter(F)
            FilterOperator.BEFORE_TODAY -> BeforeTodayFilter(F, "10:00")
            FilterOperator.TOMORROW -> TomorrowFilter(F)
            FilterOperator.THIS_WEEK -> ThisWeekFilter(F)
            FilterOperator.NEXT_WEEK -> NextWeekFilter(F)
            FilterOperator.LAST_WEEK -> LastWeekFilter(F)
            FilterOperator.THIS_MONTH -> ThisMonthFilter(F)
            FilterOperator.LAST_MONTH -> LastMonthFilter(F)
            FilterOperator.RECENT_DAYS -> RecentDaysFilter(F, 1)
            FilterOperator.EARLIER_DAYS -> EarlierDaysFilter(F, 1)
            FilterOperator.YESTERDAY -> YesterdayFilter(F)
            FilterOperator.NEXT_MONTH -> NextMonthFilter(F)
            FilterOperator.LAST_YEAR -> LastYearFilter(F)
            FilterOperator.THIS_YEAR -> ThisYearFilter(F)
            FilterOperator.NEXT_YEAR -> NextYearFilter(F)
            FilterOperator.BEFORE_NOW -> BeforeNowFilter(F)
            FilterOperator.AFTER_NOW -> AfterNowFilter(F, "-PT30M")
        }

        val FIELD = OperatorTarget.FIELD
        val EXPENSIVE = OperatorCost.EXPENSIVE

        fun system(field: SystemField) =
            Row(OperatorTarget.SYSTEM_FIELD, ValueRule.NONE, QueryCapability.EXACT_MATCH, systemField = field)

        val RELATIVE_TIME = listOf(
            FilterOperator.TODAY,
            FilterOperator.BEFORE_TODAY,
            FilterOperator.TOMORROW,
            FilterOperator.THIS_WEEK,
            FilterOperator.NEXT_WEEK,
            FilterOperator.LAST_WEEK,
            FilterOperator.THIS_MONTH,
            FilterOperator.LAST_MONTH,
            FilterOperator.RECENT_DAYS,
            FilterOperator.EARLIER_DAYS,
            FilterOperator.YESTERDAY,
            FilterOperator.NEXT_MONTH,
            FilterOperator.LAST_YEAR,
            FilterOperator.THIS_YEAR,
            FilterOperator.NEXT_YEAR,
            FilterOperator.BEFORE_NOW,
            FilterOperator.AFTER_NOW,
        )

        val TABLE: Map<FilterOperator, Row> = mapOf(
            FilterOperator.MATCH_ALL to Row(OperatorTarget.NONE, ValueRule.NONE, null),
            FilterOperator.MATCH_NONE to Row(OperatorTarget.NONE, ValueRule.NONE, null),
            FilterOperator.ID to system(SystemField.IDENTITY),
            FilterOperator.IDS to system(SystemField.IDENTITY),
            FilterOperator.AGGREGATE_ID to system(SystemField.AGGREGATE_ID),
            FilterOperator.AGGREGATE_IDS to system(SystemField.AGGREGATE_ID),
            FilterOperator.TENANT_ID to system(SystemField.TENANT_ID),
            FilterOperator.OWNER_ID to system(SystemField.OWNER_ID),
            FilterOperator.SPACE_ID to system(SystemField.SPACE_ID),
            FilterOperator.DELETION to system(SystemField.DELETED),
            FilterOperator.AND to Row(OperatorTarget.LOGICAL, ValueRule.NONE, null),
            FilterOperator.OR to Row(OperatorTarget.LOGICAL, ValueRule.NONE, null),
            FilterOperator.NOR to Row(OperatorTarget.LOGICAL, ValueRule.NONE, null, EXPENSIVE),
            FilterOperator.EQ to Row(FIELD, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH),
            FilterOperator.NE to Row(FIELD, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH, EXPENSIVE),
            FilterOperator.IN to Row(FIELD, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH),
            FilterOperator.NOT_IN to Row(FIELD, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH, EXPENSIVE),
            FilterOperator.CONTAINS_ALL to Row(FIELD, ValueRule.COLLECTION_DOMAIN, QueryCapability.EXACT_MATCH),
            FilterOperator.CONTAINS to Row(FIELD, ValueRule.NONE, QueryCapability.LITERAL_MATCH, EXPENSIVE),
            FilterOperator.STARTS_WITH to Row(FIELD, ValueRule.NONE, QueryCapability.LITERAL_MATCH),
            FilterOperator.ENDS_WITH to Row(FIELD, ValueRule.NONE, QueryCapability.LITERAL_MATCH, EXPENSIVE),
            FilterOperator.GT to Row(FIELD, ValueRule.DOMAIN, QueryCapability.RANGE),
            FilterOperator.GTE to Row(FIELD, ValueRule.DOMAIN, QueryCapability.RANGE),
            FilterOperator.LT to Row(FIELD, ValueRule.DOMAIN, QueryCapability.RANGE),
            FilterOperator.LTE to Row(FIELD, ValueRule.DOMAIN, QueryCapability.RANGE),
            FilterOperator.BETWEEN to Row(FIELD, ValueRule.DOMAIN, QueryCapability.RANGE),
            FilterOperator.IS_EMPTY to Row(FIELD, ValueRule.COLLECTION, QueryCapability.PRESENCE, EXPENSIVE),
            FilterOperator.IS_EMPTY_STRING to Row(FIELD, ValueRule.SINGLE_STRING, QueryCapability.EXACT_MATCH),
            FilterOperator.IS_NOT_EMPTY_STRING to
                Row(FIELD, ValueRule.SINGLE_STRING, QueryCapability.EXACT_MATCH, EXPENSIVE),
            FilterOperator.IS_NULL to Row(FIELD, ValueRule.NONE, QueryCapability.PRESENCE, EXPENSIVE),
            FilterOperator.IS_NOT_NULL to Row(FIELD, ValueRule.NONE, QueryCapability.PRESENCE, EXPENSIVE),
            FilterOperator.EXISTS to Row(FIELD, ValueRule.NONE, QueryCapability.PRESENCE),
            FilterOperator.NOT_EXISTS to Row(FIELD, ValueRule.NONE, QueryCapability.PRESENCE, EXPENSIVE),
            FilterOperator.SEARCH to Row(OperatorTarget.MODEL_OR_FIELDS, ValueRule.NONE, QueryCapability.FULL_TEXT_TERMS),
            FilterOperator.ELEMENT_MATCH to Row(FIELD, ValueRule.ELEMENT_SCOPE, QueryCapability.ELEMENT_SCOPE),
        ) + RELATIVE_TIME.associateWith { Row(FIELD, ValueRule.TEMPORAL, QueryCapability.RANGE) }

        /** Operators whose capability and cost do not vary with the node, checked against a sample node. */
        val FIXED: List<Triple<FilterOperator, QueryCapability?, OperatorCost>> =
            TABLE.filterKeys { it !in setOf(FilterOperator.EQ, FilterOperator.NE, FilterOperator.SEARCH) }
                .map { (operator, row) -> Triple(operator, row.capability, row.cost) }
    }
}
