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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * Where the kernel keeps aggregates' states between commands, so a command does not replay the log.
 *
 * The kernel [take]s a state out for one attempt and [put]s it back only when the attempt leaves it valid: committed
 * (the evolved state at the new version), or decided without evolving (rejected, unchanged). Any other end drops it
 * and the next command reloads. With mutable states (D7) evolving changes the cached instance itself, so a state
 * must never stay cached through an attempt that may have changed it and did not commit.
 *
 * Commands of one aggregate must not run concurrently on one cache (the executor serializes them); across nodes, a
 * stale state only costs a version conflict and a reload.
 */
interface StateCache {
    /** Removes and returns the state of [ref], or null when none is cached. */
    fun take(ref: AggregateRef): CachedState?

    /** Caches [state] of [ref]. */
    fun put(ref: AggregateRef, state: CachedState)

    /** Caches nothing. */
    object None : StateCache {
        override fun take(ref: AggregateRef): CachedState? = null

        override fun put(ref: AggregateRef, state: CachedState) = Unit
    }
}

/** An aggregate's [state] after its event stream [version]. */
class CachedState(val state: Any, val version: Int)
