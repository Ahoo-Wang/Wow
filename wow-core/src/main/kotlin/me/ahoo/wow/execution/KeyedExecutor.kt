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

package me.ahoo.wow.execution

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import reactor.core.scheduler.NonBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The execution resource every dispatcher of one [me.ahoo.wow.runtime.WowRuntime] shares (design X7): one set of
 * [workers] threads, sized by CPU cores rather than by the number of aggregate types or dispatchers.
 *
 * A dispatcher keeps one mailbox per aggregate ID. A mailbox runs its messages one at a time, in arrival order, on any
 * worker; different mailboxes run in parallel. A handler that waits (I/O, a retry backoff) holds no worker and delays
 * only its own mailbox. Each dispatcher accepts at most [maxInFlight] messages it has not finished, and requests more
 * from its transport only as they finish, so a slow dispatcher backpressures its source instead of buffering.
 *
 * A `suspend` or `Flow` message function called by a dispatcher resumes on these workers
 * ([coroutineDispatcher]) instead of `Dispatchers.Default`; its mailbox does not start the next message until it
 * returns, so per-aggregate serialization holds across suspension points.
 *
 * The runtime owns its executor and disposes it once every component has stopped.
 *
 * @param workers the number of worker threads; defaults to the available processors.
 * @param maxInFlight the most messages one dispatcher holds unfinished (running or queued in a mailbox).
 * @param name the worker thread name prefix.
 * @param throughput the most messages of one aggregate a worker runs in one turn (while they complete synchronously)
 * before it moves on to other aggregates, so one hot aggregate cannot starve the others.
 */
class KeyedExecutor(
    val workers: Int = DEFAULT_WORKERS,
    val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    val name: String = DEFAULT_NAME,
    val throughput: Int = DEFAULT_THROUGHPUT,
) : AutoCloseable {
    init {
        require(workers > 0) { "workers must be positive." }
        require(maxInFlight > 0) { "maxInFlight must be positive." }
        require(throughput > 0) { "throughput must be positive." }
    }

    private val threadId = AtomicInteger()

    /**
     * The workers: daemon threads, started on demand up to [workers]. They are Reactor [NonBlocking] threads, like the
     * `Schedulers.newParallel` threads they replace: `block()` on them fails fast, and a `@Blocking` message function
     * moves to `boundedElastic` instead of holding a shared worker. A mailbox is submitted as a plain [Runnable]
     * (one queue node per run, no future), and a task submitted after [close] is rejected.
     */
    internal val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        DispatchThread(runnable, "$name-${threadId.incrementAndGet()}")
    }

    /** The workers as a coroutine dispatcher, for `suspend` and `Flow` message functions. */
    val coroutineDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val isDisposed: Boolean
        get() = executor.isShutdown

    /** Stops the workers once their queued runs finish; a run submitted afterwards is rejected. Idempotent. */
    override fun close() {
        executor.shutdown()
    }

    override fun toString(): String =
        "KeyedExecutor(name=$name, workers=$workers, maxInFlight=$maxInFlight, throughput=$throughput)"

    companion object {
        const val DEFAULT_NAME: String = "wow-dispatch"

        @JvmField
        val DEFAULT_WORKERS: Int = Runtime.getRuntime().availableProcessors()

        /** Matches the upstream demand of the `groupBy` pipeline this executor replaces. */
        const val DEFAULT_MAX_IN_FLIGHT: Int = 256

        /** Messages of one aggregate per turn; a turn also ends when a handler does not complete synchronously. */
        const val DEFAULT_THROUGHPUT: Int = 16

        /**
         * The executor of a [me.ahoo.wow.runtime.RuntimeContext] that does not bring its own (a dispatcher prepared
         * outside a [me.ahoo.wow.runtime.WowRuntime], as in tests). Its daemon threads are never disposed.
         */
        @JvmStatic
        val shared: KeyedExecutor by lazy {
            KeyedExecutor(name = "$DEFAULT_NAME-shared")
        }
    }
}

/** A dispatch worker: a daemon thread Reactor treats as non-blocking. */
private class DispatchThread(runnable: Runnable, name: String) : Thread(runnable, name), NonBlocking {
    init {
        isDaemon = true
    }
}
