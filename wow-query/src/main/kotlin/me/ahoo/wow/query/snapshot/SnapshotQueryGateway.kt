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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Application query boundary for a snapshot aggregate.
 *
 * Storage-specific [SnapshotQueryService] implementations remain behind the handler's tail filter. This gateway makes
 * in-process query-service calls traverse the same filter chain as HTTP queries.
 */
class SnapshotQueryGateway<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val name: String,
    private val handler: SnapshotQueryHandler,
) : SnapshotQueryService<S> {
    @Suppress("UNCHECKED_CAST")
    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        return handler.single(namedAggregate, singleQuery) as Mono<MaterializedSnapshot<S>>
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return handler.dynamicSingle(namedAggregate, singleQuery)
    }

    @Suppress("UNCHECKED_CAST")
    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        return handler.list(namedAggregate, listQuery) as Flux<MaterializedSnapshot<S>>
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return handler.dynamicList(namedAggregate, listQuery)
    }

    @Suppress("UNCHECKED_CAST")
    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        return handler.paged(namedAggregate, pagedQuery) as Mono<PagedList<MaterializedSnapshot<S>>>
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return handler.dynamicPaged(namedAggregate, pagedQuery)
    }

    override fun count(condition: Condition): Mono<Long> {
        return handler.count(namedAggregate, condition)
    }
}

interface SnapshotQueryGatewayFactory {
    fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S>
}

class DefaultSnapshotQueryGatewayFactory(
    private val handler: SnapshotQueryHandler,
    private val backendProvider: SnapshotQueryBackendProvider,
) : SnapshotQueryGatewayFactory {
    private val gatewayCache = ConcurrentHashMap<NamedAggregate, SnapshotQueryService<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        return gatewayCache.computeIfAbsent(namedAggregate.materialize()) {
            val backend = backendProvider.get<Any>(it)
            SnapshotQueryGateway<Any>(
                namedAggregate = it,
                name = backend.name,
                handler = handler,
            )
        } as SnapshotQueryService<S>
    }
}
