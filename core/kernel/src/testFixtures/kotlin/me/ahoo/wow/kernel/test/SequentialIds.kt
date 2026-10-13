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

package me.ahoo.wow.kernel.test

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.id.Ids

/**
 * The ids the test DSL gives domain code (decision D171), the same on every run: the n-th global id is `id-n`, the
 * n-th id of an aggregate type is `<aggregate>-n` (`order-1`, `order-2`), each counted from 1.
 *
 * An [aggregateSpec] gives them to its decide functions, counted across the whole specification (a fork replays its
 * prefix, so it counts as the specification did up to there). A [sagaSpec] gives a saga the ids it takes on a node,
 * name-based UUIDs of its handling ([me.ahoo.wow.kernel.HandlingIds]).
 */
class SequentialIds : Ids {
    private var global = 0
    private val aggregates = HashMap<AggregateType, Int>()

    @Synchronized
    override fun next(): String = "$GLOBAL-${++global}"

    @Synchronized
    override fun next(type: AggregateType): String = "${type.name}-${aggregates.merge(type, 1, Int::plus)}"

    companion object {
        /** The prefix of the global ids. */
        const val GLOBAL: String = "id"
    }
}
