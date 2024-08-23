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
import me.ahoo.wow.api.query.SingleQuery
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.PostExchange

interface SyncSnapshotQueryApi<S : Any> : SnapshotQueryApi {

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun single(@RequestBody singleQuery: ISingleQuery): MaterializedSnapshot<S>

    @PostExchange(SNAPSHOT_SINGLE_RESOURCE_NAME)
    fun dynamicSingle(@RequestBody singleQuery: ISingleQuery): Map<String, Any>

    @PostExchange(SNAPSHOT_SINGLE_STATE_RESOURCE_NAME)
    fun singleState(@RequestBody singleQuery: ISingleQuery): S

    fun getById(id: String): MaterializedSnapshot<S> {
        SingleQuery(condition = Condition.id(id)).let {
            return single(it)
        }
    }

    fun getStateById(id: String): S {
        SingleQuery(condition = Condition.id(id)).let {
            return singleState(it)
        }
    }

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun list(@RequestBody query: IListQuery): List<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_LIST_RESOURCE_NAME)
    fun dynamicList(@RequestBody query: IListQuery): List<Map<String, Any>>

    @PostExchange(SNAPSHOT_LIST_STATE_RESOURCE_NAME)
    fun listState(@RequestBody query: IListQuery): List<S>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun paged(@RequestBody pagedQuery: IPagedQuery): PagedList<MaterializedSnapshot<S>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_RESOURCE_NAME)
    fun dynamicPaged(@RequestBody pagedQuery: IPagedQuery): PagedList<Map<String, Any>>

    @PostExchange(SNAPSHOT_PAGED_QUERY_STATE_RESOURCE_NAME)
    fun pagedState(@RequestBody pagedQuery: IPagedQuery): PagedList<S>

    @PostExchange(SNAPSHOT_COUNT_RESOURCE_NAME)
    fun count(@RequestBody condition: Condition): Long
}

fun <S : Any> IListQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): List<MaterializedSnapshot<S>> {
    return snapshotQueryApi.list(this)
}

fun <S : Any> IPagedQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): PagedList<MaterializedSnapshot<S>> {
    return snapshotQueryApi.paged(this)
}

fun <S : Any> ISingleQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): MaterializedSnapshot<S> {
    return snapshotQueryApi.single(this)
}

fun <S : Any> IListQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): List<S> {
    return snapshotQueryApi.listState(this)
}

fun <S : Any> IPagedQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): PagedList<S> {
    return snapshotQueryApi.pagedState(this)
}

fun <S : Any> ISingleQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): S {
    return snapshotQueryApi.singleState(this)
}

fun IListQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): List<Map<String, Any>> {
    return snapshotQueryApi.dynamicList(this)
}

fun IPagedQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): PagedList<Map<String, Any>> {
    return snapshotQueryApi.dynamicPaged(this)
}

fun ISingleQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): Map<String, Any> {
    return snapshotQueryApi.dynamicSingle(this)
}

fun Condition.count(snapshotQueryApi: SyncSnapshotQueryApi<*>): Long {
    return snapshotQueryApi.count(this)
}
