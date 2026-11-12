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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * The lanes of one [SubscriptionWorker]: one an aggregate with items queued, drained in [scope] one item after another,
 * in their order, until the lane is empty; the aggregate's next item opens a new lane. Each item is run by [handle];
 * one that meets a [VirtualMachineError] is told to [stopped], and the error is thrown on, the lane stopping there.
 * Every section runs under [monitor], the worker's.
 */
internal class AggregateLanes<I : Any>(
    private val monitor: Any,
    private val scope: CoroutineScope,
    private val handle: suspend (ref: AggregateRef, lane: Lane<I>, item: I) -> Unit,
    private val stopped: (item: I, error: VirtualMachineError) -> Unit,
) {
    private val active = HashMap<AggregateRef, Lane<I>>()

    /** One aggregate's queued items. */
    class Lane<I> {
        val items = ArrayDeque<I>()
    }

    /** Called under the monitor: queues [item] on [ref]'s lane, [first] or last, opening the lane when it has none. */
    fun enqueue(ref: AggregateRef, item: I, first: Boolean) {
        val lane = active[ref] ?: Lane<I>().also {
            active[ref] = it
            scope.launch { drain(ref, it) }
        }
        if (first) lane.items.addFirst(item) else lane.items.addLast(item)
    }

    /** Called under the monitor: forgets every lane, returning the items still queued in them. */
    fun clear(): List<I> = active.values.flatMap { it.items }.also { active.clear() }

    private suspend fun drain(ref: AggregateRef, lane: Lane<I>) {
        while (true) {
            val item = synchronized(monitor) {
                lane.items.removeFirstOrNull() ?: run {
                    active.remove(ref)
                    null
                }
            } ?: return
            try {
                handle(ref, lane, item)
            } catch (error: VirtualMachineError) {
                stopped(item, error)
                throw error
            }
        }
    }
}
