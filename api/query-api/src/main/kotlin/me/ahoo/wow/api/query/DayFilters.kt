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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class TodayFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TODAY

    override fun withField(field: QueryField<*>): TodayFilter = TodayFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This TodayFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): TodayFilter = TodayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is TodayFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TodayFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [TodayFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): TodayFilter = TodayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class BeforeTodayFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    val time: String,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_TODAY

    override fun withField(field: QueryField<*>): BeforeTodayFilter = BeforeTodayFilter(
        field,
        time,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        try {
            LocalTime.parse(time)
        } catch (error: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("BEFORE_TODAY time must be a time such as 18:00 or 18:00:30.", error)
        }
        validateConfiguration()
    }

    /** This BeforeTodayFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        time: String = this.time,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): BeforeTodayFilter = BeforeTodayFilter(field, time, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is BeforeTodayFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "BeforeTodayFilter(field=$field, time=$time, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, time, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [BeforeTodayFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            time: String,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): BeforeTodayFilter = BeforeTodayFilter(field, time, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class TomorrowFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TOMORROW

    override fun withField(field: QueryField<*>): TomorrowFilter = TomorrowFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This TomorrowFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): TomorrowFilter = TomorrowFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is TomorrowFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "TomorrowFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [TomorrowFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): TomorrowFilter = TomorrowFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class YesterdayFilter @InternalWowApi constructor(
    override val field: QueryField<*>,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.YESTERDAY

    override fun withField(field: QueryField<*>): YesterdayFilter = YesterdayFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init { validateConfiguration() }

    /** This YesterdayFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField<*> = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): YesterdayFilter = YesterdayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is YesterdayFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "YesterdayFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [YesterdayFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField<*>,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): YesterdayFilter = YesterdayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}
