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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.runtime.health.launchWorker
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Reads one partition once for every subscription of the group (shared tail).
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
 *
 * Nothing is read, by the shared tail or a catch-up, before [reads] completes, when given: a partition held back while
 * its node starts the others ([SubscriptionRuntime.startPartition]). Then, with the node's [catchUps], a partition
 * whose workers start behind the head of the source waits for a permit before it reads, and gives it back once each
 * worker has taken every entry up to that head, is paused on a systemic failure, or has taken nothing for [STALLED]
 * ([SubscriptionOptions.maxCatchUps]).
 */
internal class PartitionRunner(
    private val partition: Int,
    private val source: EntrySource,
    private val readers: CoroutineScope,
    private val options: SubscriptionOptions,
    private val reads: Job? = null,
    private val catchUps: Semaphore? = null,
) {
    private val lock = Mutex()
    private val joined = LinkedHashSet<SubscriptionWorker>()

    /** Every worker of the partition, attached ones included. */
    private val workers = CopyOnWriteArrayList<SubscriptionWorker>()

    /** Completes once the partition reads: past [reads], and holding a catch-up permit when it needs one. */
    private val opened = CompletableDeferred<Unit>()

    /** Whether the partition reads: no longer held back, nor waiting for a catch-up permit. */
    val reading: Boolean get() = opened.isCompleted

    /** The sequence of the last entry the shared tail fanned out, or where it started. Guarded by `lock`. */
    private var head = 0L

    /** Starts the shared tail where the most advanced of [workers] is; the others catch up from their own cursors. */
    fun start(workers: List<SubscriptionWorker>) {
        this.workers += workers
        head = workers.maxOfOrNull { it.cursor } ?: 0L
        val (current, behind) = workers.partition { it.cursor >= head }
        joined += current
        readers.launch { open() }
        behind.forEach(::catchUp)
        readers.launchWorker("shared tail of partition $partition") {
            opened.await()
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
            // The tail ended: the partition's reads end with it.
            false
        }
    }

    /** Adds [worker] to a running partition: it joins at once if it is not behind, otherwise it catches up first. */
    fun attach(worker: SubscriptionWorker) {
        workers += worker
        catchUp(worker)
    }

    /**
     * Opens the partition's reads once [reads] completes: at once without [catchUps], or when no worker is behind the
     * source's head; otherwise with a permit, held until the partition has caught up ([awaitCaughtUp]).
     */
    private suspend fun open() {
        reads?.join()
        val permits = catchUps
        if (permits == null) {
            opened.complete(Unit)
            return
        }
        val target = retryForever(options.readRetry, { "read the head of partition $partition" }) {
            source.checkpointOf(SubscriptionStart.End, partition)
        }
        if (workers.none { it.cursor < target }) {
            opened.complete(Unit)
            return
        }
        permits.acquire()
        try {
            opened.complete(Unit)
            awaitCaughtUp(target)
        } finally {
            permits.release()
        }
    }

    /**
     * Waits until each worker has taken the entries up to [target], is paused on a systemic failure, or has taken
     * nothing for [STALLED]: a subscription that waits on its downstream must not keep the node's other partitions
     * from catching up.
     */
    private suspend fun awaitCaughtUp(target: Long) {
        val moved = HashMap<SubscriptionWorker, Pair<Long, TimeMark>>()
        while (true) {
            val done = workers.all { worker ->
                val cursor = worker.cursor
                val last = moved[worker]?.takeIf { it.first == cursor }
                    ?: (cursor to TimeSource.Monotonic.markNow()).also { moved[worker] = it }
                cursor >= target || worker.paused != null || last.second.elapsedNow() >= STALLED
            }
            if (done) {
                return
            }
            delay(CAUGHT_UP_CHECK)
        }
    }

    private fun catchUp(worker: SubscriptionWorker) {
        readers.launchWorker("catch-up of ${worker.subscription.name} in partition $partition") {
            opened.await()
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
            false
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

/** How often a partition holding a catch-up permit looks whether it has caught up. */
private val CAUGHT_UP_CHECK = 100.milliseconds

/** How long a worker that takes nothing keeps its partition's catch-up permit. */
internal val STALLED = 30.seconds
