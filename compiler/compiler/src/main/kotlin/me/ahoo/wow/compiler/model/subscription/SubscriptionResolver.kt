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
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isInternal
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
import me.ahoo.wow.api.annotation.Projection
import me.ahoo.wow.api.annotation.Reactor
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.compiler.model.aggregate.Dependency
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.annotatedIn
import me.ahoo.wow.compiler.model.symbol.annotationIn
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.compiler.model.symbol.handlerName
import me.ahoo.wow.compiler.model.symbol.javaTypeOf

/**
 * Reads a subscription processor and checks its shape:
 *
 * - the class has exactly one of `@Projection`, `@Reactor`, `@Saga`, and at least one `@OnEvent` function, none of
 *   them private, no two of them with the same name (each is the subscription `processor.function`);
 * - a handler takes a `StateEvent` and, optionally, the context of its kind (a projection's or a reactor's
 *   `SubscriptionContext`, a saga's `SagaContext`), nothing else: the processor's dependencies are its constructor's;
 * - a projection's or a reactor's handler returns nothing; a saga's returns the commands it derives (a
 *   `CommandRequest`, or an `Iterable`, a `Sequence` or a `Flow` of them), or nothing (`Unit`: it derives none); any
 *   of them may instead return a Reactor publisher;
 * - `@OnEvent(aggregates)` names aggregates as `context.aggregate`, all of one context;
 * - only a saga's handler, of any kind, declares `@Sends` ([SendsResolver]): what it derives, schedules and calls,
 *   each a command class or a sealed type, which stands for its concrete subclasses. A handler that returns commands
 *   of no named class (`CommandRequest<*>`) declares what it derives; one that returns `Unit` declares none. A
 *   returned `CommandRequest<S>` of a sealed `S` derives the concrete subclasses of `S`;
 * - an `@OnRejected` handler is a saga's, takes a `CommandReceipt.Rejected` and, optionally, the
 *   `SagaContext`, returns what a saga's handler returns (`Unit` to accept the rejection), and is not
 *   an `@OnEvent` handler too; no two of a saga take
 *   the rejections of the same command class, or both of every command (`CommandReceipt.Rejected<*>`);
 * - an `@OnCommitted` handler likewise, taking a `CommandReceipt.Committed` instead, no two of a saga the receipts
 *   of the same command class; a handler of both receipts is annotated both and takes a `CommandReceipt`, bound on
 *   both paths;
 * - no annotation outside the annotation set ([AnnotationSet]);
 * - the generated binding can reach it: the class is concrete, not private, not generic, an `object` or constructed
 *   by a constructor that is not private, whose parameters are domain services, each qualified by its `@Name`;
 *   a handler's state type takes no type arguments.
 *
 * A handler a processor inherits is its handler, its annotations those of the function it overrides when the
 * override leaves them out ([annotatedIn]): a processor base class, in the module or a library, is read
 * only through the processors that extend it.
 */
internal object SubscriptionResolver {
    private enum class Kind(val annotation: String, val context: String, val label: String) {
        PROJECT(Projection::class.java.name, SubscriptionContext::class.java.name, "@Projection"),
        REACT(Reactor::class.java.name, SubscriptionContext::class.java.name, "@Reactor"),
        SAGA(Saga::class.java.name, SagaContext::class.java.name, "@Saga"),
    }

    /** The annotations that make a class a subscription processor. */
    val KIND_ANNOTATIONS: List<String> = Kind.entries.map { it.annotation }

    /** The 9.x processor annotations: a class with one of them is read as a processor, to report it. */
    val REMOVED_KIND_ANNOTATIONS: List<String> = listOf("ProjectionProcessor", "EventProcessor", "StatelessSaga")
        .map { "${AnnotationSet.PACKAGE}.$it" }

    val ON_EVENT: String = OnEvent::class.java.name
    val ON_REJECTED: String = Receipt.REJECTED.annotation
    val ON_COMMITTED: String = Receipt.COMMITTED.annotation
    private const val AGGREGATES = "aggregates"

    /** The receipt of either outcome, which a handler of both receipts takes. */
    private val RECEIPT = CommandReceipt::class.java.name
    private const val RECEIPT_NAME = "CommandReceipt"
    private const val BOTH_RECEIPTS = "@OnRejected and @OnCommitted"

    /** The prefix of Wow's built-in subscriptions, which no processor's name takes. */
    private const val RESERVED_PREFIX = "wow."
    private val STATE_EVENT = StateEvent::class.java.name
    private const val ANY = "kotlin.Any"
    private val AGGREGATE_NAME = Regex("[^.\\s]+\\.[^.\\s]+")
    private val BLOCKING = Blocking::class.java.name
    private val RETRY = Retry::class.java.name
    private val REQUEST = CommandRequest::class.java.name
    private const val UNIT = "kotlin.Unit"
    private const val MONO = "reactor.core.publisher.Mono"
    private const val FLUX = "reactor.core.publisher.Flux"

    /** What a projection's or a reactor's `Mono` emits: nothing. */
    private val NOTHING = setOf(UNIT, "java.lang.Void")
    private const val DEPENDENCIES = "me.ahoo.wow.kernel.Dependencies"
    private val COMMAND_COLLECTIONS = setOf(
        "kotlin.collections.List",
        "kotlin.collections.Collection",
        "kotlin.collections.Iterable",
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.MutableIterable",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.sequences.Sequence",
        "kotlinx.coroutines.flow.Flow",
    )

    /** The context of every handler of a saga: its `@OnEvent`, receipt and job handlers alike. */
    private val SAGA_CONTEXT = SagaContext::class.java.name

    /** Every handler context: none is a handler's first parameter. */
    private val CONTEXTS = setOf(SubscriptionContext::class.java.name, SAGA_CONTEXT)

    @OptIn(KspExperimental::class)
    fun resolve(
        processor: KSClassDeclaration,
        reactorTypes: Set<String>,
        events: List<ModuleEvent> = emptyList(),
    ): SubscriptionBinding {
        val className = processor.simpleName.asString()
        val name = processor.getAnnotationsByType(Name::class).firstOrNull()?.value ?: className
        val problems = (AnnotationSet.problems(processor, "processor $className", processor = true) + nameProblems(className, name)).toMutableList()
        val processorRetry = RetryResolver.read(processor, "processor $className", problems)
        val kinds = Kind.entries.filter { kind -> processor.hasAnnotation(kind.annotation) }
        val kind = kinds.singleOrNull()
        problems += kindProblems(className, kinds)
        val receiptFunctions = Receipt.entries.associateWith { receipt ->
            processor.getAllFunctions().filter { it.isHandler(receipt.annotation) }.toList()
        }
        val functions = processor.getAllFunctions().filter { isEventHandler(it) }.toList()
        // A saga's @OnSchedule jobs are handlers of their own.
        val jobFunctions = processor.getAllFunctions().filter { it.isHandler(ScheduleJobResolver.ON_SCHEDULE) }
            .toList()
        if (functions.isEmpty() && jobFunctions.isEmpty()) {
            problems += "processor $className declares no @OnEvent function"
        }
        receiptFunctions.forEach { (receipt, receiptHandlers) ->
            problems += receiptProblems(className, name, kind, receipt, receiptHandlers)
        }
        processor.getDeclaredFunctions().forEach {
            problems += AnnotationSet.problemsOfFunction(it, "processor $className", processor = true)
        }
        problems += straySends(processor, className)
        problems += sameNames(className, name, functions + receiptFunctions.values.flatten() + jobFunctions)
        val jobs = ScheduleJobResolver.handlers(jobFunctions, className, name, kind?.name, reactorTypes, problems)
        val handlers = (functions.mapNotNull { handler(it, name, kind, reactorTypes, events, problems) } + jobs)
            .map { it.copy(retry = it.retry?.over(processorRetry) ?: processorRetry) }
        val (rejections, commits) = Receipt.entries.map { receipt ->
            receiptHandlers(className, name, receipt, receiptFunctions.getValue(receipt), reactorTypes, problems)
        }
        val isObject = processor.classKind == ClassKind.OBJECT
        problems += constructionProblems(processor, className, isObject)
        val dependencies = if (isObject) emptyList() else dependencies(processor, className, problems)
        if (dependencies.singleOrNull()?.erasure == DEPENDENCIES) {
            problems += "processor $className takes the host's Dependencies: a processor takes the services it uses, " +
                "which the generated handlers find in them"
        }
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
            internal = processor.isInternal() || !isObject && constructorOf(processor)?.isInternal() == true,
        )
    }

    /** Two [handlers] of the processor [name] with one name, which would be one subscription. */
    private fun sameNames(className: String, name: String, handlers: List<KSFunctionDeclaration>): List<String> =
        handlers.distinct().groupBy { it.simpleName.asString() }.filterValues { it.size > 1 }.keys.map {
            "processor $className has two handlers named $it: each is the subscription $name.$it"
        }

    /** `@Sends` on a function of [processor] that is no handler: it declares what nothing sends. */
    private fun straySends(processor: KSClassDeclaration, className: String): List<String> =
        processor.getAllFunctions().filter { it.isHandler(SendsResolver.SENDS) && !isHandlerOfAnyKind(it) }.map {
            "processor $className's function ${it.simpleName.asString()} declares @Sends but is no handler: " +
                "@Sends goes on a saga's @OnEvent, @OnRejected, @OnCommitted or @OnSchedule handler"
        }.toList()

    /** A processor of none of the kinds, or of more than one. */
    private fun nameProblems(className: String, name: String): List<String> =
        if (name == RESERVED_PREFIX.dropLast(1) || name.startsWith(RESERVED_PREFIX)) {
            listOf(
                "processor $className is named '$name': its subscriptions would be named $RESERVED_PREFIX…, " +
                    "the prefix of Wow's built-in subscriptions; give it a @Name of its own",
            )
        } else {
            emptyList()
        }

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
        val bound = functions.mapNotNull { receiptHandler(it, name, receipt, reactorTypes, problems) }
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
        functions.filter { it.isHandler(RETRY) }.forEach {
            add(
                "handler $name.${it.simpleName.asString()} is annotated @Retry: an ${receipt.label} handler takes " +
                    "the budget of where its receipt arrives, the @OnEvent handler that derived the command for one " +
                    "of the saga's own context, the host's retry of wow.receipts.<context> for one from another " +
                    "context; @Retry goes on that @OnEvent handler or its processor",
            )
        }
        if (functions.isNotEmpty() && kind != null && kind != Kind.SAGA) {
            add(
                "processor $className is a ${kind.label} with ${receipt.label} handlers: only a @Saga derives " +
                    "commands, whose ${receipt.nouns} it handles",
            )
        }
    }

    /**
     * Whether [function] is an `@OnEvent` handler (or overrides one), and no receipt handler: `@OnEvent` alone declares
     * a handler, whatever the function's name.
     */
    private fun isEventHandler(function: KSFunctionDeclaration): Boolean =
        Receipt.entries.none { function.isHandler(it.annotation) } &&
            !function.isHandler(ScheduleJobResolver.ON_SCHEDULE) &&
            function.isHandler(ON_EVENT)

    /** Whether [function] is a handler of any kind: `@OnEvent`, `@OnRejected`, `@OnCommitted` or `@OnSchedule`. */
    private fun isHandlerOfAnyKind(function: KSFunctionDeclaration): Boolean =
        (Receipt.entries.map { it.annotation } + ON_EVENT + ScheduleJobResolver.ON_SCHEDULE).any {
            function.isHandler(
                it
            )
        }

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
     * and, when the parameter has a `@Name`, by that qualifier.
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
        val name = parameter.name?.asString() ?: "dependency$index"
        Dependency(name, type.render(), type.declaration.qualifiedName!!.asString(), qualifier, type.javaTypeOf())
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
        val callee = function.simpleName.asString()
        val where = "handler $processor.$callee"
        val count = problems.size
        val name = function.handlerName(where, problems)
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        problems += parameterProblems(where, parameters, kind)
        val first = parameters.firstOrNull()
        val context = parameters.getOrNull(1)
        val returned = function.returnType?.resolve()
        val reactor = returned?.qualifiedName() in reactorTypes
        if (kind != null && returned != null) {
            returnProblem(kind, returned, reactor)?.let { problems += "$where $it" }
        }
        val onEvent = function.annotationIn(ON_EVENT)
        val aggregates = onEvent.strings(AGGREGATES)
        val sends = SendsResolver.resolve(function, where, kind == Kind.SAGA, kind?.label, returned, reactor, problems)
        problems += sourceProblems(where, aggregates)
        val retry = RetryResolver.read(function.annotatedIn(RETRY) ?: function, where, problems)
        if (problems.size > count) {
            return null
        }
        val handler = HandlerBinding(
            function = name,
            subscription = "$processor.$name",
            callee = callee,
            state = null,
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.isHandler(BLOCKING),
            aggregates = aggregates,
            sends = sends,
            start = startOf(function),
            retry = retry,
        )
        if (first!!.qualifiedName() != STATE_EVENT) {
            // A handler of each event of a type, of the aggregate whose events they are.
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

    /** `@OnEvent(aggregates)`: each `context.aggregate`, all of one context. */
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

    /**
     * The binary name of the class [type], a `CommandReceipt<X>`, a `CommandReceipt.Rejected<X>` or a
     * `CommandReceipt.Committed<X>`, takes; null for `*` or `Any`.
     */
    private fun commandClass(type: KSType): String? {
        val argument = type.arguments.firstOrNull()?.type?.resolve() ?: return null
        val declaration = argument.declaration as? KSClassDeclaration ?: return null
        return declaration.takeUnless { it.qualifiedName?.asString() == ANY }?.binaryName()
    }

    /** A [receipt] handler [function] of the saga [processor]. */
    private fun receiptHandler(
        function: KSFunctionDeclaration,
        processor: String,
        receipt: Receipt,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): ReceiptBinding? {
        val callee = function.simpleName.asString()
        val where = "handler $processor.$callee"
        val count = problems.size
        val name = function.handlerName(where, problems)
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        val first = parameters.firstOrNull()
        // A handler of both receipts is bound on both paths: each describes it alike, so a problem is reported once.
        val both = Receipt.entries.all { function.isHandler(it.annotation) }
        val label = if (both) BOTH_RECEIPTS else receipt.label
        val parameter = if (both) RECEIPT_NAME else receipt.parameterName
        problems += receiptParameterProblems(where, receipt, both, first?.qualifiedName())
        val context = parameters.getOrNull(1)?.qualifiedName()
        if (context != null && context != SAGA_CONTEXT) {
            problems += "$where takes $context: an $label handler takes a $SAGA_CONTEXT after the receipt, if anything"
        }
        if (parameters.size > 2) {
            problems += "$where takes ${parameters.size} parameters: an $label handler takes ($parameter[, SagaContext])"
        }
        val returned = function.returnType?.resolve()
        val reactor = returned?.qualifiedName() in reactorTypes
        if (returned != null) {
            returnProblem(Kind.SAGA, returned, reactor)?.let { problems += "$where $it" }
        }
        val sends = SendsResolver.resolve(function, where, true, null, returned, reactor, problems)
        if (problems.size > count) {
            return null
        }
        val command = commandClass(first!!)
        return ReceiptBinding(
            function = name,
            subscription = "$processor.$name",
            callee = callee,
            command = command,
            commandSource = command?.let { first.arguments.firstOrNull()?.type?.resolve()?.qualifiedName() },
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.isHandler(BLOCKING),
            sends = sends,
            receipt = receipt,
        )
    }

    /**
     * What is wrong with the receipt a [receipt] handler takes, [taken] (its qualified name): a handler of one receipt
     * takes that receipt (`CommandReceipt.Rejected<C>`, `CommandReceipt.Committed<C>`), and a handler of [both], one
     * annotated `@OnRejected` and `@OnCommitted`, takes a `CommandReceipt<C>`: each annotation declares one path, and
     * the parameter's type alone picks none.
     */
    private fun receiptParameterProblems(
        where: String,
        receipt: Receipt,
        both: Boolean,
        taken: String?,
    ): List<String> {
        val other = Receipt.entries.single { it != receipt }
        val problem = when {
            both && taken != RECEIPT ->
                "$where is $BOTH_RECEIPTS, so it takes a $RECEIPT_NAME<C> first, not " +
                    "${taken ?: "nothing"}: a handler of both receipts tells them apart itself"

            both -> null

            taken == RECEIPT ->
                "$where takes a $RECEIPT_NAME<C>, the receipt of either outcome, but is only " +
                    "${receipt.label}: annotate it ${other.label} too, or take a ${receipt.parameterName}<C>"

            taken == other.parameter ->
                "$where is ${receipt.label} but takes a ${other.parameterName}<C>: annotate it " +
                    "${other.label} instead, or take a ${receipt.parameterName}<C>"

            taken != receipt.parameter ->
                "$where must take a ${receipt.parameterName} first, not " +
                    "${taken ?: "nothing"}: an ${receipt.label} handler receives the ${receipt.noun} of a command the saga " +
                    "sent"

            else -> null
        }
        return listOfNotNull(problem)
    }

    /** The string array argument [name] of this annotation, empty without the annotation or the argument. */
    private fun KSAnnotation?.strings(name: String): List<String> =
        (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>).orEmpty()
            .filterIsInstance<String>()

    /**
     * `@OnEvent(start)`: `BEGINNING` or `END` when the handler declares it, null for `DEFAULT` or
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
                    "events of a type, with the state after it",
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

    /**
     * Why a handler of [kind] may not return [returned], a Reactor publisher when [reactor]; null when it may. A
     * publisher is checked by what it emits: a saga's a `Mono` or a `Flux` of command requests, a projection's or a
     * reactor's a `Mono<Void>` or a `Mono<Unit>`, which completes when the handling does.
     */
    private fun returnProblem(kind: Kind, returned: KSType, reactor: Boolean): String? {
        val name = returned.qualifiedName()
        if (reactor) {
            return publisherProblem(kind, name, returned)
        }
        return if (kind == Kind.SAGA) {
            val element = returned.arguments.firstOrNull()?.type?.resolve()?.qualifiedName()
            val commands = name == UNIT || name == REQUEST || (name in COMMAND_COLLECTIONS && element == REQUEST)
            if (commands) {
                null
            } else {
                "returns $name: a @Saga handler returns the commands it derives, a $REQUEST or an Iterable, a " +
                    "Sequence or a Flow of them, or Unit for none"
            }
        } else {
            if (name == UNIT) null else "returns $name: a ${kind.label} handler returns nothing"
        }
    }

    /** Why a saga's handler may not return [returned], a Reactor publisher when [reactor]; null when it may. */
    internal fun sagaReturnProblem(returned: KSType, reactor: Boolean): String? = returnProblem(
        Kind.SAGA,
        returned,
        reactor
    )

    private fun publisherProblem(kind: Kind, name: String?, returned: KSType): String? {
        val element = returned.arguments.firstOrNull()?.type?.resolve()?.qualifiedName()
        val (allowed, problem) = if (kind == Kind.SAGA) {
            ((name == MONO || name == FLUX) && element == REQUEST) to
                "a @Saga handler that returns a publisher returns a Mono or a Flux of $REQUEST, the commands it derives"
        } else {
            (name == MONO && element in NOTHING) to
                "a ${kind.label} handler that returns a publisher returns Mono<Void> or Mono<Unit>, which completes " +
                "when its handling does"
        }
        return if (allowed) null else "returns ${returned.render()}: $problem"
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
