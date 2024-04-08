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
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryApi<S : Any> {
    companion object {
        const val SNAPSHOT_RESOURCE_NAME = "snapshot"
        const val SNAPSHOT_SINGLE_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/single"
        const val SNAPSHOT_SINGLE_STATE_RESOURCE_NAME = "$SNAPSHOT_SINGLE_RESOURCE_NAME/state"
        const val SNAPSHOT_QUERY_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/query"
        const val SNAPSHOT_QUERY_STATE_RESOURCE_NAME = "$SNAPSHOT_QUERY_RESOURCE_NAME/state"
        const val SNAPSHOT_PAGED_QUERY_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/pagination"
        const val SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME = "$SNAPSHOT_PAGED_QUERY_RESOURCE_NAME/state"
        const val SNAPSHOT_COUNT_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/count"
    }

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun single(@RequestBody condition: Condition): Mono<Snapshot<S>>

    @PostExchange(SNAPSHOT_SINGLE_STATE_RESOURCE_NAME)
    fun singleState(@RequestBody condition: Condition): Mono<S>

    fun getById(id: String): Mono<Snapshot<S>> {
        Condition.id(id).let {
            return single(it)
        }
    }

    fun getStateById(id: String): Mono<S> {
        Condition.id(id).let {
            return singleState(it)
        }
    }

    @PostExchange(SNAPSHOT_QUERY_RESOURCE_NAME)
    fun query(@RequestBody query: Query): Flux<Snapshot<S>>

    @PostExchange(SNAPSHOT_QUERY_STATE_RESOURCE_NAME)
    fun queryState(@RequestBody query: Query): Flux<S>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun pagedQuery(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<Snapshot<S>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME)
    fun pagedQueryState(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<S>>

    @PostExchange(SNAPSHOT_COUNT_RESOURCE_NAME)
    fun count(@RequestBody condition: Condition): Mono<Long>
}
