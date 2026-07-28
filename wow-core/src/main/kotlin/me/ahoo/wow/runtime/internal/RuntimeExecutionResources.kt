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

import me.ahoo.wow.infra.lifecycle.TerminalSignal
import me.ahoo.wow.infra.lifecycle.TerminalSignalDispatcher
import me.ahoo.wow.infra.lifecycle.newTerminalSignalDispatcher
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/**
 * Runtime-owned execution boundaries.
 *
 * Terminal notification and physical cleanup use dedicated bounded resources.
 * Public terminal callbacks must return promptly; physical cleanup remains
 * best-effort and is strictly bounded by [RuntimeCleanupExecutor].
 */
internal interface RuntimeExecutionResources {
    val terminationDispatcher: TerminalSignalDispatcher

    val terminationControlDispatcher: TerminalSignalDispatcher

    val shutdownScheduler: Scheduler

    val quiescenceScheduler: Scheduler

    fun dispatchCleanup(action: Runnable): Boolean
}

internal object DefaultRuntimeExecutionResources : RuntimeExecutionResources {
    private const val TERMINATION_CONTROL_THREAD_CAP: Int = 4
    private const val TERMINATION_CONTROL_QUEUE_CAPACITY: Int = 256
    private const val SHUTDOWN_THREAD_CAP: Int = 4
    private const val SHUTDOWN_QUEUE_CAPACITY: Int = 256
    private const val SHUTDOWN_THREAD_TTL_SECONDS: Int = 60

    override val terminationDispatcher: TerminalSignalDispatcher =
        TerminalSignal.dispatcher
    override val terminationControlDispatcher: TerminalSignalDispatcher =
        newTerminalSignalDispatcher(
            threadNamePrefix = "wow-runtime-termination-control",
            threadCap = TERMINATION_CONTROL_THREAD_CAP,
            queuedTaskCapacity = TERMINATION_CONTROL_QUEUE_CAPACITY,
        )
    override val shutdownScheduler: Scheduler = Schedulers.newBoundedElastic(
        SHUTDOWN_THREAD_CAP,
        SHUTDOWN_QUEUE_CAPACITY,
        "wow-runtime-shutdown",
        SHUTDOWN_THREAD_TTL_SECONDS,
        true,
    )
    override val quiescenceScheduler: Scheduler = Schedulers.parallel()

    override fun dispatchCleanup(action: Runnable): Boolean =
        RuntimeCleanupExecutor.execute(action)
}
