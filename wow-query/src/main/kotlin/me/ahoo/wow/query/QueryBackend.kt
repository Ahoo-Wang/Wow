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

package me.ahoo.wow.query

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

data class ResolvedQuery<out Q : Any>(
    val query: Q,
    val schema: QueryModelSchema,
)

data class QueryBackendBinding<out B : QueryBackend>(
    val backend: B,
    val schemaProvider: QueryModelSchemaProvider,
)

/**
 * Aggregate-bound SPI for raw query results.
 *
 * Every subscription to a returned publisher, including subscriptions created by `retry`, `repeat`, or concurrent
 * callers, must own fresh mutable [ObjectNode] instances. Implementations must not cache or share nodes across
 * subscriptions, publish cached nodes, mutate emitted nodes asynchronously, or mutate them after delivery.
 *
 * Results must contain only standard JSON-tree values. Storage-driver `Map`/`Document` values, BSON values,
 * `POJONode`, and arbitrary POJOs must be normalized inside the Backend or rejected before crossing this boundary.
 */
interface QueryBackend : NamedAggregateDecorator {
    fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode>
    fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode>
    fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>>
    fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>>
    fun count(query: ResolvedQuery<FilterExpression>): Mono<Long>
    fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode>
}
