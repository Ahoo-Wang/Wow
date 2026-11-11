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

class LastYearFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_YEAR

    override fun withField(field: QueryField<*>): LastYearFilter = LastYearFilter(field, timeZone)

    init { validateConfiguration() }

    /** This LastYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): LastYearFilter = LastYearFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastYearFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [LastYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): LastYearFilter = LastYearFilter(field, timeZone)
    }
}

class ThisYearFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_YEAR

    override fun withField(field: QueryField<*>): ThisYearFilter = ThisYearFilter(field, timeZone)

    init { validateConfiguration() }

    /** This ThisYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): ThisYearFilter = ThisYearFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisYearFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [ThisYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): ThisYearFilter = ThisYearFilter(field, timeZone)
    }
}

class NextYearFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_YEAR

    override fun withField(field: QueryField<*>): NextYearFilter = NextYearFilter(field, timeZone)

    init { validateConfiguration() }

    /** This NextYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): NextYearFilter = NextYearFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextYearFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [NextYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): NextYearFilter = NextYearFilter(field, timeZone)
    }
}
