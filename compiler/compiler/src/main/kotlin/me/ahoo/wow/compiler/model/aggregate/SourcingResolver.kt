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

package me.ahoo.wow.compiler.model.aggregate

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Alias
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.compiler.model.symbol.ModelNames.ANY_TYPES
import me.ahoo.wow.compiler.model.symbol.ModelNames.BLOCKING
import me.ahoo.wow.compiler.model.symbol.ModelNames.EVENT_STREAM
import me.ahoo.wow.compiler.model.symbol.ModelNames.SYSTEM_EVENTS
import me.ahoo.wow.compiler.model.symbol.annotatedIn
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.render

/** The sourcing functions of an aggregate's state, and the events its handlers produce. */
internal object SourcingResolver {
    /** `@OnCommand(produces)` lists events of the aggregate only. */
    fun producesProblems(commands: List<CommandBinding>, sourcing: List<SourcingBinding>): List<String> {
        // A system event is always one: a command composes a lifecycle event beside its own.
        val events = sourcing.map { it.event }.toSet() + SYSTEM_EVENTS
        return commands.flatMap { command ->
            command.declaredProduces.filterNot { it in events }.map {
                "@OnCommand(produces) of ${command.function}(${command.command}) lists $it, which is not an event " +
                    "of the aggregate: no sourcing function takes it"
            }
        }
    }

    /**
     * What a command function or an after hook produces, as its return type says ([EventYield]), is an event of the
     * aggregate: the kernel cannot evolve the state with an event no sourcing function takes, so every
     * such command would fail at run time. A system event (lifecycle, schedule) is always one.
     */
    fun yieldProblems(
        commands: List<CommandBinding>,
        afters: List<AfterBinding>,
        sourcing: List<SourcingBinding>,
    ): List<String> {
        val events = sourcing.map { it.event }.toSet() + SYSTEM_EVENTS
        val fix = "no sourcing function takes it; add one to the state, an empty one when the event changes nothing"
        // `@OnCommand(produces)` is checked on its own ([producesProblems]).
        return commands.filter { it.declaredProduces.isEmpty() }.flatMap { command ->
            command.yields.orEmpty().filterNot { it in events }.map {
                "${command.function}(${command.command}) produces $it, which is not an event of the aggregate: $fix"
            }
        } + afters.flatMap { after ->
            after.yields.orEmpty().filterNot { it in events }.map {
                "after hook ${after.function} produces $it, which is not an event of the aggregate: $fix"
            }
        }
    }

    /**
     * The bindings of the sourcing [functions] of [state]. A function taking a sealed type evolves each of its
     * concrete subclasses, those another function takes by their own class aside; one taking any other
     * interface or abstract class is a problem: the kernel finds a sourcing function by the event's exact class.
     */
    fun sourcingOf(
        functions: List<KSFunctionDeclaration>,
        state: KSClassDeclaration,
        problems: MutableList<String>,
    ): List<SourcingBinding> {
        val bound = functions.map { function -> function to sourcingBinding(function, state, problems) }
        val direct = bound.filter { (_, binding) -> binding != null && !binding.sealed }.map { it.second!! }
        val expanded = bound.mapNotNull { it.second }.filter { it.sealed }.flatMap { sealed ->
            sealedEvents(sealed.declaration!!).filterNot { event -> direct.any { it.event == event.render() } }
                .map { event -> eventBinding(event, sealed.function, sealed.returnsState, sealed.commit) }
        }
        expanded.groupBy { it.event }.filterValues { it.size > 1 }.forEach { (event, twice) ->
            problems += "the sourcing functions ${twice.joinToString { it.function }} both take $event, through " +
                "sealed types: give $event a sourcing function of its own"
        }
        return direct + expanded.distinctBy { it.event }
    }

    /** The concrete classes of the sealed [type], its sealed subclasses' included. */
    fun sealedEvents(type: KSClassDeclaration): List<KSClassDeclaration> =
        type.getSealedSubclasses().flatMap { sub ->
            if (Modifier.SEALED in sub.modifiers) sealedEvents(sub).asSequence() else sequenceOf(sub)
        }.toList()

    fun KSClassDeclaration.render(): String = asStarProjectedType().render()

    @OptIn(KspExperimental::class)
    fun sourcingBinding(
        function: KSFunctionDeclaration,
        state: KSClassDeclaration,
        problems: MutableList<String>,
    ): SourcingBinding? {
        val name = function.simpleName.asString()
        if (function.parameters.size !in 1..2) {
            problems += "sourcing function $name must take its event, and may take its commit ($EVENT_STREAM) second"
            return null
        }
        val commit = function.parameters.getOrNull(1)?.type?.resolve()
        if (commit != null && !isCommit(commit)) {
            val rendered = commit.render() + if (commit.isMarkedNullable) "?" else ""
            problems += "sourcing function $name takes $rendered second: a sourcing function's second parameter is " +
                "the commit of its event, a $EVENT_STREAM"
            return null
        }
        if (Modifier.SUSPEND in function.modifiers) {
            problems += "sourcing function $name suspends: evolving does no I/O"
        }
        if (function.annotatedIn(BLOCKING) != null) {
            problems += "sourcing function $name is annotated @Blocking: evolving does no I/O"
        }
        val eventType = function.parameters.first().type.resolve()
        val event = eventType.declaration as? KSClassDeclaration
        val returnsState = function.returnType?.resolve()?.declaration?.qualifiedName?.asString() ==
            state.qualifiedName?.asString()
        if (event == null || event.qualifiedName?.asString() in ANY_TYPES) {
            problems += "sourcing function $name takes ${eventType.render()}: a sourcing function takes the class of " +
                "its event"
            return null
        }
        if (Modifier.SEALED in event.modifiers) {
            return SourcingBinding(
                event = eventType.render(),
                eventName = null,
                function = name,
                returnsState = returnsState,
                type = event.binaryName(),
                commit = commit != null,
                declaration = event,
            )
        }
        if (event.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in event.modifiers) {
            problems += "sourcing function $name takes ${eventType.render()}, which is not a concrete class: the " +
                "kernel finds a sourcing function by its event's class, so take each event class, or a sealed type " +
                "whose subclasses are the events"
            return null
        }
        return eventBinding(event, name, returnsState, commit != null).also {
            if (it.revision < 1) {
                problems += "event ${event.simpleName.asString()} has @Revision(${it.revision}): a revision starts at 1"
            }
        }
    }

    /** Whether [type] is the commit of an event, a sourcing function's second parameter: a non-null `EventStream`. */
    private fun isCommit(type: KSType): Boolean = type.render() == EVENT_STREAM && !type.isMarkedNullable

    /** The binding of [function] evolving [event], a concrete event class, reading its [commit] or not. */
    @OptIn(KspExperimental::class)
    fun eventBinding(
        event: KSClassDeclaration,
        function: String,
        returnsState: Boolean,
        commit: Boolean,
    ): SourcingBinding =
        SourcingBinding(
            event = event.render(),
            eventName = event.getAnnotationsByType(Name::class).firstOrNull()?.value,
            revision = event.getAnnotationsByType(Revision::class).firstOrNull()?.value ?: 1,
            aliases = event.getAnnotationsByType(Alias::class).flatMap { it.value.toList() }.toList(),
            function = function,
            returnsState = returnsState,
            type = event.binaryName(),
            commit = commit,
            description = Descriptions.of(event),
        )
}
