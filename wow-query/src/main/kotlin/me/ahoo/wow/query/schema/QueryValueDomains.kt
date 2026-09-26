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

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.BeforeNowFilter
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

/** Flattens nested unions into their non-union alternatives; any other value is its own single alternative. */
fun QueryValueSchema.alternativesOrSelf(): List<QueryValueSchema> =
    if (kind == QueryValueKind.UNION) alternatives.flatMap { it.alternativesOrSelf() } else listOf(this)

/**
 * How this value stores time: the one temporal semantic type every non-null operation value shares, or `null` when
 * they declare none, or different ones.
 */
internal fun QueryValueSchema.sharedTemporal(): Temporal? = operationValues().filter { it.kind != QueryValueKind.NULL }
    .map { it.semanticType as? Temporal }.distinct().singleOrNull()

/** Whether this encoding holds instants date groups and date differences can read: a date or an epoch. */
internal val Temporal?.encodesInstant: Boolean
    get() = this == Temporal.Date || this is Temporal.Epoch

/** Whether any alternative of this value is an array, so storage may flatten it into multiple values. */
fun QueryValueSchema.hasArrayBranch(): Boolean = alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }

internal fun QueryValueSchema.accepts(values: Iterable<JsonNode>): Boolean {
    val domains = if (kind == QueryValueKind.SCALAR || kind == QueryValueKind.OBJECT) {
        listOf(this)
    } else {
        operationValues().filter { it.kind != QueryValueKind.NULL && it.kind != QueryValueKind.UNKNOWN }
    }
    return values.all { input ->
        if (input.isNull) return@all true
        // A runtime POJO is compared as the JSON it serializes to, converted once for every domain and type.
        val canonical = input.canonicalValue() ?: return@all false
        domains.any { domain -> domain.valueTypes.any { canonical.matches(it) } }
    }
}

/** This value as JSON: a runtime POJO serialized to its tree, or `null` when it serializes to a POJO again. */
private fun JsonNode.canonicalValue(): JsonNode? {
    if (!isPojo) return this
    return JsonSerializer.valueToTree<JsonNode>((this as POJONode).pojo).takeUnless { it.isPojo }
}

private fun JsonNode.matches(type: QueryValueType): Boolean = when (type) {
    QueryValueType.STRING -> isString
    QueryValueType.INTEGER -> isNumber && canConvertToExactIntegral()
    QueryValueType.DECIMAL -> isNumber
    QueryValueType.BOOLEAN -> isBoolean
    QueryValueType.OBJECT -> isObject
    else -> true
}

/**
 * The encoding this relative-time filter resolves against: the field's [temporal] encoding (its compiled
 * [QueryFieldCapabilities.temporal]), which the filter's own configuration must not contradict.
 */
internal fun RelativeTimeFilter.temporal(temporal: Temporal?, logical: QueryField = field): Temporal {
    requireValid(dateFormatter == null && temporal != null) { QueryViolation.TemporalRepresentationRequired(logical) }
    requireValid(
        when (temporal) {
            is Temporal.Epoch, Temporal.Date -> datePattern == null
            is Temporal.Formatted -> datePattern == null || datePattern == temporal.pattern
            null -> false
        },
    ) { QueryViolation.TemporalConfigurationConflict(logical) }
    return checkNotNull(temporal)
}

/** This filter encoding its window as a field with [temporal] stores time. */
internal fun RelativeTimeFilter.withTemporal(temporal: Temporal): RelativeTimeFilter = when (temporal) {
    is Temporal.Epoch -> copyTemporal(field, timeUnit = temporal.timeUnit)
    is Temporal.Formatted -> copyTemporal(field, datePattern = temporal.pattern)
    Temporal.Date -> this
}

@Suppress("CyclomaticComplexMethod")
private fun RelativeTimeFilter.copyTemporal(
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
        is BeforeNowFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
        is AfterNowFilter -> copy(field = field, datePattern = datePattern, timeUnit = timeUnit)
    }
}
