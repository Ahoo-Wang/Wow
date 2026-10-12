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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Projection
import me.ahoo.wow.api.annotation.Reactor
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent

/**
 * Reads a subscription processor (design 01 §1, design 04 §1) and checks its shape:
 *
 * - the class has exactly one of `@Projection`, `@Reactor`, `@Saga`, and at least one `@OnEvent` function (or one
 *   named `onEvent`), none of them private, no two of them with the same name (each is the subscription
 *   `processor.function`);
 * - a handler takes a `StateEvent` and, optionally, the context of its kind (`ProjectContext`, `ReactContext`,
 *   `SagaContext`), nothing else: the processor's dependencies are its constructor's;
 * - a projection's or a reactor's handler returns nothing; a saga's returns the commands it derives (a `Command`,
 *   or a list of them); any of them may instead return a Reactor publisher;
 * - no annotation outside the V10 set ([V10Annotations]).
 */
internal object SubscriptionResolver {
    private enum class Kind(val annotation: String, val context: String, val label: String) {
        PROJECT(Projection::class.java.name, ProjectContext::class.java.name, "@Projection"),
        REACT(Reactor::class.java.name, ReactContext::class.java.name, "@Reactor"),
        SAGA(Saga::class.java.name, SagaContext::class.java.name, "@Saga"),
    }

    /** The annotations that make a class a subscription processor. */
    val KIND_ANNOTATIONS: List<String> = Kind.entries.map { it.annotation }

    /** The 9.x processor annotations: a class with one of them is read as a processor, to report it. */
    val REMOVED_KIND_ANNOTATIONS: List<String> = listOf("ProjectionProcessor", "EventProcessor", "StatelessSaga")
        .map { "${V10Annotations.PACKAGE}.$it" }

    val ON_EVENT: String = OnEvent::class.java.name
    private const val ON_EVENT_NAME = "onEvent"
    private val STATE_EVENT = StateEvent::class.java.name
    private val COMMAND = Command::class.java.name
    private const val UNIT = "kotlin.Unit"
    private val COMMAND_COLLECTIONS = setOf(
        "kotlin.collections.List",
        "kotlin.collections.Collection",
        "kotlin.collections.Iterable",
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.MutableIterable",
    )
    private val CONTEXTS = Kind.entries.map { it.context }.toSet()

    @OptIn(KspExperimental::class)
    fun resolve(processor: KSClassDeclaration, reactorTypes: Set<String>): SubscriptionBinding {
        val className = processor.simpleName.asString()
        val name = processor.getAnnotationsByType(Name::class).firstOrNull()?.value ?: className
        val problems = V10Annotations.problems(processor, "processor $className").toMutableList()
        val kinds = Kind.entries.filter { kind -> processor.hasAnnotation(kind.annotation) }
        val kind = kinds.singleOrNull()
        if (kinds.isEmpty()) {
            problems += "processor $className is none of @Projection, @Reactor, @Saga: its class says which kind " +
                "its handlers are"
        }
        if (kinds.size > 1) {
            problems += "processor $className is ${kinds.joinToString(" and ") { it.label }}: a processor is of " +
                "exactly one kind"
        }
        val functions = processor.getAllFunctions().filter {
            it.hasAnnotation(ON_EVENT) || (it.simpleName.asString() == ON_EVENT_NAME && !it.isPrivate())
        }.toList()
        if (functions.isEmpty()) {
            problems += "processor $className declares no @OnEvent function"
        }
        processor.getDeclaredFunctions().forEach {
            problems += V10Annotations.problemsOfFunction(it, "processor $className")
        }
        functions.groupBy { it.simpleName.asString() }.filterValues { it.size > 1 }.keys.forEach {
            problems += "processor $className has two handlers named $it: each is the subscription $name.$it"
        }
        val handlers = functions.mapNotNull { handler(it, name, kind, reactorTypes, problems) }
        return SubscriptionBinding(
            processor = processor.qualifiedName!!.asString(),
            name = name,
            kind = kind?.name,
            handlers = handlers,
            problems = problems.distinct(),
        )
    }

    private fun handler(
        function: KSFunctionDeclaration,
        processor: String,
        kind: Kind?,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): HandlerBinding? {
        val name = function.simpleName.asString()
        val where = "handler $processor.$name"
        val count = problems.size
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        problems += parameterProblems(where, parameters, kind)
        val first = parameters.firstOrNull()
        val context = parameters.getOrNull(1)
        val returned = function.returnType?.resolve()
        val reactor = returned?.qualifiedName() in reactorTypes
        if (kind != null && returned != null && !reactor) {
            returnProblem(kind, returned)?.let { problems += "$where $it" }
        }
        if (problems.size > count) {
            return null
        }
        val state = first!!.arguments.firstOrNull()?.type?.resolve()?.qualifiedName()
        return HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            state = state,
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
        )
    }

    private fun parameterProblems(where: String, parameters: List<KSType>, kind: Kind?): List<String> = buildList {
        val first = parameters.firstOrNull()
        if (first == null || first.qualifiedName() != STATE_EVENT) {
            add(
                "$where must take a $STATE_EVENT first, not ${first?.qualifiedName() ?: "nothing"}: a handler " +
                    "receives the committed stream with the state after it",
            )
        }
        val context = parameters.getOrNull(1)?.qualifiedName()
        if (context != null && kind != null && context != kind.context) {
            add(
                "$where takes $context: a ${kind.label} handler takes a ${kind.context} after the state event, " +
                    "if anything",
            )
        }
        if (context != null && kind == null && context !in CONTEXTS) {
            add("$where takes $context: a handler takes its kind's context after the state event, if anything")
        }
        if (parameters.size > 2) {
            add(
                "$where takes ${parameters.size} parameters: a handler takes (StateEvent[, context]); the " +
                    "processor's dependencies are its constructor's",
            )
        }
    }

    private fun returnProblem(kind: Kind, returned: KSType): String? {
        val name = returned.qualifiedName()
        return if (kind == Kind.SAGA) {
            val element = returned.arguments.firstOrNull()?.type?.resolve()?.qualifiedName()
            val commands = name == COMMAND || (name in COMMAND_COLLECTIONS && element == COMMAND)
            if (commands) null else "returns $name: a @Saga handler returns the commands it derives, a $COMMAND or a list of them"
        } else {
            if (name == UNIT) null else "returns $name: a ${kind.label} handler returns nothing"
        }
    }

    private fun KSType.qualifiedName(): String? = declaration.qualifiedName?.asString()

    private fun com.google.devtools.ksp.symbol.KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
}
