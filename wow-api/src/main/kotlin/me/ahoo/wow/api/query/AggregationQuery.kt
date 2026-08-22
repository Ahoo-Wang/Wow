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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId
import java.time.ZoneOffset

data class AggregationQuery(
    override val condition: Condition = Condition.ALL,
    @get:ArraySchema(maxItems = MAX_ELEMENTS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregationElement> = emptyList(),
    @get:ArraySchema(maxItems = MAX_GROUPS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregationGroup> = emptyList(),
    @get:ArraySchema(minItems = 1, maxItems = MAX_METRICS)
    val metrics: List<AggregationMetric>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val sort: List<Sort> = emptyList(),
    @get:Schema(defaultValue = DEFAULT_LIMIT_TEXT, minimum = "1", maximum = MAX_LIMIT_TEXT)
    val limit: Int = DEFAULT_LIMIT,
) : ConditionCapable<AggregationQuery>, SortCapable {
    init {
        require(elements.size <= MAX_ELEMENTS) { "elements must contain at most $MAX_ELEMENTS paths." }
        require(groupBy.size <= MAX_GROUPS) { "groupBy must contain at most $MAX_GROUPS dimensions." }
        require(metrics.isNotEmpty()) { "metrics must not be empty." }
        require(metrics.size <= MAX_METRICS) { "metrics must contain at most $MAX_METRICS entries." }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT." }
        require(groupBy.isNotEmpty() || sort.isEmpty()) { "sort requires at least one groupBy." }

        val aliases = groupBy.map(AggregationGroup::alias) + metrics.map(AggregationMetric::alias)
        require(aliases.distinct().size == aliases.size) { "aggregation aliases must be unique." }
        val sortFields = sort.map(Sort::field)
        require(sortFields.distinct().size == sortFields.size) { "sort fields must be unique." }
        require(sortFields.all(aliases::contains)) { "sort fields must reference aggregation aliases." }
    }

    override fun withCondition(newCondition: Condition): AggregationQuery = copy(condition = newCondition)

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 10_000
        const val MAX_ELEMENTS: Int = 5
        const val MAX_AGGREGATION_FIELD_DEPTH: Int = 10
        const val MAX_GROUPS: Int = 32
        const val MAX_METRICS: Int = 64
        const val INTERNAL_ALIAS_PREFIX: String = "__wow_"
        private const val DEFAULT_LIMIT_TEXT = "100"
        private const val MAX_LIMIT_TEXT = "10000"
    }
}

data class AggregationElement(
    val path: String,
    val condition: Condition = Condition.ALL,
) {
    init {
        requireAggregationField(path, "element path")
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregationGroup.Terms::class, name = "TERMS"),
    JsonSubTypes.Type(value = AggregationGroup.Histogram::class, name = "HISTOGRAM"),
    JsonSubTypes.Type(value = AggregationGroup.DateHistogram::class, name = "DATE_HISTOGRAM"),
)
sealed interface AggregationGroup {
    val field: String
    val alias: String

    data class Terms(
        override val field: String,
        override val alias: String,
    ) : AggregationGroup {
        init {
            requireAggregationField(field)
            requireAggregationAlias(alias)
        }
    }

    data class Histogram(
        override val field: String,
        override val alias: String,
        @get:Schema(minimum = "0", exclusiveMinimum = true)
        val interval: Double,
    ) : AggregationGroup {
        init {
            requireAggregationField(field)
            requireAggregationAlias(alias)
            require(interval.isFinite() && interval > 0.0) {
                "histogram interval must be finite and greater than 0."
            }
        }
    }

    data class DateHistogram(
        override val field: String,
        override val alias: String,
        val unit: AggregationDateUnit,
        val timeZone: String = "UTC",
    ) : AggregationGroup {
        init {
            requireAggregationField(field)
            requireAggregationAlias(alias)
            requireAggregationTimeZone(timeZone)
        }
    }
}

enum class AggregationDateUnit {
    YEAR,
    QUARTER,
    MONTH,
    WEEK,
    DAY,
    HOUR,
    MINUTE,
    SECOND,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregationExpression.Field::class, name = "FIELD"),
)
sealed interface AggregationExpression {
    data class Field(val field: String) : AggregationExpression {
        init {
            requireAggregationField(field, "expression field")
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(value = AggregationMetric.Numeric::class, name = "NUMERIC"),
)
sealed interface AggregationMetric {
    val alias: String

    data class Count(override val alias: String) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Numeric(
        val function: AggregationFunction,
        val expression: AggregationExpression,
        override val alias: String,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }
}

enum class AggregationFunction {
    SUM,
    AVG,
    MIN,
    MAX,
}

private fun requireAggregationField(field: String, name: String = "aggregation field") {
    require(field.isNotBlank()) { "$name must not be blank." }
    require(field.split('.').size <= AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH) {
        "$name [$field] must contain at most ${AggregationQuery.MAX_AGGREGATION_FIELD_DEPTH} segments."
    }
}

private fun requireAggregationAlias(alias: String) {
    require(alias.isNotBlank()) { "aggregation alias must not be blank." }
    require('.' !in alias) { "aggregation alias must not contain '.'." }
    require('\u0000' !in alias) { "aggregation alias must not contain NUL." }
    require(!alias.startsWith('$')) { "aggregation alias must not start with '$'." }
    require(!alias.startsWith(AggregationQuery.INTERNAL_ALIAS_PREFIX)) {
        "aggregation alias must not start with reserved prefix [${AggregationQuery.INTERNAL_ALIAS_PREFIX}]."
    }
    require(alias != "_id") { "aggregation alias [_id] is reserved." }
}

private val PORTABLE_TIME_ZONE_IDS: Set<String> = ZoneId.getAvailableZoneIds()
private val PORTABLE_TIME_ZONE_OFFSET = Regex("[+-](?:0\\d|1\\d):[0-5]\\d")

private fun requireAggregationTimeZone(timeZone: String) {
    require(timeZone.isNotBlank()) { "date histogram timeZone must not be blank." }
    val isPortableOffset = PORTABLE_TIME_ZONE_OFFSET.matches(timeZone) &&
        runCatching { ZoneOffset.of(timeZone) }.isSuccess
    require(timeZone in PORTABLE_TIME_ZONE_IDS || isPortableOffset) {
        "Invalid date histogram timeZone [$timeZone]: use an IANA identifier or an offset in [+/-]HH:MM form."
    }
}
