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

import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.MaterializedSnapshot

interface SynchronousSnapshotCursorQueryApi<S : Any> :
    SnapshotCursorQueryApi<CursorPage<MaterializedSnapshot<S>>, CursorPage<Map<String, Any>>, CursorPage<S>>

fun <S : Any> ICursorQuery.query(snapshotQueryApi: SynchronousSnapshotCursorQueryApi<S>): CursorPage<MaterializedSnapshot<S>> =
    snapshotQueryApi.cursor(this)

fun <S : Any> ICursorQuery.queryState(snapshotQueryApi: SynchronousSnapshotCursorQueryApi<S>): CursorPage<S> =
    snapshotQueryApi.cursorState(this)

fun ICursorQuery.dynamicQuery(snapshotQueryApi: SynchronousSnapshotCursorQueryApi<*>): CursorPage<Map<String, Any>> =
    snapshotQueryApi.dynamicCursor(this)
