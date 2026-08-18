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
import me.ahoo.wow.query.compat.adaptLegacyEventDocument
import me.ahoo.wow.query.compat.legacyCountRequest
import me.ahoo.wow.query.compat.legacyListRequest
import me.ahoo.wow.query.compat.legacyPageRequest
import me.ahoo.wow.query.compat.legacySingleRequest
import me.ahoo.wow.query.compat.legacyTypedListRequest
import me.ahoo.wow.query.compat.legacyTypedPageRequest
import me.ahoo.wow.query.compat.legacyTypedSingleRequest
import me.ahoo.wow.query.compat.markLegacyTypedResult
import me.ahoo.wow.query.compat.materializeLegacyEvent
import me.ahoo.wow.query.compat.materializeLegacyList
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class GatewayEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val queryGateway: QueryGateway
) : EventStreamQueryService {
    private val target = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)

    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> = Mono.defer {
        queryGateway.single(legacyTypedSingleRequest(target, singleQuery))
            .markLegacyTypedResult()
            .map(::materializeLegacyEvent)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.defer {
        queryGateway.single(legacySingleRequest(target, singleQuery)).map(::adaptLegacyEventDocument)
    }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> = Flux.defer {
        materializeLegacyList(
            queryGateway.list(legacyTypedListRequest(target, listQuery)).markLegacyTypedResult(),
            ::materializeLegacyEvent,
        )
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.defer {
        materializeLegacyList(
            queryGateway.list(legacyListRequest(target, listQuery)),
            ::adaptLegacyEventDocument,
        )
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> = Mono.defer {
        queryGateway.page(legacyTypedPageRequest(target, pagedQuery)).markLegacyTypedResult().map { page ->
            PagedList(page.total, page.items.map(::materializeLegacyEvent))
        }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> = Mono.defer {
        queryGateway.page(legacyPageRequest(target, pagedQuery)).map { page ->
            PagedList(page.total, page.items.map(::adaptLegacyEventDocument))
        }
    }

    override fun count(condition: Condition): Mono<Long> = Mono.defer {
        queryGateway.count(legacyCountRequest(target, condition))
    }
}

class GatewayEventStreamQueryServiceFactory(
    private val queryGateway: QueryGateway
) : AbstractEventStreamQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService =
        GatewayEventStreamQueryService(namedAggregate.materialize(), queryGateway)
}
