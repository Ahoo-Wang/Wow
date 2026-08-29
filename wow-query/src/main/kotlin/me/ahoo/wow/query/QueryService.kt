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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.query

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.toFilterExpression
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Interface for performing various query operations on an aggregate.
 *
 * This service provides methods to execute single, list, paged, aggregation, and count queries,
 * with support for both typed and dynamic (untyped) results.
 *
*/
interface QueryService<R : Any> : NamedAggregateDecorator {
    fun single(singleQuery: ISingleQuery): Mono<R>
    fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument>
    fun list(listQuery: IListQuery): Flux<R>
    fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument>
    fun paged(pagedQuery: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>>

    fun cursor(query: ICursorQuery): Mono<CursorPage<R>> =
        Mono.error(UnsupportedOperationException("Cursor query is not supported."))

    fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<DynamicDocument>> =
        Mono.error(UnsupportedOperationException("Cursor query is not supported."))

    fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
        Flux.error(UnsupportedOperationException("Aggregation is not supported."))

    fun count(filter: FilterExpression): Mono<Long>

    @Deprecated("Use count(FilterExpression).")
    fun count(condition: Condition): Mono<Long> =
        count(condition.toFilterExpression())
}
