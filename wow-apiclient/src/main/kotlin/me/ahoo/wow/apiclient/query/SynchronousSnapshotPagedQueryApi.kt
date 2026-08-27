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

import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList

interface SynchronousSnapshotPagedQueryApi<S : Any> :
    SnapshotPagedQueryApi<PagedList<MaterializedSnapshot<S>>, PagedList<Map<String, Any>>, PagedList<S>>

fun <S : Any> IPagedQuery.query(snapshotQueryApi: SynchronousSnapshotPagedQueryApi<S>): PagedList<MaterializedSnapshot<S>> {
    return snapshotQueryApi.paged(this)
}

fun <S : Any> IPagedQuery.queryState(snapshotQueryApi: SynchronousSnapshotPagedQueryApi<S>): PagedList<S> {
    return snapshotQueryApi.pagedState(this)
}

fun IPagedQuery.dynamicQuery(snapshotQueryApi: SynchronousSnapshotPagedQueryApi<*>): PagedList<Map<String, Any>> {
    return snapshotQueryApi.dynamicPaged(this)
}
