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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.JavaType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor
import java.util.Date
import java.util.UUID

internal fun AggregationQuery.validateFieldTypes(namedAggregate: NamedAggregate) {
    if (groupBy.isEmpty() && metrics.none { it is AggregationMetric.Numeric }) {
        return
    }
    val stateType = namedAggregate.requiredAggregateType<Any>()
        .aggregateMetadata<Any, Any>()
        .state.aggregateType
    val snapshotType = JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, stateType)

    groupBy.forEach { group ->
        val fieldType = snapshotType.resolveField(group.field)
        require(fieldType.isAggregationScalar) { "Aggregation group field [${group.field}] must be scalar." }
        when (group) {
            is AggregationGroup.Terms -> Unit
            is AggregationGroup.Histogram -> require(fieldType.isNumeric) {
                "Histogram field [${group.field}] must be numeric."
            }

            is AggregationGroup.DateHistogram -> require(fieldType.isAggregationDate) {
                "Date histogram field [${group.field}] must be a date or epoch number."
            }
        }
    }
    metrics.filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
        require(snapshotType.resolveField(metric.field).isNumeric) {
            "Aggregation metric field [${metric.field}] must be numeric."
        }
    }
}

private fun JavaType.resolveField(path: String): JavaType {
    var current = this
    path.split('.').forEach { segment ->
        val property = current.toBeanDescription().findProperties()
            .firstOrNull { it.name == segment }
        requireNotNull(property) { "Aggregation field [$path] does not exist." }
        current = property.primaryType
    }
    return current
}

internal val JavaType.isAggregationScalar: Boolean
    get() = isNumeric || isAggregationDate || rawClass.isPrimitive || rawClass.isEnum ||
        CharSequence::class.java.isAssignableFrom(rawClass) ||
        rawClass == Boolean::class.javaObjectType || rawClass == Char::class.javaObjectType || rawClass == UUID::class.java

private val JavaType.isNumeric: Boolean
    get() = (rawClass.isPrimitive && rawClass != Boolean::class.javaPrimitiveType && rawClass != Char::class.javaPrimitiveType) ||
        Number::class.java.isAssignableFrom(rawClass)

internal val JavaType.isAggregationDate: Boolean
    get() = isNumeric || Date::class.java.isAssignableFrom(rawClass) ||
        rawClass in SUPPORTED_AGGREGATION_TEMPORAL_TYPES

private val SUPPORTED_AGGREGATION_TEMPORAL_TYPES: Set<Class<out TemporalAccessor>> = setOf(
    Instant::class.java,
    LocalDate::class.java,
    LocalDateTime::class.java,
    OffsetDateTime::class.java,
    ZonedDateTime::class.java,
)
