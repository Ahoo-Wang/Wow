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
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.compat.LegacyEventResultMapper
import me.ahoo.wow.query.compat.LegacyQueryRequestMapper
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class GatewayEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val queryGateway: QueryGateway
) : EventStreamQueryService {
    private val requestMapper = LegacyQueryRequestMapper.create(
        QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
    )
    private val resultMapper = LegacyEventResultMapper.create()

    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> = Mono.defer {
        queryGateway.single(requestMapper.single(singleQuery)).map(resultMapper::map)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.defer {
        queryGateway.single(requestMapper.single(singleQuery))
    }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> = Flux.defer {
        queryGateway.list(requestMapper.list(listQuery)).map(resultMapper::map)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.defer {
        queryGateway.list(requestMapper.list(listQuery))
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> = Mono.defer {
        queryGateway.page(requestMapper.page(pagedQuery)).map { page ->
            PagedList(page.total, page.items.map(resultMapper::map))
        }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> = Mono.defer {
        queryGateway.page(requestMapper.page(pagedQuery)).map { page -> PagedList(page.total, page.items) }
    }

    override fun count(condition: Condition): Mono<Long> = Mono.defer {
        queryGateway.count(requestMapper.count(condition))
    }
}

class GatewayEventStreamQueryServiceFactory(
    private val queryGateway: QueryGateway
) : AbstractEventStreamQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService =
        GatewayEventStreamQueryService(namedAggregate.materialize(), queryGateway)
}
