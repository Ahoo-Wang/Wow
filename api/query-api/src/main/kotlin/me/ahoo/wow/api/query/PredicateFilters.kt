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

import me.ahoo.wow.api.annotation.InternalWowApi

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

class EqualFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.EQ

    override fun withField(field: QueryField): EqualFilter = EqualFilter(field, value)

    init {
        value.requireEqualityFilterValue()
    }

    /** This EqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): EqualFilter = EqualFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is EqualFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "EqualFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [EqualFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): EqualFilter = EqualFilter(field, value)
    }
}

class NotEqualFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NE

    override fun withField(field: QueryField): NotEqualFilter = NotEqualFilter(field, value)

    init {
        value.requireEqualityFilterValue()
    }

    /** This NotEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): NotEqualFilter = NotEqualFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is NotEqualFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NotEqualFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [NotEqualFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): NotEqualFilter = NotEqualFilter(field, value)
    }
}

class GreaterThanFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GT

    override fun withField(field: QueryField): GreaterThanFilter = GreaterThanFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This GreaterThanFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): GreaterThanFilter = GreaterThanFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is GreaterThanFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "GreaterThanFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [GreaterThanFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): GreaterThanFilter = GreaterThanFilter(field, value)
    }
}

class GreaterThanOrEqualFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GTE

    override fun withField(field: QueryField): GreaterThanOrEqualFilter = GreaterThanOrEqualFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This GreaterThanOrEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): GreaterThanOrEqualFilter = GreaterThanOrEqualFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is GreaterThanOrEqualFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "GreaterThanOrEqualFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [GreaterThanOrEqualFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): GreaterThanOrEqualFilter = GreaterThanOrEqualFilter(field, value)
    }
}

class LessThanFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LT

    override fun withField(field: QueryField): LessThanFilter = LessThanFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This LessThanFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): LessThanFilter = LessThanFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is LessThanFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LessThanFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [LessThanFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): LessThanFilter = LessThanFilter(field, value)
    }
}

class LessThanOrEqualFilter @InternalWowApi constructor(override val field: QueryField, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LTE

    override fun withField(field: QueryField): LessThanOrEqualFilter = LessThanOrEqualFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This LessThanOrEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        value: Any? = this.value,
    ): LessThanOrEqualFilter = LessThanOrEqualFilter(field, value)

    override fun equals(other: Any?): Boolean =
        this === other || other is LessThanOrEqualFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LessThanOrEqualFilter(field=$field, value=$value)"

    private fun valueFields(): List<Any?> = listOf(field, value)

    companion object {
        /** A [LessThanOrEqualFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, value: Any?): LessThanOrEqualFilter = LessThanOrEqualFilter(field, value)
    }
}

class ContainsFilter @InternalWowApi constructor(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.CONTAINS

    override fun withField(field: QueryField): ContainsFilter = ContainsFilter(field, value, stringComparison)

    override fun equals(other: Any?): Boolean =
        this === other || other is ContainsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ContainsFilter(field=$field, value=$value, stringComparison=$stringComparison)"

    private fun valueFields(): List<Any?> = listOf(field, value, stringComparison)

    companion object {
        /** A [ContainsFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): ContainsFilter = ContainsFilter(field, value, stringComparison)
    }
}

class StartsWithFilter @InternalWowApi constructor(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.STARTS_WITH

    override fun withField(field: QueryField): StartsWithFilter = StartsWithFilter(field, value, stringComparison)

    override fun equals(other: Any?): Boolean =
        this === other || other is StartsWithFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "StartsWithFilter(field=$field, value=$value, stringComparison=$stringComparison)"

    private fun valueFields(): List<Any?> = listOf(field, value, stringComparison)

    companion object {
        /** A [StartsWithFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): StartsWithFilter = StartsWithFilter(field, value, stringComparison)
    }
}

class EndsWithFilter @InternalWowApi constructor(
    override val field: QueryField,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.ENDS_WITH

    override fun withField(field: QueryField): EndsWithFilter = EndsWithFilter(field, value, stringComparison)

    override fun equals(other: Any?): Boolean =
        this === other || other is EndsWithFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "EndsWithFilter(field=$field, value=$value, stringComparison=$stringComparison)"

    private fun valueFields(): List<Any?> = listOf(field, value, stringComparison)

    companion object {
        /** A [EndsWithFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): EndsWithFilter = EndsWithFilter(field, value, stringComparison)
    }
}

class InFilter @InternalWowApi constructor(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IN

    override fun withField(field: QueryField): InFilter = InFilter(field, values)

    init {
        values.requireFilterLiterals(operator)
    }

    /** This InFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        values: List<Any?> = this.values,
    ): InFilter = InFilter(field, values)

    override fun equals(other: Any?): Boolean =
        this === other || other is InFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "InFilter(field=$field, values=$values)"

    private fun valueFields(): List<Any?> = listOf(field, values)

    companion object {
        /** A [InFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, values: List<Any?>): InFilter = InFilter(field, values)
    }
}

class NotInFilter @InternalWowApi constructor(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NOT_IN

    override fun withField(field: QueryField): NotInFilter = NotInFilter(field, values)

    init {
        values.requireFilterLiterals(operator)
    }

    /** This NotInFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        values: List<Any?> = this.values,
    ): NotInFilter = NotInFilter(field, values)

    override fun equals(other: Any?): Boolean =
        this === other || other is NotInFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NotInFilter(field=$field, values=$values)"

    private fun valueFields(): List<Any?> = listOf(field, values)

    companion object {
        /** A [NotInFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, values: List<Any?>): NotInFilter = NotInFilter(field, values)
    }
}

class BetweenFilter @InternalWowApi constructor(
    override val field: QueryField,
    val lowerBound: Any?,
    val upperBound: Any?,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.BETWEEN

    override fun withField(field: QueryField): BetweenFilter = BetweenFilter(field, lowerBound, upperBound)

    init {
        lowerBound.requireComparableFilterLiteral()
        upperBound.requireComparableFilterLiteral()
    }

    /** This BetweenFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        lowerBound: Any? = this.lowerBound,
        upperBound: Any? = this.upperBound,
    ): BetweenFilter = BetweenFilter(field, lowerBound, upperBound)

    override fun equals(other: Any?): Boolean =
        this === other || other is BetweenFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "BetweenFilter(field=$field, lowerBound=$lowerBound, upperBound=$upperBound)"

    private fun valueFields(): List<Any?> = listOf(field, lowerBound, upperBound)

    companion object {
        /** A [BetweenFilter] of the given values. */
        @JvmStatic
        fun of(
            field: QueryField,
            lowerBound: Any?,
            upperBound: Any?,
        ): BetweenFilter = BetweenFilter(field, lowerBound, upperBound)
    }
}

class ContainsAllFilter @InternalWowApi constructor(override val field: QueryField, val values: List<Any?>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.CONTAINS_ALL

    override fun withField(field: QueryField): ContainsAllFilter = ContainsAllFilter(field, values)

    init {
        values.requireFilterLiterals(operator)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ContainsAllFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ContainsAllFilter(field=$field, values=$values)"

    private fun valueFields(): List<Any?> = listOf(field, values)

    companion object {
        /** A [ContainsAllFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField, values: List<Any?>): ContainsAllFilter = ContainsAllFilter(field, values)
    }
}

class IsEmptyFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY

    override fun withField(field: QueryField): IsEmptyFilter = IsEmptyFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsEmptyFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsEmptyFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsEmptyFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): IsEmptyFilter = IsEmptyFilter(field)
    }
}

class IsEmptyStringFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY_STRING

    override fun withField(field: QueryField): IsEmptyStringFilter = IsEmptyStringFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsEmptyStringFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsEmptyStringFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsEmptyStringFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): IsEmptyStringFilter = IsEmptyStringFilter(field)
    }
}

class IsNotEmptyStringFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_EMPTY_STRING

    override fun withField(field: QueryField): IsNotEmptyStringFilter = IsNotEmptyStringFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNotEmptyStringFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNotEmptyStringFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNotEmptyStringFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): IsNotEmptyStringFilter = IsNotEmptyStringFilter(field)
    }
}

class IsNullFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NULL

    override fun withField(field: QueryField): IsNullFilter = IsNullFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNullFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNullFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNullFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): IsNullFilter = IsNullFilter(field)
    }
}

class IsNotNullFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_NULL

    override fun withField(field: QueryField): IsNotNullFilter = IsNotNullFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNotNullFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNotNullFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNotNullFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): IsNotNullFilter = IsNotNullFilter(field)
    }
}

class ExistsFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.EXISTS

    override fun withField(field: QueryField): ExistsFilter = ExistsFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is ExistsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ExistsFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [ExistsFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): ExistsFilter = ExistsFilter(field)
    }
}

class NotExistsFilter @InternalWowApi constructor(override val field: QueryField) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NOT_EXISTS

    override fun withField(field: QueryField): NotExistsFilter = NotExistsFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is NotExistsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NotExistsFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [NotExistsFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField): NotExistsFilter = NotExistsFilter(field)
    }
}
