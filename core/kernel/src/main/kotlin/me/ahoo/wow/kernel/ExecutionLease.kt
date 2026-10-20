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

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Whether the command the kernel executes in this coroutine runs on the owner of its log partition (decision D261, C2
 * and C4), as the router that chose this node says:
 *
 * - [HELD]: this node holds the partition's lease and accepts its commands. Only then may a decide that does I/O skip
 *   the request-id lookup, on a miss of a complete set of the aggregate's recent requests.
 * - [NOT_HELD]: the command runs here without the lease, degraded to `Local` (decision D71) or for a partition about to
 *   be acquired. Its states are not cached: another node may be committing to the aggregate meanwhile.
 *
 * Without either (a `Local` router, a context without a lease group, a command intake), states are cached as before
 * and a decide that does I/O always looks its request id up on a miss.
 */
@InternalWowApi
class ExecutionLease private constructor(val held: Boolean) : AbstractCoroutineContextElement(Key) {
    override fun toString(): String = if (held) "ExecutionLease(held)" else "ExecutionLease(not held)"

    @InternalWowApi
    companion object Key : CoroutineContext.Key<ExecutionLease> {
        @JvmField
        val HELD: ExecutionLease = ExecutionLease(true)

        @JvmField
        val NOT_HELD: ExecutionLease = ExecutionLease(false)
    }
}
