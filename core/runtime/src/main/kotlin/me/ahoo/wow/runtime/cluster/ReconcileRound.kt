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

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * What one reconcile of a [PartitionOwnership] does to the partitions it holds once it knows its target: the lost
 * partitions stop, each one still in the target taken back once its own stop ended ([PartitionRuns.dropLost]), beside
 * the hand-offs of those outside the target and the acquisitions of those missing from it (#6379). A failure is thrown
 * once every one of them ended, so none cuts a partition's stop short.
 */
internal class ReconcileRound(private val runs: PartitionRuns, private val gates: Map<Int, PartitionGate>) {
    /** Moves the partitions held towards [goal]; those it acquires read once [reads] completes, when given. */
    suspend fun move(goal: Set<Int>, reads: Job?) = supervisorScope {
        val lost = gates.filterValues { it.lost }
        val dropping = async { runs.dropLost(lost, goal, reads) }
        val moving = async {
            runs.handOff(gates.keys.filter { it !in goal && it !in lost })
            val missing = goal.filter { !gates.containsKey(it) && it !in lost }
            coroutineScope {
                missing.forEach { partition -> launch { runs.acquire(partition, reads) } }
            }
        }
        listOf(dropping, moving).awaitEach()
    }
}

/**
 * Awaits every one of these, then throws the first failure among them: in a supervisor scope, one that fails leaves
 * the others running to their end, a partition's stop above all.
 */
internal suspend fun List<Deferred<*>>.awaitEach() {
    val failures = map { deferred -> runCatching { deferred.await() }.exceptionOrNull() }
    failures.firstOrNull { it != null }?.let { throw it }
}
