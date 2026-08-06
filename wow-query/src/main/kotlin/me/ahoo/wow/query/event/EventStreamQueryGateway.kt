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
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Application query boundary for an event-stream aggregate.
 */
class EventStreamQueryGateway(
    override val namedAggregate: NamedAggregate,
    private val handler: EventStreamQueryHandler,
) : EventStreamQueryService {
    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> {
        return handler.single(namedAggregate, singleQuery)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return handler.dynamicSingle(namedAggregate, singleQuery)
    }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> {
        return handler.list(namedAggregate, listQuery)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return handler.dynamicList(namedAggregate, listQuery)
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> {
        return handler.paged(namedAggregate, pagedQuery)
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return handler.dynamicPaged(namedAggregate, pagedQuery)
    }

    override fun count(condition: Condition): Mono<Long> {
        return handler.count(namedAggregate, condition)
    }
}

fun interface EventStreamQueryGatewayFactory {
    fun create(namedAggregate: NamedAggregate): EventStreamQueryService
}

class DefaultEventStreamQueryGatewayFactory(
    private val handler: EventStreamQueryHandler,
    private val backendProvider: EventStreamQueryBackendProvider,
) : EventStreamQueryGatewayFactory {
    private val gatewayCache = ConcurrentHashMap<NamedAggregate, EventStreamQueryService>()

    override fun create(namedAggregate: NamedAggregate): EventStreamQueryService {
        return gatewayCache.computeIfAbsent(namedAggregate.materialize()) {
            backendProvider.get(it)
            EventStreamQueryGateway(it, handler)
        }
    }
}
