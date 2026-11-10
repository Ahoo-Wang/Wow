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

package me.ahoo.wow.runtime.cluster

import me.ahoo.wow.runtime.ZeroAwaitingCounter
import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * A partition this node holds: whether it takes commands, and the commands in flight. [renewedAt] marks when the
 * lease was last acquired or renewed, taken before the call: once longer ago than `leaseTtl − renewInterval`, the
 * lease is [overdue] and the gate no longer [open], since the lease may expire before the next round renews it.
 * A gate is made as its lease is taken, [starting]: while the partition's runtimes start, its lease is renewed, but
 * it takes no command, is not overdue yet, and counts as [PartitionOwnership.pending].
 */
internal class PartitionGate(@Volatile var renewedAt: TimeMark, private val unrenewedLimit: Duration) {
    @Volatile
    var starting = true
        private set

    @Volatile
    var accepting = false

    @Volatile
    var lost = false
    val inFlight = ZeroAwaitingCounter()

    val overdue: Boolean get() = renewedAt.elapsedNow() >= unrenewedLimit

    /** Whether the partition takes commands here: accepting, and its lease renewed recently enough. */
    val open: Boolean get() = accepting && !overdue

    /** The partition has started: it takes commands, unless its lease was lost meanwhile. */
    fun started() {
        accepting = !lost
        starting = false
    }
}
