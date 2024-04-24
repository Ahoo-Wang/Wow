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
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface SnapshotQueryApi<S : Any> {
    companion object {
        const val SNAPSHOT_RESOURCE_NAME = "snapshot"
        const val SNAPSHOT_SINGLE_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/single"
        const val SNAPSHOT_SINGLE_STATE_RESOURCE_NAME = "$SNAPSHOT_SINGLE_RESOURCE_NAME/state"
        const val SNAPSHOT_LIST_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/list"
        const val SNAPSHOT_LIST_STATE_RESOURCE_NAME = "$SNAPSHOT_LIST_RESOURCE_NAME/state"
        const val SNAPSHOT_PAGED_QUERY_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/paged"
        const val SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME = "$SNAPSHOT_PAGED_QUERY_RESOURCE_NAME/state"
        const val SNAPSHOT_COUNT_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/count"
    }

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun single(@RequestBody singleQuery: SingleQuery): Mono<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun dynamicSingle(@RequestBody singleQuery: SingleQuery): Mono<Map<String, Any>>

    @PostExchange(SNAPSHOT_SINGLE_STATE_RESOURCE_NAME)
    fun singleState(@RequestBody singleQuery: SingleQuery): Mono<S>

    fun getById(id: String): Mono<MaterializedSnapshot<S>> {
        SingleQuery(condition = Condition.id(id)).let {
            return single(it)
        }
    }

    fun getStateById(id: String): Mono<S> {
        SingleQuery(condition = Condition.id(id)).let {
            return singleState(it)
        }
    }

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun list(@RequestBody query: ListQuery): Flux<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun dynamicList(@RequestBody singleQuery: SingleQuery): Flux<Map<String, Any>>

    @PostExchange(SNAPSHOT_LIST_STATE_RESOURCE_NAME)
    fun listState(@RequestBody query: ListQuery): Flux<S>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun paged(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<MaterializedSnapshot<S>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun dynamicPaged(@RequestBody singleQuery: SingleQuery): Mono<PagedList<Map<String, Any>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME)
    fun pagedState(@RequestBody pagedQuery: PagedQuery): Mono<PagedList<S>>

    @PostExchange(SNAPSHOT_COUNT_RESOURCE_NAME)
    fun count(@RequestBody condition: Condition): Mono<Long>
}
