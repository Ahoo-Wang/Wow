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
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SortCapable
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule

/**
 * The JSON codec of the query protocol (`:query-api`), the wire 9.x writes: the model is plain data, so this module
 * gives it its names, discriminators and inclusions.
 *
 * - A filter is an object whose `op` names its operator; one without `op` (a 9.x `Condition`) is rejected, and EQ
 *   and NE read only a scalar value. Filter values read as plain JSON values: a scalar, a `List` or a `Map`, numbers as
 *   [WowJson] reads them.
 * - The aggregation hierarchies are discriminated by `type`; an expression without one is a FIELD.
 * - Defaults are written by omission where 9.x omitted them: empty `sort`, projection lists, `elements`, `groupBy`
 *   and window `within`; null `having`, `cursor`, group inputs and scopes, `orderBy`, `frame` and `period`; a false
 *   `dense`; a metric filter that matches everything.
 * - [PagedList] writes `total` (`null` when uncounted), `exact`, then `list`.
 *
 * It is the one copy of the protocol's format (decision D293): the query engine, the HTTP edge and `wow-client` read
 * and write it; the query engine adds the capability descriptor's JSON to it (`me.ahoo.wow.query.json.QueryJson`).
 */
@InternalWowApi
object QueryProtocolJson {
    /** The Jackson module of the query protocol, to register into a WowJson builder: `WowJson.builder().addModule(module)`. */
    val module: JacksonModule = QueryJsonModule()

    /** `WowJson.builder().addModule(module).build()` */
    val mapper: JsonMapper = WowJson.builder().addModule(module).build()
}

private const val MODULE_NAME = "wow-query-protocol-json"

private class QueryJsonModule : SimpleModule(MODULE_NAME) {
    init {
        setMixInAnnotation(QueryField::class.java, QueryFieldMixin::class.java)
        setMixInAnnotation(FilterExpression::class.java, FilterExpressionMixin::class.java)
        setMixInAnnotation(DeletionFilter::class.java, DeletionFilterMixin::class.java)
        setMixInAnnotation(RelativeTimeFilter::class.java, RelativeTimeFilterMixin::class.java)
        setMixInAnnotation(BeforeNowFilter::class.java, RelativeNowFilterMixin::class.java)
        setMixInAnnotation(AfterNowFilter::class.java, RelativeNowFilterMixin::class.java)
        registerSubtypes(*filterSubtypes().toTypedArray())

        setMixInAnnotation(SortCapable::class.java, SortCapableMixin::class.java)
        setMixInAnnotation(Projection::class.java, ProjectionMixin::class.java)
        setMixInAnnotation(CursorQuery::class.java, CursorQueryMixin::class.java)
        setMixInAnnotation(PagedList::class.java, PagedListMixin::class.java)

        setMixInAnnotation(AggregationQuery::class.java, AggregationQueryMixin::class.java)
        setMixInAnnotation(AggregationGroup::class.java, AggregationGroupMixin::class.java)
        setMixInAnnotation(AggregationGroup.Terms::class.java, TermsMixin::class.java)
        setMixInAnnotation(AggregationGroup.Histogram::class.java, GroupExpressionMixin::class.java)
        setMixInAnnotation(AggregationGroup.DateHistogram::class.java, DenseGroupMixin::class.java)
        setMixInAnnotation(AggregationGroup.DatePart::class.java, DenseGroupMixin::class.java)
        setMixInAnnotation(AggregationExpression::class.java, AggregationExpressionMixin::class.java)
        setMixInAnnotation(AggregationMetric::class.java, AggregationMetricMixin::class.java)
        setMixInAnnotation(AggregationMetric.Edge::class.java, EdgeMetricMixin::class.java)
        setMixInAnnotation(DerivedExpression::class.java, DerivedExpressionMixin::class.java)
        setMixInAnnotation(DerivedExpression.Total::class.java, WindowWithinMixin::class.java)
        setMixInAnnotation(DerivedExpression.Running::class.java, RunningWindowMixin::class.java)
        setMixInAnnotation(DerivedExpression.Previous::class.java, PreviousWindowMixin::class.java)
        setMixInAnnotation(DerivedExpression.Rank::class.java, WindowWithinMixin::class.java)
        setMixInAnnotation(HavingExpression::class.java, HavingExpressionMixin::class.java)
        registerSubtypes(*aggregationSubtypes.toTypedArray())
    }

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addHandler(MissingExpressionTypeHandler)
    }
}

internal interface SortCapableMixin {
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val sort: List<Sort>
}

internal interface ProjectionMixin {
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val include: List<QueryField>

    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val exclude: List<QueryField>
}

internal interface CursorQueryMixin {
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val cursor: String?
}

/** `total` first, as 9.x wrote it, then `exact` beside it. */
@JsonPropertyOrder("total", "exact", "list")
internal interface PagedListMixin
