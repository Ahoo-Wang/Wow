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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryService<S : Any> {
    val namedAggregate: NamedAggregate
    fun single(condition: Condition): Mono<Snapshot<S>>
    fun query(query: IQuery): Flux<Snapshot<S>>
    fun pagedQuery(pagedQuery: IPagedQuery): Mono<PagedList<Snapshot<S>>>
    fun count(condition: Condition): Mono<Long>
}

class NoOpSnapshotQueryService<S : Any>(override val namedAggregate: NamedAggregate) : SnapshotQueryService<S> {

    override fun single(condition: Condition): Mono<Snapshot<S>> {
        return Mono.empty()
    }

    override fun query(query: IQuery): Flux<Snapshot<S>> {
        return Flux.empty()
    }

    override fun pagedQuery(pagedQuery: IPagedQuery): Mono<PagedList<Snapshot<S>>> {
        return Mono.just(PagedList.empty())
    }

    override fun count(condition: Condition): Mono<Long> {
        return Mono.just(0)
    }
}

interface SnapshotQueryServiceFactory {
    fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S>
}

object NoOpSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        return NoOpSnapshotQueryService(namedAggregate = namedAggregate.materialize())
    }
}
