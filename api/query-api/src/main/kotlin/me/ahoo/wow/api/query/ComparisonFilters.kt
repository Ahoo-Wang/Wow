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

class EqualFilter @InternalWowApi constructor(override val field: QueryField<*>, value: Any?) : FieldPredicate {
    /** Its value, a list of an array equality, copied: a collection its caller changes later changes neither this filter nor its hash. */
    val value: Any? = if (value is List<*>) value.copied() else value

    override val operator: FilterOperator = FilterOperator.EQ

    override fun withField(field: QueryField<*>): EqualFilter = EqualFilter(field, value)

    init {
        value.requireEqualityFilterValue()
    }

    /** This EqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): EqualFilter = EqualFilter(field, value)
    }
}

class NotEqualFilter @InternalWowApi constructor(override val field: QueryField<*>, value: Any?) : FieldPredicate {
    /** Its value, a list of an array equality, copied: a collection its caller changes later changes neither this filter nor its hash. */
    val value: Any? = if (value is List<*>) value.copied() else value

    override val operator: FilterOperator = FilterOperator.NE

    override fun withField(field: QueryField<*>): NotEqualFilter = NotEqualFilter(field, value)

    init {
        value.requireEqualityFilterValue()
    }

    /** This NotEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): NotEqualFilter = NotEqualFilter(field, value)
    }
}

class GreaterThanFilter @InternalWowApi constructor(override val field: QueryField<*>, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GT

    override fun withField(field: QueryField<*>): GreaterThanFilter = GreaterThanFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This GreaterThanFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): GreaterThanFilter = GreaterThanFilter(field, value)
    }
}

class GreaterThanOrEqualFilter @InternalWowApi constructor(override val field: QueryField<*>, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.GTE

    override fun withField(field: QueryField<*>): GreaterThanOrEqualFilter = GreaterThanOrEqualFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This GreaterThanOrEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): GreaterThanOrEqualFilter = GreaterThanOrEqualFilter(field, value)
    }
}

class LessThanFilter @InternalWowApi constructor(override val field: QueryField<*>, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LT

    override fun withField(field: QueryField<*>): LessThanFilter = LessThanFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This LessThanFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): LessThanFilter = LessThanFilter(field, value)
    }
}

class LessThanOrEqualFilter @InternalWowApi constructor(override val field: QueryField<*>, val value: Any?) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.LTE

    override fun withField(field: QueryField<*>): LessThanOrEqualFilter = LessThanOrEqualFilter(field, value)

    init {
        value.requireComparableFilterLiteral()
    }

    /** This LessThanOrEqualFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, value: Any?): LessThanOrEqualFilter = LessThanOrEqualFilter(field, value)
    }
}

class BetweenFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val lowerBound: Any?,
    val upperBound: Any?,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.BETWEEN

    override fun withField(field: QueryField<*>): BetweenFilter = BetweenFilter(field, lowerBound, upperBound)

    init {
        lowerBound.requireComparableFilterLiteral()
        upperBound.requireComparableFilterLiteral()
    }

    /** This BetweenFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
            field: QueryField<*>,
            lowerBound: Any?,
            upperBound: Any?,
        ): BetweenFilter = BetweenFilter(field, lowerBound, upperBound)
    }
}
