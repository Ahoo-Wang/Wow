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
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Projection
import me.ahoo.wow.api.annotation.Reactor
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.subscribe.CommandRejected
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
 * - `@OnEvent(aggregates)` names aggregates as `context.aggregate`, all of one context, and only a saga's handler
 *   declares `@OnEvent(derives)`, each a full command type name `context.aggregate.Command` (decision D138);
 * - an `@OnRejected` handler (decisions D136, D153) is a saga's, takes a `CommandRejected` and, optionally, the
 *   `SagaContext`, returns what a saga's handler returns, and is not an `@OnEvent` handler too; no two of a saga take
 *   the rejections of the same command class, or both of every command (`CommandRejected<*>`);
 * - no annotation outside the V10 set ([V10Annotations]);
 * - the generated binding can reach it: the class is concrete, not private, not generic, an `object` or constructed
 *   by a constructor that is not private, whose parameters are domain services, each qualified by its `@Name`
 *   (decision D119); a handler's state type takes no type arguments.
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
    val ON_REJECTED: String = OnRejected::class.java.name
    private const val ON_EVENT_NAME = "onEvent"
    private const val AGGREGATES = "aggregates"
    private const val DERIVES = "derives"
    private val STATE_EVENT = StateEvent::class.java.name
    private val COMMAND_REJECTED = CommandRejected::class.java.name
    private const val ANY = "kotlin.Any"
    private val AGGREGATE_NAME = Regex("[^.\\s]+\\.[^.\\s]+")
    private val COMMAND_NAME = Regex("[^.\\s]+\\.[^.\\s]+\\.[^.\\s]+")
    private val BLOCKING = Blocking::class.java.name
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
        val rejectionFunctions = processor.getAllFunctions().filter { it.hasAnnotation(ON_REJECTED) }.toList()
        val functions = processor.getAllFunctions().filter {
            !it.hasAnnotation(ON_REJECTED) &&
                (it.hasAnnotation(ON_EVENT) || (it.simpleName.asString() == ON_EVENT_NAME && !it.isPrivate()))
        }.toList()
        if (functions.isEmpty()) {
            problems += "processor $className declares no @OnEvent function"
        }
        problems += rejectionProblems(className, name, kind, rejectionFunctions)
        processor.getDeclaredFunctions().forEach {
            problems += V10Annotations.problemsOfFunction(it, "processor $className")
        }
        (functions + rejectionFunctions).groupBy { it.simpleName.asString() }.filterValues { it.size > 1 }.keys.forEach {
            problems += "processor $className has two handlers named $it: each is the subscription $name.$it"
        }
        val handlers = functions.mapNotNull { handler(it, name, kind, reactorTypes, problems) }
        val rejections = rejectionFunctions.mapNotNull { rejection(it, name, reactorTypes, problems) }
        rejections.groupBy { it.command }.filterValues { it.size > 1 }.forEach { (command, same) ->
            problems += "processor $className has two @OnRejected handlers of the rejections of " +
                "${command ?: "every command (CommandRejected<*>)"}: ${same.joinToString { it.function }}"
        }
        val isObject = processor.classKind == ClassKind.OBJECT
        problems += constructionProblems(processor, className, isObject)
        val dependencies = if (isObject) emptyList() else dependencies(processor, className, problems)
        return SubscriptionBinding(
            processor = processor.qualifiedName!!.asString(),
            name = name,
            kind = kind?.name,
            handlers = handlers,
            problems = problems.distinct(),
            packageName = processor.packageName.asString(),
            simpleNames = generateSequence<KSDeclaration>(processor) { it.parentDeclaration }
                .map { it.simpleName.asString() }.toList().asReversed(),
            isObject = isObject,
            dependencies = dependencies,
            rejections = rejections,
        )
    }

    /** `@OnRejected` handlers that are `@OnEvent` handlers too, or of a processor that is not a saga. */
    private fun rejectionProblems(
        className: String,
        name: String,
        kind: Kind?,
        functions: List<KSFunctionDeclaration>,
    ): List<String> = buildList {
        functions.filter { it.hasAnnotation(ON_EVENT) }.forEach {
            add(
                "handler $name.${it.simpleName.asString()} is both @OnEvent and @OnRejected: a handler takes " +
                    "either a state event or a rejection",
            )
        }
        if (functions.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            add(
                "processor $className is a ${kind.label} with @OnRejected handlers: only a @Saga derives commands, " +
                    "whose rejections it handles",
            )
        }
    }

    /** Whether the generated binding can construct [processor], or use it when it [isObject]. */
    private fun constructionProblems(
        processor: KSClassDeclaration,
        className: String,
        isObject: Boolean
    ): List<String> =
        buildList {
            val concrete = processor.classKind == ClassKind.CLASS || isObject
            if (!concrete || Modifier.ABSTRACT in processor.modifiers) {
                add(
                    "processor $className is not a concrete class or an object: the generated subscriptions construct it"
                )
            }
            if (generateSequence<KSDeclaration>(processor) { it.parentDeclaration }.any { it.isPrivate() }) {
                add("processor $className is private: the generated subscriptions construct it")
            }
            if (processor.typeParameters.isNotEmpty()) {
                add("processor $className has type parameters: the generated subscriptions construct it")
            }
            if (!isObject && constructorOf(processor)?.isPrivate() == true) {
                add("processor $className's constructor is private: the generated subscriptions construct it")
            }
        }

    private fun constructorOf(processor: KSClassDeclaration): KSFunctionDeclaration? =
        processor.primaryConstructor ?: processor.getConstructors().firstOrNull()

    /**
     * The constructor parameters of [processor], each a domain service the host's `Dependencies` gives by its type
     * and, when the parameter has a `@Name`, by that qualifier (decision D119).
     */
    @OptIn(KspExperimental::class)
    private fun dependencies(
        processor: KSClassDeclaration,
        className: String,
        problems: MutableList<String>,
    ): List<Dependency> = constructorOf(processor)?.parameters.orEmpty().mapIndexed { index, parameter ->
        val type = parameter.type.resolve()
        val qualifier = parameter.getAnnotationsByType(Name::class).firstOrNull()?.value
        if (qualifier != null && qualifier.isBlank()) {
            problems += "processor $className's parameter ${parameter.name?.asString()} has a blank @Name: a " +
                "qualifier names the dependency"
        }
        Dependency("dependency$index", type.render(), type.declaration.qualifiedName!!.asString(), qualifier)
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
        val onEvent = function.annotations.firstOrNull { it.isOf(ON_EVENT) }
        val aggregates = onEvent.strings(AGGREGATES)
        val derives = onEvent.strings(DERIVES)
        problems += sourceProblems(where, aggregates)
        problems += derivesProblems(where, derives, kind)
        if (problems.size > count) {
            return null
        }
        val stateType = first!!.arguments.firstOrNull()?.type?.resolve()
        if (stateType != null && stateType.arguments.isNotEmpty()) {
            problems += "$where takes a StateEvent of ${stateType.render()}: an aggregate's state takes no type " +
                "arguments"
            return null
        }
        return HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            state = stateType?.qualifiedName(),
            nullableState = stateType == null || stateType.isMarkedNullable,
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.hasAnnotation(BLOCKING),
            aggregates = aggregates,
            derives = derives,
            derived = if (kind == Kind.SAGA) returned?.let { derivedClasses(it, reactor) } else null,
        )
    }

    /** `@OnEvent(aggregates)`: each `context.aggregate`, all of one context (decision D138). */
    private fun sourceProblems(where: String, aggregates: List<String>): List<String> = buildList {
        aggregates.filterNot { AGGREGATE_NAME.matches(it) }.forEach {
            add("$where reads the aggregate \"$it\": @OnEvent(aggregates) names each as context.aggregate")
        }
        val contexts = aggregates.map { it.substringBefore('.') }.distinct()
        if (contexts.size > 1) {
            add(
                "$where reads aggregates of the contexts ${contexts.joinToString(" and ")}: a handler reads the " +
                    "aggregates of one context",
            )
        }
    }

    /** `@OnEvent(derives)`: a saga's, each `context.aggregate.Command` (decision D138). */
    private fun derivesProblems(where: String, derives: List<String>, kind: Kind?): List<String> = buildList {
        if (derives.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            add("$where declares @OnEvent(derives), but a ${kind.label} handler derives no commands")
        }
        derives.filterNot { COMMAND_NAME.matches(it) }.forEach {
            add("$where derives \"$it\": @OnEvent(derives) names each command as context.aggregate.Command")
        }
    }

    /**
     * The binary names of the command classes a saga's handler returns ([returned], or what the Reactor publisher it
     * returns emits): `X` of a `Command<X>` or of a list of them; null when the type does not name the class
     * (`Command<*>`, `Command<Any>`).
     */
    private fun derivedClasses(returned: KSType, reactor: Boolean): List<String>? {
        val emitted = if (reactor) returned.arguments.firstOrNull()?.type?.resolve() ?: return null else returned
        val command = if (emitted.qualifiedName() == COMMAND) {
            emitted
        } else {
            emitted.arguments.firstOrNull()?.type?.resolve()?.takeIf { it.qualifiedName() == COMMAND } ?: return null
        }
        return commandClass(command)?.let(::listOf)
    }

    /** The binary name of the class [type], a `Command<X>` or a `CommandRejected<X>`, takes; null for `*` or `Any`. */
    private fun commandClass(type: KSType): String? {
        val argument = type.arguments.firstOrNull()?.type?.resolve() ?: return null
        val declaration = argument.declaration as? KSClassDeclaration ?: return null
        return declaration.takeUnless { it.qualifiedName?.asString() == ANY }?.binaryName()
    }

    /** An `@OnRejected` handler [function] of the saga [processor] (decisions D136, D153). */
    private fun rejection(
        function: KSFunctionDeclaration,
        processor: String,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): RejectionBinding? {
        val name = function.simpleName.asString()
        val where = "handler $processor.$name"
        val count = problems.size
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        val first = parameters.firstOrNull()
        if (first == null || first.qualifiedName() != COMMAND_REJECTED) {
            problems += "$where must take a $COMMAND_REJECTED first, not ${first?.qualifiedName() ?: "nothing"}: an " +
                "@OnRejected handler receives the rejection of a command the saga derived"
        }
        val context = parameters.getOrNull(1)?.qualifiedName()
        if (context != null && context != Kind.SAGA.context) {
            problems += "$where takes $context: an @OnRejected handler takes a ${Kind.SAGA.context} after the " +
                "rejection, if anything"
        }
        if (parameters.size > 2) {
            problems += "$where takes ${parameters.size} parameters: an @OnRejected handler takes " +
                "(CommandRejected[, SagaContext])"
        }
        val returned = function.returnType?.resolve()
        val reactor = returned?.qualifiedName() in reactorTypes
        if (returned != null && !reactor) {
            returnProblem(Kind.SAGA, returned)?.let { problems += "$where $it" }
        }
        if (problems.size > count) {
            return null
        }
        val command = commandClass(first!!)
        return RejectionBinding(
            function = name,
            subscription = "$processor.$name",
            command = command,
            commandSource = command?.let { first.arguments.firstOrNull()?.type?.resolve()?.qualifiedName() },
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.hasAnnotation(BLOCKING),
            derived = returned?.let { derivedClasses(it, reactor) },
        )
    }

    /** The string array argument [name] of this annotation, empty without the annotation or the argument. */
    private fun KSAnnotation?.strings(name: String): List<String> =
        (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>).orEmpty()
            .filterIsInstance<String>()

    private fun KSAnnotation.isOf(qualifiedName: String): Boolean =
        annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

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

    private fun KSType.render(): String {
        val name = declaration.qualifiedName!!.asString()
        val rendered = if (arguments.isEmpty()) {
            name
        } else {
            name + arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.render() ?: "*" }
        }
        return if (isMarkedNullable) "$rendered?" else rendered
    }

    private fun com.google.devtools.ksp.symbol.KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
}
