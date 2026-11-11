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
    override val zoneId: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    override fun withField(field: QueryField<*>): ThisMonthFilter = ThisMonthFilter(field, zoneId)

    init {
        validateConfiguration()
    }

    /** This ThisMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
    ): ThisMonthFilter = ThisMonthFilter(field, zoneId)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisMonthFilter(field=$field, zoneId=$zoneId)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId)

    companion object {
        /** A [ThisMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
        ): ThisMonthFilter = ThisMonthFilter(field, zoneId)
    }
}

class LastMonthFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val zoneId: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    override fun withField(field: QueryField<*>): LastMonthFilter = LastMonthFilter(field, zoneId)

    init {
        validateConfiguration()
    }

    /** This LastMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
    ): LastMonthFilter = LastMonthFilter(field, zoneId)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastMonthFilter(field=$field, zoneId=$zoneId)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId)

    companion object {
        /** A [LastMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
        ): LastMonthFilter = LastMonthFilter(field, zoneId)
    }
}

class NextMonthFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val zoneId: String? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    override fun withField(field: QueryField<*>): NextMonthFilter = NextMonthFilter(field, zoneId)

    init { validateConfiguration() }

    /** This NextMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
    ): NextMonthFilter = NextMonthFilter(field, zoneId)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextMonthFilter(field=$field, zoneId=$zoneId)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId)

    companion object {
        /** A [NextMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
        ): NextMonthFilter = NextMonthFilter(field, zoneId)
    }
}
