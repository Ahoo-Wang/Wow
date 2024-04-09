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

interface SnapshotQueryContext<Q : Any, R : Any> {
    val namedAggregate: NamedAggregate
    val queryType: QueryType
    var query: Q
    var result: R
}

enum class QueryType {
    SINGLE,
    QUERY,
    PAGED_QUERY,
    COUNT
}

class SingleSnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    @field:Volatile
    override var query: Condition,
    @field:Volatile
    override var result: Mono<Snapshot<S>> = Mono.empty()
) : SnapshotQueryContext<Condition, Mono<Snapshot<S>>> {
    override val queryType: QueryType
        get() = QueryType.SINGLE
}

class QuerySnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    @field:Volatile
    override var query: IQuery,
    @field:Volatile
    override var result: Flux<Snapshot<S>> = Flux.empty()
) : SnapshotQueryContext<IQuery, Flux<Snapshot<S>>> {
    override val queryType: QueryType
        get() = QueryType.QUERY
}

class PagedSnapshotQueryContext<S : Any>(
    override val namedAggregate: NamedAggregate,
    @field:Volatile
    override var query: IPagedQuery,
    @field:Volatile
    override var result: Mono<PagedList<Snapshot<S>>> = Mono.empty()
) : SnapshotQueryContext<IPagedQuery, Mono<PagedList<Snapshot<S>>>> {
    override val queryType: QueryType
        get() = QueryType.PAGED_QUERY
}

class CountSnapshotQueryContext(
    override val namedAggregate: NamedAggregate,
    @field:Volatile
    override var query: Condition,
    @field:Volatile
    override var result: Mono<Long> = Mono.empty()
) : SnapshotQueryContext<Condition, Mono<Long>> {
    override val queryType: QueryType
        get() = QueryType.COUNT
}
