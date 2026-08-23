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
    val datePattern: String?

    @get:JsonIgnore
    val dateFormatter: DateTimeFormatter?

    fun resolvedDateFormatter(): DateTimeFormatter? = dateFormatter ?: datePattern.toDateFormatter()
}

@JsonTypeName("TODAY")
data class TodayFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TODAY

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("BEFORE_TODAY")
data class BeforeTodayFilter(
    val field: LogicalField,
    val time: String,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.BEFORE_TODAY

    init {
        LocalTime.parse(time)
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("TOMORROW")
data class TomorrowFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.TOMORROW

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("THIS_WEEK")
data class ThisWeekFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_WEEK

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("NEXT_WEEK")
data class NextWeekFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.NEXT_WEEK

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("LAST_WEEK")
data class LastWeekFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_WEEK

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("THIS_MONTH")
data class ThisMonthFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.THIS_MONTH

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("LAST_MONTH")
data class LastMonthFilter(
    val field: LogicalField,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.LAST_MONTH

    init {
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("RECENT_DAYS")
data class RecentDaysFilter(
    val field: LogicalField,
    val days: Int,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.RECENT_DAYS

    init {
        require(days >= 1) { "RECENT_DAYS days must be greater than zero." }
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}

@JsonTypeName("EARLIER_DAYS")
data class EarlierDaysFilter(
    val field: LogicalField,
    val days: Int,
    val zoneId: String? = null,
    override val datePattern: String? = null,
    @get:JsonIgnore override val dateFormatter: DateTimeFormatter? = null,
) : RelativeTimeFilter {
    override val operator: FilterOperator = FilterOperator.EARLIER_DAYS

    init {
        require(days >= 1) { "EARLIER_DAYS days must be greater than zero." }
        zoneId.requireZoneId()
        datePattern.toDateFormatter()
    }
}
