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
 * The types an [AccessWalking] found without a store of their history ([me.ahoo.wow.spi.access.AccessWalks.holds]): a
 * type still without one is read again after one run, then after twice as many runs each time, up to [MAX_SKIPPED],
 * so a type no one writes costs a read now and then, not one a run.
 */
internal class AbsentTypes {
    private class Absent {
        @Volatile
        var skip: Int = 0

        @Volatile
        var left: Int = 0
    }

    private val types = ConcurrentHashMap<AggregateType, Absent>()

    /** Whether this run reads whether [type] holds a store: always unless found absent, else when its wait ends. */
    fun due(type: AggregateType): Boolean {
        val absent = types[type] ?: return true
        if (absent.left > 0) {
            absent.left--
            return false
        }
        return true
    }

    /** A run found [type] without a store: read again after twice as many runs as last time, up to [MAX_SKIPPED]. */
    fun absent(type: AggregateType) {
        val absent = types.computeIfAbsent(type) { Absent() }
        absent.skip = (absent.skip * 2).coerceIn(1, MAX_SKIPPED)
        absent.left = absent.skip
    }

    /** A run found [type]'s store: it is read every run again. */
    fun found(type: AggregateType) {
        types.remove(type)
    }

    companion object {
        /** The most runs a type without a store is not read again for: 16, about 80 s at the default interval. */
        const val MAX_SKIPPED: Int = 16
    }
}
