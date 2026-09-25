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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.query.AfterNowFilter
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BeforeNowFilter
import me.ahoo.wow.api.query.BeforeTodayFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.EarlierDaysFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LastMonthFilter
import me.ahoo.wow.api.query.LastWeekFilter
import me.ahoo.wow.api.query.LastYearFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NextMonthFilter
import me.ahoo.wow.api.query.NextWeekFilter
import me.ahoo.wow.api.query.NextYearFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RecentDaysFilter
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.ThisMonthFilter
import me.ahoo.wow.api.query.ThisWeekFilter
import me.ahoo.wow.api.query.ThisYearFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.TomorrowFilter
import me.ahoo.wow.api.query.YesterdayFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.spec.spec

/**
 * The field a predicate names, or `null` for filters that name none (logical, system-field, match-all/none and
 * search filters, whose fields are a set). Pairs with [me.ahoo.wow.api.query.spec.FilterOperatorSpec]: the spec
 * says what the operator needs, this says where the node keeps its field.
 */
@Suppress("CyclomaticComplexMethod")
fun FilterExpression.predicateField(): QueryField? = when (this) {
    is EqualFilter -> field
    is NotEqualFilter -> field
    is GreaterThanFilter -> field
    is GreaterThanOrEqualFilter -> field
    is LessThanFilter -> field
    is LessThanOrEqualFilter -> field
    is ContainsFilter -> field
    is StartsWithFilter -> field
    is EndsWithFilter -> field
    is InFilter -> field
    is NotInFilter -> field
    is BetweenFilter -> field
    is ContainsAllFilter -> field
    is IsEmptyFilter -> field
    is IsEmptyStringFilter -> field
    is IsNotEmptyStringFilter -> field
    is IsNullFilter -> field
    is IsNotNullFilter -> field
    is ExistsFilter -> field
    is NotExistsFilter -> field
    is ElementMatchFilter -> field
    is RelativeTimeFilter -> field
    MatchAllFilter,
    MatchNoneFilter,
    is AndFilter,
    is OrFilter,
    is NorFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is DeletionFilter,
    is SearchFilter,
    is ExpressionFilter,
    -> null
}

/** The field capability this node's operator requires, per its [me.ahoo.wow.api.query.spec.FilterOperatorSpec]. */
fun FilterExpression.requiredCapability(): QueryCapability =
    checkNotNull(spec.requiredCapability(this)) { "Filter [$operator] requires no field capability." }

/**
 * This predicate naming [field] instead of its [predicateField]; filters that name no field are returned unchanged.
 * The inverse of [predicateField], used to rewrite a query's field references.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun FilterExpression.withPredicateField(field: QueryField): FilterExpression = when (this) {
    is EqualFilter -> copy(field = field)
    is NotEqualFilter -> copy(field = field)
    is GreaterThanFilter -> copy(field = field)
    is GreaterThanOrEqualFilter -> copy(field = field)
    is LessThanFilter -> copy(field = field)
    is LessThanOrEqualFilter -> copy(field = field)
    is ContainsFilter -> copy(field = field)
    is StartsWithFilter -> copy(field = field)
    is EndsWithFilter -> copy(field = field)
    is InFilter -> copy(field = field)
    is NotInFilter -> copy(field = field)
    is BetweenFilter -> copy(field = field)
    is ContainsAllFilter -> copy(field = field)
    is IsEmptyFilter -> copy(field = field)
    is IsEmptyStringFilter -> copy(field = field)
    is IsNotEmptyStringFilter -> copy(field = field)
    is IsNullFilter -> copy(field = field)
    is IsNotNullFilter -> copy(field = field)
    is ExistsFilter -> copy(field = field)
    is NotExistsFilter -> copy(field = field)
    is ElementMatchFilter -> copy(field = field)
    is TodayFilter -> copy(field = field)
    is BeforeTodayFilter -> copy(field = field)
    is TomorrowFilter -> copy(field = field)
    is ThisWeekFilter -> copy(field = field)
    is NextWeekFilter -> copy(field = field)
    is LastWeekFilter -> copy(field = field)
    is ThisMonthFilter -> copy(field = field)
    is LastMonthFilter -> copy(field = field)
    is RecentDaysFilter -> copy(field = field)
    is EarlierDaysFilter -> copy(field = field)
    is YesterdayFilter -> copy(field = field)
    is NextMonthFilter -> copy(field = field)
    is LastYearFilter -> copy(field = field)
    is ThisYearFilter -> copy(field = field)
    is NextYearFilter -> copy(field = field)
    is BeforeNowFilter -> copy(field = field)
    is AfterNowFilter -> copy(field = field)
    MatchAllFilter,
    MatchNoneFilter,
    is AndFilter,
    is OrFilter,
    is NorFilter,
    is IdFilter,
    is IdsFilter,
    is AggregateIdFilter,
    is AggregateIdsFilter,
    is TenantIdFilter,
    is OwnerIdFilter,
    is SpaceIdFilter,
    is DeletionFilter,
    is SearchFilter,
    is ExpressionFilter,
    -> this
}
