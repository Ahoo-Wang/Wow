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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * A run of a [SubscriptionRuntime], from its start to its stop. Guarded by its `lifecycle`, except [workers], which
 * waits and the checkpoint ticker read from any thread.
 */
internal class Running(
    val root: Job,
    val scope: CoroutineScope,
    /** The aggregates held back in the partitions the run starts, the moved ones looked for once. */
    val parked: ParkedAtStart,
) {
    val partitions = ConcurrentHashMap<Int, PartitionRun>()
    val workers = ConcurrentHashMap<Pair<String, Int>, SubscriptionWorker>()

    /**
     * The partitions [SubscriptionRuntime.startPartition] is reading the starts of, outside the mutex, each done once
     * it opened.
     */
    val starting = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /** The subscriptions that had read the stream when the runtime started ([WorkerStarts.joinedSubscriptions]). */
    var joined: Set<String> = emptySet()
}

/**
 * One partition being read: its shared tail and catch-up reads, and its subscriptions' lanes, stopped together.
 * Its reads wait for [reads], when given ([SubscriptionRuntime.startPartition]).
 */
internal class PartitionRun(
    val partition: Int,
    run: Running,
    reads: Job?,
    source: EntrySource,
    options: SubscriptionOptions,
    catchUps: Semaphore?,
) {
    val readersJob = SupervisorJob(run.root)
    val lanesJob = SupervisorJob(run.root)
    val lanes = CoroutineScope(run.scope.coroutineContext + lanesJob)
    val runner = PartitionRunner(
        partition,
        source,
        CoroutineScope(run.scope.coroutineContext + readersJob),
        options,
        reads,
        catchUps,
    )
    val workers = ArrayList<SubscriptionWorker>()

    /** Whether the partition reads: not held back, nor waiting for a catch-up permit. */
    val reading: Boolean get() = runner.reading
}
