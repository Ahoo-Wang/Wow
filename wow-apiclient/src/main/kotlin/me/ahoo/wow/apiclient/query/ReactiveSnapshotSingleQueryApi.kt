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

import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.query.dsl.singleQuery
import reactor.core.publisher.Mono

interface ReactiveSnapshotSingleQueryApi<S : Any> :
    SnapshotSingleQueryApi<Mono<MaterializedSnapshot<S>>, Mono<Map<String, Any>>, Mono<S>> {
    override fun getById(id: String): Mono<MaterializedSnapshot<S>> {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return single(it).switchNotFoundToEmpty()
        }
    }

    override fun getStateById(id: String): Mono<S> {
        singleQuery {
            condition {
                id(id)
            }
        }.let {
            return singleState(it).switchNotFoundToEmpty()
        }
    }
}

fun <S : Any> ISingleQuery.query(snapshotQueryApi: ReactiveSnapshotSingleQueryApi<S>): Mono<MaterializedSnapshot<S>> {
    return snapshotQueryApi.single(this).switchNotFoundToEmpty()
}

fun <S : Any> ISingleQuery.queryState(snapshotQueryApi: ReactiveSnapshotSingleQueryApi<S>): Mono<S> {
    return snapshotQueryApi.singleState(this).switchNotFoundToEmpty()
}

fun ISingleQuery.dynamicQuery(snapshotQueryApi: ReactiveSnapshotSingleQueryApi<*>): Mono<Map<String, Any>> {
    return snapshotQueryApi.dynamicSingle(this).switchNotFoundToEmpty()
}
