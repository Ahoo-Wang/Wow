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

class IsEmptyFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY

    override fun withField(field: QueryField<*>): IsEmptyFilter = IsEmptyFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsEmptyFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsEmptyFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsEmptyFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): IsEmptyFilter = IsEmptyFilter(field)
    }
}

class IsEmptyStringFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_EMPTY_STRING

    override fun withField(field: QueryField<*>): IsEmptyStringFilter = IsEmptyStringFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsEmptyStringFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsEmptyStringFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsEmptyStringFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): IsEmptyStringFilter = IsEmptyStringFilter(field)
    }
}

class IsNotEmptyStringFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_EMPTY_STRING

    override fun withField(field: QueryField<*>): IsNotEmptyStringFilter = IsNotEmptyStringFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNotEmptyStringFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNotEmptyStringFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNotEmptyStringFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): IsNotEmptyStringFilter = IsNotEmptyStringFilter(field)
    }
}

class IsNullFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NULL

    override fun withField(field: QueryField<*>): IsNullFilter = IsNullFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNullFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNullFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNullFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): IsNullFilter = IsNullFilter(field)
    }
}

class IsNotNullFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.IS_NOT_NULL

    override fun withField(field: QueryField<*>): IsNotNullFilter = IsNotNullFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is IsNotNullFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "IsNotNullFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [IsNotNullFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): IsNotNullFilter = IsNotNullFilter(field)
    }
}

class ExistsFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.EXISTS

    override fun withField(field: QueryField<*>): ExistsFilter = ExistsFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is ExistsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ExistsFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [ExistsFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): ExistsFilter = ExistsFilter(field)
    }
}

class NotExistsFilter @InternalWowApi constructor(override val field: QueryField<*>) : FieldPredicate {
    override val operator: FilterOperator = FilterOperator.NOT_EXISTS

    override fun withField(field: QueryField<*>): NotExistsFilter = NotExistsFilter(field)

    override fun equals(other: Any?): Boolean =
        this === other || other is NotExistsFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NotExistsFilter(field=$field)"

    private fun valueFields(): List<Any?> = listOf(field)

    companion object {
        /** A [NotExistsFilter] of the given values. */
        @JvmStatic
        fun of(field: QueryField<*>): NotExistsFilter = NotExistsFilter(field)
    }
}
