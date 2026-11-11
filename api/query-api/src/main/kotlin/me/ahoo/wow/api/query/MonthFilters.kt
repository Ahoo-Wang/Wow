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

class ThisMonthFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    override fun withField(field: QueryField<*>): ThisMonthFilter = ThisMonthFilter(field, timeZone)

    init {
        validateConfiguration()
    }

    /** This ThisMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): ThisMonthFilter = ThisMonthFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisMonthFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [ThisMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): ThisMonthFilter = ThisMonthFilter(field, timeZone)
    }
}

class LastMonthFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    override fun withField(field: QueryField<*>): LastMonthFilter = LastMonthFilter(field, timeZone)

    init {
        validateConfiguration()
    }

    /** This LastMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): LastMonthFilter = LastMonthFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastMonthFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [LastMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): LastMonthFilter = LastMonthFilter(field, timeZone)
    }
}

class NextMonthFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    override fun withField(field: QueryField<*>): NextMonthFilter = NextMonthFilter(field, timeZone)

    init { validateConfiguration() }

    /** This NextMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): NextMonthFilter = NextMonthFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextMonthFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [NextMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): NextMonthFilter = NextMonthFilter(field, timeZone)
    }
}
