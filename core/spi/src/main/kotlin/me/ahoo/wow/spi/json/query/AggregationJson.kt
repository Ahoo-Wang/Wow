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

package me.ahoo.wow.spi.json.query

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.deser.DeserializationProblemHandler
import tools.jackson.databind.jsontype.NamedType
import tools.jackson.databind.jsontype.TypeIdResolver

private const val TYPE = "type"

/** The aggregation subtypes under their `type` names. */
internal val aggregationSubtypes: List<NamedType> = listOf(
    NamedType(AggregationGroup.Terms::class.java, "TERMS"),
    NamedType(AggregationGroup.Histogram::class.java, "HISTOGRAM"),
    NamedType(AggregationGroup.DateHistogram::class.java, "DATE_HISTOGRAM"),
    NamedType(AggregationGroup.DatePart::class.java, "DATE_PART"),
    NamedType(AggregationExpression.Field::class.java, "FIELD"),
    NamedType(AggregationExpression.Constant::class.java, "CONSTANT"),
    NamedType(AggregationExpression.Binary::class.java, "BINARY"),
    NamedType(AggregationExpression.DateDiff::class.java, "DATE_DIFF"),
    NamedType(AggregationMetric.Count::class.java, "COUNT"),
    NamedType(AggregationMetric.Numeric::class.java, "NUMERIC"),
    NamedType(AggregationMetric.Any::class.java, "ANY"),
    NamedType(AggregationMetric.DistinctCount::class.java, "DISTINCT_COUNT"),
    NamedType(AggregationMetric.Percentile::class.java, "PERCENTILE"),
    NamedType(AggregationMetric.Derived::class.java, "DERIVED"),
    NamedType(AggregationMetric.First::class.java, "FIRST"),
    NamedType(AggregationMetric.Last::class.java, "LAST"),
    NamedType(DerivedExpression.MetricRef::class.java, "METRIC_REF"),
    NamedType(DerivedExpression.Constant::class.java, "CONSTANT"),
    NamedType(DerivedExpression.Binary::class.java, "BINARY"),
    NamedType(DerivedExpression.Total::class.java, "TOTAL"),
    NamedType(DerivedExpression.Running::class.java, "RUNNING"),
    NamedType(DerivedExpression.Previous::class.java, "PREVIOUS"),
    NamedType(DerivedExpression.Rank::class.java, "RANK"),
    NamedType(HavingExpression.Condition::class.java, "CONDITION"),
    NamedType(HavingExpression.Between::class.java, "BETWEEN"),
    NamedType(HavingExpression.In::class.java, "IN"),
    NamedType(HavingExpression.IsNull::class.java, "IS_NULL"),
    NamedType(HavingExpression.And::class.java, "AND"),
    NamedType(HavingExpression.Or::class.java, "OR"),
)

internal interface AggregationQueryMixin {
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val elements: List<AggregationElement>

    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val groupBy: List<AggregationGroup>

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val having: HavingExpression?
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = TYPE)
internal interface AggregationGroupMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val field: QueryField?

    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val scope: AggregationGroupScope?
}

/** The computed input of a TERMS or HISTOGRAM group, written only in place of its field. */
internal interface GroupExpressionMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val expression: AggregationExpression?
}

internal interface TermsMixin : GroupExpressionMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val missingKey: String?
}

/** `dense` of DATE_HISTOGRAM and DATE_PART, written only when set. */
internal interface DenseGroupMixin {
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val dense: Boolean
}

/** An expression without `type` is a FIELD; see [MissingExpressionTypeHandler]. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = TYPE)
internal interface AggregationExpressionMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = TYPE)
internal interface AggregationMetricMixin {
    /** A metric filter that matches every record is the default, written by omission. */
    @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterExclusion::class)
    val filter: FilterExpression
}

internal interface EdgeMetricMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val orderBy: QueryField?
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = TYPE)
internal interface DerivedExpressionMixin

/** The partition groups of a window, written only when it names some. */
internal interface WindowWithinMixin {
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val within: List<String>
}

internal interface RunningWindowMixin : WindowWithinMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val frame: Int?
}

internal interface PreviousWindowMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val period: AggregationDateUnit?
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = TYPE)
internal interface HavingExpressionMixin

/** The value filter of [JsonInclude.Include.CUSTOM]: a value equal to it is not written. */
internal class MatchAllFilterExclusion {
    override fun equals(other: Any?): Boolean = other === MatchAllFilter

    override fun hashCode(): Int = MatchAllFilter.hashCode()
}

/** Reads an [AggregationExpression] without a `type` as a FIELD, as 9.x's `@MissingTypeImpl` did. */
internal object MissingExpressionTypeHandler : DeserializationProblemHandler() {
    override fun handleMissingTypeId(
        ctxt: DeserializationContext,
        baseType: JavaType,
        idResolver: TypeIdResolver,
        failureMsg: String,
    ): JavaType? = if (baseType.rawClass == AggregationExpression::class.java) {
        ctxt.constructSpecializedType(baseType, AggregationExpression.Field::class.java)
    } else {
        null
    }
}
