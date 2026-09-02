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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.schema.QueryModelSchema
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentHashMap

const val QUERY_KEY = "__QUERY__"
const val RESULT_KEY = "__RESULT__"

@Suppress("UNCHECKED_CAST")
interface QueryContext<Q : Any, R : Any> {
    val queryType: QueryType
    val schema: QueryModelSchema
    val attributes: MutableMap<String, Any>
    val namedAggregate: NamedAggregate
    fun setQuery(query: Q): QueryContext<Q, R> {
        return setAttribute(QUERY_KEY, query)
    }

    fun getQuery(): Q {
        return checkNotNull(getAttribute<Q>(QUERY_KEY))
    }

    fun rewriteQuery(rewrite: (Q) -> Q): QueryContext<Q, R> {
        return setQuery(rewrite(getQuery()))
    }

    fun appendFilter(append: FilterExpression): QueryContext<Q, R> {
        val rewritten = when (val query = getQuery()) {
            is RewritableFilter<*> -> query.appendFilter(append)
            else -> error("Query type [${query::class}] does not support filters.")
        }
        return setQuery(rewritten as Q)
    }

    fun setResult(result: R): QueryContext<Q, R> {
        return setAttribute(RESULT_KEY, result)
    }

    fun setResult(handle: (Q) -> R): QueryContext<Q, R> {
        return setResult(handle(getQuery()))
    }

    fun getRequiredResult(): R {
        return checkNotNull(getAttribute<R>(RESULT_KEY))
    }

    fun rewriteResult(rewrite: (R) -> R): QueryContext<Q, R> {
        return setResult(rewrite(getRequiredResult()))
    }

    fun setAttribute(key: String, value: Any): QueryContext<Q, R> {
        attributes[key] = value
        return this
    }

    fun <V> getAttribute(key: String): V? {
        return attributes[key] as V?
    }

    fun asSingleQuery(): QueryContext<ISingleQuery, Mono<ObjectNode>> {
        return this as QueryContext<ISingleQuery, Mono<ObjectNode>>
    }

    fun asListQuery(): QueryContext<IListQuery, Flux<ObjectNode>> {
        return this as QueryContext<IListQuery, Flux<ObjectNode>>
    }

    fun asPagedQuery(): QueryContext<IPagedQuery, Mono<PagedList<ObjectNode>>> {
        return this as QueryContext<IPagedQuery, Mono<PagedList<ObjectNode>>>
    }

    fun asCursorQuery(): QueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>> {
        return this as QueryContext<ICursorQuery, Mono<CursorPage<ObjectNode>>>
    }

    fun asCountQuery(): QueryContext<FilterExpression, Mono<Long>> {
        return this as QueryContext<FilterExpression, Mono<Long>>
    }

    fun asAggregationQuery(): QueryContext<AggregationQuery, Flux<ObjectNode>> {
        return this as QueryContext<AggregationQuery, Flux<ObjectNode>>
    }
}

class DefaultQueryContext<Q : Any, R : Any>(
    override val queryType: QueryType,
    override val namedAggregate: NamedAggregate,
    override val schema: QueryModelSchema,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : QueryContext<Q, R>
