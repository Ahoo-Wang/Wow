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

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId

data class AggregationQuery(
    override val filter: FilterExpression = MatchAllFilter,
    @get:ArraySchema(maxItems = MAX_ELEMENTS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregationElement> = emptyList(),
    @get:ArraySchema(maxItems = MAX_GROUPS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregationGroup> = emptyList(),
    @get:ArraySchema(minItems = 1, maxItems = MAX_METRICS)
    val metrics: List<AggregationMetric>,
    @get:ArraySchema(maxItems = MAX_SORT_FIELDS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val sort: List<Sort> = emptyList(),
    @get:Schema(defaultValue = DEFAULT_LIMIT_TEXT, minimum = "1", maximum = MAX_LIMIT_TEXT)
    val limit: Int = DEFAULT_LIMIT,
) : FilterCapable<AggregationQuery>, SortCapable {
    init {
        require(elements.size <= MAX_ELEMENTS) { "elements must contain at most $MAX_ELEMENTS paths." }
        require(groupBy.size <= MAX_GROUPS) { "groupBy must contain at most $MAX_GROUPS dimensions." }
        require(metrics.isNotEmpty()) { "metrics must not be empty." }
        require(metrics.size <= MAX_METRICS) { "metrics must contain at most $MAX_METRICS entries." }
        require(sort.size <= MAX_SORT_FIELDS) { "sort must contain at most $MAX_SORT_FIELDS fields." }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT." }
        require(groupBy.isNotEmpty() || sort.isEmpty()) { "sort requires at least one groupBy." }

        val aliases = groupBy.map(AggregationGroup::alias) + metrics.map(AggregationMetric::alias)
        require(aliases.distinct().size == aliases.size) { "aggregation aliases must be unique." }
        val sortFields = sort.map(Sort::field)
        require(sortFields.distinct().size == sortFields.size) { "sort fields must be unique." }
        require(sortFields.all(aliases::contains)) { "sort fields must reference aggregation aliases." }
        require(effectiveSort().size <= MAX_SORT_FIELDS) {
            "effective sort must contain at most $MAX_SORT_FIELDS fields."
        }
    }

    override fun withFilter(newFilter: FilterExpression): AggregationQuery = copy(filter = newFilter)

    fun effectiveSort(): List<Sort> = buildList {
        addAll(sort)
        val sorted = sort.mapTo(hashSetOf(), Sort::field)
        groupBy.map(AggregationGroup::alias)
            .filterNot(sorted::contains)
            .forEach { add(Sort(it, Sort.Direction.ASC)) }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 10_000
        const val MAX_ELEMENTS: Int = 5
        const val MAX_GROUPS: Int = 32
        const val MAX_METRICS: Int = 64
        const val MAX_SORT_FIELDS: Int = 32
        private const val DEFAULT_LIMIT_TEXT = "100"
        private const val MAX_LIMIT_TEXT = "10000"

        @JvmStatic
        @JsonCreator
        internal fun fromJson(
            @JsonProperty("filter") filter: FilterExpression?,
            @JsonProperty("elements") elements: List<AggregationElement>?,
            @JsonProperty("groupBy") groupBy: List<AggregationGroup>?,
            @JsonProperty("metrics") metrics: List<AggregationMetric>?,
            @JsonProperty("sort") sort: List<Sort>?,
            @JsonProperty("limit") limit: Int?,
        ): AggregationQuery = AggregationQuery(
            filter = filter ?: MatchAllFilter,
            elements = elements.orEmpty(),
            groupBy = groupBy.orEmpty(),
            metrics = metrics.orEmpty(),
            sort = sort.orEmpty(),
            limit = limit ?: DEFAULT_LIMIT,
        )
    }
}

data class AggregationElement(
    val path: LogicalField,
    val filter: FilterExpression = MatchAllFilter,
) {
    init {
        require(filter.containsElementUnsupportedFilter().not()) {
            "Aggregation element filter cannot contain root filters."
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(AggregationGroup.Terms::class, name = "TERMS"),
    JsonSubTypes.Type(AggregationGroup.Histogram::class, name = "HISTOGRAM"),
    JsonSubTypes.Type(AggregationGroup.DateHistogram::class, name = "DATE_HISTOGRAM"),
)
sealed interface AggregationGroup {
    val field: LogicalField
    val alias: String

    data class Terms(
        override val field: LogicalField,
        override val alias: String,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
        }
    }

    data class Histogram(
        override val field: LogicalField,
        override val alias: String,
        @get:Schema(minimum = "0", exclusiveMinimum = true)
        val interval: Double,
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            require(interval.isFinite() && interval > 0.0) {
                "histogram interval must be finite and greater than 0."
            }
        }
    }

    data class DateHistogram(
        override val field: LogicalField,
        override val alias: String,
        val unit: AggregationDateUnit,
        val timeZone: String = "UTC",
    ) : AggregationGroup {
        init {
            requireAggregationAlias(alias)
            ZoneId.of(timeZone)
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

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type",
    defaultImpl = AggregationExpression.Field::class,
)
@JsonSubTypes(JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"))
interface AggregationExpression {
    data class Field(val field: LogicalField) : AggregationExpression
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(AggregationMetric.Numeric::class, name = "NUMERIC"),
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

private fun requireAggregationAlias(alias: String) {
    require('.' !in alias) { "aggregation alias must contain one segment." }
    LogicalField(alias)
}
