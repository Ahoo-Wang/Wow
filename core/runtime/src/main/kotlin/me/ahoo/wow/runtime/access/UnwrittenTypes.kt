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

package me.ahoo.wow.runtime.access

import me.ahoo.wow.api.aggregate.AggregateType
import java.util.concurrent.ConcurrentHashMap

/**
 * The types an [AccessWalking] found without a store of their history ([me.ahoo.wow.spi.access.AccessWalks.holds]),
 * as this node saw them since: a store created while it watched is of streams this version wrote, so the type's walk
 * is recorded complete over them instead of walked.
 *
 * - **Fresh**: a type stays fresh while every run that looked found no member of the walks serving it under another
 *   schema ([absent], [seen]). Then every stream its store holds was appended by a node of this schema, with its
 *   attributes: [created] says so once a run finds the store. One run that saw another schema's member makes it a
 *   type like any other, whose walk is claimed over its heads; so is one this node did not see without a store (it
 *   started after the store was created).
 * - **Backoff**: a type still without a store is read again after one run, then after twice as many runs each time,
 *   up to [MAX_SKIPPED]: a type no one writes costs a read now and then, not one a run. The members are looked at
 *   every run meanwhile, so a mixed deployment is never missed.
 */
internal class UnwrittenTypes {
    private class Unwritten {
        @Volatile
        var fresh: Boolean = true

        @Volatile
        var skip: Int = 0

        @Volatile
        var left: Int = 0
    }

    private val types = ConcurrentHashMap<AggregateType, Unwritten>()

    operator fun contains(type: AggregateType): Boolean = types.containsKey(type)

    /** A run's look at the members while [type] has no store: [mixed] when one served it under another schema. */
    fun seen(type: AggregateType, mixed: Boolean) {
        if (mixed) {
            types[type]?.fresh = false
        }
    }

    /** Whether this run reads whether [type] holds a store: always for a type not watched, else once its backoff ends. */
    fun due(type: AggregateType): Boolean {
        val unwritten = types[type] ?: return true
        if (unwritten.left > 0) {
            unwritten.left--
            return false
        }
        return true
    }

    /** A run found [type] without a store, [mixed] as [seen] says. */
    fun absent(type: AggregateType, mixed: Boolean) {
        val unwritten = types.computeIfAbsent(type) { Unwritten() }
        seen(type, mixed)
        unwritten.skip = (unwritten.skip * 2).coerceIn(1, MAX_SKIPPED)
        unwritten.left = unwritten.skip
    }

    /**
     * A run found [type]'s store, after it looked at the members ([seen]): whether this node watched it from before the
     * store was created, fresh throughout, so every stream it holds is this schema's. The type is no longer watched.
     */
    fun created(type: AggregateType): Boolean = types.remove(type)?.fresh == true

    companion object {
        /** The most runs a type without a store is not read again for: 16, about 80 s at the default interval. */
        const val MAX_SKIPPED: Int = 16
    }
}
