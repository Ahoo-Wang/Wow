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

package me.ahoo.wow.schema.typed.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.schema.typed.SnapshotAggregationElements
import me.ahoo.wow.schema.typed.SnapshotAggregationFields

data class AggregatedAggregationQuery<CommandAggregateType : Any>(
    val condition: AggregatedCondition<CommandAggregateType> = AggregatedCondition(),
    @get:ArraySchema(maxItems = AggregationQuery.MAX_ELEMENTS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregatedAggregationElement<CommandAggregateType>> = emptyList(),
    @get:ArraySchema(maxItems = AggregationQuery.MAX_GROUPS)
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregatedAggregationGroup<CommandAggregateType>> = emptyList(),
    @get:ArraySchema(minItems = 1, maxItems = AggregationQuery.MAX_METRICS)
    val metrics: List<AggregatedAggregationMetric<CommandAggregateType>>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val sort: List<Sort> = emptyList(),
    @get:Schema(defaultValue = "100", minimum = "1", maximum = "10000")
    val limit: Int = AggregationQuery.DEFAULT_LIMIT,
)

data class AggregatedAggregationElement<CommandAggregateType : Any>(
    val path: SnapshotAggregationElements<CommandAggregateType>,
    val condition: AggregatedCondition<CommandAggregateType> = AggregatedCondition(),
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregatedAggregationGroup.Terms::class, name = "TERMS"),
    JsonSubTypes.Type(value = AggregatedAggregationGroup.Histogram::class, name = "HISTOGRAM"),
    JsonSubTypes.Type(value = AggregatedAggregationGroup.DateHistogram::class, name = "DATE_HISTOGRAM"),
)
sealed interface AggregatedAggregationGroup<CommandAggregateType : Any> {
    val field: SnapshotAggregationFields<CommandAggregateType>
    val alias: String

    data class Terms<CommandAggregateType : Any>(
        override val field: SnapshotAggregationFields<CommandAggregateType>,
        override val alias: String,
    ) : AggregatedAggregationGroup<CommandAggregateType>

    data class Histogram<CommandAggregateType : Any>(
        override val field: SnapshotAggregationFields<CommandAggregateType>,
        override val alias: String,
        @get:Schema(minimum = "0", exclusiveMinimum = true)
        val interval: Double,
    ) : AggregatedAggregationGroup<CommandAggregateType>

    data class DateHistogram<CommandAggregateType : Any>(
        override val field: SnapshotAggregationFields<CommandAggregateType>,
        override val alias: String,
        val unit: AggregationDateUnit,
        val timeZone: String = "UTC",
    ) : AggregatedAggregationGroup<CommandAggregateType>
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregatedAggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(value = AggregatedAggregationMetric.Numeric::class, name = "NUMERIC"),
)
sealed interface AggregatedAggregationMetric<CommandAggregateType : Any> {
    val alias: String

    data class Count<CommandAggregateType : Any>(
        override val alias: String,
    ) : AggregatedAggregationMetric<CommandAggregateType>

    data class Numeric<CommandAggregateType : Any>(
        val function: AggregationFunction,
        val expression: AggregatedAggregationExpression<CommandAggregateType>,
        override val alias: String,
    ) : AggregatedAggregationMetric<CommandAggregateType>
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AggregatedAggregationExpression.Field::class, name = "FIELD"),
)
sealed interface AggregatedAggregationExpression<CommandAggregateType : Any> {
    data class Field<CommandAggregateType : Any>(
        val field: SnapshotAggregationFields<CommandAggregateType>,
    ) : AggregatedAggregationExpression<CommandAggregateType>
}
