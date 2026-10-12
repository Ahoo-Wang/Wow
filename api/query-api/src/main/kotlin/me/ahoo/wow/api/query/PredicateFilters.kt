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

private const val EQUALITY_VALUE_MESSAGE = "EQ/NE value must be a JSON scalar, scalar array, or runtime value."

private fun Any?.requireFilterLiteral() {
    require(isFilterScalar()) { "Filter value must be a JSON scalar." }
}

private fun Any?.requireEqualityFilterValue() {
    if (this is List<*>) {
        forEach { require(it.isRuntimeFilterValue() || it.isFilterScalar()) { EQUALITY_VALUE_MESSAGE } }
    } else {
        require(isRuntimeFilterValue() || isFilterScalar()) { EQUALITY_VALUE_MESSAGE }
    }
}

private fun Any?.requireComparableFilterLiteral() {
    if (!isRuntimeFilterValue()) requireFilterLiteral()
    require(this != null) { "Comparison filter value cannot be null." }
}

private fun List<Any?>.requireFilterLiterals(operator: FilterOperator) {
    require(isNotEmpty()) { "$operator values cannot be empty." }
    forEach {
        if (!it.isRuntimeFilterValue()) it.requireFilterLiteral()
        require(it != null) { "$operator values cannot contain null." }
    }
}

data class EqualFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.EQ

    override fun withField(field: QueryField): EqualFilter = copy(field = field)

    init {
        value.requireEqualityFilterValue()
    }
}

data class NotEqualFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NE

    override fun withField(field: QueryField): NotEqualFilter = copy(field = field)

    init {
        value.requireEqualityFilterValue()
    }
}

data class GreaterThanFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GT

    override fun withField(field: QueryField): GreaterThanFilter = copy(field = field)

    init {
        value.requireComparableFilterLiteral()
    }
}

data class GreaterThanOrEqualFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GTE

    override fun withField(field: QueryField): GreaterThanOrEqualFilter = copy(field = field)

    init {
        value.requireComparableFilterLiteral()
    }
}

data class LessThanFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LT

    override fun withField(field: QueryField): LessThanFilter = copy(field = field)

    init {
        value.requireComparableFilterLiteral()
    }
}

data class LessThanOrEqualFilter(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LTE

    override fun withField(field: QueryField): LessThanOrEqualFilter = copy(field = field)

    init {
        value.requireComparableFilterLiteral()
    }
}

data class ContainsFilter(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.CONTAINS

    override fun withField(field: QueryField): ContainsFilter = copy(field = field)
}

data class StartsWithFilter(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.STARTS_WITH

    override fun withField(field: QueryField): StartsWithFilter = copy(field = field)
}

data class EndsWithFilter(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.ENDS_WITH

    override fun withField(field: QueryField): EndsWithFilter = copy(field = field)
}

data class InFilter(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IN

    override fun withField(field: QueryField): InFilter = copy(field = field)

    init {
        values.requireFilterLiterals(operator)
    }
}

data class NotInFilter(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NOT_IN

    override fun withField(field: QueryField): NotInFilter = copy(field = field)

    init {
        values.requireFilterLiterals(operator)
    }
}

data class BetweenFilter(
    override val field: QueryField,
    val lowerBound: Any?,
    val upperBound: Any?,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.BETWEEN

    override fun withField(field: QueryField): BetweenFilter = copy(field = field)

    init {
        lowerBound.requireComparableFilterLiteral()
        upperBound.requireComparableFilterLiteral()
    }
}

data class ContainsAllFilter(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.CONTAINS_ALL

    override fun withField(field: QueryField): ContainsAllFilter = copy(field = field)

    init {
        values.requireFilterLiterals(operator)
    }
}

data class IsEmptyFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY

    override fun withField(field: QueryField): IsEmptyFilter = copy(field = field)
}

data class IsEmptyStringFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY_STRING

    override fun withField(field: QueryField): IsEmptyStringFilter = copy(field = field)
}

data class IsNotEmptyStringFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_EMPTY_STRING

    override fun withField(field: QueryField): IsNotEmptyStringFilter = copy(field = field)
}

data class IsNullFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NULL

    override fun withField(field: QueryField): IsNullFilter = copy(field = field)
}

data class IsNotNullFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_NULL

    override fun withField(field: QueryField): IsNotNullFilter = copy(field = field)
}

data class ExistsFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.EXISTS

    override fun withField(field: QueryField): ExistsFilter = copy(field = field)
}

data class NotExistsFilter(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NOT_EXISTS

    override fun withField(field: QueryField): NotExistsFilter = copy(field = field)
}
