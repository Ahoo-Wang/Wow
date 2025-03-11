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

import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import reactor.core.publisher.Flux

interface ReactiveSnapshotListQueryApi<S : Any> :
    SnapshotListQueryApi<Flux<MaterializedSnapshot<S>>, Flux<Map<String, Any>>, Flux<S>>

fun <S : Any> IListQuery.query(snapshotQueryApi: ReactiveSnapshotListQueryApi<S>): Flux<MaterializedSnapshot<S>> {
    return snapshotQueryApi.list(this)
}

fun <S : Any> IListQuery.queryState(snapshotQueryApi: ReactiveSnapshotListQueryApi<S>): Flux<S> {
    return snapshotQueryApi.listState(this)
}

fun IListQuery.dynamicQuery(snapshotQueryApi: ReactiveSnapshotListQueryApi<*>): Flux<Map<String, Any>> {
    return snapshotQueryApi.dynamicList(this)
}
