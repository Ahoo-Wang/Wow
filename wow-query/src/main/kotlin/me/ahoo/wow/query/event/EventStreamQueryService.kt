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

package me.ahoo.wow.query.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.QueryService
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface EventStreamQueryService : QueryService<DomainEventStream>

class NoOpEventStreamQueryService(override val namedAggregate: NamedAggregate) : EventStreamQueryService {
    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> {
        return Mono.empty()
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return Mono.empty()
    }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> {
        return Flux.empty()
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return Flux.empty()
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> {
        return Mono.just(PagedList.empty())
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return Mono.just(PagedList.empty())
    }

    override fun count(condition: Condition): Mono<Long> {
        return Mono.just(0)
    }
}
