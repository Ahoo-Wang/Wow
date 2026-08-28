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
import me.ahoo.wow.api.serialization.MissingTypeImpl
import java.time.ZoneId

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class AggregationQuery(
    override val filter: FilterExpression = MatchAllFilter,
    @get:ArraySchema(maxItems = MAX_ELEMENTS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregationElement> = emptyList(),
    @get:ArraySchema(maxItems = MAX_GROUPS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregationGroup> = emptyList(),
    @get:ArraySchema(minItems = 1, maxItems = MAX_METRICS, schema = Schema(implementation = AggregationMetric::class))
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
        metrics.requireValidExpressions()

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
        const val MAX_EXPRESSION_DEPTH: Int = 8
        const val MAX_EXPRESSION_NODES: Int = 256
        private const val DEFAULT_LIMIT_TEXT = "100"
        private const val MAX_LIMIT_TEXT = "10000"
    }
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
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

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
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

@MissingTypeImpl(AggregationExpression.Field::class)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"),
    JsonSubTypes.Type(AggregationExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(AggregationExpression.Binary::class, name = "BINARY"),
)
@Schema(
    oneOf = [
        AggregationExpression.Field::class,
        AggregationExpression.Constant::class,
        AggregationExpression.Binary::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
interface AggregationExpression {
    data class Field(val field: LogicalField) : AggregationExpression

    data class Constant(val value: Double) : AggregationExpression {
        init {
            require(value.isFinite()) { "aggregation constant must be finite." }
        }
    }

    data class Binary(
        val operator: AggregationExpressionOperator,
        val left: AggregationExpression,
        val right: AggregationExpression,
    ) : AggregationExpression
}

enum class AggregationExpressionOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(AggregationMetric.Numeric::class, name = "NUMERIC"),
    JsonSubTypes.Type(AggregationMetric.Any::class, name = "ANY"),
)
@Schema(
    oneOf = [
        AggregationMetric.Count::class,
        AggregationMetric.Numeric::class,
        AggregationMetric.Any::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
sealed interface AggregationMetric {
    @get:Schema(accessMode = Schema.AccessMode.READ_WRITE)
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

    data class Any(
        val field: LogicalField,
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
    require(!alias.startsWith("__wow")) { "aggregation alias must not use the reserved __wow prefix." }
    LogicalField(alias)
}

private data class PendingExpression(
    val expression: AggregationExpression,
    val depth: Int,
)

private fun List<AggregationMetric>.requireValidExpressions() {
    val pending = ArrayDeque<PendingExpression>()
    filterIsInstance<AggregationMetric.Numeric>().forEach { metric ->
        pending.addLast(PendingExpression(metric.expression, 1))
    }
    var nodes = 0
    while (pending.isNotEmpty()) {
        val (expression, depth) = pending.removeLast()
        require(depth <= AggregationQuery.MAX_EXPRESSION_DEPTH) {
            "aggregation expression depth must be at most ${AggregationQuery.MAX_EXPRESSION_DEPTH}."
        }
        nodes++
        require(nodes <= AggregationQuery.MAX_EXPRESSION_NODES) {
            "aggregation expressions must contain at most ${AggregationQuery.MAX_EXPRESSION_NODES} nodes."
        }
        when (expression) {
            is AggregationExpression.Field,
            is AggregationExpression.Constant,
            -> Unit

            is AggregationExpression.Binary -> {
                pending.addLast(PendingExpression(expression.left, depth + 1))
                pending.addLast(PendingExpression(expression.right, depth + 1))
            }

            else -> throw IllegalArgumentException(
                "Unsupported aggregation expression: ${expression::class.java.name}.",
            )
        }
    }
}
