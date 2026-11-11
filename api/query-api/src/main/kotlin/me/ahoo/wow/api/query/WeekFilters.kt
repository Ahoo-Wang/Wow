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

class ThisWeekFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_WEEK

    override fun withField(field: QueryField<*>): ThisWeekFilter = ThisWeekFilter(field, timeZone)

    init {
        validateConfiguration()
    }

    /** This ThisWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): ThisWeekFilter = ThisWeekFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisWeekFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [ThisWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): ThisWeekFilter = ThisWeekFilter(field, timeZone)
    }
}

class NextWeekFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_WEEK

    override fun withField(field: QueryField<*>): NextWeekFilter = NextWeekFilter(field, timeZone)

    init {
        validateConfiguration()
    }

    /** This NextWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): NextWeekFilter = NextWeekFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextWeekFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [NextWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): NextWeekFilter = NextWeekFilter(field, timeZone)
    }
}

class LastWeekFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val timeZone: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_WEEK

    override fun withField(field: QueryField<*>): LastWeekFilter = LastWeekFilter(field, timeZone)

    init {
        validateConfiguration()
    }

    /** This LastWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        timeZone: String? = this.timeZone,
    ): LastWeekFilter = LastWeekFilter(field, timeZone)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastWeekFilter(field=$field, timeZone=$timeZone)"

    private fun valueFields(): List<Any?> = listOf(field, timeZone)

    companion object {
        /** A [LastWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            timeZone: String? = null,
        ): LastWeekFilter = LastWeekFilter(field, timeZone)
    }
}
