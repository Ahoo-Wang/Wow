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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.EventStore
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the `whenCommand`s that branch from one given stage independent, as they were before the DSL drove the
 * production pipeline: each starts from the history as it was when the given stage was reached, not after the
 * commands of its siblings (several `whenCommand`s in one `on { }` or `given(...) { }` block, or several `given`s on
 * one stage).
 *
 * The first `whenCommand` for an aggregate runs on the given stage's own stores. Every later one runs on new in-memory
 * stores holding a copy of the history up to the base point: the version the aggregate had before any sibling ran.
 */
internal class SiblingHistory {
    private data class Key(val eventStore: EventStore, val aggregateId: AggregateId)

    private val basePoints = ConcurrentHashMap<Key, Mono<Int>>()

    /**
     * The runtime a `whenCommand` for [shared]'s aggregate runs on, and the work that puts the base history into it
     * (none for the first sibling, which runs on [shared] itself).
     */
    fun <C : Any, S : Any> branch(shared: AggregateTestRuntime<C, S>): Branch<C, S> {
        val key = Key(shared.eventStore, shared.aggregateId)
        var first = false
        val basePoint = basePoints.computeIfAbsent(key) {
            first = true
            Mono.defer { shared.version() }.cache()
        }
        if (first) {
            return Branch(shared, basePoint.then())
        }
        val isolated = shared.isolated()
        return Branch(isolated, basePoint.flatMap { isolated.copyHistory(shared, it) })
    }

    class Branch<C : Any, S : Any>(
        val runtime: AggregateTestRuntime<C, S>,
        /** Measures the base point (the first branch) or copies the base history (the others). */
        val start: Mono<Void>,
    )
}
