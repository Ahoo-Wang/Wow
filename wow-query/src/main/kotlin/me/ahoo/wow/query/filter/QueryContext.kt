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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.RewritableCondition
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

const val QUERY_KEY = "__QUERY__"
const val RESULT_KEY = "__RESULT__"

@Suppress("UNCHECKED_CAST")
interface QueryContext<Q : Any, R : Any> {
    val queryType: QueryType
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

    fun <E : Any> asSingleQuery(): QueryContext<ISingleQuery, Mono<E>> {
        return this as QueryContext<ISingleQuery, Mono<E>>
    }

    fun <E : Any> asListQuery(): QueryContext<IListQuery, Flux<E>> {
        return this as QueryContext<IListQuery, Flux<E>>
    }

    fun <E : Any> asPagedQuery(): QueryContext<IPagedQuery, Mono<PagedList<E>>> {
        return this as QueryContext<IPagedQuery, Mono<PagedList<E>>>
    }

    fun asRewritableQuery(): QueryContext<RewritableCondition<*>, R> {
        return this as QueryContext<RewritableCondition<*>, R>
    }

    fun asCountQuery(): QueryContext<Condition, Mono<Long>> {
        return this as QueryContext<Condition, Mono<Long>>
    }
}

class DefaultQueryContext<Q : Any, R : Any>(
    override val queryType: QueryType,
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : QueryContext<Q, R>
