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

package me.ahoo.wow.runtime.internal

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One bounded daemon thread pool shared by the runtime's lifecycle lanes (shutdown, terminal delivery, termination
 * control, physical cleanup).
 *
 * Each lane runs at most its own concurrency on the pool and queues the rest up to its own capacity. Lanes cannot
 * starve each other: the pool's thread cap is the sum of the lanes' concurrency, so a lane whose tasks block (a
 * terminal observer that never returns) holds only its own threads. Idle threads time out.
 *
 * The lanes bound the work, not the pool: a lane releases its slot just before its pool thread returns, so a task
 * submitted in that instant can find every pool thread still busy. The pool therefore keeps its threads as core
 * threads and hands such a task to an unbounded queue, where it waits only for that thread to finish returning. It is
 * never rejected while its lane has room, and the queue cannot grow beyond the lanes' concurrency.
 */
internal class RuntimeLifecyclePool(
    private val threadNamePrefix: String,
    private val maxThreads: Int,
    keepAliveSeconds: Long = DEFAULT_KEEP_ALIVE_SECONDS,
) {
    init {
        require(maxThreads > 0) {
            "maxThreads must be positive."
        }
    }

    private val threadId = AtomicInteger()
    private val reservedThreads = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        maxThreads,
        maxThreads,
        keepAliveSeconds,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "$threadNamePrefix-${threadId.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    /**
     * Reserves [concurrency] of this pool's threads for a lane named [name] (its threads carry that name while they
     * run its tasks), which queues at most [queueCapacity] tasks beyond them.
     */
    fun lane(name: String, concurrency: Int, queueCapacity: Int): RuntimeLifecycleLane {
        require(concurrency > 0) {
            "concurrency must be positive."
        }
        require(queueCapacity >= 0) {
            "queueCapacity must not be negative."
        }
        val reserved = reservedThreads.addAndGet(concurrency)
        if (reserved > maxThreads) {
            reservedThreads.addAndGet(-concurrency)
            error("Lifecycle pool[$threadNamePrefix] would reserve $reserved threads, more than its $maxThreads.")
        }
        return RuntimeLifecycleLane(name, concurrency, queueCapacity, executor)
    }

    private companion object {
        const val DEFAULT_KEEP_ALIVE_SECONDS: Long = 60
    }
}

/**
 * A bounded lane of a [RuntimeLifecyclePool]: at most `concurrency` tasks run at once, at most `queueCapacity` wait,
 * and a task beyond both is rejected with [RejectedExecutionException]. [dispose] drops the waiting tasks, interrupts
 * the running ones and rejects every later task.
 */
internal class RuntimeLifecycleLane(
    private val name: String,
    private val concurrency: Int,
    private val queueCapacity: Int,
    private val pool: Executor,
) : Executor {
    private val monitor = Any()
    private val queue = ArrayDeque<Runnable>()
    private val running = HashSet<Thread>()
    private var active = 0
    private var disposed = false
    private val threadId = AtomicInteger()

    override fun execute(command: Runnable) {
        synchronized(monitor) {
            if (disposed) {
                throw RejectedExecutionException("Lifecycle lane[$name] is disposed.")
            }
            if (active >= concurrency) {
                if (queue.size >= queueCapacity) {
                    throw RejectedExecutionException("Lifecycle lane[$name] is saturated.")
                }
                queue.addLast(command)
                return
            }
            active++
        }
        try {
            pool.execute { drain(command) }
        } catch (error: RejectedExecutionException) {
            synchronized(monitor) { active-- }
            throw error
        }
    }

    /** Removes [command] if it is still waiting; a task that already runs is not affected. */
    fun cancel(command: Runnable): Boolean =
        synchronized(monitor) {
            queue.removeFirstOccurrence(command)
        }

    fun dispose() {
        synchronized(monitor) {
            if (disposed) {
                return
            }
            disposed = true
            queue.clear()
            // Interrupt under the monitor: a thread leaves `running` only under it, so every thread interrupted here
            // is still inside this lane's task and clears the interrupt before the pool hands it another lane's task.
            running.forEach(Thread::interrupt)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun drain(first: Runnable) {
        val thread = Thread.currentThread()
        val poolName = thread.name
        thread.name = "$name-${threadId.incrementAndGet()}"
        var task: Runnable? = first
        try {
            while (task != null) {
                synchronized(monitor) {
                    running.add(thread)
                    // Admitted before disposal, but its thread starts after it: disposal could not interrupt it.
                    if (disposed) {
                        thread.interrupt()
                    }
                }
                try {
                    task.run()
                } catch (error: Throwable) {
                    thread.uncaughtExceptionHandler?.uncaughtException(thread, error)
                } finally {
                    synchronized(monitor) { running.remove(thread) }
                    // A lane-disposal interrupt targets the task, not the pool thread.
                    Thread.interrupted()
                }
                task = synchronized(monitor) {
                    queue.pollFirst().also { if (it == null) active-- }
                }
            }
        } finally {
            thread.name = poolName
        }
    }
}
