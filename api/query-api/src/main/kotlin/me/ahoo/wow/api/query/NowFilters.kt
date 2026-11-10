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
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class RecentDaysFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.RECENT_DAYS

    override fun withField(field: QueryField<*>): RecentDaysFilter = RecentDaysFilter(
        field,
        days,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        require(days >= 1) { "RECENT_DAYS days must be greater than zero." }
        validateConfiguration()
    }

    /** This RecentDaysFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        days: Int = this.days,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): RecentDaysFilter = RecentDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is RecentDaysFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RecentDaysFilter(field=$field, days=$days, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, days, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [RecentDaysFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            days: Int,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): RecentDaysFilter = RecentDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class EarlierDaysFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.EARLIER_DAYS

    override fun withField(field: QueryField<*>): EarlierDaysFilter = EarlierDaysFilter(
        field,
        days,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        require(days >= 1) { "EARLIER_DAYS days must be greater than zero." }
        validateConfiguration()
    }

    /** This EarlierDaysFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        days: Int = this.days,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): EarlierDaysFilter = EarlierDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is EarlierDaysFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "EarlierDaysFilter(field=$field, days=$days, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, days, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [EarlierDaysFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            days: Int,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): EarlierDaysFilter = EarlierDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

private const val ZERO_OFFSET = "PT0S"

private fun String.requireOffset(operator: FilterOperator): Duration = runCatching { Duration.parse(this) }.getOrElse {
    throw IllegalArgumentException("$operator offset must be an ISO-8601 duration such as PT0S or -PT30M.", it)
}

/**
 * A moment relative to the server's clock: the field is before `now + offset`. The server resolves `now` once per
 * query, so every condition of one query sees the same moment and the client's clock does not matter.
 *
 * The offset is an ISO-8601 duration added to `now`: `PT0S` (the default) is now itself, `-PT30M` is 30 minutes ago.
 */
class BeforeNowFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_NOW

    override fun withField(field: QueryField<*>): BeforeNowFilter = BeforeNowFilter(
        field,
        offset,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    val offsetDuration: Duration = offset.requireOffset(operator)

    init {
        validateConfiguration()
    }

    /** This BeforeNowFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        offset: String = this.offset,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): BeforeNowFilter = BeforeNowFilter(field, offset, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is BeforeNowFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "BeforeNowFilter(field=$field, offset=$offset, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, offset, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [BeforeNowFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            offset: String = ZERO_OFFSET,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): BeforeNowFilter = BeforeNowFilter(field, offset, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

/** The field is after `now + offset`; see [BeforeNowFilter]. */
class AfterNowFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.AFTER_NOW

    override fun withField(field: QueryField<*>): AfterNowFilter = AfterNowFilter(
        field,
        offset,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    val offsetDuration: Duration = offset.requireOffset(operator)

    init {
        validateConfiguration()
    }

    /** This AfterNowFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        offset: String = this.offset,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): AfterNowFilter = AfterNowFilter(field, offset, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is AfterNowFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "AfterNowFilter(field=$field, offset=$offset, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, offset, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [AfterNowFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            offset: String = ZERO_OFFSET,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): AfterNowFilter = AfterNowFilter(field, offset, zoneId, datePattern, dateFormatter, timeUnit)
    }
}
