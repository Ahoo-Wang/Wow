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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.annotation.InternalWowApi
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Where a [WaitCoordinator] ends its waits at their deadlines, apart from their waiters: the half of its dependency on
 * time that its clock does not cover. [SHARDED], the runtime's deadline threads, unless a test holds the deadlines to
 * pass them itself.
 */
@InternalWowApi
fun interface WaitDeadlines {
    /** Runs [task] [delayMillis] from now, on none of the caller's threads; cancelling the future unschedules it. */
    fun schedule(delayMillis: Long, task: Runnable): Future<*>

    companion object {
        /**
         * Ends the waits of every coordinator of the process at their deadlines: daemon threads that only end them and
         * never run their waiters, which resume in their coordinator's execution context. Sharded: each
         * wait schedules its deadline on one picked at random and cancels it there, so the commands of a busy node
         * do not all take one delay queue's lock twice each.
         */
        val SHARDED: WaitDeadlines = ShardedDeadlines(
            List(Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_DEADLINE_SHARDS)) { shard ->
                ScheduledThreadPoolExecutor(1) { task ->
                    Thread(task, "wow-wait-deadlines-$shard").apply { isDaemon = true }
                }.apply { removeOnCancelPolicy = true }
            },
        )

        private const val MAX_DEADLINE_SHARDS = 4
    }
}

/** [WaitDeadlines.SHARDED]: each deadline on one of [shards], picked at random. */
private class ShardedDeadlines(private val shards: List<ScheduledExecutorService>) : WaitDeadlines {
    override fun schedule(delayMillis: Long, task: Runnable): Future<*> =
        shards[ThreadLocalRandom.current().nextInt(shards.size)].schedule(task, delayMillis, TimeUnit.MILLISECONDS)
}
