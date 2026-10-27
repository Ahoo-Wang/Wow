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

package me.ahoo.wow.runtime

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * How a node's executors take commands.
 *
 * - [laneCapacity]: the commands one aggregate may have waiting in the executor.
 * - [maxBatch]: the most of one aggregate's queued commands the executor commits with one append;
 * 1 runs each alone.
 * - [maxInFlight]: the commands the node may have in flight in its executors, all contexts together
 *   ([InFlightLimit]); beyond it a command is refused as busy. Each partition append queue holds at most as many.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class ExecutorOptions @InternalWowApi constructor(
    val laneCapacity: Int = Executor.DEFAULT_LANE_CAPACITY,
    val maxBatch: Int = Executor.DEFAULT_MAX_BATCH,
    val maxInFlight: Int = InFlightLimit.DEFAULT_MAX,
) {
    init {
        require(laneCapacity >= 1) { "A lane takes at least one command." }
        require(maxBatch >= 1) { "A batch takes at least one command." }
        require(maxInFlight >= 1) { "A node takes at least one command in flight." }
    }

    /** This ExecutorOptions with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        laneCapacity: Int = this.laneCapacity,
        maxBatch: Int = this.maxBatch,
        maxInFlight: Int = this.maxInFlight,
    ): ExecutorOptions = ExecutorOptions(laneCapacity, maxBatch, maxInFlight)

    override fun equals(other: Any?): Boolean =
        this === other || other is ExecutorOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ExecutorOptions(laneCapacity=$laneCapacity, maxBatch=$maxBatch, maxInFlight=$maxInFlight)"

    private fun valueFields(): List<Any?> = listOf(laneCapacity, maxBatch, maxInFlight)
}
