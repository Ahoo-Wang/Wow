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

/**
 * The execution resource every dispatcher of one [me.ahoo.wow.runtime.WowRuntime] shares (design X7): one set of
 * [workers] threads, sized by CPU cores rather than by the number of aggregate types or dispatchers.
 *
 * A dispatcher keeps one mailbox per aggregate ID. A mailbox runs its messages one at a time, in arrival order, on the
 * worker it was given when it was created (a running worker with nothing queued, else a parked one); different
 * mailboxes run in parallel. Each worker has its own FIFO queue, so a mailbox that has run [throughput] messages goes
 * behind the other mailboxes of its worker. A handler that waits (I/O, a retry backoff) holds no worker and delays
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

    /**
     * The workers: daemon threads that Reactor treats as [NonBlocking][reactor.core.scheduler.NonBlocking], like the
     * `Schedulers.newParallel` threads they replace: `block()` on them fails fast, and a `@Blocking` message function
     * moves to `boundedElastic` instead of holding a shared worker. A task submitted after [close] is rejected.
     */
    internal val dispatchWorkers: DispatchWorkers = DispatchWorkers(workers, name)

    /** The workers as a coroutine dispatcher, for `suspend` and `Flow` message functions. */
    val coroutineDispatcher: CoroutineDispatcher = dispatchWorkers.asCoroutineDispatcher()

    val isDisposed: Boolean
        get() = dispatchWorkers.closed

    /** Stops the workers once their queued runs finish; a run submitted afterwards is rejected. Idempotent. */
    override fun close() {
        dispatchWorkers.close()
    }

    /**
     * Stops the workers at once (the runtime's force stop, like disposing a Reactor scheduler): queued mailboxes are
     * discarded on the calling thread — their messages are rejected and stay unacknowledged for redelivery — and no
     * mailbox starts another message. A handler already running finishes its current step. Idempotent.
     */
    fun forceClose() {
        dispatchWorkers.forceClose()
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
