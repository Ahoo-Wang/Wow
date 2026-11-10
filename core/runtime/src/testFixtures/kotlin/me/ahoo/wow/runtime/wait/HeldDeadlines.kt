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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.result.CommandResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import kotlin.time.Duration.Companion.seconds

/**
 * The deadlines of a test's waits, held until it [passes][pass] them: a test that asserts how far a wait got before
 * its deadline passes it once the wait got there, instead of racing the machine's speed against a timeout.
 */
class HeldDeadlines : WaitDeadlines {
    private val held = CopyOnWriteArrayList<FutureTask<Unit>>()

    override fun schedule(delayMillis: Long, task: Runnable): Future<*> = FutureTask(task, Unit).also { held += it }

    /** Passes every deadline held: each one not cancelled ends its wait, here, at the last hop it reached. */
    fun pass() {
        held.forEach(FutureTask<Unit>::run)
    }

    /**
     * [results], a wait's, collected to their end, the deadlines passed once one of them [reached] where the test wants
     * the wait to time out: a hop's result is emitted once it is reached, so the timeout that ends them is at that hop.
     * Ten seconds bound a wait that never gets there, for a test that hangs instead of failing.
     */
    suspend fun timedOut(results: Flow<CommandResult>, reached: (CommandResult) -> Boolean): List<CommandResult> =
        withTimeout(10.seconds) { results.onEach { if (reached(it)) pass() }.toList() }

    /**
     * Passes the deadlines on [waits], the one thread a coordinator's waits and executions run on, behind what is
     * queued there now and what that queues in turn: an execution that ended has queued its waiter's continuation,
     * which reaches the wait's next hop before the deadline passes.
     */
    fun passOn(waits: Executor) {
        waits.execute { waits.execute(::pass) }
    }
}
