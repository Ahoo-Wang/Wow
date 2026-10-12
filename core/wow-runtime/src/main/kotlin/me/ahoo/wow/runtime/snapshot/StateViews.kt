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

package me.ahoo.wow.runtime.snapshot

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec

/**
 * The state views this node took as it committed (design 01 §2, 04 §1): the state after a stream, serialized once
 * while the aggregate's lane still holds it, so the snapshot subscription of the partition's owner writes it without
 * rebuilding the state and never touches the live, mutable instance.
 *
 * [record] runs in the executor's lane right after a commit, for the aggregate [types] whose snapshots are enabled;
 * it keeps the latest view of at most [capacity] aggregates, evicting the least recently used. A view is a cache: one
 * that is missing (evicted, taken on another node, or not recorded yet when the entry is handled) costs the
 * subscription a rebuild from the snapshot and the log's tail, never a wrong snapshot, since a view is used only for
 * the exact version it was taken at.
 */
class StateViews(
    private val codec: EventCodec,
    types: Collection<AggregateType>,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val types: Set<AggregateType> = types.toSet()
    private val views = LruMap<AggregateRef, View>(capacity)

    /** The number of views held. */
    val size: Int get() = views.size

    /** Takes the view of [committed]'s state, when [model]'s snapshots are enabled. */
    fun record(model: AggregateModel<*>, committed: CommandOutcome.Committed) {
        if (model.type in types) {
            views[committed.stream.ref] = View(committed.stream.version, codec.stateText(committed.state))
        }
    }

    /** Removes and returns the view of [ref] taken at [version], or null when there is none at that version. */
    fun take(ref: AggregateRef, version: Int): String? = views.removeIf(ref) { it.version == version }?.state

    private class View(val version: Int, val state: String)

    companion object {
        const val DEFAULT_CAPACITY = 4_096
    }
}
