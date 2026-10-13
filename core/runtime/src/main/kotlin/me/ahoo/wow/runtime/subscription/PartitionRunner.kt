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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads one partition once for every subscription of the group (design 04 §1, shared tail).
 *
 * The shared tail fans each entry out to the *joined* workers, in sequence order, and records it as `head`. A worker
 * whose cursor is behind `head` cannot join: it reads the partition itself from its own cursor (catch-up) and joins
 * once its cursor reaches `head`. The check and the join happen under `lock`, which the shared tail also holds while
 * it fans an entry out and moves `head`, so the first entry the shared tail fans to a newly joined worker is the one
 * right after `head`: nothing is skipped. An entry the worker already took (its cursor may be past `head`) is dropped
 * by the worker, so nothing is taken twice.
 *
 * A joined worker without room for the next entry is detached rather than waited for, so one slow subscription never
 * holds up the others; it catches up on its own and joins again. In the steady state every worker is joined and the
 * partition is read once, whatever the number of subscriptions.
 */
internal class PartitionRunner(
    private val partition: Int,
    private val source: EntrySource,
    private val readers: CoroutineScope,
    private val options: SubscriptionOptions,
) {
    private val lock = Mutex()
    private val joined = LinkedHashSet<SubscriptionWorker>()

    /** The sequence of the last entry the shared tail fanned out, or where it started. Guarded by `lock`. */
    private var head = 0L

    /** Starts the shared tail where the most advanced of [workers] is; the others catch up from their own cursors. */
    fun start(workers: List<SubscriptionWorker>) {
        head = workers.maxOfOrNull { it.cursor } ?: 0L
        val (current, behind) = workers.partition { it.cursor >= head }
        joined += current
        behind.forEach(::catchUp)
        readers.launch {
            retryForever(options.readRetry, { "read the shared tail of partition $partition" }) {
                source.tail(partition, head.takeIf { it > 0 }).collect { entry ->
                    lock.withLock {
                        if (entry.position.sequence > head) {
                            val iterator = joined.iterator()
                            while (iterator.hasNext()) {
                                val worker = iterator.next()
                                if (!worker.tryAccept(entry)) {
                                    iterator.remove()
                                    catchUp(worker)
                                }
                            }
                            head = entry.position.sequence
                        }
                    }
                }
            }
        }
    }

    /** Adds [worker] to a running partition: it joins at once if it is not behind, otherwise it catches up first. */
    fun attach(worker: SubscriptionWorker) = catchUp(worker)

    private fun catchUp(worker: SubscriptionWorker) {
        readers.launch {
            // Joins without reading when the worker is not behind.
            retryForever(
                options.readRetry,
                { "read partition $partition for ${worker.subscription.name} to catch up" },
            ) {
                if (!tryJoin(worker)) {
                    source.tail(partition, worker.cursor.takeIf { it > 0 })
                        .takeWhile { entry ->
                            worker.accept(entry)
                            !tryJoin(worker)
                        }
                        .collect()
                }
            }
        }
    }

    private suspend fun tryJoin(worker: SubscriptionWorker): Boolean = lock.withLock {
        if (worker.cursor >= head) {
            joined += worker
            true
        } else {
            false
        }
    }
}
