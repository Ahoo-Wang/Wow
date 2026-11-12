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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.health.launchWorker

/**
 * The lanes of one [SubscriptionWorker], [name]d: one an aggregate with items queued, drained in [scope] one item after
 * another, in their order, until the lane is empty; the aggregate's next item opens a new lane. Each item is run by
 * [handle], which handles its own failures. Whatever escapes it, but the scope's stop, is told to [stopped] with the
 * aggregate, and ends the lane as a loop of the node ends ([launchWorker]), the worker's lanes named once: the node's
 * liveness is `DOWN`, or the node fatal for a fatal error.
 * The lane stays active, so the aggregate's later entries wait behind the one it did not settle rather than run past
 * it, until a restart reads the partition again from its checkpoint. Every section runs under [monitor], the worker's.
 */
internal class AggregateLanes<I : Any>(
    private val name: String,
    private val monitor: Any,
    private val scope: CoroutineScope,
    private val handle: suspend (ref: AggregateRef, lane: Lane<I>, item: I) -> Unit,
    private val stopped: (ref: AggregateRef, item: I, failure: Throwable) -> Unit,
) {
    private val active = HashMap<AggregateRef, Lane<I>>()

    /** The loop name of every lane, made once: a lane opens for nearly every entry. */
    private val loop = "lanes of $name"

    /** One aggregate's queued items. */
    class Lane<I> {
        val items = ArrayDeque<I>()
    }

    /** Called under the monitor: queues [item] on [ref]'s lane, [first] or last, opening the lane when it has none. */
    fun enqueue(ref: AggregateRef, item: I, first: Boolean) {
        val lane = active[ref] ?: Lane<I>().also {
            active[ref] = it
            scope.launchWorker(loop) { next(ref, it) }
        }
        if (first) lane.items.addFirst(item) else lane.items.addLast(item)
    }

    /** Called under the monitor: forgets every lane, returning the items still queued in them. */
    fun clear(): List<I> = active.values.flatMap { it.items }.also { active.clear() }

    /** Runs the next item of [ref]'s [lane]: false once the lane is empty, and forgotten. */
    @Suppress("TooGenericExceptionCaught") // Whatever escaped the item ends the lane, its action answered first.
    private suspend fun next(ref: AggregateRef, lane: Lane<I>): Boolean {
        val item = synchronized(monitor) {
            lane.items.removeFirstOrNull() ?: run {
                active.remove(ref)
                null
            }
        } ?: return false
        try {
            handle(ref, lane, item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            stopped(ref, item, failure)
            throw failure
        }
        return true
    }
}
