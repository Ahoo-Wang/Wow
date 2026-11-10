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

class ContainsFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.CONTAINS

    override fun withField(field: QueryField<*>): ContainsFilter = ContainsFilter(field, value, stringComparison)

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
            field: QueryField<*>,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): ContainsFilter = ContainsFilter(field, value, stringComparison)
    }
}

class StartsWithFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.STARTS_WITH

    override fun withField(field: QueryField<*>): StartsWithFilter = StartsWithFilter(field, value, stringComparison)

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
            field: QueryField<*>,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): StartsWithFilter = StartsWithFilter(field, value, stringComparison)
    }
}

class EndsWithFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val value: String,
    val stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.ENDS_WITH

    override fun withField(field: QueryField<*>): EndsWithFilter = EndsWithFilter(field, value, stringComparison)

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
            field: QueryField<*>,
            value: String,
            stringComparison: StringComparison = StringComparison.CASE_SENSITIVE,
        ): EndsWithFilter = EndsWithFilter(field, value, stringComparison)
    }
}
