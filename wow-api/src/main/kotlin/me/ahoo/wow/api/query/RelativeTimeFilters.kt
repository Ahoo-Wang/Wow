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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeName
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

sealed interface RelativeTimeFilter : FilterExpression {
    val field: QueryField
    val zoneId: String?
    val datePattern: String?

    @get:JsonIgnore
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

@JsonTypeName(QueryProtocol.FilterExpression.Operator.TODAY)
data class TodayFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TODAY

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.BEFORE_TODAY)
data class BeforeTodayFilter(
    override val field: QueryField,
    val time: String,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_TODAY

    init {
        try {
            LocalTime.parse(time)
        } catch (error: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("BEFORE_TODAY time must be a time such as 18:00 or 18:00:30.", error)
        }
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.TOMORROW)
data class TomorrowFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TOMORROW

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.THIS_WEEK)
data class ThisWeekFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NEXT_WEEK)
data class NextWeekFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.LAST_WEEK)
data class LastWeekFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.THIS_MONTH)
data class ThisMonthFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.LAST_MONTH)
data class LastMonthFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    init {
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.RECENT_DAYS)
data class RecentDaysFilter(
    override val field: QueryField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.RECENT_DAYS

    init {
        require(days >= 1) { "RECENT_DAYS days must be greater than zero." }
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.EARLIER_DAYS)
data class EarlierDaysFilter(
    override val field: QueryField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.EARLIER_DAYS

    init {
        require(days >= 1) { "EARLIER_DAYS days must be greater than zero." }
        validateConfiguration()
    }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.YESTERDAY)
data class YesterdayFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.YESTERDAY

    init { validateConfiguration() }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NEXT_MONTH)
data class NextMonthFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    init { validateConfiguration() }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.LAST_YEAR)
data class LastYearFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_YEAR

    init { validateConfiguration() }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.THIS_YEAR)
data class ThisYearFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_YEAR

    init { validateConfiguration() }
}

@JsonTypeName(QueryProtocol.FilterExpression.Operator.NEXT_YEAR)
data class NextYearFilter(
    override val field: QueryField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_YEAR

    init { validateConfiguration() }
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
@JsonTypeName(QueryProtocol.FilterExpression.Operator.BEFORE_NOW)
data class BeforeNowFilter(
    override val field: QueryField,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_NOW

    @get:JsonIgnore
    val offsetDuration: Duration = offset.requireOffset(operator)

    init {
        validateConfiguration()
    }
}

/** The field is after `now + offset`; see [BeforeNowFilter]. */
@JsonTypeName(QueryProtocol.FilterExpression.Operator.AFTER_NOW)
data class AfterNowFilter(
    override val field: QueryField,
    val offset: String = ZERO_OFFSET,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
    override val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.AFTER_NOW

    @get:JsonIgnore
    val offsetDuration: Duration = offset.requireOffset(operator)

    init {
        validateConfiguration()
    }
}

private const val ZERO_OFFSET = "PT0S"
