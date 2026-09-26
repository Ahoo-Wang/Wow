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

import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.schema.QueryCapability
import tools.jackson.databind.node.JsonNodeFactory

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

    /**
     * A computed aggregation expression over the fields it reads; each needs the aggregation capability its
     * expression node reads it with (`AGGREGATE_NUMERIC` for a field, `AGGREGATE_TEMPORAL` for a date difference).
     */
    EXPRESSION,
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

/** How many fields a filter operator names. */
enum class FieldArity {
    /** None: the operator matches everything or nothing, combines operands, or targets a system field. */
    NONE,

    /** Exactly one field. */
    ONE,

    /** A set of fields: the fields a search names (none for model search), or the fields an expression reads. */
    MANY,
}

/** How many literal values a filter operator carries. */
enum class ValueArity {
    /** None: presence, empty-string, relative-time, logical and element-scope operators. */
    NONE,

    /** One value. */
    ONE,

    /** Two bounds. */
    TWO,

    /** A list of values, as long as the node makes it. */
    LIST,
}

/** The fields and values a filter operator names. */
data class Arity(val fields: FieldArity, val values: ValueArity)

/** How normalization lowers an operator to the operators backends implement. */
sealed interface Lowering {
    /**
     * Rewrites a node on its own field into operators every backend implements, independently of the schema or the
     * clock; a node that needs no rewrite is returned as it is.
     */
    fun interface Rewrite : Lowering {
        fun lower(node: FilterExpression): FilterExpression
    }

    /** A relative time window, lowered to a range at admission's moment, encoded as the field stores time. */
    data object RelativeTime : Lowering
}

/**
 * The single source of what a filter operator needs: its target and arity, the storage capability it requires, how
 * its values are checked, what it costs and how normalization lowers it. Validation, entry gates, normalization and
 * backends read this table instead of restating it.
 */
@Suppress("LongParameterList")
class FilterOperatorSpec private constructor(
    val operator: FilterOperator,
    val target: OperatorTarget,
    val valueRule: ValueRule,
    val arity: Arity,
    val systemField: SystemField? = null,
    /** The capability the operator requires in general; [requiredCapability] refines it for one node. */
    val baseCapability: QueryCapability? = null,
    private val nodeCapability: ((FilterExpression) -> QueryCapability)? = null,
    /** The cost of the operator in general; [cost] refines it for one node when the value matters. */
    val baseCost: OperatorCost = OperatorCost.NORMAL,
    private val nodeCost: ((FilterExpression) -> OperatorCost)? = null,
    /** How normalization lowers the operator, or `null` when backends implement it as it is. */
    val lowering: Lowering? = null,
    private val listSize: ((FilterExpression) -> Int)? = null,
    private val nodeMatchesAll: ((FilterExpression) -> Boolean)? = null,
) {
    /** The capability the field must grant for [node]; `null` when the operator names no field capability. */
    fun requiredCapability(node: FilterExpression): QueryCapability? {
        val checked = node.requireOperator()
        return nodeCapability?.invoke(checked) ?: baseCapability
    }

    fun cost(node: FilterExpression): OperatorCost = nodeCost?.invoke(node.requireOperator()) ?: baseCost

    /** The number of literal values [node] carries, as [Arity.values] counts them. */
    fun valueCount(node: FilterExpression): Int {
        val checked = node.requireOperator()
        return when (arity.values) {
            ValueArity.NONE -> 0
            ValueArity.ONE -> 1
            ValueArity.TWO -> 2
            ValueArity.LIST -> checkNotNull(listSize).invoke(checked)
        }
    }

    /**
     * Whether [node] provably matches every record: `MATCH_ALL`, `DELETION` of every state, an `AND` whose operands all
     * do, or an `OR` with an operand that does. Any other node is treated as restrictive.
     */
    fun matchesAll(node: FilterExpression): Boolean = nodeMatchesAll?.invoke(node.requireOperator()) ?: false

    /** [node] lowered by its [Rewrite][Lowering.Rewrite]; any other node is returned as it is. */
    fun lower(node: FilterExpression): FilterExpression {
        val checked = node.requireOperator()
        return (lowering as? Lowering.Rewrite)?.lower(checked) ?: checked
    }

    private fun FilterExpression.requireOperator(): FilterExpression = also {
        val expected = this@FilterOperatorSpec.operator
        require(it.operator == expected) { "Filter [${it.operator}] does not match spec [$expected]." }
    }

    override fun toString(): String = "FilterOperatorSpec($operator)"

    companion object {
        fun of(operator: FilterOperator): FilterOperatorSpec = SPECS[operator.ordinal]

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        private fun specOf(operator: FilterOperator): FilterOperatorSpec = when (operator) {
            FilterOperator.MATCH_ALL -> FilterOperatorSpec(
                operator,
                OperatorTarget.NONE,
                ValueRule.NONE,
                NO_ARITY,
                nodeMatchesAll = { true },
            )
            FilterOperator.MATCH_NONE -> FilterOperatorSpec(operator, OperatorTarget.NONE, ValueRule.NONE, NO_ARITY)

            FilterOperator.ID -> system(operator, SystemField.IDENTITY)
            FilterOperator.IDS -> system(operator, SystemField.IDENTITY) { (it as IdsFilter).values.size }
            FilterOperator.AGGREGATE_ID -> system(operator, SystemField.AGGREGATE_ID)
            FilterOperator.AGGREGATE_IDS -> system(operator, SystemField.AGGREGATE_ID) {
                (it as AggregateIdsFilter).values.size
            }
            FilterOperator.TENANT_ID -> system(operator, SystemField.TENANT_ID)
            FilterOperator.OWNER_ID -> system(operator, SystemField.OWNER_ID)
            FilterOperator.SPACE_ID -> system(operator, SystemField.SPACE_ID)
            FilterOperator.DELETION -> FilterOperatorSpec(
                operator,
                OperatorTarget.SYSTEM_FIELD,
                ValueRule.NONE,
                Arity(FieldArity.NONE, ValueArity.ONE),
                systemField = SystemField.DELETED,
                baseCapability = QueryCapability.EXACT_MATCH,
                nodeMatchesAll = { (it as DeletionFilter).deletionState == DeletionState.ALL },
            )

            FilterOperator.AND -> FilterOperatorSpec(
                operator,
                OperatorTarget.LOGICAL,
                ValueRule.NONE,
                NO_ARITY,
                nodeMatchesAll = { node -> (node as AndFilter).operands.all { it.spec.matchesAll(it) } },
            )
            FilterOperator.OR -> FilterOperatorSpec(
                operator,
                OperatorTarget.LOGICAL,
                ValueRule.NONE,
                NO_ARITY,
                nodeMatchesAll = { node -> (node as OrFilter).operands.any { it.spec.matchesAll(it) } },
            )
            FilterOperator.NOR -> FilterOperatorSpec(
                operator,
                OperatorTarget.LOGICAL,
                ValueRule.NONE,
                NO_ARITY,
                baseCost = OperatorCost.EXPENSIVE
            )

            FilterOperator.EQ -> field(
                operator,
                ValueRule.DOMAIN,
                QueryCapability.EXACT_MATCH,
                ValueArity.ONE,
                nodeCapability = ::equalityCapability,
                lowering = Lowering.Rewrite(::lowerNullEquality),
            )
            FilterOperator.NE -> field(
                operator,
                ValueRule.DOMAIN,
                QueryCapability.EXACT_MATCH,
                ValueArity.ONE,
                baseCost = OperatorCost.EXPENSIVE,
                nodeCapability = ::equalityCapability,
                lowering = Lowering.Rewrite(::lowerNullEquality),
            )
            FilterOperator.IN -> field(
                operator,
                ValueRule.DOMAIN,
                QueryCapability.EXACT_MATCH,
                ValueArity.LIST,
                listSize = { (it as InFilter).values.size },
            )
            FilterOperator.NOT_IN -> field(
                operator,
                ValueRule.DOMAIN,
                QueryCapability.EXACT_MATCH,
                ValueArity.LIST,
                baseCost = OperatorCost.EXPENSIVE,
                listSize = { (it as NotInFilter).values.size },
            )
            FilterOperator.CONTAINS_ALL -> field(
                operator,
                ValueRule.COLLECTION_DOMAIN,
                QueryCapability.EXACT_MATCH,
                ValueArity.LIST,
                listSize = { (it as ContainsAllFilter).values.size },
            )

            FilterOperator.CONTAINS,
            FilterOperator.ENDS_WITH,
            -> field(
                operator,
                ValueRule.NONE,
                QueryCapability.LITERAL_MATCH,
                ValueArity.ONE,
                baseCost = OperatorCost.EXPENSIVE,
            )

            FilterOperator.STARTS_WITH -> field(
                operator,
                ValueRule.NONE,
                QueryCapability.LITERAL_MATCH,
                ValueArity.ONE,
                nodeCost = ::startsWithCost,
            )

            FilterOperator.GT,
            FilterOperator.GTE,
            FilterOperator.LT,
            FilterOperator.LTE,
            -> field(operator, ValueRule.DOMAIN, QueryCapability.RANGE, ValueArity.ONE)
            FilterOperator.BETWEEN -> field(operator, ValueRule.DOMAIN, QueryCapability.RANGE, ValueArity.TWO)

            FilterOperator.IS_EMPTY -> field(
                operator,
                ValueRule.COLLECTION,
                QueryCapability.PRESENCE,
                ValueArity.NONE,
                baseCost = OperatorCost.EXPENSIVE
            )
            FilterOperator.IS_EMPTY_STRING -> field(
                operator,
                ValueRule.SINGLE_STRING,
                QueryCapability.EXACT_MATCH,
                ValueArity.NONE,
                lowering = Lowering.Rewrite { node ->
                    EqualFilter((node as IsEmptyStringFilter).field, EMPTY_STRING)
                },
            )
            FilterOperator.IS_NOT_EMPTY_STRING -> field(
                operator,
                ValueRule.SINGLE_STRING,
                QueryCapability.EXACT_MATCH,
                ValueArity.NONE,
                baseCost = OperatorCost.EXPENSIVE,
                lowering = Lowering.Rewrite { node ->
                    val field = (node as IsNotEmptyStringFilter).field
                    AndFilter(listOf(IsNotNullFilter(field), NotEqualFilter(field, EMPTY_STRING)))
                },
            )

            FilterOperator.EXISTS -> field(operator, ValueRule.NONE, QueryCapability.PRESENCE, ValueArity.NONE)

            FilterOperator.IS_NULL,
            FilterOperator.IS_NOT_NULL,
            FilterOperator.NOT_EXISTS,
            -> field(
                operator,
                ValueRule.NONE,
                QueryCapability.PRESENCE,
                ValueArity.NONE,
                baseCost = OperatorCost.EXPENSIVE,
            )

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
            -> field(
                operator,
                ValueRule.TEMPORAL,
                QueryCapability.RANGE,
                ValueArity.NONE,
                lowering = Lowering.RelativeTime,
            )

            FilterOperator.SEARCH -> FilterOperatorSpec(
                operator,
                OperatorTarget.MODEL_OR_FIELDS,
                ValueRule.NONE,
                Arity(FieldArity.MANY, ValueArity.ONE),
                baseCapability = QueryCapability.FULL_TEXT_TERMS,
                nodeCapability = ::searchCapability,
            )

            FilterOperator.ELEMENT_MATCH -> field(
                operator,
                ValueRule.ELEMENT_SCOPE,
                QueryCapability.ELEMENT_SCOPE,
                ValueArity.NONE,
            )

            FilterOperator.EXPRESSION -> FilterOperatorSpec(
                operator,
                OperatorTarget.EXPRESSION,
                ValueRule.NONE,
                Arity(FieldArity.MANY, ValueArity.ONE),
                baseCost = OperatorCost.EXPENSIVE,
            )
        }

        private fun system(
            operator: FilterOperator,
            field: SystemField,
            listSize: ((FilterExpression) -> Int)? = null,
        ) = FilterOperatorSpec(
            operator,
            OperatorTarget.SYSTEM_FIELD,
            ValueRule.NONE,
            Arity(FieldArity.NONE, if (listSize == null) ValueArity.ONE else ValueArity.LIST),
            systemField = field,
            baseCapability = QueryCapability.EXACT_MATCH,
            listSize = listSize,
        )

        @Suppress("LongParameterList")
        private fun field(
            operator: FilterOperator,
            valueRule: ValueRule,
            capability: QueryCapability,
            values: ValueArity,
            baseCost: OperatorCost = OperatorCost.NORMAL,
            nodeCost: ((FilterExpression) -> OperatorCost)? = null,
            nodeCapability: ((FilterExpression) -> QueryCapability)? = null,
            lowering: Lowering? = null,
            listSize: ((FilterExpression) -> Int)? = null,
        ) = FilterOperatorSpec(
            operator,
            OperatorTarget.FIELD,
            valueRule,
            Arity(FieldArity.ONE, values),
            baseCapability = capability,
            nodeCapability = nodeCapability,
            baseCost = baseCost,
            nodeCost = nodeCost,
            lowering = lowering,
            listSize = listSize,
        )

        /** `EQ` / `NE` of `null` ask whether the field is absent or present: `IS_NULL` / `IS_NOT_NULL`. */
        private fun lowerNullEquality(node: FilterExpression): FilterExpression = when (node) {
            is EqualFilter -> if (node.value.isNull) IsNullFilter(node.field) else node
            is NotEqualFilter -> if (node.value.isNull) IsNotNullFilter(node.field) else node
            else -> error("Unexpected equality filter [${node.operator}].")
        }

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

        private val NO_ARITY = Arity(FieldArity.NONE, ValueArity.NONE)
        private val EMPTY_STRING = JsonNodeFactory.instance.stringNode("")

        // Declared last: building the table reads the helpers and constants above.
        private val SPECS: List<FilterOperatorSpec> = FilterOperator.entries.map(::specOf)
    }
}

val FilterOperator.spec: FilterOperatorSpec
    get() = FilterOperatorSpec.of(this)

val FilterExpression.spec: FilterOperatorSpec
    get() = operator.spec
