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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The waits started on this node (design 04 §6.3): a wait is registered before its command is executed, takes the
 * signals of its hops as they arrive, and is removed when its caller has its answer or gives up. A signal for a wait
 * this node does not hold (ended, timed out, or never here) is dropped. It holds at most the bound each registration
 * names (review R3.4-3): a registration beyond it is refused.
 */
@InternalWowApi
class WaitRegistry {
    private val waits = ConcurrentHashMap<String, Waiting>()
    private val registered = AtomicInteger()

    /** The waits registered now. */
    val size: Int get() = registered.get()

    /**
     * Applies [signal] to its wait.
     *
     * @return true when a wait took it; false when there is no such wait, it already ended, or the signal is a
     * duplicate.
     */
    fun signal(signal: WaitSignal): Boolean = waits[signal.waitId]?.offer(signal.result) ?: false

    /** Registers the wait [waitId] unless [max] waits are registered already: null then. */
    internal fun register(waitId: String, hops: List<Hop>, rootCommandId: String, max: Int = Int.MAX_VALUE): Waiting? {
        while (true) {
            val count = registered.get()
            if (count >= max) {
                return null
            }
            if (registered.compareAndSet(count, count + 1)) {
                break
            }
        }
        val waiting = Waiting(WaitState(hops, rootCommandId))
        if (waits.putIfAbsent(waitId, waiting) != null) {
            registered.decrementAndGet()
            error("Wait $waitId is already registered.")
        }
        return waiting
    }

    internal fun remove(waitId: String) {
        waits.remove(waitId)?.let { waiting ->
            registered.decrementAndGet()
            waiting.close()
        }
    }
}

/**
 * One registered wait: its [WaitState], guarded by this object's monitor, and the channel its hop results go out
 * through, closed after the final one.
 */
internal class Waiting(private val state: WaitState) {
    val results = Channel<CommandResult>(Channel.UNLIMITED)

    /** Completed once the wait's deadline has passed ([expire]), whether or not the wait had ended. */
    val deadlinePassed = CompletableDeferred<Unit>()

    /** Whether the wait ended. */
    val ended: Boolean get() = synchronized(this) { state.outcome != null }

    fun offer(result: CommandResult): Boolean = synchronized(this) { publish(state.offer(result)) }

    fun finish(result: CommandResult): Boolean = synchronized(this) { publish(state.finish(result)) }

    /**
     * The deadline passed (decision D64): ends the wait, unless it already ended, FAILED with [ResultCodes.WAIT_TIMEOUT]
     * at the last hop it reached, its [message] about that hop, at [now]; then completes [deadlinePassed]. Never runs
     * the waiter: it resumes in its own context.
     */
    fun expire(now: Long, message: (Hop) -> String) {
        synchronized(this) {
            if (state.outcome == null) {
                val last = checkNotNull(state.lastReached) { "A registered wait has reached ACCEPTED." }
                publish(
                    state.finish(last.failed(ResultCodes.WAIT_TIMEOUT, message(last.hop)).copyWith(signalTime = now))
                )
            }
        }
        deadlinePassed.complete(Unit)
    }

    /** [result] with the results its command answered at COMMITTED ([WaitState.carry]), as known now. */
    fun carry(result: CommandResult): CommandResult = synchronized(this) { state.carry(result) }

    fun close() {
        results.close()
    }

    /** Called under the monitor. */
    private fun publish(completed: List<CommandResult>?): Boolean {
        if (completed == null) {
            return false
        }
        completed.forEach { results.trySend(it) }
        if (state.outcome != null) {
            results.close()
        }
        return true
    }
}
