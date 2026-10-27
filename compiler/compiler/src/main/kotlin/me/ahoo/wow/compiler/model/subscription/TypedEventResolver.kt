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

package me.ahoo.wow.compiler.model.subscription

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.compiler.model.symbol.concreteClasses

/** An event class of an aggregate of the module: its [type], its [binary] name and its [state]. */
internal data class ModuleEvent(val type: KSType, val binary: String, val state: String)

/**
 * The event a handler of each event of a type takes: `StateTypedEvent<S, E>` ([TYPED]) or `E` itself
 * ([BARE]), [source] being `E`'s qualified name, [state] the state of the aggregate whose events it takes, declared or
 * inferred from `E` (null for another context's stream whose `S` it leaves out), and [events] the binary names of the
 * module's events that are an `E`; for the events of the aggregate `@OnEvent(aggregates)` names, another context's,
 * [names] are the events' names instead, as their api classes give them.
 */
internal data class EventParameter(
    val form: String,
    val source: String,
    val state: String?,
    val events: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    /** Whether the handler takes a null state: another context's stream published without it. */
    val nullableState: Boolean = false,
) {
    companion object {
        const val TYPED = "TYPED"
        const val BARE = "BARE"
    }
}

/**
 * Reads the event parameter of a handler of each event of a type: the aggregate is the one of the
 * module whose events are an `E` (`E` may be a supertype of several of them), its state the `S` of a
 * `StateTypedEvent<S, E>` when it names one. `E` must be an event of exactly one aggregate of the module: a handler of
 * another context's events by type waits for its peer's event classes.
 */
internal object TypedEventResolver {
    val STATE_TYPED_EVENT: String = StateTypedEvent::class.java.name

    fun resolve(
        where: String,
        first: KSType,
        aggregates: List<String>,
        events: List<ModuleEvent>,
        problems: MutableList<String>,
    ): EventParameter? {
        val typed = first.declaration.qualifiedName?.asString() == STATE_TYPED_EVENT
        val declaredState = if (typed) first.arguments.firstOrNull()?.type?.resolve() else null
        val event = (if (typed) first.arguments.getOrNull(1)?.type?.resolve() else first)?.makeNotNullable()
        val eventName = event?.declaration?.qualifiedName?.asString()
        if (event == null || eventName == null) {
            problems += "$where takes a StateTypedEvent of no event type: name the event, StateTypedEvent<S, E>"
            return null
        }
        val state = declaredState?.declaration?.qualifiedName?.asString()
        if (aggregates.isNotEmpty()) {
            val nullable = declaredState == null || declaredState.isMarkedNullable
            return named(where, typed, event, eventName, state, problems)?.copy(nullableState = nullable)
        }
        return local(where, typed, event, eventName, state, events, problems)
    }

    /** The event parameter of a handler of the module's own events that are an `E`. */
    @Suppress("LongParameterList")
    private fun local(
        where: String,
        typed: Boolean,
        event: KSType,
        eventName: String,
        state: String?,
        events: List<ModuleEvent>,
        problems: MutableList<String>,
    ): EventParameter? {
        val matches = events.filter { event.isAssignableFrom(it.type) && (state == null || it.state == state) }
        val states = matches.map { it.state }.distinct()
        return when {
            matches.isEmpty() -> {
                problems += "$where takes $eventName, which is no event of an aggregate of this module" +
                    (state?.let { " whose state is $it" } ?: "") + ": for another context's event, name its " +
                    "aggregate with @OnEvent(aggregates = [\"context.aggregate\"])"
                null
            }

            states.size > 1 -> {
                problems += "$where takes $eventName, an event of the aggregates of the states " +
                    "${states.joinToString()}: take a StateTypedEvent<S, $eventName> whose S names one"
                null
            }

            else -> EventParameter(
                form = if (typed) EventParameter.TYPED else EventParameter.BARE,
                source = eventName,
                state = states.single(),
                events = matches.map { it.binary }.distinct().sorted(),
            )
        }
    }

    /**
     * The event parameter of a handler of the aggregate `@OnEvent(aggregates)` names, another context's: `E` is
     * a class of that context's api, or a sealed type of them, and the events' names are their `@Name`s or simple
     * names, as the contract names them.
     */
    private fun named(
        where: String,
        typed: Boolean,
        event: KSType,
        eventName: String,
        state: String?,
        problems: MutableList<String>,
    ): EventParameter? {
        val classes = (event.declaration as? KSClassDeclaration)?.let(::concreteClasses).orEmpty()
        if (classes.isEmpty()) {
            problems += "$where takes $eventName of the aggregate @OnEvent(aggregates) names: name an event class " +
                "of its context's api, or a sealed type of them"
            return null
        }
        return EventParameter(
            form = if (typed) EventParameter.TYPED else EventParameter.BARE,
            source = eventName,
            state = state,
            names = classes.map(::nameOf).distinct().sorted(),
        )
    }

    /** The name the model gives the event [declaration]: its `@Name`, else its simple name. */
    @OptIn(KspExperimental::class)
    private fun nameOf(declaration: KSClassDeclaration): String =
        declaration.getAnnotationsByType(Name::class).firstOrNull()?.value ?: declaration.simpleName.asString()
}
