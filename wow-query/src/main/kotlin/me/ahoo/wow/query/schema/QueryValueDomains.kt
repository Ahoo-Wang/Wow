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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode
import java.util.concurrent.TimeUnit

/** Operations on primitive arrays use one member layer, without flattening the definition. */
fun QueryValueSchema.operationValues(): List<QueryValueSchema> = when (kind) {
    QueryValueKind.ARRAY -> checkNotNull(items).alternativesOrSelf()
    QueryValueKind.UNION -> alternatives.flatMap { it.operationValues() }
    else -> listOf(this)
}

internal fun QueryValueSchema.alternativesOrSelf(): List<QueryValueSchema> =
    if (kind == QueryValueKind.UNION) alternatives.flatMap { it.alternativesOrSelf() } else listOf(this)

internal fun QueryValueSchema.accepts(values: Iterable<JsonNode>): Boolean {
    if (kind == QueryValueKind.SCALAR || kind == QueryValueKind.OBJECT) {
        return values.all { input -> input.isNull || valueTypes.any { input.matches(it) } }
    }
    val domains = operationValues().filter { it.kind != QueryValueKind.NULL }
    return values.all { input ->
        input.isNull || domains.any { domain ->
            domain.kind != QueryValueKind.UNKNOWN && domain.valueTypes.any { input.matches(it) }
        }
    }
}

private fun JsonNode.matches(type: QueryValueType): Boolean {
    if (isPojo) {
        val canonical = JsonSerializer.valueToTree<JsonNode>((this as POJONode).pojo)
        return !canonical.isPojo && canonical.matches(type)
    }
    return when (type) {
        QueryValueType.STRING -> isString
        QueryValueType.INTEGER -> isNumber && canConvertToExactIntegral()
        QueryValueType.DECIMAL -> isNumber
        QueryValueType.BOOLEAN -> isBoolean
        QueryValueType.OBJECT -> isObject
        else -> true
    }
}

internal fun RelativeTimeFilter.temporal(value: QueryValueSchema): QuerySemanticType {
    val domains = value.operationValues().filter { it.kind != QueryValueKind.NULL }
    val temporal = domains.map { it.semanticType }.distinct().singleOrNull()
    requireSchema(
        dateFormatter == null && temporal != null
    ) { "Relative-time field requires a known temporal representation." }
    requireSchema(
        when (temporal) {
            is Temporal.Epoch, Temporal.Date -> datePattern == null
            is Temporal.Formatted -> datePattern == null || datePattern == temporal.pattern
            else -> false
        },
    ) { "Relative-time configuration conflicts with its value definition." }
    return checkNotNull(temporal)
}

internal fun RelativeTimeFilter.withTemporal(value: QueryValueSchema): RelativeTimeFilter = when (
    val temporal = temporal(
        value
    )
) {
    is Temporal.Epoch -> copyTemporal(field, timeUnit = temporal.timeUnit)
    is Temporal.Formatted -> copyTemporal(field, datePattern = temporal.pattern)
    else -> this
}

internal inline fun requireSchema(accepted: Boolean, message: () -> String) {
    if (!accepted) throw QuerySchemaValidationException(message())
}

@Suppress("CyclomaticComplexMethod")
internal fun RelativeTimeFilter.copyTemporal(
    field: QueryField,
    datePattern: String? = this.datePattern,
    timeUnit: TimeUnit = this.timeUnit,
): RelativeTimeFilter {
    if (field === this.field && datePattern == this.datePattern && timeUnit == this.timeUnit) return this
    return when (this) {
        is TodayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is BeforeTodayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is TomorrowFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastWeekFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is RecentDaysFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is EarlierDaysFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is YesterdayFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextMonthFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is LastYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is ThisYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is NextYearFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
    }
}
