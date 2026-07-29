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

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide, best-effort executor for cleanup code outside Wow's control.
 *
 * Both its worker count and retained queue are hard bounded. A permanently
 * blocked cleanup can therefore consume only a fixed process-wide budget;
 * callers must treat a `false` return as cleanup rejection and continue their
 * logical force-stop path.
 */
internal object RuntimeCleanupExecutor {
    private const val THREAD_CAPACITY: Int = 8
    private const val QUEUE_CAPACITY: Int = 256
    private const val THREAD_KEEP_ALIVE_SECONDS: Long = 30
    private val threadId = AtomicInteger()

    private val executor = ThreadPoolExecutor(
        THREAD_CAPACITY,
        THREAD_CAPACITY,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { command ->
            Thread(
                command,
                "wow-runtime-cleanup-${threadId.incrementAndGet()}",
            ).apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    internal fun execute(action: Runnable): Boolean =
        try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
}
