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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

const val QUERY_KEY = "__QUERY__"
const val RESULT_KEY = "__RESULT__"

@Suppress("UNCHECKED_CAST")
interface SnapshotQueryContext<SOURCE : SnapshotQueryContext<SOURCE, Q, R>, Q : Any, R : Any> {
    val attributes: MutableMap<String, Any>
    val namedAggregate: NamedAggregate
    val queryType: QueryType

    fun setQuery(query: Q): SOURCE {
        return setAttribute(QUERY_KEY, query)
    }

    fun getQuery(): Q {
        return checkNotNull(getAttribute<Q>(QUERY_KEY))
    }

    fun setResult(result: R): SOURCE {
        return setAttribute(RESULT_KEY, result)
    }

    fun getRequiredResult(): R {
        return checkNotNull(getAttribute<R>(RESULT_KEY))
    }

    fun setAttribute(key: String, value: Any): SOURCE {
        attributes[key] = value
        return this as SOURCE
    }

    fun <V> getAttribute(key: String): V? {
        return attributes[key] as V?
    }
}

enum class QueryType(val isDynamic: Boolean) {
    SINGLE(false),
    DYNAMIC_SINGLE(true),
    LIST(false),
    DYNAMIC_LIST(true),
    PAGED(false),
    DYNAMIC_PAGED(true),
    COUNT(false),
}

class SingleSnapshotQueryContext<R : Any>(
    override val namedAggregate: NamedAggregate,
    override val queryType: QueryType,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<SingleSnapshotQueryContext<R>, ISingleQuery, Mono<R>>

class ListSnapshotQueryContext<R : Any>(
    override val namedAggregate: NamedAggregate,
    override val queryType: QueryType,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<ListSnapshotQueryContext<R>, IListQuery, Flux<R>>

class PagedSnapshotQueryContext<R : Any>(
    override val namedAggregate: NamedAggregate,
    override val queryType: QueryType,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<PagedSnapshotQueryContext<R>, IPagedQuery, Mono<PagedList<R>>>

class CountSnapshotQueryContext(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<CountSnapshotQueryContext, Condition, Mono<Long>> {
    override val queryType: QueryType
        get() = QueryType.COUNT
}
