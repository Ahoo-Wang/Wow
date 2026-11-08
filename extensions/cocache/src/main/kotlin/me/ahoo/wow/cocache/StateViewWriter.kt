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

package me.ahoo.wow.cocache

import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.wow.cocache.api.StateKey
import java.time.Duration

/**
 * Writes the states of one aggregate type into its cache at their versions, the owner's writes after each commit:
 * CoCache writes each only over an older version ([SuspendingVersionedCache.putIfNewer]), atomically, so a commit delivered
 * again, a replay, or an old owner beside the new one never takes the cache back. A deleted aggregate is a tombstone
 * at its version ([SuspendingVersionedCache.invalidateIfNewer]), which reads as absent and which no older state undoes. A
 * reader's fill in flight holds the key's lease, which the write takes over: the fill is refused.
 *
 * Each write lives for [ttl] when given, else for the cache's lifetime (its `ttl`), the tombstone too.
 */
class StateViewWriter<V : Any> internal constructor(
    private val cache: SuspendingVersionedCache<StateKey, V>,
    private val ttl: Duration?,
) {
    /** Writes [view] under [key] at [version]: true when written, false when the cache holds that version or a later. */
    suspend fun write(key: StateKey, view: V, version: Int): Boolean =
        cache.putIfNewer(key, view, version.toLong(), ttl) == VersionedPut.Written

    /** Writes the tombstone of [key], deleted at [version]: true when written, false when the cache holds a later one. */
    suspend fun delete(key: StateKey, version: Int): Boolean =
        cache.invalidateIfNewer(key, version.toLong(), ttl) == VersionedPut.Written

    override fun toString(): String = "StateViewWriter(${cache.name})"
}
