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

package me.ahoo.wow.spi.log

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * How a backend reads an aggregate's streams by version ([EventLog.read], [EventLog.readEntries]): a page of at most
 * [SIZE] streams at a time, by the version key, each page emitted before the next is read. A read then holds one page,
 * whatever the aggregate's history: a replay from version 1 of an aggregate of hundreds of thousands of streams, which
 * held them all, holds 512. Each page is a call of its own, so a long read holds no connection between pages; a read
 * within one page is one call, as before.
 */
@InternalWowApi
object VersionPages {
    /** The most streams one call reads. */
    const val SIZE: Int = 512

    /**
     * The items with versions in [fromVersion]..[toVersion], in version order: [page] reads at most [SIZE] of them from
     * a version on, in version order, and [versionOf] tells an item's version. A page of fewer than [SIZE], or one that
     * reaches [toVersion], is the last.
     */
    fun <T> byVersion(
        fromVersion: Int,
        toVersion: Int,
        versionOf: (T) -> Int,
        page: suspend (from: Int) -> List<T>,
    ): Flow<T> = flow {
        var from = fromVersion
        while (from <= toVersion) {
            val items = page(from)
            items.forEach { emit(it) }
            val last = items.lastOrNull()?.let(versionOf) ?: return@flow
            if (items.size < SIZE || last >= toVersion) {
                return@flow
            }
            from = last + 1
        }
    }
}
