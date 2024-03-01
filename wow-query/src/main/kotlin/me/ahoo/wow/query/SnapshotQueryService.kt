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
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryService<S : Any> {
    fun single(condition: Condition, tenantId: String = TenantId.DEFAULT_TENANT_ID): Mono<Snapshot<S>>
    fun query(query: IQuery, tenantId: String = TenantId.DEFAULT_TENANT_ID): Flux<Snapshot<S>>
    fun pagedQuery(pagedQuery: IPagedQuery, tenantId: String = TenantId.DEFAULT_TENANT_ID): Mono<PagedList<Snapshot<S>>>
    fun count(condition: Condition, tenantId: String = TenantId.DEFAULT_TENANT_ID): Mono<Long>
}

object NoOpSnapshotQueryService : SnapshotQueryService<Any> {
    override fun single(condition: Condition, tenantId: String): Mono<Snapshot<Any>> {
        return Mono.empty()
    }

    override fun query(query: IQuery, tenantId: String): Flux<Snapshot<Any>> {
        return Flux.empty()
    }

    override fun pagedQuery(pagedQuery: IPagedQuery, tenantId: String): Mono<PagedList<Snapshot<Any>>> {
        return Mono.just(PagedList(0, emptyList()))
    }

    override fun count(condition: Condition, tenantId: String): Mono<Long> {
        return Mono.just(0)
    }
}

interface SnapshotQueryServiceFactory {
    fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S>
}

object NoOpSnapshotQueryServiceFactory : SnapshotQueryServiceFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryService<S> {
        return NoOpSnapshotQueryService as SnapshotQueryService<S>
    }
}
