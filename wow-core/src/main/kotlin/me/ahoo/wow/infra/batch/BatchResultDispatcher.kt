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

/**
 * Isolates subscriber callbacks from batch lanes and reports when every
 * accepted result callback has drained.
 */
internal class BatchResultDispatcher(
    name: String,
    maxPendingItems: Int,
    private val onTerminated: () -> Unit,
) {
    private val dispatchContext = ThreadLocal<Boolean>()
    private val threadCount = RESULT_DISPATCHER_THREADS.coerceAtMost(maxPendingItems)
    private val executor = object : ThreadPoolExecutor(
        threadCount,
        threadCount,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingItems),
        { runnable ->
            Thread(
                runnable,
                "$name-batch-result-${RESULT_THREAD_SEQUENCE.incrementAndGet()}"
            ).apply {
                isDaemon = true
            }
        }
    ) {
        override fun terminated() {
            onTerminated()
        }
    }

    val isDispatchingResult: Boolean
        get() = dispatchContext.get() == true

    fun dispatch(signal: () -> Unit) {
        val contextualSignal = Runnable {
            val previousContext = dispatchContext.get()
            dispatchContext.set(true)
            try {
                signal()
            } finally {
                if (previousContext == null) {
                    dispatchContext.remove()
                } else {
                    dispatchContext.set(previousContext)
                }
            }
        }
        try {
            executor.execute(contextualSignal)
        } catch (_: RejectedExecutionException) {
            contextualSignal.run()
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    private companion object {
        const val RESULT_DISPATCHER_THREADS: Int = 4
        val RESULT_THREAD_SEQUENCE: AtomicInteger = AtomicInteger()
    }
}
