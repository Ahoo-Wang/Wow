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
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.reactive.function.client.WebClientResponseException

interface SyncSnapshotQueryApi<S : Any> :
    SnapshotSingleQueryApi<MaterializedSnapshot<S>?, Map<String, Any>?, S?>,
    SnapshotListQueryApi<List<MaterializedSnapshot<S>>, List<Map<String, Any>>, List<S>>,
    SnapshotPagedQueryApi<PagedList<MaterializedSnapshot<S>>, PagedList<Map<String, Any>>, PagedList<S>>,
    SnapshotCountQueryApi<Long> {

    override fun getById(id: String): MaterializedSnapshot<S>? {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return switchNotFoundToNull { single(it) }
        }
    }

    override fun getStateById(id: String): S? {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return switchNotFoundToNull { singleState(it) }
        }
    }
}

fun <T> switchNotFoundToNull(query: () -> T): T? {
    return try {
        query()
    } catch (ignore: WebClientResponseException.NotFound) {
        null
    } catch (ignore: HttpClientErrorException.NotFound) {
        null
    }
}

fun <S : Any> IListQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): List<MaterializedSnapshot<S>> {
    return snapshotQueryApi.list(this)
}

fun <S : Any> IPagedQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): PagedList<MaterializedSnapshot<S>> {
    return snapshotQueryApi.paged(this)
}

fun <S : Any> ISingleQuery.query(snapshotQueryApi: SyncSnapshotQueryApi<S>): MaterializedSnapshot<S>? {
    return switchNotFoundToNull { snapshotQueryApi.single(this) }
}

fun <S : Any> IListQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): List<S> {
    return snapshotQueryApi.listState(this)
}

fun <S : Any> IPagedQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): PagedList<S> {
    return snapshotQueryApi.pagedState(this)
}

fun <S : Any> ISingleQuery.queryState(snapshotQueryApi: SyncSnapshotQueryApi<S>): S? {
    return switchNotFoundToNull { snapshotQueryApi.singleState(this) }
}

fun IListQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): List<Map<String, Any>> {
    return snapshotQueryApi.dynamicList(this)
}

fun IPagedQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): PagedList<Map<String, Any>> {
    return snapshotQueryApi.dynamicPaged(this)
}

fun ISingleQuery.dynamicQuery(snapshotQueryApi: SyncSnapshotQueryApi<*>): Map<String, Any>? {
    return switchNotFoundToNull { snapshotQueryApi.dynamicSingle(this) }
}

fun Condition.count(snapshotQueryApi: SyncSnapshotQueryApi<*>): Long {
    return snapshotQueryApi.count(this)
}
