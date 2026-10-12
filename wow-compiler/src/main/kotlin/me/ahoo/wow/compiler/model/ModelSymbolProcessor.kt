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
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Alias
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.compiler.AggregateRootResolver.toName
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import tools.jackson.databind.json.JsonMapper

/**
 * Compiles annotated aggregate roots into V10 aggregate models (decision D37): for each `@AggregateRoot` it
 * generates `fun <root>Model(context: String): AggregateModel<State>`, built with the kernel DSL, so the annotated
 * and the DSL style are one model and the runtime never reflects.
 *
 * Command and event types are named `context.aggregate.Name` (decision D55): the class's simple name, or its `@Name`.
 *
 * It recognizes only the V10 annotation set (design 01 §3, decision D67; [V10Annotations]): any other annotation of
 * `me.ahoo.wow.api.annotation` on the root, its state, its commands or its events is a problem that points to the
 * migration skill, and so is an identity annotation on a command's field (decision D68). An event's `@Name`,
 * `@Revision` and `@Alias` and a command's `@Name` flow into the model; a command's `@Alias` is recorded but not bound,
 * since the kernel's command types have no former names yet. The generated factory's last parameter, `configure`,
 * runs on the model builder after the bindings, so the host can add what annotations do not bind yet, such as the
 * upgrades a `@Revision` above 1 needs.
 *
 * Subscription processors (`@Projection`, `@Reactor`, `@Saga` with `@OnEvent` handlers) are checked by
 * [SubscriptionResolver]; their bindings are not generated yet, since the runtime has no annotation-facing
 * subscription API to bind them to.
 *
 * It recognizes these forms: a root built from its state (`ctor(state)`) or a root that is its own state, command
 * functions annotated `@OnCommand` or named `onCommand`, sourcing functions annotated `@OnSourcing` or named
 * `onSourcing`, after hooks annotated `@AfterCommand` or named `afterCommand`, `@CreateAggregate` commands and
 * suspending functions, `@AllowCreate`, functions returning a Reactor publisher (awaited through wow-reactor), and
 * parameters after the command, which become parameters of the generated factory (dependencies bound once, when
 * the host builds the model). Anything else is a problem: with `wow.model=generate` it fails the compilation; with
 * `wow.model=report` the processor generates nothing and writes `META-INF/wow/model-report.json`, which spike H4
 * reads to measure coverage. Without the option it does nothing.
 *
 * The context is a parameter because a domain module rarely holds the `@BoundedContext` that names it: the runtime
 * binds it when it assembles the Manifest fragments.
 *
 * With `wow.model=generate`, a model without problems also gets its Manifest fragment (decisions D73, D74,
 * [ManifestFragmentBuilder]): `META-INF/wow/manifest/<module>.json` holds the module's aggregates and subscriptions
 * under its bounded context, with schemas left as references for the build to fill in. The KSP option
 * `wow.context` names the context (else the module's one `@BoundedContext` does; with neither, no fragment is
 * written), and `wow.manifest.module` names the file (else the context does).
 */
class ModelSymbolProcessor(
    private val mode: Mode,
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val manifest: ManifestOptions = ManifestOptions(),
) : SymbolProcessor {
    enum class Mode { OFF, GENERATE, REPORT }

    /**
     * Where the Manifest fragment goes: [module] names it (`META-INF/wow/manifest/<module>.json`), and [context]
     * names the bounded context the module's aggregates and subscriptions belong to. Without [context], the one
     * `@BoundedContext` of the module names it; without [module], the context does.
     */
    data class ManifestOptions(val module: String? = null, val context: String? = null)

    private val bindings = mutableListOf<AggregateBinding>()
    private val subscriptions = mutableListOf<SubscriptionBinding>()
    private val sources = mutableSetOf<KSFile>()

    /** The `@BoundedContext`s of the module: name to description. */
    private val boundedContexts = sortedMapOf<String, String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (mode == Mode.OFF) {
            return emptyList()
        }
        collectBoundedContexts(resolver)
        val roots = resolver.getSymbolsWithAnnotation(AGGREGATE_ROOT).filterIsInstance<KSClassDeclaration>().toList()
        val deferred = roots.filterNot { it.validate() }
        roots.filter { it.validate() }.forEach { root ->
            root.containingFile?.let { sources += it }
            val binding = analyze(root)
            bindings += binding
            if (mode == Mode.GENERATE) {
                if (binding.problems.isEmpty()) {
                    binding.commands.filter { it.aliases.isNotEmpty() }.forEach {
                        logger.warn(
                            "${binding.root}: @Alias of ${it.command} is not bound: the kernel's command types " +
                                "have no former names yet",
                            root,
                        )
                    }
                    generate(binding, root)
                } else {
                    binding.problems.forEach { logger.error("${binding.root}: $it", root) }
                }
            }
        }
        return deferred + processSubscriptions(resolver)
    }

    private fun processSubscriptions(resolver: Resolver): List<KSAnnotated> {
        val handlerClasses = resolver.getSymbolsWithAnnotation(SubscriptionResolver.ON_EVENT)
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull { it.parentDeclaration as? KSClassDeclaration }
        val processors = (
            (SubscriptionResolver.KIND_ANNOTATIONS + SubscriptionResolver.REMOVED_KIND_ANNOTATIONS).asSequence()
                .flatMap { resolver.getSymbolsWithAnnotation(it) }
                .filterIsInstance<KSClassDeclaration>() + handlerClasses
            ).distinct().toList()
        val deferred = processors.filterNot { it.validate() }
        processors.filter { it.validate() }.forEach { processor ->
            processor.containingFile?.let { sources += it }
            val binding = SubscriptionResolver.resolve(processor, REACTOR_TYPES)
            subscriptions += binding
            if (mode == Mode.GENERATE) {
                if (binding.problems.isEmpty()) {
                    logger.warn(
                        "${binding.processor}: subscription bindings are not generated yet; its handlers " +
                            binding.handlers.joinToString { it.subscription } + " are checked only",
                        processor,
                    )
                } else {
                    binding.problems.forEach { logger.error("${binding.processor}: $it", processor) }
                }
            }
        }
        return deferred
    }

    @OptIn(KspExperimental::class)
    private fun collectBoundedContexts(resolver: Resolver) {
        val declarations = resolver.getSymbolsWithAnnotation(BOUNDED_CONTEXT).filterIsInstance<KSClassDeclaration>()
        declarations.forEach { declaration ->
            declaration.containingFile?.let { sources += it }
            declaration.getAnnotationsByType(BoundedContext::class).forEach {
                boundedContexts[it.name] = it.description
            }
        }
    }

    override fun finish() {
        if (bindings.isEmpty() && subscriptions.isEmpty()) {
            return
        }
        when (mode) {
            Mode.REPORT -> codeGenerator.createNewFileByPath(
                Dependencies(aggregating = true, sources = sources.toTypedArray()),
                "META-INF/wow/model-report",
                "json",
            ).bufferedWriter().use { it.write(ModelReport.json(bindings, subscriptions)) }

            Mode.GENERATE -> writeManifestFragment()
            Mode.OFF -> Unit
        }
    }

    /**
     * Writes the module's Manifest fragment ([ManifestFragmentBuilder]) when the model compiled without problems and
     * its bounded context is known.
     */
    private fun writeManifestFragment() {
        if (bindings.any { it.problems.isNotEmpty() } || subscriptions.any { it.problems.isNotEmpty() }) {
            return
        }
        val context = manifest.context ?: boundedContexts.keys.singleOrNull()
        if (context == null) {
            logger.warn(
                "No Manifest fragment: the module declares ${boundedContexts.size} @BoundedContext; set the KSP " +
                    "option ${ModelSymbolProcessorProvider.CONTEXT_OPTION} to name its bounded context",
            )
            return
        }
        val module = manifest.module ?: context
        val description = boundedContexts[context]?.ifBlank { null }
        val fragment = ManifestFragmentBuilder.build(context, description, bindings, subscriptions)
        codeGenerator.createNewFileByPath(
            Dependencies(aggregating = true, sources = sources.toTypedArray()),
            "${Manifest.FRAGMENT_DIRECTORY}/$module",
            "json",
        ).bufferedWriter().use { it.write(FRAGMENT_WRITER.writeValueAsString(ManifestTree.write(fragment))) }
    }

    @OptIn(KspExperimental::class)
    private fun analyze(root: KSClassDeclaration): AggregateBinding {
        val problems = mutableListOf<String>()
        val rootName = root.qualifiedName!!.asString()
        val constructor = root.primaryConstructor ?: root.getConstructors().firstOrNull()
        val firstParameter = constructor?.parameters?.firstOrNull()?.type?.resolve()?.declaration as? KSClassDeclaration
        val rootIsState = constructor == null || firstParameter == null ||
            firstParameter.qualifiedName?.asString() == STRING
        val state = if (rootIsState) root else firstParameter!!
        if (!rootIsState && constructor!!.parameters.size != 1) {
            problems += "the root's constructor must take only its state"
        }
        val stateArguments = stateArguments(state, problems)

        val dependencies = linkedMapOf<String, Dependency>()
        val commandFunctions = root.functionsNamedOrAnnotated(ON_COMMAND, OnCommand::class.java.name)
        val commands = commandFunctions.mapNotNull { function -> commandBinding(function, dependencies, problems) }
        commands.groupBy { it.command }.filterValues { it.size > 1 }.keys.forEach {
            problems += "two command functions handle $it"
        }
        val sourcing = state.functionsNamedOrAnnotated(ON_SOURCING, OnSourcing::class.java.name)
            .mapNotNull { function -> sourcingBinding(function, state, problems) }
        val afters = root.functionsNamedOrAnnotated(AFTER_COMMAND, AfterCommand::class.java.name)
            .mapNotNull { function -> afterBinding(function, dependencies, problems) }
        val commandTypes = commandFunctions.mapNotNull { firstParameterClass(it, unwrapCommand = true) }
        val eventTypes = state.functionsNamedOrAnnotated(ON_SOURCING, OnSourcing::class.java.name)
            .mapNotNull { function -> firstParameterClass(function, unwrapCommand = false) }
        problems += annotationProblems(root, state, commandTypes, eventTypes)
        return AggregateBinding(
            root = rootName,
            packageName = root.packageName.asString(),
            rootSimpleName = root.simpleName.asString(),
            aggregateName = root.toName(),
            state = state.qualifiedName!!.asString(),
            rootIsState = rootIsState,
            stateArguments = stateArguments,
            dependencies = dependencies.values.toList(),
            commands = commands,
            sourcing = sourcing,
            afters = afters,
            problems = problems,
            stateType = state.binaryName(),
            description = root.getAnnotationsByType(Description::class).firstOrNull()?.value,
        )
    }

    private fun KSClassDeclaration.isFramework(): Boolean =
        qualifiedName?.asString().orEmpty().let { name -> FRAMEWORK_PACKAGES.any { name.startsWith(it) } }

    private fun firstParameterClass(function: KSFunctionDeclaration, unwrapCommand: Boolean): KSClassDeclaration? {
        val type = function.parameters.firstOrNull()?.type?.resolve() ?: return null
        val unwrapped = if (unwrapCommand && type.declaration.qualifiedName?.asString() == COMMAND) {
            type.arguments.firstOrNull()?.type?.resolve() ?: return null
        } else {
            type
        }
        return unwrapped.declaration as? KSClassDeclaration
    }

    /** What V10 does not recognize on the model's types: the root, its state, its commands and its events. */
    private fun annotationProblems(
        root: KSClassDeclaration,
        state: KSClassDeclaration,
        commands: List<KSClassDeclaration>,
        events: List<KSClassDeclaration>,
    ): List<String> = (
        V10Annotations.problemsOfType(root, "aggregate root") +
            (if (state != root) V10Annotations.problemsOfType(state, "state") else emptyList()) +
            commands.distinct().filterNot { it.isFramework() }.flatMap { V10Annotations.problemsOfType(it, "command") } +
            events.distinct().filterNot { it.isFramework() }.flatMap { V10Annotations.problemsOfType(it, "event") }
        ).distinct()

    private fun stateArguments(state: KSClassDeclaration, problems: MutableList<String>): List<String> {
        val constructor = state.primaryConstructor ?: state.getConstructors().firstOrNull()
        val parameters = constructor?.parameters.orEmpty()
        val strings = parameters.all { it.type.resolve().declaration.qualifiedName?.asString() == STRING }
        return when {
            parameters.size == 1 && strings -> listOf("ref.id")
            parameters.size == 2 && strings -> listOf("ref.id", "ref.tenantId")
            else -> {
                problems += "the state needs a constructor (id) or (id, tenantId)"
                emptyList()
            }
        }
    }

    @OptIn(KspExperimental::class)
    private fun commandBinding(
        function: KSFunctionDeclaration,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
    ): CommandBinding? {
        val name = function.simpleName.asString()
        val parameters = function.parameters
        if (parameters.isEmpty()) {
            problems += "$name takes no command"
            return null
        }
        val declared = parameters.first().type.resolve()
        // `Command<X>` asks for the admitted command, not only its body X: the generated code passes the scope's.
        val admitted = declared.declaration.qualifiedName?.asString() == COMMAND
        val commandType = if (admitted) declared.arguments.first().type?.resolve() ?: return null else declared
        val command = commandType.declaration as? KSClassDeclaration ?: return null
        if (FRAMEWORK_PACKAGES.any { commandType.render().startsWith(it) }) {
            problems += "$name takes ${commandType.render()}: a V10 decide function takes the command body, " +
                "or $COMMAND of it"
            return null
        }
        if (command.hasAnnotation(Revision::class.java.name)) {
            problems += "command ${command.simpleName.asString()} is annotated @Revision: only events have revisions"
        }
        return CommandBinding(
            command = commandType.render(),
            commandName = command.getAnnotationsByType(Name::class).firstOrNull()?.value,
            aliases = command.getAnnotationsByType(Alias::class).flatMap { it.value.toList() }.toList(),
            function = name,
            admitted = admitted,
            arguments = parameters.drop(1).mapNotNull { argument(name, it.type.resolve(), dependencies, problems) },
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactorReturn(function) != null,
            creation = when {
                command.hasAnnotation(CreateAggregate::class.java.name) -> "REQUIRED"
                command.hasAnnotation(AllowCreate::class.java.name) -> "ALLOWED"
                else -> "NONE"
            },
            type = command.binaryName(),
            yields = EventYield.of(function.returnType?.resolve()),
        )
    }

    /**
     * The argument for a parameter after the command: the decide scope itself, or a dependency the model factory
     * takes. A framework type of 9.x has no V10 counterpart to pass.
     */
    private fun argument(
        function: String,
        type: KSType,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
    ): String? {
        val rendered = type.render()
        if (rendered == DECIDE_SCOPE) {
            return "this"
        }
        if (FRAMEWORK_PACKAGES.any { rendered.startsWith(it) }) {
            problems += "$function takes $rendered, a 9.x framework type with no V10 counterpart"
            return null
        }
        return dependencies.getOrPut(rendered) {
            val simple = type.declaration.simpleName.asString().replaceFirstChar { it.lowercase() }
            val unique = if (dependencies.values.any { it.name == simple }) simple + dependencies.size else simple
            Dependency(unique, rendered)
        }.name
    }

    @OptIn(KspExperimental::class)
    private fun sourcingBinding(
        function: KSFunctionDeclaration,
        state: KSClassDeclaration,
        problems: MutableList<String>,
    ): SourcingBinding? {
        val name = function.simpleName.asString()
        if (function.parameters.size != 1) {
            problems += "sourcing function $name must take only its event"
            return null
        }
        if (Modifier.SUSPEND in function.modifiers) {
            problems += "sourcing function $name suspends: evolving does no I/O"
        }
        val eventType = function.parameters.single().type.resolve()
        val event = eventType.declaration as? KSClassDeclaration ?: return null
        val returned = function.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        val revision = event.getAnnotationsByType(Revision::class).firstOrNull()?.value ?: 1
        if (revision < 1) {
            problems += "event ${event.simpleName.asString()} has @Revision($revision): a revision starts at 1"
        }
        return SourcingBinding(
            event = eventType.render(),
            eventName = event.getAnnotationsByType(Name::class).firstOrNull()?.value,
            revision = revision,
            aliases = event.getAnnotationsByType(Alias::class).flatMap { it.value.toList() }.toList(),
            function = name,
            returnsState = returned == state.qualifiedName?.asString(),
            type = event.binaryName(),
        )
    }

    private fun afterBinding(
        function: KSFunctionDeclaration,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
    ): AfterBinding? {
        val name = function.simpleName.asString()
        val annotation = function.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AfterCommand::class.java.name
        }
        fun classes(argument: String): List<String> =
            (annotation?.arguments?.firstOrNull { it.name?.asString() == argument }?.value as? List<*>).orEmpty()
                .filterIsInstance<KSType>().map { it.render() }
        return AfterBinding(
            function = name,
            parameter = function.parameters.firstOrNull()?.type?.resolve()?.render(),
            arguments = function.parameters.drop(
                1
            ).mapNotNull { argument(name, it.type.resolve(), dependencies, problems) },
            reactor = reactorReturn(function) != null,
            include = classes("include"),
            exclude = classes("exclude"),
            suspending = Modifier.SUSPEND in function.modifiers,
            yields = EventYield.of(function.returnType?.resolve()),
        )
    }

    private fun reactorReturn(function: KSFunctionDeclaration): String? =
        function.returnType?.resolve()?.declaration?.qualifiedName?.asString()?.takeIf { it in REACTOR_TYPES }

    private fun generate(binding: AggregateBinding, root: KSClassDeclaration) {
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, sources = listOfNotNull(root.containingFile).toTypedArray()),
            binding.packageName,
            "${binding.rootSimpleName}Model",
        ).bufferedWriter().use { it.write(ModelSource.render(binding)) }
    }

    private fun KSClassDeclaration.functionsNamedOrAnnotated(
        conventionalName: String,
        annotation: String,
    ): List<KSFunctionDeclaration> = getAllFunctions()
        // Not only public: 9.x found package-private Java handlers too, and the generated model shares their package.
        .filter { !it.isPrivate() && (it.simpleName.asString() == conventionalName || it.hasAnnotation(annotation)) }
        .toList()

    /** The JVM binary name of this class: `a.b.Outer$Inner` for a nested class. */
    private fun KSClassDeclaration.binaryName(): String {
        val names = generateSequence<KSDeclaration>(this) { it.parentDeclaration }.map { it.simpleName.asString() }
        val packageName = packageName.asString()
        return (if (packageName.isEmpty()) "" else "$packageName.") + names.toList().asReversed().joinToString("$")
    }

    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

    private fun KSType.render(): String {
        val name = declaration.qualifiedName!!.asString()
        if (arguments.isEmpty()) {
            return name
        }
        return name + arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.render() ?: "*" }
    }

    private companion object {
        val AGGREGATE_ROOT = AggregateRoot::class.java.name
        val BOUNDED_CONTEXT = BoundedContext::class.java.name

        // A plain tree needs no Kotlin module, whose reflection KSP's class loader does not provide.
        val FRAGMENT_WRITER = JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
        const val ON_COMMAND = "onCommand"
        const val ON_SOURCING = "onSourcing"
        const val AFTER_COMMAND = "afterCommand"
        const val STRING = "kotlin.String"
        const val DECIDE_SCOPE = "me.ahoo.wow.api.decide.DecideScope"
        const val COMMAND = "me.ahoo.wow.api.decide.Command"

        /** Packages of 9.x framework types; Wow's own platform domains live under `me.ahoo.wow` too. */
        val FRAMEWORK_PACKAGES = listOf(
            "me.ahoo.wow.api.",
            "me.ahoo.wow.command.",
            "me.ahoo.wow.event.",
            "me.ahoo.wow.eventsourcing.",
            "me.ahoo.wow.messaging.",
            "me.ahoo.wow.modeling.",
            "me.ahoo.wow.infra.",
            "me.ahoo.wow.ioc.",
            "me.ahoo.wow.saga.",
        )
        val REACTOR_TYPES = setOf(
            "reactor.core.publisher.Mono",
            "reactor.core.publisher.Flux",
            "org.reactivestreams.Publisher",
        )
    }
}

class ModelSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val mode = when (environment.options[OPTION]) {
            "generate" -> ModelSymbolProcessor.Mode.GENERATE
            "report" -> ModelSymbolProcessor.Mode.REPORT
            else -> ModelSymbolProcessor.Mode.OFF
        }
        val manifest = ModelSymbolProcessor.ManifestOptions(
            module = environment.options[MODULE_OPTION]?.ifBlank { null },
            context = environment.options[CONTEXT_OPTION]?.ifBlank { null },
        )
        return ModelSymbolProcessor(mode, environment.codeGenerator, environment.logger, manifest)
    }

    companion object {
        /** The KSP option: `generate`, `report`, or absent for nothing. */
        const val OPTION = "wow.model"

        /** The KSP option naming the module's Manifest fragment, `META-INF/wow/manifest/<module>.json`. */
        internal const val MODULE_OPTION = "wow.manifest.module"

        /** The KSP option naming the bounded context of the module's aggregates and subscriptions. */
        internal const val CONTEXT_OPTION = "wow.context"
    }
}
