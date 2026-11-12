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

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The bound on one lease group's lease calls on this node ([ClusterOptions.leaseParallelism]): at most [parallelism]
 * run at a time. Its [PartitionOwnership] builds it and hands it to its [PartitionRuns].
 */
internal class LeaseCalls(parallelism: Int) {
    private val permits = Semaphore(parallelism)

    /** Runs [call] within a permit. */
    suspend fun <T> bounded(call: suspend () -> T): T = permits.withPermit { call() }
}
