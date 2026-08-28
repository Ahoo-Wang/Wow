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

import com.fasterxml.jackson.annotation.JsonTypeName
import tools.jackson.databind.JsonNode

internal fun JsonNode.requireFilterLiteral() {
    require(isNull || isString || isNumber || isBoolean) { "Filter value must be a JSON scalar." }
}

private fun JsonNode.requireEqualityFilterValue() {
    if (isArray) {
        forEach { require(it.isPojo || it.isNull || it.isString || it.isNumber || it.isBoolean) }
    } else {
        require(isPojo || isNull || isString || isNumber || isBoolean) {
            "EQ/NE value must be a JSON scalar, scalar array, or runtime POJO."
        }
    }
}

private fun JsonNode.requireComparableFilterLiteral() {
    if (!isPojo) requireFilterLiteral()
    require(!isNull) { "Comparison filter value cannot be null." }
}

private fun List<JsonNode>.requireFilterLiterals(operator: FilterOperator) {
    require(isNotEmpty()) { "$operator values cannot be empty." }
    forEach {
        if (!it.isPojo) it.requireFilterLiteral()
        require(!it.isNull) { "$operator values cannot contain null." }
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.EQ)
data class EqualFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.EQ

    init {
        value.requireEqualityFilterValue()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NE)
data class NotEqualFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NE

    init {
        value.requireEqualityFilterValue()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.GT)
data class GreaterThanFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.GT

    init {
        value.requireComparableFilterLiteral()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.GTE)
data class GreaterThanOrEqualFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.GTE

    init {
        value.requireComparableFilterLiteral()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.LT)
data class LessThanFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.LT

    init {
        value.requireComparableFilterLiteral()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.LTE)
data class LessThanOrEqualFilter(val field: LogicalField, val value: JsonNode) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.LTE

    init {
        value.requireComparableFilterLiteral()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.CONTAINS)
data class ContainsFilter(
    val field: LogicalField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.CONTAINS
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.STARTS_WITH)
data class StartsWithFilter(
    val field: LogicalField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.STARTS_WITH
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.ENDS_WITH)
data class EndsWithFilter(
    val field: LogicalField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.ENDS_WITH
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.IN)
data class InFilter(val field: LogicalField, val values: List<JsonNode>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.IN

    init {
        values.requireFilterLiterals(operator)
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NOT_IN)
data class NotInFilter(val field: LogicalField, val values: List<JsonNode>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NOT_IN

    init {
        values.requireFilterLiterals(operator)
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.BETWEEN)
data class BetweenFilter(
    val field: LogicalField,
    val lowerBound: JsonNode,
    val upperBound: JsonNode,
) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.BETWEEN

    init {
        lowerBound.requireComparableFilterLiteral()
        upperBound.requireComparableFilterLiteral()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.CONTAINS_ALL)
data class ContainsAllFilter(val field: LogicalField, val values: List<JsonNode>) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.CONTAINS_ALL

    init {
        values.requireFilterLiterals(operator)
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.IS_EMPTY)
data class IsEmptyFilter(val field: LogicalField) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.IS_NULL)
data class IsNullFilter(val field: LogicalField) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.IS_NULL
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.IS_NOT_NULL)
data class IsNotNullFilter(val field: LogicalField) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.IS_NOT_NULL
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.EXISTS)
data class ExistsFilter(val field: LogicalField) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.EXISTS
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NOT_EXISTS)
data class NotExistsFilter(val field: LogicalField) : FilterExpression {
    override val operator: FilterOperator = FilterOperator.NOT_EXISTS
}
