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

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.spec.spec
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties

/**
 * Guards the structure every consumer of a filter tree relies on: a node naming a field is a [FieldPredicate], a
 * system-field operator (as its spec states) never applies to one element, and [childFilters] is the tree's walker.
 */
class FilterStructureGuardTest {
    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `every filter node with a field should be a field predicate`() {
        val concrete = FilterExpression::class.concreteSubclasses()
        concrete.map { it.simpleName }.assert().hasSize(FilterOperator.entries.size)
        concrete.forEach { type ->
            val hasField = type.memberProperties.any { it.name == FIELD && it.returnType.classifier == QueryField::class }
            type.isSubclassOf(FieldPredicate::class).assert().`as`(type.simpleName).isEqualTo(hasField)
        }
    }

    @TestFactory
    fun `withField should replace only the field`(): List<DynamicTest> =
        FilterOperator.entries.map(::sampleOf).filterIsInstance<FieldPredicate>().map { predicate ->
            DynamicTest.dynamicTest(predicate.operator.name) {
                val moved = predicate.withField(OTHER_FIELD)
                (moved as FieldPredicate).field.assert().isEqualTo(OTHER_FIELD)
                moved.javaClass.assert().isEqualTo(predicate.javaClass)
                val expected = jsonMapper.valueToTree<ObjectNode>(predicate)
                    .put(FIELD, OTHER_FIELD.path)
                jsonMapper.valueToTree<JsonNode>(moved).assert().isEqualTo(expected)
                moved.withField(predicate.field).assert().isEqualTo(predicate)
            }
        }

    @TestFactory
    fun `every system field operator should be rejected inside an element`(): List<DynamicTest> =
        FilterOperator.entries.filter { it.spec.systemField != null }.map { operator ->
            DynamicTest.dynamicTest(operator.name) {
                val node = sampleOf(operator)
                listOf(node, AndFilter(listOf(MatchAllFilter, node)), NorFilter(listOf(node))).forEach { predicate ->
                    assertThrows<IllegalArgumentException> { ElementMatchFilter(ITEMS, predicate) }
                    assertThrows<IllegalArgumentException> { AggregationElement(ITEMS, predicate) }
                }
            }
        }

    @TestFactory
    fun `every other field and logical operator should be accepted inside an element`(): List<DynamicTest> =
        FilterOperator.entries
            .filter {
                it.spec.systemField == null && it != FilterOperator.SEARCH && it != FilterOperator.EXPRESSION
            }
            .map { operator ->
                DynamicTest.dynamicTest(operator.name) {
                    val node = sampleOf(operator)
                    ElementMatchFilter(ITEMS, node).predicate.assert().isEqualTo(node)
                    AggregationElement(ITEMS, node).filter.assert().isEqualTo(node)
                }
            }

    @Test
    fun `childFilters should expose logical operands and element predicates`() {
        val leaf = EqualFilter(SKU, VALUE)
        AndFilter(listOf(leaf, MatchAllFilter)).childFilters().assert().containsExactly(leaf, MatchAllFilter)
        OrFilter(listOf(leaf)).childFilters().assert().containsExactly(leaf)
        NorFilter(listOf(leaf)).childFilters().assert().containsExactly(leaf)
        ElementMatchFilter(ITEMS, leaf).childFilters().assert().containsExactly(leaf)
        FilterOperator.entries
            .filter { it !in setOf(FilterOperator.AND, FilterOperator.OR, FilterOperator.NOR) }
            .filter { it != FilterOperator.ELEMENT_MATCH }
            .forEach { sampleOf(it).childFilters().assert().`as`(it.name).isEmpty() }
    }

    private fun KClass<*>.concreteSubclasses(): List<KClass<*>> =
        if (isSealed) sealedSubclasses.flatMap { it.concreteSubclasses() }.distinct() else listOf(this)

    companion object {
        private const val FIELD = "field"
        private val SKU = QueryField("sku")
        private val ITEMS = QueryField("items")
        private val OTHER_FIELD = QueryField("other.path")
        private val VALUE = JsonNodeFactory.instance.stringNode("value")
        private val NUMBER = JsonNodeFactory.instance.numberNode(1)

        /** One valid node per operator; a new operator does not compile until it has one. */
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun sampleOf(operator: FilterOperator): FilterExpression = when (operator) {
            FilterOperator.MATCH_ALL -> MatchAllFilter
            FilterOperator.MATCH_NONE -> MatchNoneFilter
            FilterOperator.ID -> IdFilter("id")
            FilterOperator.IDS -> IdsFilter(listOf("id"))
            FilterOperator.AGGREGATE_ID -> AggregateIdFilter("id")
            FilterOperator.AGGREGATE_IDS -> AggregateIdsFilter(listOf("id"))
            FilterOperator.TENANT_ID -> TenantIdFilter("tenant")
            FilterOperator.OWNER_ID -> OwnerIdFilter("owner")
            FilterOperator.SPACE_ID -> SpaceIdFilter("space")
            FilterOperator.AND -> AndFilter(listOf(EqualFilter(SKU, VALUE)))
            FilterOperator.OR -> OrFilter(listOf(EqualFilter(SKU, VALUE)))
            FilterOperator.NOR -> NorFilter(listOf(EqualFilter(SKU, VALUE)))
            FilterOperator.EQ -> EqualFilter(SKU, VALUE)
            FilterOperator.NE -> NotEqualFilter(SKU, VALUE)
            FilterOperator.GT -> GreaterThanFilter(SKU, NUMBER)
            FilterOperator.GTE -> GreaterThanOrEqualFilter(SKU, NUMBER)
            FilterOperator.LT -> LessThanFilter(SKU, NUMBER)
            FilterOperator.LTE -> LessThanOrEqualFilter(SKU, NUMBER)
            FilterOperator.CONTAINS -> ContainsFilter(SKU, "v", StringComparison.CASE_INSENSITIVE)
            FilterOperator.STARTS_WITH -> StartsWithFilter(SKU, "v")
            FilterOperator.ENDS_WITH -> EndsWithFilter(SKU, "v")
            FilterOperator.IN -> InFilter(SKU, listOf(VALUE))
            FilterOperator.NOT_IN -> NotInFilter(SKU, listOf(VALUE))
            FilterOperator.BETWEEN -> BetweenFilter(SKU, NUMBER, NUMBER)
            FilterOperator.CONTAINS_ALL -> ContainsAllFilter(SKU, listOf(VALUE))
            FilterOperator.IS_EMPTY -> IsEmptyFilter(SKU)
            FilterOperator.IS_EMPTY_STRING -> IsEmptyStringFilter(SKU)
            FilterOperator.IS_NOT_EMPTY_STRING -> IsNotEmptyStringFilter(SKU)
            FilterOperator.IS_NULL -> IsNullFilter(SKU)
            FilterOperator.IS_NOT_NULL -> IsNotNullFilter(SKU)
            FilterOperator.EXISTS -> ExistsFilter(SKU)
            FilterOperator.NOT_EXISTS -> NotExistsFilter(SKU)
            FilterOperator.DELETION -> DeletionFilter(DeletionState.DELETED)
            FilterOperator.ELEMENT_MATCH -> ElementMatchFilter(ITEMS, EqualFilter(SKU, VALUE))
            FilterOperator.SEARCH -> SearchFilter("text", setOf(SKU))
            FilterOperator.TODAY -> TodayFilter(SKU, zoneId = "UTC")
            FilterOperator.BEFORE_TODAY -> BeforeTodayFilter(SKU, "18:00")
            FilterOperator.TOMORROW -> TomorrowFilter(SKU)
            FilterOperator.THIS_WEEK -> ThisWeekFilter(SKU)
            FilterOperator.NEXT_WEEK -> NextWeekFilter(SKU)
            FilterOperator.LAST_WEEK -> LastWeekFilter(SKU)
            FilterOperator.THIS_MONTH -> ThisMonthFilter(SKU)
            FilterOperator.LAST_MONTH -> LastMonthFilter(SKU)
            FilterOperator.RECENT_DAYS -> RecentDaysFilter(SKU, 3)
            FilterOperator.EARLIER_DAYS -> EarlierDaysFilter(SKU, 3)
            FilterOperator.YESTERDAY -> YesterdayFilter(SKU)
            FilterOperator.NEXT_MONTH -> NextMonthFilter(SKU)
            FilterOperator.LAST_YEAR -> LastYearFilter(SKU)
            FilterOperator.THIS_YEAR -> ThisYearFilter(SKU)
            FilterOperator.NEXT_YEAR -> NextYearFilter(SKU)
            FilterOperator.BEFORE_NOW -> BeforeNowFilter(SKU, "-PT30M")
            FilterOperator.AFTER_NOW -> AfterNowFilter(SKU)
            FilterOperator.EXPRESSION -> ExpressionFilter(
                AggregationExpression.Field(SKU),
                ComparisonOperator.GT,
                1.0,
            )
        }
    }
}
