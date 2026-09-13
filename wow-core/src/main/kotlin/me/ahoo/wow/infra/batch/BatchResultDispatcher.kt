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

package me.ahoo.wow.infra.batch

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Publishes each request once, with a bounded queue and no caller-runs fallback. */
internal class BatchResultDispatcher(
    name: String,
    maxPendingItems: Int,
    private val lock: Any,
    private val onTerminated: () -> Unit,
) {
    private val dispatchContext = ThreadLocal<Boolean>()
    private val threadCount = RESULT_DISPATCHER_THREADS.coerceAtMost(maxPendingItems)
    private val executor = ThreadPoolExecutor(
        threadCount,
        threadCount,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingItems),
        { runnable ->
            Thread(runnable, "$name-batch-result-${RESULT_THREAD_SEQUENCE.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
    )
    private var sealed = false

    @Volatile
    private var shutdownCalled = false
    private val outstanding = AtomicInteger()
    private var drainClaimed = false

    val isDispatchingResult: Boolean
        get() = dispatchContext.get() == true

    /** The caller holds the result lock across settlement, publication and sealing. */
    fun publish(request: BatchRequest<*>) {
        if (sealed || !request.claimNotification()) {
            return
        }
        outstanding.incrementAndGet()
        try {
            executor.execute {
                dispatchContext.set(true)
                try {
                    request.signalSettled()
                } finally {
                    dispatchContext.remove()
                    val drained = outstanding.decrementAndGet() == 0 && shutdownCalled && synchronized(lock) {
                        claimDrain()
                    }
                    if (drained) {
                        onTerminated()
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            outstanding.decrementAndGet()
            request.releaseNotification()
            throw error
        }
    }

    /** Called under the result lock; grants the lock-free shutdown action to one caller. */
    fun seal(): Boolean {
        if (sealed) {
            return false
        }
        sealed = true
        return true
    }

    /** Called outside the result lock by the caller that sealed publication. */
    fun shutdown() {
        executor.shutdown()
        val drained = synchronized(lock) {
            shutdownCalled = true
            claimDrain()
        }
        if (drained) {
            onTerminated()
        }
    }

    private fun claimDrain(): Boolean {
        if (!shutdownCalled || outstanding.get() != 0 || drainClaimed) {
            return false
        }
        drainClaimed = true
        return true
    }

    private companion object {
        const val RESULT_DISPATCHER_THREADS: Int = 4
        val RESULT_THREAD_SEQUENCE: AtomicInteger = AtomicInteger()
    }
}
