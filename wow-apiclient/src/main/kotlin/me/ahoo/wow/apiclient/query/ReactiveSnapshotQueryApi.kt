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

package me.ahoo.wow.apiclient.query

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.dsl.singleQuery
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface ReactiveSnapshotQueryApi<S : Any> : SnapshotQueryApi {

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun single(@RequestBody singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun dynamicSingle(@RequestBody singleQuery: ISingleQuery): Mono<Map<String, Any>>

    @PostExchange(SNAPSHOT_SINGLE_STATE_RESOURCE_NAME)
    fun singleState(@RequestBody singleQuery: ISingleQuery): Mono<S>

    fun getById(id: String): Mono<MaterializedSnapshot<S>> {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return single(it).switchNotFoundToEmpty()
        }
    }

    fun getStateById(id: String): Mono<S> {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return singleState(it).switchNotFoundToEmpty()
        }
    }

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun list(@RequestBody query: IListQuery): Flux<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun dynamicList(@RequestBody query: IListQuery): Flux<Map<String, Any>>

    @PostExchange(SNAPSHOT_LIST_STATE_RESOURCE_NAME)
    fun listState(@RequestBody query: IListQuery): Flux<S>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun paged(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun dynamicPaged(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<Map<String, Any>>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME)
    fun pagedState(@RequestBody pagedQuery: IPagedQuery): Mono<PagedList<S>>

    @PostExchange(SNAPSHOT_COUNT_RESOURCE_NAME)
    fun count(@RequestBody condition: Condition): Mono<Long>
}

fun <T> Mono<T>.switchNotFoundToEmpty(): Mono<T> {
    return onErrorResume(WebClientResponseException.NotFound::class.java) {
        Mono.empty()
    }
}

fun <S : Any> IListQuery.query(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Flux<MaterializedSnapshot<S>> {
    return snapshotQueryApi.list(this)
}

fun <S : Any> IPagedQuery.query(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Mono<PagedList<MaterializedSnapshot<S>>> {
    return snapshotQueryApi.paged(this)
}

fun <S : Any> ISingleQuery.query(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Mono<MaterializedSnapshot<S>> {
    return snapshotQueryApi.single(this).switchNotFoundToEmpty()
}

fun <S : Any> IListQuery.queryState(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Flux<S> {
    return snapshotQueryApi.listState(this)
}

fun <S : Any> IPagedQuery.queryState(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Mono<PagedList<S>> {
    return snapshotQueryApi.pagedState(this)
}

fun <S : Any> ISingleQuery.queryState(snapshotQueryApi: ReactiveSnapshotQueryApi<S>): Mono<S> {
    return snapshotQueryApi.singleState(this).switchNotFoundToEmpty()
}

fun IListQuery.dynamicQuery(snapshotQueryApi: ReactiveSnapshotQueryApi<*>): Flux<Map<String, Any>> {
    return snapshotQueryApi.dynamicList(this)
}

fun IPagedQuery.dynamicQuery(snapshotQueryApi: ReactiveSnapshotQueryApi<*>): Mono<PagedList<Map<String, Any>>> {
    return snapshotQueryApi.dynamicPaged(this)
}

fun ISingleQuery.dynamicQuery(snapshotQueryApi: ReactiveSnapshotQueryApi<*>): Mono<Map<String, Any>> {
    return snapshotQueryApi.dynamicSingle(this).switchNotFoundToEmpty()
}

fun Condition.count(snapshotQueryApi: ReactiveSnapshotQueryApi<*>): Mono<Long> {
    return snapshotQueryApi.count(this)
}
