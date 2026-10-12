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

/** Builds an [AggregateModel] of state [S] for the aggregate [name] of bounded context [context]. */
fun <S : Any> aggregate(
    context: String,
    name: String,
    block: AggregateModelBuilder<S>.() -> Unit,
): AggregateModel<S> = AggregateModelBuilder<S>(AggregateType(context, name)).apply(block).build()

/**
 * The Kotlin DSL of an aggregate model. Event types are named `context.aggregate.EventName` unless a name is given.
 */
class AggregateModelBuilder<S : Any>(val type: AggregateType) {
    private var initial: ((AggregateRef) -> S)? = null
    private val deciders = LinkedHashMap<Class<*>, DecideFunction<S>>()
    private val evolvers = LinkedHashMap<Class<*>, (S, Any) -> S>()
    private val eventTypes = ArrayList<EventType>()

    /** The state of an aggregate that has no event yet. */
    fun initial(factory: (AggregateRef) -> S) {
        initial = factory
    }

    /** Decides commands of type [C] with a plain function: the fast path (D36). */
    inline fun <reified C : Any> decide(create: Boolean = false, noinline decide: (S, C) -> Decision) {
        @Suppress("UNCHECKED_CAST")
        addDecider(C::class.java, DecideFunction.Sync(create, decide as (S, Any) -> Decision))
    }

    /** Decides commands of type [C] with a suspending function, for decisions that read other systems. */
    inline fun <reified C : Any> decideSuspending(
        create: Boolean = false,
        noinline decide: suspend (S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(C::class.java, DecideFunction.Suspending(create, decide as suspend (S, Any) -> Decision))
    }

    /** Evolves the state by changing it in place: the default, concise form (D7). */
    inline fun <reified E : Any> evolve(
        name: String = defaultEventName(E::class.java),
        revision: Int = 1,
        noinline evolve: S.(E) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val mutate = evolve as S.(Any) -> Unit
        addEvolver(EventType(name, revision, E::class.java)) { state, event ->
            state.mutate(event)
            state
        }
    }

    /** Evolves the state by returning a new one, for immutable states. */
    inline fun <reified E : Any> evolveTo(
        name: String = defaultEventName(E::class.java),
        revision: Int = 1,
        noinline evolve: (S, E) -> S,
    ) {
        @Suppress("UNCHECKED_CAST")
        addEvolver(EventType(name, revision, E::class.java), evolve as (S, Any) -> S)
    }

    /** The log name of an event type declared without one. */
    fun defaultEventName(eventClass: Class<*>): String = "$type.${eventClass.simpleName}"

    fun addDecider(commandType: Class<*>, decider: DecideFunction<S>) {
        require(deciders.putIfAbsent(commandType, decider) == null) {
            "$type declares two decide functions for ${commandType.name}."
        }
    }

    fun addEvolver(eventType: EventType, evolver: (S, Any) -> S) {
        require(evolvers.putIfAbsent(eventType.type, evolver) == null) {
            "$type declares two evolve functions for ${eventType.type.name}."
        }
        eventTypes += eventType
    }

    fun build(): AggregateModel<S> = AggregateModel(
        type = type,
        initial = requireNotNull(initial) { "$type needs an initial state." },
        deciders = deciders.toMap(),
        evolvers = evolvers.toMap(),
        eventTypes = eventTypes.toList(),
    )
}
