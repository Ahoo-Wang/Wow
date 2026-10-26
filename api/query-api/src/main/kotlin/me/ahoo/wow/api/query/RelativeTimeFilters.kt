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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private fun String?.requireZoneId() {
    if (this != null) {
        require(isNotBlank()) { "zoneId cannot be blank." }
        zoneIdOf(this)
    }
}

private fun String?.toDateFormatter(): DateTimeFormatter? {
    if (this == null) return null
    require(isNotBlank()) { "datePattern cannot be blank." }
    return dateFormatterOf(this)
}

/** Parses a time zone id, rejecting unknown ids with the framework's own message rather than the JDK's. */
internal fun zoneIdOf(id: String): ZoneId = ZONES.parse(id) {
    try {
        ZoneId.of(id)
    } catch (error: java.time.DateTimeException) {
        throw IllegalArgumentException("Unknown time zone [$id].", error)
    }
}

/** Parses a date-time pattern, rejecting invalid patterns with the framework's own message rather than the JDK's. */
internal fun dateFormatterOf(pattern: String): DateTimeFormatter = PATTERNS.parse(pattern) {
    try {
        DateTimeFormatter.ofPattern(pattern)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("datePattern [$pattern] is not a valid date-time pattern.", error)
    }
}

/*
 * Zones and formatters are immutable and every relative-time node of every query parses one, so each is parsed once.
 * Ids and patterns come from callers: only valid ones are kept, the MAX_PARSED most recently used of each, so no
 * run of distinct keys can pin the cache and leave later ones parsed per use.
 */
private const val MAX_PARSED = 256
private val ZONES = ParsedCache<ZoneId>()
private val PATTERNS = ParsedCache<DateTimeFormatter>()

/** A bounded, least-recently-used cache of parsed values; a key whose parse throws is never kept. */
private class ParsedCache<V : Any> {
    private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > MAX_PARSED
    }

    fun parse(key: String, parse: () -> V): V {
        synchronized(entries) { entries[key] }?.let { return it }
        val parsed = parse()
        synchronized(entries) { entries.putIfAbsent(key, parsed) }
        return parsed
    }
}

sealed interface RelativeTimeFilter : FieldPredicate {
    val zoneId: String?
    val datePattern: String?

    /** A formatter given in process instead of [datePattern]; never written on the wire. */
    val dateFormatter: DateTimeFormatter?

    /** Unit used for numeric time fields; ignored when a date formatter is configured. */
    val timeUnit: TimeUnit

    fun resolvedDateFormatter(): DateTimeFormatter? = dateFormatter ?: datePattern.toDateFormatter()

    /** The zone [zoneId] names, parsed once per id; `null` when the filter names none. */
    fun resolvedZoneId(): ZoneId? = zoneId?.let(::zoneIdOf)
}

private fun RelativeTimeFilter.validateConfiguration() {
    zoneId.requireZoneId()
    datePattern.toDateFormatter()
}

class TodayFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TODAY

    override fun withField(field: QueryField): TodayFilter = TodayFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): TodayFilter = TodayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class BeforeTodayFilter @InternalWowApi constructor(
    override val field: QueryField,
    val time: String,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_TODAY

    override fun withField(field: QueryField): BeforeTodayFilter = BeforeTodayFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            time: String,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): BeforeTodayFilter = BeforeTodayFilter(field, time, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class TomorrowFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TOMORROW

    override fun withField(field: QueryField): TomorrowFilter = TomorrowFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): TomorrowFilter = TomorrowFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class ThisWeekFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_WEEK

    override fun withField(field: QueryField): ThisWeekFilter = ThisWeekFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This ThisWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): ThisWeekFilter = ThisWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisWeekFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [ThisWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): ThisWeekFilter = ThisWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class NextWeekFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_WEEK

    override fun withField(field: QueryField): NextWeekFilter = NextWeekFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This NextWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): NextWeekFilter = NextWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextWeekFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [NextWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): NextWeekFilter = NextWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class LastWeekFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_WEEK

    override fun withField(field: QueryField): LastWeekFilter = LastWeekFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This LastWeekFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): LastWeekFilter = LastWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastWeekFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastWeekFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [LastWeekFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): LastWeekFilter = LastWeekFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class ThisMonthFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    override fun withField(field: QueryField): ThisMonthFilter = ThisMonthFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This ThisMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): ThisMonthFilter = ThisMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisMonthFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [ThisMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): ThisMonthFilter = ThisMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class LastMonthFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    override fun withField(field: QueryField): LastMonthFilter = LastMonthFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init {
        validateConfiguration()
    }

    /** This LastMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): LastMonthFilter = LastMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastMonthFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [LastMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): LastMonthFilter = LastMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class RecentDaysFilter @InternalWowApi constructor(
    override val field: QueryField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.RECENT_DAYS

    override fun withField(field: QueryField): RecentDaysFilter = RecentDaysFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            days: Int,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): RecentDaysFilter = RecentDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class EarlierDaysFilter @InternalWowApi constructor(
    override val field: QueryField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.EARLIER_DAYS

    override fun withField(field: QueryField): EarlierDaysFilter = EarlierDaysFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            days: Int,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): EarlierDaysFilter = EarlierDaysFilter(field, days, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class YesterdayFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.YESTERDAY

    override fun withField(field: QueryField): YesterdayFilter = YesterdayFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): YesterdayFilter = YesterdayFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class NextMonthFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    override fun withField(field: QueryField): NextMonthFilter = NextMonthFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init { validateConfiguration() }

    /** This NextMonthFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): NextMonthFilter = NextMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextMonthFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextMonthFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [NextMonthFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): NextMonthFilter = NextMonthFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class LastYearFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_YEAR

    override fun withField(field: QueryField): LastYearFilter = LastYearFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init { validateConfiguration() }

    /** This LastYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): LastYearFilter = LastYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is LastYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "LastYearFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [LastYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): LastYearFilter = LastYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class ThisYearFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_YEAR

    override fun withField(field: QueryField): ThisYearFilter = ThisYearFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init { validateConfiguration() }

    /** This ThisYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): ThisYearFilter = ThisYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is ThisYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ThisYearFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [ThisYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): ThisYearFilter = ThisYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

class NextYearFilter @InternalWowApi constructor(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_YEAR

    override fun withField(field: QueryField): NextYearFilter = NextYearFilter(
        field,
        zoneId,
        datePattern,
        dateFormatter,
        timeUnit
    )

    init { validateConfiguration() }

    /** This NextYearFilter with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        field: QueryField = this.field,
        zoneId: String? = this.zoneId,
        datePattern: String? = this.datePattern,
        dateFormatter: DateTimeFormatter? = this.dateFormatter,
        timeUnit: TimeUnit = this.timeUnit,
    ): NextYearFilter = NextYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)

    override fun equals(other: Any?): Boolean =
        this === other || other is NextYearFilter && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "NextYearFilter(field=$field, zoneId=$zoneId, datePattern=$datePattern, dateFormatter=$dateFormatter, timeUnit=$timeUnit)"

    private fun valueFields(): List<Any?> = listOf(field, zoneId, datePattern, dateFormatter, timeUnit)

    companion object {
        /** A [NextYearFilter] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            field: QueryField,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): NextYearFilter = NextYearFilter(field, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

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
    override val field: QueryField,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_NOW

    override fun withField(field: QueryField): BeforeNowFilter = BeforeNowFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
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
    override val field: QueryField,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.AFTER_NOW

    override fun withField(field: QueryField): AfterNowFilter = AfterNowFilter(
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
        field: QueryField = this.field,
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
            field: QueryField,
            offset: String = ZERO_OFFSET,
            zoneId: String? = null,
            datePattern: String? = null,
            dateFormatter: DateTimeFormatter? = null,
            timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ): AfterNowFilter = AfterNowFilter(field, offset, zoneId, datePattern, dateFormatter, timeUnit)
    }
}

private const val ZERO_OFFSET = "PT0S"
