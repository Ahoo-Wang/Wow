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

import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime-owned execution boundaries: the only owner of the runtime's lifecycle threads.
 *
 * Shutdown, terminal notification, termination control and physical cleanup each run on their own bounded lane of
 * one lifecycle pool ([RuntimeLifecyclePool]), so none of them can occupy another's threads; shutdown deadlines run
 * on one timer thread. Public terminal callbacks must return promptly; physical cleanup remains best-effort and is
 * strictly bounded by its lane.
 */
internal interface RuntimeExecutionResources {
    val terminationDispatcher: TerminalSignalDispatcher

    val terminationControlDispatcher: TerminalSignalDispatcher

    val shutdownScheduler: Scheduler

    val quiescenceScheduler: Scheduler

    /** The timer that runs shutdown deadlines. */
    val deadlineScheduler: Scheduler
        get() = DefaultRuntimeExecutionResources.deadlineScheduler

    fun dispatchCleanup(action: Runnable): Boolean
}

internal object DefaultRuntimeExecutionResources : RuntimeExecutionResources {
    private const val TERMINATION_THREAD_CAP: Int = 8
    private const val TERMINATION_QUEUE_CAPACITY: Int = 256
    private const val TERMINATION_CONTROL_THREAD_CAP: Int = 4
    private const val TERMINATION_CONTROL_QUEUE_CAPACITY: Int = 256
    private const val SHUTDOWN_THREAD_CAP: Int = 4
    private const val SHUTDOWN_QUEUE_CAPACITY: Int = 256
    private const val CLEANUP_THREAD_CAP: Int = 8
    private const val CLEANUP_QUEUE_CAPACITY: Int = 256
    private const val LIFECYCLE_THREAD_CAP: Int =
        TERMINATION_THREAD_CAP + TERMINATION_CONTROL_THREAD_CAP + SHUTDOWN_THREAD_CAP + CLEANUP_THREAD_CAP
    private const val DEADLINE_THREAD_TTL_SECONDS: Long = 60

    private val deadlineThreadId = AtomicInteger()
    private val lifecyclePool = RuntimeLifecyclePool("wow-runtime-lifecycle", LIFECYCLE_THREAD_CAP)

    override val terminationDispatcher: TerminalSignalDispatcher =
        newTerminalSignalDispatcher(
            lane = lifecyclePool.lane("wow-terminal-signal", TERMINATION_THREAD_CAP, TERMINATION_QUEUE_CAPACITY),
            threadCap = TERMINATION_THREAD_CAP,
            queuedTaskCapacity = TERMINATION_QUEUE_CAPACITY,
        )
    override val terminationControlDispatcher: TerminalSignalDispatcher =
        newTerminalSignalDispatcher(
            lane = lifecyclePool.lane(
                "wow-runtime-termination-control",
                TERMINATION_CONTROL_THREAD_CAP,
                TERMINATION_CONTROL_QUEUE_CAPACITY,
            ),
            threadCap = TERMINATION_CONTROL_THREAD_CAP,
            queuedTaskCapacity = TERMINATION_CONTROL_QUEUE_CAPACITY,
        )
    override val shutdownScheduler: Scheduler = Schedulers.fromExecutor(
        lifecyclePool.lane("wow-runtime-shutdown", SHUTDOWN_THREAD_CAP, SHUTDOWN_QUEUE_CAPACITY),
        true,
    )
    override val quiescenceScheduler: Scheduler = Schedulers.parallel()
    override val deadlineScheduler: Scheduler = Schedulers.fromExecutorService(
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "wow-runtime-deadline-${deadlineThreadId.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(DEADLINE_THREAD_TTL_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        },
        "wow-runtime-deadline",
    )
    private val cleanupLane = lifecyclePool.lane("wow-runtime-cleanup", CLEANUP_THREAD_CAP, CLEANUP_QUEUE_CAPACITY)

    override fun dispatchCleanup(action: Runnable): Boolean =
        try {
            cleanupLane.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
}
