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
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import tools.jackson.databind.JsonNode

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
    private val afterFunctions = ArrayList<AfterFunction<S>>()
    private val evolvers = LinkedHashMap<Class<*>, (S, Any) -> S>()
    private val eventNames = LinkedHashMap<Class<*>, Pair<String, Int>>()
    private val upgrades = HashMap<Class<*>, MutableMap<Int, (JsonNode) -> JsonNode>>()
    private val aliases = HashMap<Class<*>, MutableSet<String>>()

    /** The state of an aggregate that has no event yet. */
    fun initial(factory: (AggregateRef) -> S) {
        initial = factory
    }

    /** Decides commands of type [C] with a plain function: the fast path (D36). */
    inline fun <reified C : Any> decide(create: Boolean = false, noinline decide: DecideScope.(S, C) -> Decision) {
        @Suppress("UNCHECKED_CAST")
        addDecider(C::class.java, DecideFunction.Sync(create, decide as DecideScope.(S, Any) -> Decision))
    }

    /** Decides commands of type [C] with a suspending function, for decisions that read other systems. */
    inline fun <reified C : Any> decideSuspending(
        create: Boolean = false,
        noinline decide: suspend DecideScope.(S, C) -> Decision,
    ) {
        @Suppress("UNCHECKED_CAST")
        addDecider(C::class.java, DecideFunction.Suspending(create, decide as suspend DecideScope.(S, Any) -> Decision))
    }

    /**
     * An after hook (D39) for every command that is one of [include] (all when empty) and none of [exclude]: its
     * events follow the command's own in the same stream.
     */
    fun after(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        after: DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Sync(applies(include, exclude), after)
    }

    /** A suspending after hook; see [after]. */
    fun afterSuspending(
        include: Set<Class<*>> = emptySet(),
        exclude: Set<Class<*>> = emptySet(),
        after: suspend DecideScope.(S, Any) -> List<Any>,
    ) {
        afterFunctions += AfterFunction.Suspending(applies(include, exclude), after)
    }

    /** Evolves the state by changing it in place: the default, concise form (D7). */
    inline fun <reified E : Any> evolve(
        name: String = defaultEventName(E::class.java),
        revision: Int = 1,
        noinline evolve: S.(E) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val mutate = evolve as S.(Any) -> Unit
        addEvolver(E::class.java, name, revision) { state, event ->
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
        addEvolver(E::class.java, name, revision, evolve as (S, Any) -> S)
    }

    /** Reads revision [from] payloads of event [E] as revision `from + 1` ones (D22). */
    inline fun <reified E : Any> upgrade(from: Int, noinline upgrade: (JsonNode) -> JsonNode) {
        addUpgrade(E::class.java, from, upgrade)
    }

    /** Reads records of event [E] stored under its former name [former]. */
    inline fun <reified E : Any> alias(former: String) {
        addAlias(E::class.java, former)
    }

    /** The log name of an event type declared without one. */
    fun defaultEventName(eventClass: Class<*>): String = "$type.${eventClass.simpleName}"

    fun addDecider(commandType: Class<*>, decider: DecideFunction<S>) {
        require(deciders.putIfAbsent(commandType, decider) == null) {
            "$type declares two decide functions for ${commandType.name}."
        }
    }

    fun addEvolver(eventClass: Class<*>, name: String, revision: Int, evolver: (S, Any) -> S) {
        require(evolvers.putIfAbsent(eventClass, evolver) == null) {
            "$type declares two evolve functions for ${eventClass.name}."
        }
        eventNames[eventClass] = name to revision
    }

    fun addUpgrade(eventClass: Class<*>, from: Int, upgrade: (JsonNode) -> JsonNode) {
        require(upgrades.getOrPut(eventClass) { HashMap() }.putIfAbsent(from, upgrade) == null) {
            "$type declares two upgrades of ${eventClass.name} from revision $from."
        }
    }

    fun addAlias(eventClass: Class<*>, former: String) {
        aliases.getOrPut(eventClass) { LinkedHashSet() } += former
    }

    fun build(): AggregateModel<S> = AggregateModel(
        type = type,
        initial = requireNotNull(initial) { "$type needs an initial state." },
        deciders = deciders.toMap(),
        evolvers = evolvers.toMap(),
        eventTypes = eventNames.map { (eventClass, nameAndRevision) ->
            EventType(
                name = nameAndRevision.first,
                revision = nameAndRevision.second,
                type = eventClass,
                upgrades = upgrades[eventClass].orEmpty().toMap(),
                aliases = aliases[eventClass].orEmpty().toSet(),
            )
        },
        afterFunctions = afterFunctions.toList(),
    )

    private fun applies(include: Set<Class<*>>, exclude: Set<Class<*>>): (Class<*>) -> Boolean =
        { command -> (include.isEmpty() || command in include) && command !in exclude }
}
