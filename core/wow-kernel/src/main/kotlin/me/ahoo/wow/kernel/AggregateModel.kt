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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Decision

/**
 * A decide function, in the form its signature has (decision D36). The kernel calls a [Sync] one directly, with no
 * continuation and no dispatch; only a [Suspending] one runs as a coroutine call.
 */
sealed interface DecideFunction<S : Any> {
    /** Whether the command may create the aggregate: it is decided on the initial state when none exists yet. */
    val create: Boolean

    class Sync<S : Any>(override val create: Boolean, val decide: (S, Any) -> Decision) : DecideFunction<S>

    class Suspending<S : Any>(override val create: Boolean, val decide: suspend (S, Any) -> Decision) :
        DecideFunction<S>
}

/** How an event type is named in the log and which schema revision the model reads it as. */
data class EventType(val name: String, val revision: Int, val type: Class<*>) {
    init {
        require(revision >= 1) { "An event revision starts at 1." }
    }
}

/**
 * An aggregate type compiled into tables: what each command type runs, how each event type evolves the state, and
 * how each event type is named in the log. Built once, read on every command; the hot path only looks things up.
 *
 * State is mutable by default (decision D7): [evolve] may change [S] in place and return it. The kernel never lets
 * a changed instance outlive a failure: it evolves a state freshly loaded for the attempt and drops it if the
 * attempt does not commit.
 */
class AggregateModel<S : Any>(
    val type: AggregateType,
    private val initial: (AggregateRef) -> S,
    private val deciders: Map<Class<*>, DecideFunction<S>>,
    private val evolvers: Map<Class<*>, (S, Any) -> S>,
    eventTypes: Collection<EventType>,
) {
    private val eventsByType: Map<Class<*>, EventType> = eventTypes.associateBy { it.type }
    private val eventsByName: Map<String, EventType> = eventTypes.associateBy { it.name }

    init {
        require(eventsByName.size == eventTypes.size) { "Two event types of $type share a name." }
        val missing = eventTypes.map { it.type } - evolvers.keys
        require(missing.isEmpty()) { "Event types of $type without an evolve function: $missing" }
    }

    /** The state of an aggregate that has no event yet. */
    fun initialState(ref: AggregateRef): S = initial(ref)

    /** The decide function for [commandType], or null when the aggregate does not handle it. */
    fun decider(commandType: Class<*>): DecideFunction<S>? = deciders[commandType]

    /** Applies [event] to [state]. */
    fun evolve(state: S, event: Any): S {
        val evolver = requireNotNull(evolvers[event.javaClass]) { "$type cannot evolve ${event.javaClass.name}." }
        return evolver(state, event)
    }

    /** The log name and revision of [eventClass]. */
    fun eventType(eventClass: Class<*>): EventType =
        requireNotNull(eventsByType[eventClass]) { "${eventClass.name} is not an event of $type." }

    /** The event type named [name] in the log. */
    fun eventType(name: String): EventType =
        requireNotNull(eventsByName[name]) { "$type has no event named $name." }
}
