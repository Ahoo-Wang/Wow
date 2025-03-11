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

interface SynchronousSnapshotSingleQueryApi<S : Any> :
    SnapshotSingleQueryApi<MaterializedSnapshot<S>?, Map<String, Any>?, S?> {
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

fun <S : Any> ISingleQuery.query(snapshotQueryApi: SynchronousSnapshotSingleQueryApi<S>): MaterializedSnapshot<S>? {
    return switchNotFoundToNull { snapshotQueryApi.single(this) }
}

fun <S : Any> ISingleQuery.queryState(snapshotQueryApi: SynchronousSnapshotSingleQueryApi<S>): S? {
    return switchNotFoundToNull { snapshotQueryApi.singleState(this) }
}

fun ISingleQuery.dynamicQuery(snapshotQueryApi: SynchronousSnapshotSingleQueryApi<*>): Map<String, Any>? {
    return switchNotFoundToNull { snapshotQueryApi.dynamicSingle(this) }
}
