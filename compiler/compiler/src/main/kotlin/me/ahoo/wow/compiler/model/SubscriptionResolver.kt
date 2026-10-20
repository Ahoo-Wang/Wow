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
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Projection
import me.ahoo.wow.api.annotation.Reactor
import me.ahoo.wow.api.annotation.Retry
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
 *   or a list of them), or nothing (`Unit`, decision D259: it derives none); any of them may instead return a Reactor
 *   publisher;
 * - `@OnEvent(aggregates)` names aggregates as `context.aggregate`, all of one context, and only a saga's handler
 *   declares `@OnEvent(derives)` (decisions D138, D259), each a command class or a sealed type, which stands for its
 *   concrete subclasses; a handler that returns `Unit` declares none. A returned `Command<S>` of a sealed `S` derives
 *   the concrete subclasses of `S`. Only a saga's handler declares `@OnEvent(calls)`, the commands it sends with
 *   `SagaContext.call` (decision D259), named as `derives` names them;
 * - an `@OnRejected` handler (decisions D136, D153) is a saga's, takes a `CommandRejected` and, optionally, the
 *   `SagaContext`, returns what a saga's handler returns (`Unit` to accept the rejection, decision D259), and is not
 *   an `@OnEvent` handler too; no two of a saga take
 *   the rejections of the same command class, or both of every command (`CommandRejected<*>`);
 * - an `@OnCommitted` handler (decision D192) likewise, taking a `CommandCommitted` instead: no handler is both, nor
 *   two of a saga the receipts of the same command class;
 * - no annotation outside the V10 set ([V10Annotations]);
 * - the generated binding can reach it: the class is concrete, not private, not generic, an `object` or constructed
 *   by a constructor that is not private, whose parameters are domain services, each qualified by its `@Name`
 *   (decision D119); a handler's state type takes no type arguments.
 *
 * A handler a processor inherits is its handler, its annotations those of the function it overrides when the
 * override leaves them out ([annotatedIn], finding A4-2): a processor base class, in the module or a library, is read
 * only through the processors that extend it.
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
    val ON_REJECTED: String = Receipt.REJECTED.annotation
    val ON_COMMITTED: String = Receipt.COMMITTED.annotation
    private const val ON_EVENT_NAME = "onEvent"
    private const val AGGREGATES = "aggregates"
    private const val DERIVES = "derives"
    private const val SCHEDULES = "schedules"
    private const val CALLS = "calls"
    private val STATE_EVENT = StateEvent::class.java.name
    private const val ANY = "kotlin.Any"
    private val AGGREGATE_NAME = Regex("[^.\\s]+\\.[^.\\s]+")
    private val COMMAND_NAME = Regex("[^.\\s]+\\.[^.\\s]+\\.[^.\\s]+")
    private val BLOCKING = Blocking::class.java.name
    private val RETRY = Retry::class.java.name
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
    fun resolve(
        processor: KSClassDeclaration,
        reactorTypes: Set<String>,
        events: List<ModuleEvent> = emptyList(),
    ): SubscriptionBinding {
        val className = processor.simpleName.asString()
        val name = processor.getAnnotationsByType(Name::class).firstOrNull()?.value ?: className
        val problems = V10Annotations.problems(processor, "processor $className", processor = true).toMutableList()
        val processorRetry = RetryResolver.read(processor, "processor $className", problems)
        val kinds = Kind.entries.filter { kind -> processor.hasAnnotation(kind.annotation) }
        val kind = kinds.singleOrNull()
        problems += kindProblems(className, kinds)
        val receiptFunctions = Receipt.entries.associateWith { receipt ->
            processor.getAllFunctions().filter { it.isHandler(receipt.annotation) }.toList()
        }
        val functions = processor.getAllFunctions().filter { isEventHandler(it) }.toList()
        // A saga's @OnSchedule jobs (decision D198) are handlers of their own.
        val jobFunctions = processor.getAllFunctions().filter { it.isHandler(ScheduleJobResolver.ON_SCHEDULE) }
            .toList()
        if (functions.isEmpty() && jobFunctions.isEmpty()) {
            problems += "processor $className declares no @OnEvent function"
        }
        receiptFunctions.forEach { (receipt, receiptHandlers) ->
            problems += receiptProblems(className, name, kind, receipt, receiptHandlers)
        }
        processor.getDeclaredFunctions().forEach {
            problems += V10Annotations.problemsOfFunction(it, "processor $className", processor = true)
        }
        (functions + receiptFunctions.values.flatten() + jobFunctions).distinct().groupBy { it.simpleName.asString() }
            .filterValues { it.size > 1 }.keys.forEach {
                problems += "processor $className has two handlers named $it: each is the subscription $name.$it"
            }
        val jobs = ScheduleJobResolver.handlers(jobFunctions, className, name, kind?.name, reactorTypes, problems) {
            returnProblem(Kind.SAGA, it)
        }
        val handlers = (functions.mapNotNull { handler(it, name, kind, reactorTypes, events, problems) } + jobs)
            .map { it.copy(retry = it.retry?.over(processorRetry) ?: processorRetry) }
        val (rejections, commits) = Receipt.entries.map { receipt ->
            receiptHandlers(className, name, receipt, receiptFunctions.getValue(receipt), reactorTypes, problems)
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
            commits = commits,
        )
    }

    /** A processor of none of the kinds, or of more than one. */
    private fun kindProblems(className: String, kinds: List<Kind>): List<String> = when {
        kinds.isEmpty() -> listOf(
            "processor $className is none of @Projection, @Reactor, @Saga: its class says which kind its handlers are",
        )

        kinds.size > 1 -> listOf(
            "processor $className is ${kinds.joinToString(" and ") { it.label }}: a processor is of exactly one kind",
        )

        else -> emptyList()
    }

    /**
     * The [receipt] handlers [functions] of the processor [name], each a function of one receipt kind only (one of
     * both is reported once, as an `@OnRejected` handler), no two of the same command class.
     */
    @Suppress("LongParameterList")
    private fun receiptHandlers(
        className: String,
        name: String,
        receipt: Receipt,
        functions: List<KSFunctionDeclaration>,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): List<ReceiptBinding> {
        val bound = functions.filterNot { receipt == Receipt.COMMITTED && it.isHandler(ON_REJECTED) }
            .mapNotNull { receiptHandler(it, name, receipt, reactorTypes, problems) }
        bound.groupBy { it.command }.filterValues { it.size > 1 }.forEach { (command, same) ->
            problems += "processor $className has two ${receipt.label} handlers of the ${receipt.nouns} of " +
                "${command ?: "every command (${receipt.parameterName}<*>)"}: ${same.joinToString { it.function }}"
        }
        return bound
    }

    /**
     * [receipt] handlers that are `@OnEvent` handlers too, or of both receipt kinds, or annotated `@Retry`, or of a
     * processor that is not a saga.
     */
    private fun receiptProblems(
        className: String,
        name: String,
        kind: Kind?,
        receipt: Receipt,
        functions: List<KSFunctionDeclaration>,
    ): List<String> = buildList {
        functions.filter { it.isHandler(ON_EVENT) }.forEach {
            add(
                "handler $name.${it.simpleName.asString()} is both @OnEvent and ${receipt.label}: a handler takes " +
                    "either a state event or a ${receipt.noun}",
            )
        }
        if (receipt == Receipt.COMMITTED) {
            functions.filter { it.isHandler(ON_REJECTED) }.forEach {
                add(
                    "handler $name.${it.simpleName.asString()} is both @OnRejected and @OnCommitted: a handler takes " +
                        "either the rejection of a command or the receipt of its commit",
                )
            }
        }
        functions.filter { it.isHandler(RETRY) }.forEach {
            add(
                "handler $name.${it.simpleName.asString()} is annotated @Retry: ${receipt.retryReason}; @Retry goes " +
                    "on an @OnEvent handler or its processor",
            )
        }
        if (functions.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            add(
                "processor $className is a ${kind.label} with ${receipt.label} handlers: only a @Saga derives " +
                    "commands, whose ${receipt.nouns} it handles",
            )
        }
    }

    /** Why a handler of this kind takes no `@Retry`. */
    private val Receipt.retryReason: String
        get() = when (this) {
            Receipt.REJECTED -> "an @OnRejected handler compensates a business outcome, which is not retried"
            Receipt.COMMITTED -> "an @OnCommitted handler runs in its context's receipts subscription, retried by the host"
        }

    /**
     * Whether [function] is an `@OnEvent` handler (or overrides one), or a public one named `onEvent`, and no receipt
     * handler.
     */
    private fun isEventHandler(function: KSFunctionDeclaration): Boolean =
        Receipt.entries.none { function.isHandler(it.annotation) } &&
            !function.isHandler(ScheduleJobResolver.ON_SCHEDULE) &&
            (function.isHandler(ON_EVENT) || (function.simpleName.asString() == ON_EVENT_NAME && !function.isPrivate()))

    /** Whether this function, or one it overrides, carries [annotation] ([annotatedIn]). */
    private fun KSFunctionDeclaration.isHandler(annotation: String): Boolean = annotatedIn(annotation) != null

    /**
     * Whether [declaration] is a base class of processors rather than one: an interface or an abstract class with
     * none of the processor kinds. Its handlers are read through the processors extending it.
     */
    fun isProcessorBase(declaration: KSClassDeclaration): Boolean =
        (declaration.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in declaration.modifiers) &&
            (KIND_ANNOTATIONS + REMOVED_KIND_ANNOTATIONS).none { declaration.hasAnnotation(it) }

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

    @Suppress("LongParameterList")
    private fun handler(
        function: KSFunctionDeclaration,
        processor: String,
        kind: Kind?,
        reactorTypes: Set<String>,
        events: List<ModuleEvent>,
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
        val onEvent = function.annotationIn(ON_EVENT)
        val aggregates = onEvent.strings(AGGREGATES)
        val declared = onEvent.classes(DERIVES)
        val derives = commandClasses(where, "derives", declared, problems).map { it.binaryName() }
        val schedules = onEvent.strings(SCHEDULES)
        val calls = callsOf(where, onEvent, kind, problems)
        problems += sourceProblems(where, aggregates)
        problems += derivesProblems(where, declared.isNotEmpty(), kind, returned)
        problems += schedulesProblems(where, schedules, kind)
        val retry = RetryResolver.read(function.annotatedIn(RETRY) ?: function, where, problems)
        if (problems.size > count) {
            return null
        }
        val handler = HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            state = null,
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.isHandler(BLOCKING),
            aggregates = aggregates,
            derives = derives,
            schedules = schedules,
            calls = calls.map { it.binaryName() },
            callSources = calls.map { it.qualifiedName!!.asString() },
            start = startOf(function),
            derived = if (kind == Kind.SAGA) returned?.let { derivedClasses(it, reactor) } else null,
            retry = retry,
        )
        if (first!!.qualifiedName() != STATE_EVENT) {
            // Decision D264: a handler of each event of a type, of the aggregate whose events they are.
            val parameter = TypedEventResolver.resolve(where, first, aggregates, events, problems) ?: return null
            return handler.copy(state = parameter.state, nullableState = parameter.nullableState, event = parameter)
        }
        return streamHandler(handler, where, first, problems)
    }

    /** [handler] of the whole stream, the `StateEvent<S>` it takes [first]: `S` takes no type arguments. */
    private fun streamHandler(
        handler: HandlerBinding,
        where: String,
        first: KSType,
        problems: MutableList<String>,
    ): HandlerBinding? {
        val stateType = first.arguments.firstOrNull()?.type?.resolve()
        if (stateType != null && stateType.arguments.isNotEmpty()) {
            problems += "$where takes a StateEvent of ${stateType.render()}: an aggregate's state takes no type " +
                "arguments"
            return null
        }
        return handler.copy(
            state = stateType?.qualifiedName(),
            nullableState = stateType == null || stateType.isMarkedNullable,
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

    /** `@OnEvent(derives)`: a saga's (decision D138), whose handler does not return `Unit` (decision D259). */
    private fun derivesProblems(where: String, declared: Boolean, kind: Kind?, returned: KSType?): List<String> =
        buildList {
            if (declared && kind != null && kind != Kind.SAGA) {
                add("$where declares @OnEvent(derives), but a ${kind.label} handler derives no commands")
            }
            if (declared && returned?.qualifiedName() == UNIT) {
                add("$where returns Unit, so it derives no commands, but declares @OnEvent(derives)")
            }
        }

    /** `@OnEvent(calls)` (decision D259): the command classes a saga's handler calls with `SagaContext.call`. */
    private fun callsOf(
        where: String,
        onEvent: KSAnnotation?,
        kind: Kind?,
        problems: MutableList<String>,
    ): List<KSClassDeclaration> {
        val calling = onEvent.classes(CALLS)
        if (calling.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            problems += "$where declares @OnEvent(calls), but a ${kind.label} handler calls no other context"
        }
        return commandClasses(where, CALLS, calling, problems)
    }

    /**
     * The command classes [types] name, as `@OnEvent(member)` declares them (decision D259): a
     * class names itself, a sealed type its concrete subclasses, transitively. `Any`, and an interface or an abstract
     * class that is not sealed, name no command class: a problem.
     */
    private fun commandClasses(
        where: String,
        member: String,
        types: List<KSType>,
        problems: MutableList<String>,
    ): List<KSClassDeclaration> = types.flatMap { type ->
        val declaration = type.declaration.classOf()
        val classes = declaration?.takeUnless { it.qualifiedName?.asString() == ANY }?.let(::concreteClasses).orEmpty()
        if (classes.isEmpty()) {
            problems += "$where $member ${type.render()}, which is no command class: " +
                "@OnEvent($member) names command classes, or a sealed type whose subclasses they are"
        }
        classes
    }.distinctBy { it.binaryName() }

    /** This declaration's class, a type alias read through to the class it names; null for anything else. */
    private fun KSDeclaration.classOf(): KSClassDeclaration? = when (this) {
        is KSClassDeclaration -> this
        is KSTypeAlias -> type.resolve().declaration.classOf()
        else -> null
    }

    /**
     * [declaration] when it is a concrete class or an object, the concrete classes among its sealed subclasses
     * (`getSealedSubclasses`, which reads a library's classes too) when it is sealed, and none otherwise.
     */
    private fun concreteClasses(declaration: KSClassDeclaration): List<KSClassDeclaration> = when {
        Modifier.SEALED in declaration.modifiers ->
            declaration.getSealedSubclasses().flatMap { concreteClasses(it) }.distinct().toList()

        declaration.classKind == ClassKind.OBJECT -> listOf(declaration)
        declaration.classKind == ClassKind.CLASS && Modifier.ABSTRACT !in declaration.modifiers -> listOf(declaration)
        else -> emptyList()
    }

    /** `@OnEvent(schedules)`: a saga's, each `context.aggregate.Command` (decision D166). */
    private fun schedulesProblems(where: String, schedules: List<String>, kind: Kind?): List<String> = buildList {
        if (schedules.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            add("$where declares @OnEvent(schedules), but a ${kind.label} handler schedules no commands")
        }
        schedules.filterNot { COMMAND_NAME.matches(it) }.forEach {
            add("$where schedules \"$it\": @OnEvent(schedules) names each command as context.aggregate.Command")
        }
    }

    /**
     * The binary names of the command classes a saga's handler returns ([returned], or what the Reactor publisher it
     * returns emits): `X` of a `Command<X>` or of a list of them, the concrete subclasses of `X` when it is sealed
     * (decision D259); none for `Unit`; null when the type does not name the class (`Command<*>`, `Command<Any>`).
     */
    private fun derivedClasses(returned: KSType, reactor: Boolean): List<String>? {
        if (!reactor && returned.qualifiedName() == UNIT) {
            return emptyList()
        }
        val emitted = if (reactor) returned.arguments.firstOrNull()?.type?.resolve() ?: return null else returned
        val command = if (emitted.qualifiedName() == COMMAND) {
            emitted
        } else {
            emitted.arguments.firstOrNull()?.type?.resolve()?.takeIf { it.qualifiedName() == COMMAND } ?: return null
        }
        val declaration = command.arguments.firstOrNull()?.type?.resolve()?.declaration?.classOf()
        if (declaration != null && Modifier.SEALED in declaration.modifiers) {
            return concreteClasses(declaration).map { it.binaryName() }.ifEmpty { null }
        }
        return commandClass(command)?.let(::listOf)
    }

    /**
     * The binary name of the class [type], a `Command<X>`, a `CommandRejected<X>` or a `CommandCommitted<X>`, takes;
     * null for `*` or `Any`.
     */
    private fun commandClass(type: KSType): String? {
        val argument = type.arguments.firstOrNull()?.type?.resolve() ?: return null
        val declaration = argument.declaration as? KSClassDeclaration ?: return null
        return declaration.takeUnless { it.qualifiedName?.asString() == ANY }?.binaryName()
    }

    /** A [receipt] handler [function] of the saga [processor] (decisions D136, D153, D192). */
    private fun receiptHandler(
        function: KSFunctionDeclaration,
        processor: String,
        receipt: Receipt,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): ReceiptBinding? {
        val name = function.simpleName.asString()
        val where = "handler $processor.$name"
        val count = problems.size
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        val first = parameters.firstOrNull()
        if (first == null || first.qualifiedName() != receipt.parameter) {
            problems += "$where must take a ${receipt.parameter} first, not ${first?.qualifiedName() ?: "nothing"}: " +
                "an ${receipt.label} handler receives the ${receipt.noun} of a command the saga derived"
        }
        val context = parameters.getOrNull(1)?.qualifiedName()
        if (context != null && context != Kind.SAGA.context) {
            problems += "$where takes $context: an ${receipt.label} handler takes a ${Kind.SAGA.context} after the " +
                "${receipt.noun}, if anything"
        }
        if (parameters.size > 2) {
            problems += "$where takes ${parameters.size} parameters: an ${receipt.label} handler takes " +
                "(${receipt.parameterName}[, SagaContext])"
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
        return ReceiptBinding(
            function = name,
            subscription = "$processor.$name",
            command = command,
            commandSource = command?.let { first.arguments.firstOrNull()?.type?.resolve()?.qualifiedName() },
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.isHandler(BLOCKING),
            derived = returned?.let { derivedClasses(it, reactor) },
            receipt = receipt,
        )
    }

    /** The class array argument [name] of this annotation, empty without the annotation or the argument. */
    private fun KSAnnotation?.classes(name: String): List<KSType> =
        (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>).orEmpty()
            .filterIsInstance<KSType>()

    /** The string array argument [name] of this annotation, empty without the annotation or the argument. */
    private fun KSAnnotation?.strings(name: String): List<String> =
        (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>).orEmpty()
            .filterIsInstance<String>()

    /**
     * `@OnEvent(start)` (decision D194): `BEGINNING` or `END` when the handler declares it, null for `DEFAULT` or
     * without the annotation.
     */
    @OptIn(KspExperimental::class)
    private fun startOf(function: KSFunctionDeclaration): String? =
        (function.annotatedIn(ON_EVENT) ?: function).getAnnotationsByType(OnEvent::class).map { it.start }
            .firstOrNull { it != OnEvent.Start.DEFAULT }?.name

    private fun parameterProblems(where: String, parameters: List<KSType>, kind: Kind?): List<String> = buildList {
        val first = parameters.firstOrNull()
        if (first == null || first.qualifiedName() in CONTEXTS) {
            add(
                "$where must take a $STATE_EVENT, a StateTypedEvent or an event first, not " +
                    "${first?.qualifiedName() ?: "nothing"}: a handler receives the committed stream, or each of its " +
                    "events of a type, with the state after it (decision D264)",
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
                "$where takes ${parameters.size} parameters: a handler takes (StateEvent[, context]), or a StateTypedEvent or an event instead of the StateEvent; the " +
                    "processor's dependencies are its constructor's",
            )
        }
    }

    private fun returnProblem(kind: Kind, returned: KSType): String? {
        val name = returned.qualifiedName()
        return if (kind == Kind.SAGA) {
            val element = returned.arguments.firstOrNull()?.type?.resolve()?.qualifiedName()
            val commands = name == UNIT || name == COMMAND || (name in COMMAND_COLLECTIONS && element == COMMAND)
            if (commands) {
                null
            } else {
                "returns $name: a @Saga handler returns the commands it derives, a $COMMAND or a list of them, or " +
                    "Unit for none"
            }
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
