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
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.query.QueryService
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryService<S : Any> : Named, QueryService<MaterializedSnapshot<S>>
class NoOpSnapshotQueryService<S : Any>(override val namedAggregate: NamedAggregate) : SnapshotQueryService<S> {
    override val name: String
        get() = NoOpSnapshotStore.NAME

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        return Mono.defer { Mono.error(backendUnavailable()) }
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        return Mono.defer { Mono.error(backendUnavailable()) }
    }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        return Flux.defer { Flux.error(backendUnavailable()) }
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return Flux.defer { Flux.error(backendUnavailable()) }
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        return Mono.defer { Mono.error(backendUnavailable()) }
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        return Mono.defer { Mono.error(backendUnavailable()) }
    }

    override fun count(condition: Condition): Mono<Long> {
        return Mono.defer { Mono.error(backendUnavailable()) }
    }

    private fun backendUnavailable(): QueryException = QueryException(
        QueryErrorCode.BACKEND_NOT_READY,
        QueryStage.BACKEND_RESOLUTION,
        QueryErrorReason.BACKEND_UNAVAILABLE
    )
}
