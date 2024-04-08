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

package me.ahoo.wow.apiclient

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.apiclient.SnapshotQueryApi.Companion.SNAPSHOT_PAGED_QUERY_RESOURCE_NAME
import me.ahoo.wow.apiclient.SnapshotQueryApi.Companion.SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TenantSnapshotQueryApi<S : Any> {
    companion object {
        const val TENANT_ID_PREFIX_PATH = "tenant/{tenantId}/"
    }

    @PostExchange(SnapshotQueryApi.SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun single(@PathVariable tenantId: String, @RequestBody condition: Condition): Mono<MaterializedSnapshot<S>>

    @PostExchange(SnapshotQueryApi.SNAPSHOT_SINGLE_STATE_RESOURCE_NAME)
    fun singleState(@PathVariable tenantId: String, @RequestBody condition: Condition): Mono<S>

    fun getById(tenantId: String, id: String): Mono<MaterializedSnapshot<S>> {
        Condition.id(id).let {
            return single(tenantId, it)
        }
    }

    fun getStateById(tenantId: String, id: String): Mono<S> {
        Condition.id(id).let {
            return singleState(tenantId, it)
        }
    }

    @PostExchange(SnapshotQueryApi.SNAPSHOT_QUERY_RESOURCE_NAME)
    fun query(@PathVariable tenantId: String, @RequestBody query: Query): Flux<MaterializedSnapshot<S>>

    @PostExchange(SnapshotQueryApi.SNAPSHOT_QUERY_STATE_RESOURCE_NAME)
    fun queryState(@PathVariable tenantId: String, @RequestBody query: Query): Flux<S>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun pagedQuery(
        @PathVariable tenantId: String,
        @RequestBody pagedQuery: PagedQuery
    ): Mono<PagedList<MaterializedSnapshot<S>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME)
    fun pagedQueryState(@PathVariable tenantId: String, @RequestBody pagedQuery: PagedQuery): Mono<PagedList<S>>

    @PostExchange(SnapshotQueryApi.SNAPSHOT_COUNT_RESOURCE_NAME)
    fun count(@PathVariable tenantId: String, @RequestBody condition: Condition): Mono<Long>
}
