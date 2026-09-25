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

import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.schema.QueryCapability

/** What a filter operator applies to. */
enum class OperatorTarget {
    /** Matches everything or nothing; names no field. */
    NONE,

    /** Combines operand filters. */
    LOGICAL,

    /** A system field the query model defines, named by [FilterOperatorSpec.systemField]. */
    SYSTEM_FIELD,

    /** A field the request names. */
    FIELD,

    /** The model's full-text search when no field is named, otherwise the named fields. */
    MODEL_OR_FIELDS,
}

/** System fields a filter operator can target without naming them. */
enum class SystemField {
    IDENTITY,
    AGGREGATE_ID,
    TENANT_ID,
    OWNER_ID,
    SPACE_ID,
    DELETED,
}

/** How a filter's values are checked against the field's declared value domain. */
enum class ValueRule {
    /** The operator carries no values to check. */
    NONE,

    /** Every value falls within the field's declared domain. */
    DOMAIN,

    /** The field is a known collection and every value falls within its element domain. */
    COLLECTION_DOMAIN,

    /** The field is a known collection. */
    COLLECTION,

    /** The field is a single-valued string. */
    SINGLE_STRING,

    /** The field has temporal semantics the relative time window can be computed against. */
    TEMPORAL,

    /** The nested predicate is checked inside the field's element scope. */
    ELEMENT_SCOPE,
}

enum class OperatorCost {
    NORMAL,

    /** Typically cannot use an index; entry gates may refuse it. */
    EXPENSIVE,
}

/**
 * The single source of what a filter operator needs: its target, the storage capability it requires, how its values
 * are checked and what it costs. Validation, entry gates and backends read this table instead of restating it.
 */
class FilterOperatorSpec private constructor(
    val operator: FilterOperator,
    val target: OperatorTarget,
    val valueRule: ValueRule,
    val systemField: SystemField? = null,
    private val capability: (FilterExpression) -> QueryCapability? = { null },
    private val cost: (FilterExpression) -> OperatorCost = { OperatorCost.NORMAL },
) {
    /** The capability the field must grant for [node]; `null` when the operator names no field capability. */
    fun requiredCapability(node: FilterExpression): QueryCapability? = capability(node.requireOperator())

    fun cost(node: FilterExpression): OperatorCost = cost.invoke(node.requireOperator())

    private fun FilterExpression.requireOperator(): FilterExpression = also {
        val expected = this@FilterOperatorSpec.operator
        require(it.operator == expected) { "Filter [${it.operator}] does not match spec [$expected]." }
    }

    override fun toString(): String = "FilterOperatorSpec($operator)"

    companion object {
        fun of(operator: FilterOperator): FilterOperatorSpec = SPECS[operator.ordinal]

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        private fun specOf(operator: FilterOperator): FilterOperatorSpec = when (operator) {
            FilterOperator.MATCH_ALL,
            FilterOperator.MATCH_NONE,
            -> FilterOperatorSpec(operator, OperatorTarget.NONE, ValueRule.NONE)

            FilterOperator.ID, FilterOperator.IDS -> system(operator, SystemField.IDENTITY)
            FilterOperator.AGGREGATE_ID, FilterOperator.AGGREGATE_IDS -> system(operator, SystemField.AGGREGATE_ID)
            FilterOperator.TENANT_ID -> system(operator, SystemField.TENANT_ID)
            FilterOperator.OWNER_ID -> system(operator, SystemField.OWNER_ID)
            FilterOperator.SPACE_ID -> system(operator, SystemField.SPACE_ID)
            FilterOperator.DELETION -> system(operator, SystemField.DELETED)

            FilterOperator.AND, FilterOperator.OR -> FilterOperatorSpec(
                operator,
                OperatorTarget.LOGICAL,
                ValueRule.NONE
            )
            FilterOperator.NOR -> FilterOperatorSpec(operator, OperatorTarget.LOGICAL, ValueRule.NONE, cost = EXPENSIVE)

            FilterOperator.EQ -> field(operator, ValueRule.DOMAIN, capability = ::equalityCapability)
            FilterOperator.NE -> field(operator, ValueRule.DOMAIN, capability = ::equalityCapability, cost = EXPENSIVE)
            FilterOperator.IN -> field(operator, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH)
            FilterOperator.NOT_IN -> field(operator, ValueRule.DOMAIN, QueryCapability.EXACT_MATCH, cost = EXPENSIVE)
            FilterOperator.CONTAINS_ALL -> field(operator, ValueRule.COLLECTION_DOMAIN, QueryCapability.EXACT_MATCH)

            FilterOperator.CONTAINS,
            FilterOperator.ENDS_WITH,
            -> field(operator, ValueRule.NONE, QueryCapability.LITERAL_MATCH, cost = EXPENSIVE)

            FilterOperator.STARTS_WITH -> field(
                operator,
                ValueRule.NONE,
                QueryCapability.LITERAL_MATCH,
                cost = ::startsWithCost,
            )

            FilterOperator.GT,
            FilterOperator.GTE,
            FilterOperator.LT,
            FilterOperator.LTE,
            FilterOperator.BETWEEN,
            -> field(operator, ValueRule.DOMAIN, QueryCapability.RANGE)

            FilterOperator.IS_EMPTY -> field(operator, ValueRule.COLLECTION, QueryCapability.PRESENCE, cost = EXPENSIVE)
            FilterOperator.IS_EMPTY_STRING -> field(operator, ValueRule.SINGLE_STRING, QueryCapability.EXACT_MATCH)
            FilterOperator.IS_NOT_EMPTY_STRING -> field(
                operator,
                ValueRule.SINGLE_STRING,
                QueryCapability.EXACT_MATCH,
                cost = EXPENSIVE,
            )

            FilterOperator.EXISTS -> field(operator, ValueRule.NONE, QueryCapability.PRESENCE)

            FilterOperator.IS_NULL,
            FilterOperator.IS_NOT_NULL,
            FilterOperator.NOT_EXISTS,
            -> field(operator, ValueRule.NONE, QueryCapability.PRESENCE, cost = EXPENSIVE)

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
            -> field(operator, ValueRule.TEMPORAL, QueryCapability.RANGE)

            FilterOperator.SEARCH -> FilterOperatorSpec(
                operator,
                OperatorTarget.MODEL_OR_FIELDS,
                ValueRule.NONE,
                capability = ::searchCapability,
            )

            FilterOperator.ELEMENT_MATCH -> field(operator, ValueRule.ELEMENT_SCOPE, QueryCapability.ELEMENT_SCOPE)
        }

        private val EXPENSIVE: (FilterExpression) -> OperatorCost = { OperatorCost.EXPENSIVE }

        private fun system(operator: FilterOperator, field: SystemField) = FilterOperatorSpec(
            operator,
            OperatorTarget.SYSTEM_FIELD,
            ValueRule.NONE,
            systemField = field,
            capability = { QueryCapability.EXACT_MATCH },
        )

        private fun field(
            operator: FilterOperator,
            valueRule: ValueRule,
            capability: QueryCapability,
            cost: (FilterExpression) -> OperatorCost = { OperatorCost.NORMAL },
        ) = field(operator, valueRule, { capability }, cost)

        private fun field(
            operator: FilterOperator,
            valueRule: ValueRule,
            capability: (FilterExpression) -> QueryCapability,
            cost: (FilterExpression) -> OperatorCost = { OperatorCost.NORMAL },
        ) = FilterOperatorSpec(operator, OperatorTarget.FIELD, valueRule, capability = capability, cost = cost)

        /** Equality with `null` asks whether the field is present, not for an exact match. */
        private fun equalityCapability(node: FilterExpression): QueryCapability {
            val value = when (node) {
                is EqualFilter -> node.value
                is NotEqualFilter -> node.value
                else -> error("Unexpected equality filter [${node.operator}].")
            }
            return if (value.isNull) QueryCapability.PRESENCE else QueryCapability.EXACT_MATCH
        }

        private fun startsWithCost(node: FilterExpression): OperatorCost {
            node as StartsWithFilter
            return if (node.value.isEmpty() || node.stringComparison == StringComparison.CASE_INSENSITIVE) {
                OperatorCost.EXPENSIVE
            } else {
                OperatorCost.NORMAL
            }
        }

        private fun searchCapability(node: FilterExpression): QueryCapability {
            node as SearchFilter
            return if (node.mode == SearchMode.TERMS) QueryCapability.FULL_TEXT_TERMS else QueryCapability.FULL_TEXT_PHRASE
        }

        // Declared last: building the table reads the helpers above.
        private val SPECS: List<FilterOperatorSpec> = FilterOperator.entries.map(::specOf)
    }
}

val FilterOperator.spec: FilterOperatorSpec
    get() = FilterOperatorSpec.of(this)

val FilterExpression.spec: FilterOperatorSpec
    get() = operator.spec
