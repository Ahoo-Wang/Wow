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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun String?.requireZoneId() {
    if (this != null) {
        require(isNotBlank()) { "zoneId cannot be blank." }
        ZoneId.of(this)
    }
}

private fun String?.toDateFormatter(): DateTimeFormatter? {
    if (this == null) return null
    require(isNotBlank()) { "datePattern cannot be blank." }
    return DateTimeFormatter.ofPattern(this)
}

sealed interface RelativeTimeFilter : FilterExpression {
    val field: LogicalField
    val zoneId: String?
    val datePattern: String?

    @get:JsonIgnore
    val dateFormatter: DateTimeFormatter?

    fun resolvedDateFormatter(): DateTimeFormatter? = dateFormatter ?: datePattern.toDateFormatter()
}

private fun RelativeTimeFilter.validateConfiguration() {
    zoneId.requireZoneId()
    datePattern.toDateFormatter()
}

@JsonTypeName("TODAY")
data class TodayFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TODAY

    init {
        validateConfiguration()
    }
}

@JsonTypeName("BEFORE_TODAY")
data class BeforeTodayFilter(
    override val field: LogicalField,
    val time: String,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_TODAY

    init {
        LocalTime.parse(time)
        validateConfiguration()
    }
}

@JsonTypeName("TOMORROW")
data class TomorrowFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TOMORROW

    init {
        validateConfiguration()
    }
}

@JsonTypeName("THIS_WEEK")
data class ThisWeekFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName("NEXT_WEEK")
data class NextWeekFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName("LAST_WEEK")
data class LastWeekFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_WEEK

    init {
        validateConfiguration()
    }
}

@JsonTypeName("THIS_MONTH")
data class ThisMonthFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    init {
        validateConfiguration()
    }
}

@JsonTypeName("LAST_MONTH")
data class LastMonthFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    init {
        validateConfiguration()
    }
}

@JsonTypeName("RECENT_DAYS")
data class RecentDaysFilter(
    override val field: LogicalField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.RECENT_DAYS

    init {
        require(days >= 1) { "RECENT_DAYS days must be greater than zero." }
        validateConfiguration()
    }
}

@JsonTypeName("EARLIER_DAYS")
data class EarlierDaysFilter(
    override val field: LogicalField,
    val days: Int,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.EARLIER_DAYS

    init {
        require(days >= 1) { "EARLIER_DAYS days must be greater than zero." }
        validateConfiguration()
    }
}

@JsonTypeName("YESTERDAY")
data class YesterdayFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.YESTERDAY

    init { validateConfiguration() }
}

@JsonTypeName("NEXT_MONTH")
data class NextMonthFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_MONTH

    init { validateConfiguration() }
}

@JsonTypeName("LAST_YEAR")
data class LastYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_YEAR

    init { validateConfiguration() }
}

@JsonTypeName("THIS_YEAR")
data class ThisYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_YEAR

    init { validateConfiguration() }
}

@JsonTypeName("NEXT_YEAR")
data class NextYearFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_YEAR

    init { validateConfiguration() }
}
