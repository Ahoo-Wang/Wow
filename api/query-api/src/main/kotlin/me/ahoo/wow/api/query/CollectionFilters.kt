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

class InFilter @InternalWowApi constructor(override val field: QueryField<*>, values: List<Any?>) : FieldPredicate {
    /** Its values, copied: a collection its caller changes later changes neither this filter nor its hash. */
    val values: List<Any?> = values.copied()

    override val operator: FilterOperator = FilterOperator.IN

    override fun withField(field: QueryField<*>): InFilter = InFilter(field, values)

    init {
        values.requireFilterLiterals(operator)
    }

    /** This InFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, values: List<Any?>): InFilter = InFilter(field, values)
    }
}

class NotInFilter @InternalWowApi constructor(override val field: QueryField<*>, values: List<Any?>) : FieldPredicate {
    /** Its values, copied: a collection its caller changes later changes neither this filter nor its hash. */
    val values: List<Any?> = values.copied()

    override val operator: FilterOperator = FilterOperator.NOT_IN

    override fun withField(field: QueryField<*>): NotInFilter = NotInFilter(field, values)

    init {
        values.requireFilterLiterals(operator)
    }

    /** This NotInFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
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
        fun of(field: QueryField<*>, values: List<Any?>): NotInFilter = NotInFilter(field, values)
    }
}

class ContainsAllFilter @InternalWowApi constructor(override val field: QueryField<*>, values: List<Any?>) : FieldPredicate {
    /** Its values, copied: a collection its caller changes later changes neither this filter nor its hash. */
    val values: List<Any?> = values.copied()

    override val operator: FilterOperator = FilterOperator.CONTAINS_ALL

    override fun withField(field: QueryField<*>): ContainsAllFilter = ContainsAllFilter(field, values)

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
        fun of(field: QueryField<*>, values: List<Any?>): ContainsAllFilter = ContainsAllFilter(field, values)
    }
}
