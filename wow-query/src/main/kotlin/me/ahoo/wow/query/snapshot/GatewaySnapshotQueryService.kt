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
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.compat.LegacyQueryRequestMapper
import me.ahoo.wow.query.compat.LegacySnapshotResultMapper
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class GatewaySnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val queryGateway: QueryGateway
) : SnapshotQueryService<S> {
    override val name: String = "query-gateway"
    private val requestMapper = LegacyQueryRequestMapper.create(
        QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
    )
    private val resultMapper = LegacySnapshotResultMapper.create<S>(namedAggregate)

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> = Mono.defer {
        queryGateway.single(requestMapper.single(singleQuery)).map(resultMapper::map)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.defer {
        queryGateway.single(requestMapper.single(singleQuery))
    }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> = Flux.defer {
        queryGateway.list(requestMapper.list(listQuery)).map(resultMapper::map)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.defer {
        queryGateway.list(requestMapper.list(listQuery))
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> = Mono.defer {
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

class GatewaySnapshotQueryServiceFactory(
    private val queryGateway: QueryGateway
) : AbstractSnapshotQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> =
        GatewaySnapshotQueryService<Any>(namedAggregate.materialize(), queryGateway)
}
