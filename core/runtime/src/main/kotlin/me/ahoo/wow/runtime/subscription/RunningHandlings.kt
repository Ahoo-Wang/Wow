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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.ZeroAwaitingCounter
import java.util.concurrent.ConcurrentHashMap

/**
 * The handlings a [SubscriptionWorker] runs, as a stop sees them ([SubscriptionRuntime] halting its partition): what
 * the stop waits for ([awaitNone]) once the work not started is abandoned ([stop]).
 *
 * A handling enters as its lane starts it ([started]) and leaves once ([ended]). One the worker gives up as it stops
 * ([givenUp]: a retry, or a wait before an attempt, not started) leaves at once, so the stop does not wait for it, and
 * leaves nothing more as its lane's cancellation ends it. An aggregate runs one handling at a time, in its lane, so its
 * ref names the handling.
 */
internal class RunningHandlings {
    private val count = ZeroAwaitingCounter()
    private val stopped = CompletableDeferred<Unit>()

    /** The handlings given up and not ended yet, by their aggregate. */
    private val left: MutableSet<AggregateRef> = ConcurrentHashMap.newKeySet()

    /** Completed by [stop]: what a wait before an attempt also waits on, to give the entry up. */
    val stop: Deferred<Unit> get() = stopped

    /** How many handlings run now, those given up left out. */
    val value: Int get() = count.value

    /** The worker stops: its work not started is abandoned. */
    fun stop() {
        stopped.complete(Unit)
    }

    /** A handling starts in its lane. */
    fun started() {
        count.increment()
    }

    /** [ref]'s handling ends: it leaves, unless it left as it was given up. */
    fun ended(ref: AggregateRef) {
        if (!(stopped.isCompleted && left.remove(ref))) {
            count.decrement()
        }
    }

    /** [ref]'s handling is given up as the worker stops: it leaves now, and its end leaves nothing more. */
    fun givenUp(ref: AggregateRef) {
        if (left.add(ref)) {
            count.decrement()
        }
    }

    /** Returns once no handling runs. */
    suspend fun awaitNone() {
        count.awaitZero()
    }
}
