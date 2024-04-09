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
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
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
        attributes[QUERY_KEY] = query
        return this as SOURCE
    }

    fun getQuery(): Q {
        return checkNotNull(attributes[QUERY_KEY]) as Q
    }

    fun setResult(result: R): SOURCE {
        attributes[RESULT_KEY] = result
        return this as SOURCE
    }

    fun getRequiredResult(): R {
        return checkNotNull(attributes[RESULT_KEY]) as R
    }
}

enum class QueryType {
    SINGLE,
    QUERY,
    PAGED_QUERY,
    COUNT
}

class SingleSnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<SingleSnapshotQueryContext<S>, Condition, Mono<Snapshot<S>>> {
    override val queryType: QueryType
        get() = QueryType.SINGLE
}

class QuerySnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<QuerySnapshotQueryContext<S>, IQuery, Flux<Snapshot<S>>> {
    override val queryType: QueryType
        get() = QueryType.QUERY
}

class PagedSnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<PagedSnapshotQueryContext<S>, IPagedQuery, Mono<PagedList<Snapshot<S>>>> {
    override val queryType: QueryType
        get() = QueryType.PAGED_QUERY
}

class CountSnapshotQueryContext(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : SnapshotQueryContext<CountSnapshotQueryContext, Condition, Mono<Long>> {
    override val queryType: QueryType
        get() = QueryType.COUNT
}
