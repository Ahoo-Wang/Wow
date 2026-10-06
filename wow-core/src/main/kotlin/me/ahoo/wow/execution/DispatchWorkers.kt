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
 * its affinity selects (a mailbox always to the same one, like a 9.2 `publishOn` group to its scheduler thread). A
 * busy worker picks up newly queued tasks without being woken; an idle worker is unparked only by the submission that
 * finds it parked. There is no shared queue: no lock, and no cascade of wake-ups between workers.
 */
internal class DispatchWorkers(
    size: Int,
    name: String,
) : Executor {
    private val workers: Array<Worker> = Array(size) { Worker("$name-${it + 1}") }
    private val nextWorker = AtomicInteger()

    @Volatile
    var closed: Boolean = false
        private set

    init {
        workers.forEach(Thread::start)
    }

    /** Runs [task] on the worker [affinity] selects; rejects once [close]d. */
    fun execute(task: Runnable, affinity: Int) {
        if (closed) {
            throw RejectedExecutionException("Dispatch workers are closed.")
        }
        workers[Math.floorMod(affinity, workers.size)].submit(task)
    }

    /** Runs [task] on the next worker in turn (coroutine resumptions). */
    override fun execute(task: Runnable) {
        execute(task, nextWorker.getAndIncrement())
    }

    /** A worker index for a new mailbox: round robin, like the worker a 9.2 `publishOn` group was given. */
    fun nextAffinity(): Int = nextWorker.getAndIncrement()

    /** Lets every worker finish its queued tasks and exit; later submissions are rejected. Idempotent. */
    fun close() {
        closed = true
        workers.forEach { LockSupport.unpark(it) }
    }

    private inner class Worker(name: String) : Thread(name), NonBlocking {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        /** 1 while the worker is about to park or parked: the submission that resets it unparks the worker. */
        @Volatile
        @JvmField
        var idle: Int = 0

        init {
            isDaemon = true
        }

        fun submit(task: Runnable) {
            queue.offer(task)
            if (idle == 1 && IDLE.compareAndSet(this, 1, 0)) {
                LockSupport.unpark(this)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun run() {
            while (true) {
                val task = queue.poll() ?: awaitTask() ?: return
                try {
                    task.run()
                } catch (error: Throwable) {
                    Exceptions.throwIfJvmFatal(error)
                    uncaughtExceptionHandler?.uncaughtException(this, error)
                }
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

    private companion object {
        val IDLE: AtomicIntegerFieldUpdater<Worker> =
            AtomicIntegerFieldUpdater.newUpdater(Worker::class.java, "idle")
    }
}
