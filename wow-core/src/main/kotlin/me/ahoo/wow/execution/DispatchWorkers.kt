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

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.Exceptions
import reactor.core.scheduler.NonBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.locks.LockSupport

/**
 * The worker threads of a [KeyedExecutor]: each worker has its own lock-free FIFO queue, and a task goes to the worker
 * its affinity selects (a mailbox always to the same one, chosen by [nextAffinity] when the mailbox is created). A
 * busy worker picks up newly queued tasks without being woken; an idle worker is unparked only by the submission that
 * finds it parked. There is no shared queue: no lock, and no cascade of wake-ups between workers.
 *
 * A worker thread starts on its first submission, so a runtime that never dispatches (a gateway-only service) starts
 * none. A task's failure, a JVM-fatal error included, is contained: the worker logs it and goes on, so the mailboxes
 * pinned to it are never stranded on a dead thread.
 */
internal class DispatchWorkers(
    size: Int,
    name: String,
    /** Starts a worker thread; a test replaces it to make a start fail. */
    private val startThread: (Thread) -> Unit = Thread::start,
) : Executor {
    private val workers: Array<Worker> = Array(size) { Worker("$name-${it + 1}") }
    private val nextWorker = AtomicInteger()

    @Volatile
    var closed: Boolean = false
        private set

    /** Set by [forceClose]: queued tasks are discarded instead of run. */
    @Volatile
    var forced: Boolean = false
        private set

    /** A task that can be dropped without running when the workers are force-closed. */
    interface Discardable {
        /** Called instead of running the task; it must release what the task holds (e.g. reject its messages). */
        fun discard()
    }

    /**
     * Runs [task] on the worker [affinity] selects. Rejects once [close]d: either before queueing, or — when the
     * worker exited while the task was being queued — by taking the task back, so a task is always run or rejected.
     */
    fun execute(task: Runnable, affinity: Int) {
        if (closed) {
            throw RejectedExecutionException("Dispatch workers are closed.")
        }
        workers[Math.floorMod(affinity, workers.size)].submit(task)
    }

    /**
     * Runs [task] on the next worker in turn (coroutine resumptions). A dead worker rejects it; kotlinx.coroutines then
     * cancels the coroutine's job and runs the task on `Dispatchers.IO`, so the coroutine ends with its cancellation
     * instead of hanging.
     */
    override fun execute(task: Runnable) {
        execute(task, nextWorker.getAndIncrement())
    }

    /**
     * A worker index for a new mailbox. Waking a parked worker costs more than a short wait behind a running one, so
     * starting from the next worker in turn it picks the first running worker with an empty queue, else the first
     * parked one, else the next in turn.
     */
    fun nextAffinity(): Int {
        val start = Math.floorMod(nextWorker.getAndIncrement(), workers.size)
        var parked = -1
        for (offset in workers.indices) {
            val index = (start + offset) % workers.size
            val worker = workers[index]
            if (worker.dead) {
                continue
            }
            if (worker.idle == 0) {
                if (worker.hasNoQueuedTask()) {
                    return index
                }
            } else if (parked < 0) {
                parked = index
            }
        }
        if (parked >= 0) {
            return parked
        }
        // Every live worker is busy: the next live one in turn. Only when all are dead is a dead one returned, and its
        // submissions are rejected rather than stranded.
        for (offset in workers.indices) {
            val index = (start + offset) % workers.size
            if (!workers[index].dead) {
                return index
            }
        }
        return start
    }

    /** Lets every worker finish its queued tasks and exit; later submissions are rejected. Idempotent. */
    fun close() {
        closed = true
        workers.forEach { LockSupport.unpark(it) }
    }

    /**
     * Stops at once, like disposing a Reactor scheduler: later submissions are rejected, and every queued task is
     * discarded ([Discardable.discard]) on the calling thread instead of run. A task already running finishes its
     * current step; a mailbox does not start another message once [forced] is set. Idempotent.
     */
    fun forceClose() {
        forced = true
        close()
        workers.forEach { it.discardQueued() }
    }

    private inner class Worker(name: String) : Thread(name), NonBlocking {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        /** Set once the worker has decided to exit; a submission seeing it takes its task back. */
        @Volatile
        private var exited = false

        /** 1 once the thread has been started (lazily, by the first submission). */
        @Volatile
        @JvmField
        var started: Int = 0

        /**
         * Set if the thread ends abnormally. It should not ([runOrDiscard] contains every failure), but if it does,
         * [nextAffinity] no longer picks it and its submissions are rejected (failing their dispatch) instead of
         * waiting forever in a queue nobody drains.
         */
        @Volatile
        var dead = false
            private set

        /** 1 while the worker is about to park or parked: the submission that resets it unparks the worker. */
        @Volatile
        @JvmField
        var idle: Int = 0

        init {
            isDaemon = true
        }

        fun hasNoQueuedTask(): Boolean = queue.isEmpty()

        fun submit(task: Runnable) {
            if (started == 0 && STARTED.compareAndSet(this, 0, 1)) {
                startOrDie()
            }
            queue.offer(task)
            // The worker sets `exited` and then drains once more: if the drain did not take this task, take it back.
            if (exited && queue.remove(task)) {
                throw RejectedExecutionException("Dispatch workers are closed.")
            }
            if (idle == 1 && IDLE.compareAndSet(this, 1, 0)) {
                LockSupport.unpark(this)
            }
        }

        /**
         * Starts the thread. If it cannot start (no native thread left), the worker is dead: it is no longer selected,
         * a task queued meanwhile by another submission is discarded, and this submission is rejected.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun startOrDie() {
            try {
                startThread(this)
            } catch (error: Throwable) {
                dead = true
                exited = true
                log.error(error) { "Dispatch worker [$name] could not start; it is no longer selected." }
                discardQueued()
                throw RejectedExecutionException("Dispatch worker [$name] could not start.", error)
            }
        }

        fun discardQueued() {
            while (true) {
                discard(queue.poll() ?: return)
            }
        }

        override fun run() {
            var drained = false
            try {
                while (true) {
                    val task = queue.poll() ?: awaitTask() ?: break
                    runOrDiscard(task)
                }
                drained = true
            } finally {
                exited = true
                if (!drained) {
                    dead = true
                    log.error { "Dispatch worker [$name] ended unexpectedly; it is no longer selected." }
                    discardQueued()
                }
            }
            // A task queued after the last check and before `exited` was published: run (or discard) it here.
            while (true) {
                runOrDiscard(queue.poll() ?: return)
            }
        }

        /**
         * Runs [task] and contains any failure, so a shared worker outlives a failing task as the 9.2 `newParallel`
         * threads did. A JVM-fatal error is logged at ERROR; a mailbox has already failed its dispatch with it, which
         * reports it to the runtime.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun runOrDiscard(task: Runnable) {
            try {
                if (forced) {
                    discard(task)
                } else {
                    task.run()
                }
            } catch (error: Throwable) {
                contain(error)
            }
        }

        /**
         * The next task, parking until one is queued; `null` once closed and drained. (Spinning before parking was
         * measured: it did not pay for the CPU it burns.)
         */
        private fun awaitTask(): Runnable? {
            while (true) {
                idle = 1
                // Re-check after publishing idle: a submission either sees idle == 1 (and unparks) or was queued first.
                queue.poll()?.let {
                    idle = 0
                    return it
                }
                if (closed) {
                    idle = 0
                    return null
                }
                LockSupport.park(this)
                idle = 0
                queue.poll()?.let { return it }
                if (closed && queue.isEmpty()) {
                    return null
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun discard(task: Runnable) {
        try {
            (task as? Discardable)?.discard()
        } catch (error: Throwable) {
            contain(error)
        }
    }

    /**
     * Reports a task failure without rethrowing it: a JVM-fatal error is logged at ERROR, any other goes to the
     * thread's uncaught-exception handler, whose own failure is logged too.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun contain(error: Throwable) {
        val thread = Thread.currentThread()
        if (Exceptions.isJvmFatal(error)) {
            log.error(error) { "Dispatch worker [${thread.name}] contained a fatal error thrown by a task." }
            return
        }
        try {
            thread.uncaughtExceptionHandler?.uncaughtException(thread, error)
        } catch (handlerError: Throwable) {
            log.error(handlerError) { "Dispatch worker [${thread.name}] uncaught-exception handler failed." }
        }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
        val STARTED: AtomicIntegerFieldUpdater<Worker> =
            AtomicIntegerFieldUpdater.newUpdater(Worker::class.java, "started")
        val IDLE: AtomicIntegerFieldUpdater<Worker> =
            AtomicIntegerFieldUpdater.newUpdater(Worker::class.java, "idle")
    }
}
