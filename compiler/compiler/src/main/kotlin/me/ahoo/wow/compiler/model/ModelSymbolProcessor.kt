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
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
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
import me.ahoo.wow.api.lifecycle.Lifecycle
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
 * `@Revision` and `@Alias` and a command's `@Name` and `@Alias` flow into the model ([AliasCheck] checks the aliases).
 * The `@Upgrade` functions of the module (decision D82, [UpgradeResolver]) are chained per event: an event at revision
 * `n` needs exactly one from each of the revisions 1 to `n - 1`, none missing (no upgrade at all included), and an
 * upgrade of a class that is not an event of an aggregate of the module is an error. The generated factory's last
 * parameter, `configure`, runs on the model builder after the bindings, as a general extension point.
 *
 * Subscription processors (`@Projection`, `@Reactor`, `@Saga` with `@OnEvent` handlers) are checked by
 * [SubscriptionResolver] and bound by [SubscriptionSource]: each gets `fun <processor>Handlers(dependencies)` in its
 * package, one runtime handler per function, and the module a `SubscriptionProvider` beside its model provider,
 * registered for `ServiceLoader` in `META-INF/services/me.ahoo.wow.runtime.binding.SubscriptionProvider`; the
 * generated code builds on `wow-runtime`, so a module that declares processors depends on it; without it, they are
 * only checked, and a warning says their subscriptions are not bound.
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
 * written), and `wow.manifest.module` names the file (else the context does). Beside the fragment goes the module's
 * binding index (decision D107, [ModelProviderSource]): a `ModelProvider` that builds every model of the module with
 * the factories' parameters from the host's `Dependencies`, registered in `META-INF/services` for `ServiceLoader`.
 *
 * `@AggregateRoot(lifecycle = [...])` narrows the lifecycle commands a model handles (decision D91): the generated
 * model calls `lifecycle(...)` with them before `configure`, and the fragment lists them.
 *
 * `@Name` on a dependency parameter qualifies it (decision D119): the binding index asks for the dependency of its
 * type by that name, and two parameters of one type with different qualifiers are two dependencies.
 * `@OnCommand(produces = [...])` declares the events of a function whose return type does not say (decision D120),
 * such as a `Decision`; a conventionally named `onCommand` needs the annotation to declare them. The module's
 * `@ErrorCode` constants (decision D121, [ErrorCodeResolver]) are the context's `errors` in the fragment.
 */
internal class ModelSymbolProcessor(
    private val mode: Mode,
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val manifest: ManifestOptions,
) : SymbolProcessor {
    enum class Mode { OFF, GENERATE, REPORT }

    /**
     * Where the Manifest fragment goes: [module] names it (`META-INF/wow/manifest/<module>.json`), and [context]
     * names the bounded context the module's aggregates and subscriptions belong to. Without [context], the one
     * `@BoundedContext` of the module names it; without [module], the context does.
     */
    internal data class ManifestOptions(val module: String?, val context: String?)

    private val bindings = mutableListOf<AggregateBinding>()

    /** The module's `@Upgrade` functions, and those of them an aggregate's event has claimed. */
    private val upgrades = mutableListOf<UpgradeFunction>()
    private val claimedUpgrades = mutableSetOf<UpgradeFunction>()
    private val reportedUpgrades = mutableSetOf<UpgradeFunction>()
    private val subscriptions = mutableListOf<SubscriptionBinding>()

    /** Whether the module compiles against wow-runtime, whose binding API the generated subscriptions build on. */
    private var bindable = false

    /** The module's `@ErrorCode` codes without problems, by code (decision D121). */
    private val errorCodes = sortedMapOf<String, ErrorCodeBinding>()
    private val sources = mutableSetOf<KSFile>()

    /** The packages of the module's aggregate roots and subscription processors, where its binding index goes. */
    private val packages = sortedSetOf<String>()

    /** The `@BoundedContext`s of the module: name to description. */
    private val boundedContexts = sortedMapOf<String, String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (mode == Mode.OFF) {
            return emptyList()
        }
        collectBoundedContexts(resolver)
        resolver.getSymbolsWithAnnotation(UpgradeResolver.UPGRADE).filterIsInstance<KSFunctionDeclaration>().forEach {
            it.containingFile?.let { file -> sources += file }
            upgrades += UpgradeResolver.read(it)
        }
        val deferredErrorCodes = processErrorCodes(resolver)
        val roots = resolver.getSymbolsWithAnnotation(AGGREGATE_ROOT).filterIsInstance<KSClassDeclaration>().toList()
        val deferred = roots.filterNot { it.validate() }
        val analyzed = roots.filter { it.validate() }.map { root ->
            root.containingFile?.let { sources += it }
            packages += root.packageName.asString()
            root to analyze(root)
        }
        // Former names two types claim are found across the module, the aggregates of earlier rounds included.
        val duplicates = AliasCheck.duplicates(bindings + analyzed.map { it.second }, context())
        analyzed.forEach { (root, analyzedBinding) ->
            val binding = analyzedBinding.copy(
                problems = analyzedBinding.problems + duplicates[analyzedBinding.root].orEmpty(),
            )
            bindings += binding
            if (mode == Mode.GENERATE) {
                if (binding.problems.isEmpty()) {
                    generate(binding, root)
                } else {
                    binding.problems.forEach { logger.error("${binding.root}: $it", root) }
                }
            }
        }
        if (deferred.isEmpty()) {
            reportUnclaimedUpgrades()
        }
        return deferred + deferredErrorCodes + processSubscriptions(resolver)
    }

    /**
     * Collects the module's `@ErrorCode` constants (decision D121); in generate mode, their problems and a code two
     * of them hold fail the compilation.
     */
    private fun processErrorCodes(resolver: Resolver): List<KSAnnotated> {
        val properties = resolver.getSymbolsWithAnnotation(ErrorCodeResolver.ERROR_CODE)
            .filterIsInstance<KSPropertyDeclaration>().toList()
        val stringType = resolver.builtIns.stringType
        properties.filter { it.validate() }.forEach { property ->
            sources += listOfNotNull(property.containingFile)
            val binding = ErrorCodeResolver.resolve(property, stringType)
            val code = binding.error?.code
            val previous = code?.let { errorCodes[it] }
            val problems = binding.problems + listOfNotNull(
                previous?.let { "error code $code is declared by ${it.property} and ${binding.property}" },
            )
            if (problems.isEmpty() && code != null) {
                errorCodes[code] = binding
            } else if (mode == Mode.GENERATE) {
                problems.forEach { logger.error(it, property) }
            }
        }
        return properties.filterNot { it.validate() }
    }

    /** The bounded context of the module's types, when an option or a single `@BoundedContext` names it. */
    private fun context(): String? = manifest.context ?: boundedContexts.keys.singleOrNull()

    /** An `@Upgrade` of a class that is not an event of an aggregate of the module upgrades nothing. */
    private fun reportUnclaimedUpgrades() {
        if (mode != Mode.GENERATE) {
            return
        }
        (upgrades - claimedUpgrades - reportedUpgrades).forEach { upgrade ->
            reportedUpgrades += upgrade
            val event = upgrade.event ?: "an unresolved class"
            logger.error(
                "upgrade ${upgrade.name} upgrades $event, which is not an event of an aggregate of this module",
                upgrade.declaration,
            )
        }
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
        bindable = resolver.getClassDeclarationByName(resolver.getKSNameFromString(SubscriptionSource.BOUND_HANDLER)) != null
        processors.filter { it.validate() }.forEach { processor ->
            processor.containingFile?.let { sources += it }
            packages += processor.packageName.asString()
            val binding = SubscriptionResolver.resolve(processor, REACTOR_TYPES)
            subscriptions += binding
            if (mode == Mode.GENERATE) {
                if (binding.problems.isNotEmpty()) {
                    binding.problems.forEach { logger.error("${binding.processor}: $it", processor) }
                } else if (bindable) {
                    generate(binding, processor)
                } else {
                    logger.warn(
                        "${binding.processor}: its subscriptions are not bound: the module does not depend on " +
                            "wow-runtime, whose binding API the generated subscriptions build on",
                        processor,
                    )
                }
            }
        }
        return deferred
    }

    private fun generate(binding: SubscriptionBinding, processor: KSClassDeclaration) {
        codeGenerator.createNewFile(
            Dependencies(aggregating = false, sources = listOfNotNull(processor.containingFile).toTypedArray()),
            binding.packageName,
            "${binding.simpleNames.joinToString("")}Subscriptions",
        ).bufferedWriter().use { it.write(SubscriptionSource.render(binding)) }
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
        if (bindings.isEmpty() && subscriptions.isEmpty() && errorCodes.isEmpty()) {
            return
        }
        // With the processor off, nothing was collected: the mode is report or generate.
        if (mode == Mode.REPORT) {
            codeGenerator.createNewFileByPath(
                Dependencies(aggregating = true, sources = sources.toTypedArray()),
                "META-INF/wow/model-report",
                "json",
            ).bufferedWriter().use { it.write(ModelReport.json(bindings, subscriptions)) }
        } else {
            writeManifestFragment()
        }
    }

    /**
     * Writes the module's Manifest fragment ([ManifestFragmentBuilder]) when its bounded context is known. A model
     * with problems never gets here: their errors make KSP call [onError] instead of [finish].
     */
    private fun writeManifestFragment() {
        val context = context()
        if (context == null) {
            logger.warn(
                "No Manifest fragment and no model provider: the module declares ${boundedContexts.size} " +
                    "@BoundedContext; set the KSP option ${ModelSymbolProcessorProvider.CONTEXT_OPTION} to name " +
                    "its bounded context",
            )
            return
        }
        val module = manifest.module ?: context
        val description = boundedContexts[context]?.ifBlank { null }
        val errors = errorCodes.values.mapNotNull(ErrorCodeBinding::error)
        val fragment = ManifestFragmentBuilder.build(context, description, bindings, subscriptions, errors)
        codeGenerator.createNewFileByPath(
            Dependencies(aggregating = true, sources = sources.toTypedArray()),
            "${Manifest.FRAGMENT_DIRECTORY}/$module",
            "json",
        ).bufferedWriter().use { it.write(FRAGMENT_WRITER.writeValueAsString(ManifestTree.write(fragment))) }
        writeModelProvider(context, "${Manifest.FRAGMENT_DIRECTORY}/$module.json", module)
        if (bindable && subscriptions.isNotEmpty()) {
            writeSubscriptionProvider(context, module)
        }
    }

    /**
     * Writes the module's subscription binding index ([SubscriptionSource.renderProvider]) beside its model provider,
     * and registers it for `ServiceLoader` in `META-INF/services/me.ahoo.wow.runtime.binding.SubscriptionProvider`.
     */
    private fun writeSubscriptionProvider(context: String, module: String) {
        val packageName = ModelProviderSource.commonPackage(packages)
        val className = SubscriptionSource.className(module)
        val dependencies = Dependencies(aggregating = true, sources = sources.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, className).bufferedWriter().use {
            it.write(SubscriptionSource.renderProvider(packageName, className, context, subscriptions))
        }
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/${SubscriptionSource.SUBSCRIPTION_PROVIDER}",
            "",
        ).bufferedWriter().use {
            it.write((if (packageName.isEmpty()) className else "$packageName.$className") + "\n")
        }
    }

    /**
     * Writes the module's binding index (decision D107, [ModelProviderSource]) beside its fragment, and registers it
     * for `ServiceLoader` in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`.
     */
    private fun writeModelProvider(context: String, fragment: String, module: String) {
        val packageName = ModelProviderSource.commonPackage(packages)
        val className = ModelProviderSource.className(module)
        val dependencies = Dependencies(aggregating = true, sources = sources.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, className).bufferedWriter().use {
            it.write(ModelProviderSource.render(packageName, className, context, fragment, bindings))
        }
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/${ModelProviderSource.MODEL_PROVIDER}",
            "",
        ).bufferedWriter().use {
            it.write((if (packageName.isEmpty()) className else "$packageName.$className") + "\n")
        }
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
        problems += producesProblems(commands, sourcing)
        val commandTypes = commandFunctions.mapNotNull { firstParameterClass(it, unwrapCommand = true) }
        val eventTypes = state.functionsNamedOrAnnotated(ON_SOURCING, OnSourcing::class.java.name)
            .mapNotNull { function -> firstParameterClass(function, unwrapCommand = false) }
        problems += annotationProblems(root, state, commandTypes, eventTypes)
        val aggregateName = root.toName()
        problems += aliasProblems(aggregateName, commands, sourcing)
        val upgradeBindings = upgradeBindings(sourcing, problems)
        return AggregateBinding(
            root = rootName,
            packageName = root.packageName.asString(),
            rootSimpleName = root.simpleName.asString(),
            aggregateName = aggregateName,
            state = state.qualifiedName!!.asString(),
            rootIsState = rootIsState,
            stateArguments = stateArguments,
            dependencies = dependencies.values.toList(),
            commands = commands,
            sourcing = sourcing,
            afters = afters,
            upgrades = upgradeBindings,
            problems = problems,
            stateType = state.binaryName(),
            description = root.getAnnotationsByType(Description::class).firstOrNull()?.value,
            lifecycle = lifecycle(root, problems),
        )
    }

    /** `@OnCommand(produces)` lists events of the aggregate only (decision D120). */
    private fun producesProblems(commands: List<CommandBinding>, sourcing: List<SourcingBinding>): List<String> {
        val events = sourcing.map { it.event }.toSet()
        return commands.flatMap { command ->
            command.declaredProduces.filterNot { it in events }.map {
                "@OnCommand(produces) of ${command.function}(${command.command}) lists $it, which is not an event " +
                    "of the aggregate: no sourcing function takes it"
            }
        }
    }

    /**
     * The system names of the lifecycle commands `@AggregateRoot(lifecycle)` lists (decision D91), sorted; a class
     * that is not a lifecycle command is a problem. Without the argument, every one.
     */
    private fun lifecycle(root: KSClassDeclaration, problems: MutableList<String>): List<String> {
        val annotation = root.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AGGREGATE_ROOT
        }
        val listed = annotation?.arguments?.firstOrNull { it.name?.asString() == LIFECYCLE }?.value as? List<*>
            ?: return AggregateBinding.ALL_LIFECYCLE_COMMANDS
        val commands = LIFECYCLE_COMMANDS
        return listed.filterIsInstance<KSType>().mapNotNull { type ->
            val name = type.declaration.qualifiedName?.asString()
            commands[name].also {
                if (it == null) {
                    problems += "@AggregateRoot(lifecycle) lists $name, which is not a lifecycle command: one of " +
                        commands.keys.sorted().joinToString { command -> command.substringAfterLast('.') }
                }
            }
        }.distinct().sorted()
    }

    /** The problems of the `@Alias` values of the aggregate's commands and events ([AliasCheck]). */
    private fun aliasProblems(
        aggregateName: String,
        commands: List<CommandBinding>,
        sourcing: List<SourcingBinding>,
    ): List<String> {
        val context = context()
        return commands.flatMap {
            val simpleName = AliasCheck.simpleName(it.type)
            AliasCheck.problems(
                "command",
                simpleName,
                it.commandName ?: simpleName,
                it.aliases,
                aggregateName,
                context
            )
        } + sourcing.flatMap {
            val simpleName = AliasCheck.simpleName(it.type)
            AliasCheck.problems("event", simpleName, it.eventName ?: simpleName, it.aliases, aggregateName, context)
        }
    }

    /**
     * The upgrades of the aggregate's events, from the module's `@Upgrade` functions (decision D82); the problems of
     * their chains, a missing chain included, go to [problems].
     */
    private fun upgradeBindings(
        sourcing: List<SourcingBinding>,
        problems: MutableList<String>,
    ): List<UpgradeBinding> = sourcing.flatMap { event ->
        val functions = upgrades.filter { it.event == event.event }
        claimedUpgrades += functions
        problems += UpgradeResolver.chainProblems(AliasCheck.simpleName(event.type), event.revision, functions)
        functions.sortedBy { it.from }.map { it.binding.copy(event = event.event) }
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
        if (parameters.first().hasAnnotation(Name::class.java.name)) {
            problems += "$name's command parameter is annotated @Name: a qualifier names a dependency parameter"
        }
        val returned = EventYield.of(function.returnType?.resolve())
        val producesListed = declaredProduces(function)
        if (producesListed.isNotEmpty() && returned != null) {
            problems += "$name's return type names the events it produces: @OnCommand(produces) is for a return " +
                "type that does not, such as a Decision"
        }
        return CommandBinding(
            command = commandType.render(),
            commandName = command.getAnnotationsByType(Name::class).firstOrNull()?.value,
            aliases = command.getAnnotationsByType(Alias::class).flatMap { it.value.toList() }.toList(),
            function = name,
            admitted = admitted,
            arguments = parameters.drop(1).mapNotNull { argument(name, it, dependencies, problems) },
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactorReturn(function) != null,
            creation = when {
                command.hasAnnotation(CreateAggregate::class.java.name) -> "REQUIRED"
                command.hasAnnotation(AllowCreate::class.java.name) -> "ALLOWED"
                else -> "NONE"
            },
            type = command.binaryName(),
            yields = returned ?: producesListed.ifEmpty { null },
            declaredProduces = producesListed,
        )
    }

    /** The events `@OnCommand(produces)` lists on [function], by qualified name (decision D120). */
    private fun declaredProduces(function: KSFunctionDeclaration): List<String> {
        val annotation = function.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == OnCommand::class.java.name
        }
        val listed = annotation?.arguments?.firstOrNull { it.name?.asString() == PRODUCES }?.value as? List<*>
        return listed.orEmpty().filterIsInstance<KSType>().mapNotNull { it.declaration.qualifiedName?.asString() }
            .distinct()
    }

    /**
     * The argument for a parameter after the command: the decide scope itself, or a dependency the model factory
     * takes, qualified by the parameter's `@Name` (decision D119). One type with one qualifier, or none, is one
     * dependency, however many functions take it. A framework type of 9.x has no V10 counterpart to pass.
     */
    @OptIn(KspExperimental::class)
    private fun argument(
        function: String,
        parameter: KSValueParameter,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
    ): String? {
        val type = parameter.type.resolve()
        val rendered = type.render()
        val qualifier = parameter.getAnnotationsByType(Name::class).firstOrNull()?.value
        val where = "$function's parameter ${parameter.name?.asString()}"
        if (rendered == DECIDE_SCOPE) {
            if (qualifier != null) {
                problems += "$where is annotated @Name: the decide scope takes no qualifier"
            }
            return "this"
        }
        if (FRAMEWORK_PACKAGES.any { rendered.startsWith(it) }) {
            problems += "$function takes $rendered, a 9.x framework type with no V10 counterpart"
            return null
        }
        if (qualifier != null && qualifier.isBlank()) {
            problems += "$where has a blank @Name: a qualifier names the dependency"
            return null
        }
        return dependencies.getOrPut(if (qualifier == null) rendered else "$rendered@$qualifier") {
            val simple = type.declaration.simpleName.asString().withFirst(Char::lowercaseChar) +
                qualifier?.let(::identifierSuffix).orEmpty()
            val unique = if (dependencies.values.any { it.name == simple }) simple + dependencies.size else simple
            Dependency(unique, rendered, type.declaration.qualifiedName!!.asString(), qualifier)
        }.name
    }

    /** `"fx.primary"` gives `FxPrimary`, to name a qualified dependency's factory parameter. */
    private fun identifierSuffix(qualifier: String): String =
        qualifier.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.withFirst(Char::uppercaseChar) }

    /** This non-empty name with its first character mapped by [first]. */
    private fun String.withFirst(first: (Char) -> Char): String = first(this[0]) + substring(1)

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
            arguments = function.parameters.drop(1).mapNotNull { argument(name, it, dependencies, problems) },
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
        val upgradeFiles = upgrades.filter { upgrade -> binding.upgrades.any { it.function == upgrade.name } }
            .mapNotNull { it.declaration.containingFile }
        codeGenerator.createNewFile(
            Dependencies(
                aggregating = false,
                sources = (listOfNotNull(root.containingFile) + upgradeFiles).distinct().toTypedArray(),
            ),
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
        const val LIFECYCLE = "lifecycle"
        const val PRODUCES = "produces"

        /** The lifecycle commands' system names, by their classes' names. */
        val LIFECYCLE_COMMANDS: Map<String, String> = Lifecycle.COMMANDS.entries.associate { (name, type) ->
            type.name to name
        }
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

    internal companion object {
        /** The KSP option: `generate`, `report`, or absent for nothing. */
        const val OPTION = "wow.model"

        /** The KSP option naming the module's Manifest fragment, `META-INF/wow/manifest/<module>.json`. */
        internal const val MODULE_OPTION = "wow.manifest.module"

        /** The KSP option naming the bounded context of the module's aggregates and subscriptions. */
        internal const val CONTEXT_OPTION = "wow.context"
    }
}
