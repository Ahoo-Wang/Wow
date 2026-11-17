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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.runtime.health.launchWorker
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
 * whose workers start behind the head of the source reads the first [FREE_CATCH_UP] entries of that backlog at once,
 * its readers together, without a permit: the gap a hand-over or a fail-over leaves fits in it (#6407). Only the
 * backlog counts, the entries up to the head it opened behind: those past it are live and read as they come. One
 * whose workers all pass that head within the page never asks for a permit; the reader that takes it past says so.
 * Otherwise its readers stop at the page's end and the partition waits for a permit for the rest, as a large backlog
 * does, whoever left it. It gives the permit back once each worker has taken
 * every entry up to that head, is paused on a systemic failure, or has taken nothing for [STALLED]
 * ([SubscriptionOptions.maxCatchUps]). A reader the page ended reads again from its own position once the permit is
 * held: it holds none of the entries it did not take meanwhile.
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

    /** Completes once the partition reads: past [reads], its first page at once when it is behind its head. */
    private val opened = CompletableDeferred<Unit>()

    /**
     * Completes once the partition's readers may read on without counting: without [catchUps], not behind its head,
     * caught up within its first page, or holding a permit.
     */
    private val permitted = CompletableDeferred<Unit>()

    /** Completes once the partition's readers have taken its first page without a permit: it then waits for one. */
    private val pageTaken = CompletableDeferred<Unit>()

    /** Completes once a reader took the partition's last worker behind [target] past it, within the first page. */
    private val passed = CompletableDeferred<Unit>()

    /** The backlog's entries the partition's readers may still take, together, before it holds a permit. */
    private val free = AtomicInteger(FREE_CATCH_UP)

    /**
     * The head of the source the partition opened behind: its backlog is the entries up to it, which spend the first
     * page; those past it are live, and spend nothing.
     */
    @Volatile
    private var target = Long.MAX_VALUE

    /** Whether the partition reads: no longer held back, nor waiting for a catch-up permit past its first page. */
    val reading: Boolean get() = opened.isCompleted && (permitted.isCompleted || !pageTaken.isCompleted)

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
            var pageEnded = false
            retryForever(options.readRetry, { "read the shared tail of partition $partition" }) {
                pageEnded = false
                source.tail(partition, head.takeIf { it > 0 })
                    .takeWhile { entry -> mayTake(entry).also { pageEnded = !it } }
                    .collect { entry ->
                        fanOut(entry)
                        took(entry)
                    }
            }
            // The first page ended the read: it goes on from `head` with the permit. Else the tail ended, and the
            // partition's reads with it.
            if (pageEnded) permitted.await()
            pageEnded
        }
    }

    /** Hands [entry] to the joined workers; one without room for it is detached and catches up on its own. */
    private suspend fun fanOut(entry: SourcedEntry) = lock.withLock {
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

    /** Adds [worker] to a running partition: it joins at once if it is not behind, otherwise it catches up first. */
    fun attach(worker: SubscriptionWorker) {
        workers += worker
        catchUp(worker)
    }

    /**
     * Opens the partition's reads once [reads] completes: without counting when there are no [catchUps], or when no
     * worker is behind the source's head. Otherwise its readers take the first page at once; one that reaches the
     * head within it needs no permit, else the partition takes one for the rest, held until it has caught up
     * ([awaitCaughtUp]).
     */
    private suspend fun open() {
        reads?.join()
        val permits = catchUps
        if (permits == null) {
            permitted.complete(Unit)
            opened.complete(Unit)
            return
        }
        val target = retryForever(options.readRetry, { "read the head of partition $partition" }) {
            source.checkpointOf(SubscriptionStart.End, partition)
        }
        if (workers.none { it.cursor < target }) {
            permitted.complete(Unit)
            opened.complete(Unit)
            return
        }
        this.target = target
        opened.complete(Unit)
        if (caughtUpWithinPage(target)) {
            permitted.complete(Unit)
            return
        }
        permits.acquire()
        try {
            permitted.complete(Unit)
            awaitCaughtUp(target)
        } finally {
            permits.release()
        }
    }

    /**
     * Whether the partition caught up to [target] before its readers took its first page: a reader that takes its last
     * worker past it says so ([took]). The poll ([awaitCaughtUp]) is left for the workers no reader moves past it: one
     * paused on a systemic failure, or one that has taken nothing for [STALLED].
     */
    private suspend fun caughtUpWithinPage(target: Long): Boolean = coroutineScope {
        val caughtUp = async { awaitCaughtUp(target) }
        select {
            passed.onAwait { true }
            caughtUp.onAwait { true }
            pageTaken.onAwait { false }
        }.also { caughtUp.cancel() }
    }

    /**
     * Whether a reader may take [entry]: always once the partition is [permitted], or when the entry is past [target],
     * live; an entry of the backlog while its first page lasts, the last reader past the page marking it taken.
     */
    private fun mayTake(entry: SourcedEntry): Boolean {
        if (permitted.isCompleted || entry.position.sequence > target || free.getAndDecrement() > 0) {
            return true
        }
        pageTaken.complete(Unit)
        return false
    }

    /** After a reader took [entry]: the partition has passed [target] within its page once no worker is behind it. */
    private fun took(entry: SourcedEntry) {
        if (permitted.isCompleted || entry.position.sequence < target) return
        if (workers.all { it.cursor >= target || it.paused != null }) {
            passed.complete(Unit)
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
            var pageEnded = false
            // Joins without reading when the worker is not behind.
            retryForever(
                options.readRetry,
                { "read partition $partition for ${worker.subscription.name} to catch up" },
            ) {
                pageEnded = false
                if (!tryJoin(worker)) {
                    source.tail(partition, worker.cursor.takeIf { it > 0 })
                        .takeWhile { entry ->
                            pageEnded = !mayTake(entry)
                            if (!pageEnded) {
                                worker.accept(entry)
                                took(entry)
                            }
                            !pageEnded && !tryJoin(worker)
                        }
                        .collect()
                }
            }
            // The first page ended the catch-up: it goes on from the worker's cursor with the permit.
            if (pageEnded) permitted.await()
            pageEnded
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

/**
 * The entries of its backlog a partition behind its head reads at once, all its readers together, before it needs a
 * catch-up permit ([SubscriptionOptions.maxCatchUps]): more than the gap a hand-over or a fail-over leaves, a few
 * entries, and the share of one page each partition of a node's first activation reads at once (#6407). Entries
 * committed after it opened do not count.
 */
internal const val FREE_CATCH_UP = 512

/** How often a partition holding a catch-up permit looks whether it has caught up. */
private val CAUGHT_UP_CHECK = 100.milliseconds

/** How long a worker that takes nothing keeps its partition's catch-up permit. */
internal val STALLED = 30.seconds
