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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.api.annotation.Unaddressed
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.ContextName
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
 * [SubscriptionResolver] and bound by [SubscriptionSource] (decision D149): each gets
 * `fun <processor>Handlers(dependencies)` in its package, one kernel `ProcessorBinding` per function, and the
 * module's model provider lists them all in its `processors`; the generated code builds on `wow-kernel` only, as the
 * models do, so a domain module never depends on the runtime.
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
 *
 * A `@MountedCommand` (decision D182) of the module is mounted under the aggregate of the module it names: the
 * generated model mounts it (`mount<C>()`), and the fragment lists it among the aggregate's commands as mounted, with
 * its `transformsTo`. One that names no aggregate of the module, that a command function of its aggregate also takes,
 * or whose name its aggregate's commands already use, fails the compilation, and so does a `transformsTo` entry not
 * named `context.aggregate.Command`.
 *
 * `@OnEvent(aggregates = ["ctx.agg"])` names the aggregates a handler reads (decision D138): of the module's context,
 * they must be aggregates of the module whose state the handler takes; of another context, the handler's binding
 * names them, and the runtime reads their published streams for it (decision D134). A handler of a nullable state,
 * `StateEvent<S?>` or `StateEvent<*>`, says so in its binding and in its Manifest source (decision D140). A saga's
 * `@OnRejected` handlers (decisions D136, D153) and `@OnCommitted` handlers (decision D192) are in the fragment too, and
 * bound with each of its handlers.
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

    /** The module's `@MountedCommand`s, and those of them an aggregate of the module has claimed. */
    private val mountedCommands = mutableListOf<Pair<MountedBinding, KSClassDeclaration>>()
    private val claimedMounted = mutableSetOf<String>()
    private val reportedMounted = mutableSetOf<String>()

    /** The module's `@ErrorCode` codes without problems, by code (decision D121). */
    private val errorCodes = sortedMapOf<String, ErrorCodeBinding>()
    private val sources = mutableSetOf<KSFile>()

    /** Whether the module has `wow-query-api`, so each state gets its `<State>Properties` ([QueryPropertiesSource]). */
    private var queryApi: Boolean? = null

    /** The states whose `<State>Properties` were generated. */
    private val propertiesOf = mutableSetOf<String>()

    /** The packages of the module's aggregate roots and subscription processors, where its binding index goes. */
    private val packages = sortedSetOf<String>()

    /**
     * The `@BoundedContext`s of the module: name to description. In generate mode, a name that breaks the rule of a
     * context name ([ContextName], decision D169), the annotation's or the KSP option's, fails the compilation.
     */
    private val boundedContexts = sortedMapOf<String, String>()

    /** Whether the KSP option naming the context was checked against the rule of a context name, once per build. */
    private var optionChecked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (mode == Mode.OFF) {
            return emptyList()
        }
        collectBoundedContexts(resolver)
        queryApi = queryApi ?: QueryPropertiesSource.available(resolver)
        resolver.getSymbolsWithAnnotation(UpgradeResolver.UPGRADE).filterIsInstance<KSFunctionDeclaration>().forEach {
            it.containingFile?.let { file -> sources += file }
            upgrades += UpgradeResolver.read(it)
        }
        val deferredErrorCodes = processErrorCodes(resolver)
        val deferredMounted = collectMountedCommands(resolver)
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
                binding.warnings.forEach { logger.warn("${binding.root}: $it", root) }
                if (binding.problems.isEmpty()) {
                    generate(binding, root)
                } else {
                    binding.problems.forEach { logger.error("${binding.root}: $it", root) }
                }
            }
        }
        if (deferred.isEmpty()) {
            reportUnclaimedUpgrades()
            reportUnclaimedMounted()
        }
        return deferred + deferredErrorCodes + deferredMounted + processSubscriptions(resolver)
    }

    /** Collects the module's `@MountedCommand` classes (decision D182); the ones not yet valid are deferred. */
    private fun collectMountedCommands(resolver: Resolver): List<KSAnnotated> {
        val declarations = resolver.getSymbolsWithAnnotation(MountedCommandResolver.MOUNTED_COMMAND)
            .filterIsInstance<KSClassDeclaration>().toList()
        declarations.filter { it.validate() }.forEach { declaration ->
            declaration.containingFile?.let { sources += it }
            mountedCommands += MountedCommandResolver.read(declaration) to declaration
        }
        return declarations.filterNot { it.validate() }
    }

    /** The mounted commands of the aggregate [aggregateName] with [commands], and their problems in [problems]. */
    private fun mountedOf(
        aggregateName: String,
        commands: List<CommandBinding>,
        problems: MutableList<String>,
    ): List<MountedBinding> {
        val mounted = mountedCommands.map { it.first }.filter { it.aggregate == aggregateName }
        claimedMounted += mounted.map { it.command }
        mounted.forEach { problems += MountedCommandResolver.problems(it, commands) }
        return mounted
    }

    /** A `@MountedCommand` that names no aggregate of the module is mounted nowhere. */
    private fun reportUnclaimedMounted() {
        if (mode != Mode.GENERATE) {
            return
        }
        mountedCommands.filter { (binding, _) -> binding.command !in claimedMounted && binding.command !in reportedMounted }
            .forEach { (binding, declaration) ->
                reportedMounted += binding.command
                binding.problems.forEach { logger.error(it, declaration) }
                if (binding.aggregate.isNotBlank()) {
                    logger.error(
                        "mounted command ${binding.command} is mounted under ${binding.aggregate}, which is not an " +
                            "aggregate of this module",
                        declaration,
                    )
                }
            }
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
        val handlerClasses = listOf(
            SubscriptionResolver.ON_EVENT,
            SubscriptionResolver.ON_REJECTED,
            SubscriptionResolver.ON_COMMITTED,
        ).asSequence()
            .flatMap { resolver.getSymbolsWithAnnotation(it) }
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull { it.parentDeclaration as? KSClassDeclaration }
            // A processor base class is read through the processors extending it (finding A4-2).
            .filterNot { SubscriptionResolver.isProcessorBase(it) }
        val processors = (
            (SubscriptionResolver.KIND_ANNOTATIONS + SubscriptionResolver.REMOVED_KIND_ANNOTATIONS).asSequence()
                .flatMap { resolver.getSymbolsWithAnnotation(it) }
                .filterIsInstance<KSClassDeclaration>() + handlerClasses
            ).distinct().toList()
        val deferred = processors.filterNot { it.validate() }
        processors.filter { it.validate() }.forEach { processor ->
            processor.containingFile?.let { sources += it }
            packages += processor.packageName.asString()
            val binding = SubscriptionResolver.resolve(processor, REACTOR_TYPES)
            subscriptions += binding
            if (mode == Mode.GENERATE) {
                if (binding.problems.isNotEmpty()) {
                    binding.problems.forEach { logger.error("${binding.processor}: $it", processor) }
                } else {
                    generate(binding, processor)
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
                if (mode == Mode.GENERATE) {
                    val problem = ContextName.problem(it.name)
                    if (problem != null) {
                        logger.error("@BoundedContext: $problem", declaration)
                    }
                }
            }
        }
        if (mode == Mode.GENERATE && !optionChecked) {
            optionChecked = true
            manifest.context?.let(ContextName::problem)?.let {
                logger.error("KSP option ${ModelSymbolProcessorProvider.CONTEXT_OPTION}: $it")
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
        val problems = ManifestFragmentBuilder.problems(context, bindings, subscriptions)
        if (problems.isNotEmpty()) {
            problems.forEach { logger.error(it) }
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
    }

    /**
     * Writes the module's binding index (decision D107, [ModelProviderSource]) beside its fragment, with the handlers
     * of its subscription processors (decision D149), and registers it for `ServiceLoader` in
     * `META-INF/services/me.ahoo.wow.kernel.ModelProvider`.
     */
    private fun writeModelProvider(context: String, fragment: String, module: String) {
        val packageName = ModelProviderSource.commonPackage(packages)
        val className = ModelProviderSource.className(module)
        val dependencies = Dependencies(aggregating = true, sources = sources.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, className).bufferedWriter().use {
            it.write(ModelProviderSource.render(packageName, className, context, fragment, bindings, subscriptions))
        }
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/${ModelProviderSource.MODEL_PROVIDER}",
            "",
        ).bufferedWriter().use {
            it.write((if (packageName.isEmpty()) className else "$packageName.$className") + "\n")
        }
    }

    /**
     * The root's state, decided explicitly (finding A4-4), not from the order or the defaults of its constructors: a
     * root is built from its state when one of its constructors takes exactly one parameter whose class has sourcing
     * functions (`@OnSourcing`, or named `onSourcing`), and that class is the state; the root may have other
     * constructors, such as a Java no-argument one, and the parameter may have a default value. Else the root is its
     * own state (decision D203). Two such constructors of different states are a problem, and so is a constructor
     * whose first parameter is a state but that takes more.
     */
    private fun stateOf(root: KSClassDeclaration, problems: MutableList<String> = mutableListOf()): KSClassDeclaration {
        val constructors = root.getConstructors().toList()
        fun KSValueParameter.stateClass(): KSClassDeclaration? =
            (type.resolve().declaration as? KSClassDeclaration)?.takeIf { it != root && it.hasSourcingFunctions() }
        val states = constructors.filter { it.parameters.size == 1 }.mapNotNull { it.parameters.single().stateClass() }
            .distinct()
        if (states.size > 1) {
            problems += "the root's constructors take the states ${states.joinToString { it.simpleName.asString() }}: " +
                "a root is built from one state, so keep one constructor taking it"
        }
        val state = states.firstOrNull()
        if (state == null) {
            constructors.firstOrNull { it.parameters.size > 1 && it.parameters.first().stateClass() != null }?.let {
                problems += "the root's constructor must take only its state"
            }
        }
        return state ?: root
    }

    /** Whether this class, or a superclass, has a sourcing function: what makes it an aggregate's state. */
    private fun KSClassDeclaration.hasSourcingFunctions(): Boolean = getAllFunctions().any {
        it.simpleName.asString() == ON_SOURCING || it.annotatedIn(ON_SOURCING_ANNOTATION) != null
    }

    @OptIn(KspExperimental::class)
    private fun analyze(root: KSClassDeclaration): AggregateBinding {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rootName = root.qualifiedName!!.asString()
        val state = stateOf(root, problems)
        val rootIsState = state == root
        val stateArguments = stateArguments(state, problems)

        val dependencies = linkedMapOf<String, Dependency>()
        val commandFunctions = root.functionsNamedOrAnnotated(ON_COMMAND, ON_COMMAND_ANNOTATION, problems)
        val commands = commandFunctions.mapNotNull { function ->
            commandBinding(function, dependencies, problems, warnings)
        }
        commands.groupBy { it.command }.filterValues { it.size > 1 }.keys.forEach {
            problems += "two command functions handle $it"
        }
        val sourcingFunctions = state.functionsNamedOrAnnotated(ON_SOURCING, ON_SOURCING_ANNOTATION, problems)
        val sourcing = sourcingOf(sourcingFunctions, state, problems)
        val commandTypes = commandFunctions.mapNotNull { firstParameterClass(it, unwrapCommand = true) }
        val afters = root.functionsNamedOrAnnotated(AFTER_COMMAND, AFTER_COMMAND_ANNOTATION, problems)
            .mapNotNull { function -> afterBinding(function, commandTypes, dependencies, problems, warnings) }
        if (!rootIsState) {
            problems += misplacedProblems(root, state)
        }
        problems += producesProblems(commands, sourcing)
        problems += yieldProblems(commands, afters, sourcing)
        val eventTypes = sourcingFunctions.mapNotNull { function -> firstParameterClass(function, unwrapCommand = false) }
        problems += annotationProblems(root, state, commandTypes, eventTypes)
        val aggregateName = root.toName()
        problems += aliasProblems(aggregateName, commands, sourcing)
        val upgradeBindings = upgradeBindings(sourcing, problems)
        val mounted = mountedOf(aggregateName, commands, problems)
        val ownership = AggregateRootArguments.ownership(root)
        val retiredEvents = RetiredEvents.of(root)
        problems += RetiredEvents.problems(retiredEvents, aggregateName, context(), commands, sourcing, mounted)
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
            description = Descriptions.of(root),
            lifecycle = AggregateRootArguments.lifecycle(root, ownership, problems),
            publication = AggregateRootArguments.publication(root, problems),
            ownership = ownership,
            mounted = mounted,
            snapshot = AggregateRootArguments.snapshot(root),
            stateRevision = AggregateRootArguments.stateRevision(root, problems),
            retiredEvents = retiredEvents,
            tags = TagsResolver.of(state, problems),
            warnings = warnings,
        )
    }

    /**
     * The handler annotations on the wrong class of an aggregate whose root is built from its state (finding A4-2):
     * command functions and after hooks are the root's, sourcing functions the state's. The generated model would not
     * see them.
     */
    private fun misplacedProblems(root: KSClassDeclaration, state: KSClassDeclaration): List<String> {
        fun annotated(type: KSClassDeclaration, annotation: String): List<String> =
            type.getAllFunctions().filter { it.annotatedIn(annotation) != null }.map { it.simpleName.asString() }
                .toList()
        val rootName = root.simpleName.asString()
        val stateName = state.simpleName.asString()
        return annotated(state, ON_COMMAND_ANNOTATION).map {
            "the state $stateName's function $it is annotated @OnCommand: command functions are the aggregate " +
                "root's, so move it to $rootName"
        } + annotated(state, AFTER_COMMAND_ANNOTATION).map {
            "the state $stateName's function $it is annotated @AfterCommand: after hooks are the aggregate root's, " +
                "so move it to $rootName"
        } + annotated(root, ON_SOURCING_ANNOTATION).map {
            "the aggregate root $rootName's function $it is annotated @OnSourcing: sourcing functions are the " +
                "state's, so move it to $stateName"
        }
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
     * What a command function or an after hook produces, as its return type says ([EventYield]), is an event of the
     * aggregate (finding A4-9): the kernel cannot evolve the state with an event no sourcing function takes, so every
     * such command would fail at run time. A system event (lifecycle, schedule) is always one.
     */
    private fun yieldProblems(
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
     * concrete subclasses (finding A4-9), those another function takes by their own class aside; one taking any other
     * interface or abstract class is a problem: the kernel finds a sourcing function by the event's exact class.
     */
    private fun sourcingOf(
        functions: List<KSFunctionDeclaration>,
        state: KSClassDeclaration,
        problems: MutableList<String>,
    ): List<SourcingBinding> {
        val bound = functions.map { function -> function to sourcingBinding(function, state, problems) }
        val direct = bound.filter { (_, binding) -> binding != null && !binding.sealed }.map { it.second!! }
        val expanded = bound.mapNotNull { it.second }.filter { it.sealed }.flatMap { sealed ->
            sealedEvents(sealed.declaration!!).filterNot { event -> direct.any { it.event == event.render() } }
                .map { event -> eventBinding(event, sealed.function, sealed.returnsState) }
        }
        expanded.groupBy { it.event }.filterValues { it.size > 1 }.forEach { (event, twice) ->
            problems += "the sourcing functions ${twice.joinToString { it.function }} both take $event, through " +
                "sealed types: give $event a sourcing function of its own"
        }
        return direct + expanded.distinctBy { it.event }
    }

    /** The concrete classes of the sealed [type], its sealed subclasses' included. */
    private fun sealedEvents(type: KSClassDeclaration): List<KSClassDeclaration> =
        type.getSealedSubclasses().flatMap { sub ->
            if (Modifier.SEALED in sub.modifiers) sealedEvents(sub).asSequence() else sequenceOf(sub)
        }.toList()

    private fun KSClassDeclaration.render(): String = asStarProjectedType().render()

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
        V10Annotations.problemsOfType(root, "aggregate root", state = state == root) +
            (if (state != root) V10Annotations.problemsOfType(state, "state", state = true) else emptyList()) +
            commands.distinct().filterNot { it.isFramework() }.flatMap { V10Annotations.problemsOfType(it, "command") } +
            events.distinct().filterNot { it.isFramework() }.flatMap { V10Annotations.problemsOfType(it, "event") }
        ).distinct()

    /**
     * The arguments the initial state is built with (decision D203). The recommended constructor is `()`, or one whose
     * parameters all have default values: the aggregate's identity lives in its ref and stream, so the state is built
     * without arguments. Allowed alternatives, for a domain that cannot work without the id: `(id: String)`, built
     * with `ref.id`, and `(id: String, tenantId: String)`, built with `ref.id, ref.tenantId`.
     */
    private fun stateArguments(state: KSClassDeclaration, problems: MutableList<String>): List<String> {
        val parameters = (state.primaryConstructor ?: state.getConstructors().firstOrNull())?.parameters
        val strings = parameters.orEmpty().all { it.type.resolve().declaration.qualifiedName?.asString() == STRING }
        return when {
            parameters == null -> stateConstructorProblem(state, problems)
            parameters.size == 1 && strings -> listOf("ref.id")
            parameters.size == 2 && strings -> listOf("ref.id", "ref.tenantId")
            parameters.all { it.hasDefault } -> emptyList()
            else -> stateConstructorProblem(state, problems)
        }
    }

    private fun stateConstructorProblem(state: KSClassDeclaration, problems: MutableList<String>): List<String> {
        problems += "the state ${state.simpleName.asString()} needs a constructor () (recommended: the aggregate's " +
            "id is in its ref), or one whose parameters all have default values; (id: String) and " +
            "(id: String, tenantId: String) are allowed when the domain needs the id"
        return emptyList()
    }

    @OptIn(KspExperimental::class)
    private fun commandBinding(
        function: KSFunctionDeclaration,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
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
        val commandType = if (admitted) declared.arguments.first().type?.resolve() else declared
        val command = commandType?.declaration as? KSClassDeclaration
        if (commandType == null || command == null || command.qualifiedName?.asString() == ANY) {
            val taken = if (commandType == null) "$COMMAND<*>" else commandType.render()
            problems += "$name takes $taken: a command function names the class of its command, as its first " +
                "parameter X or $COMMAND<X>"
            return null
        }
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
        val reactor = reactorReturn(function) != null
        val blocking = blocking(function, reactor, warnings)
        val producesListed = declaredProduces(function)
        val schedules = declaredSchedules(function, problems)
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
            reactor = reactor,
            creation = when {
                command.hasAnnotation(CreateAggregate::class.java.name) -> "REQUIRED"
                command.hasAnnotation(AllowCreate::class.java.name) -> "ALLOWED"
                else -> "NONE"
            },
            unaddressed = command.hasAnnotation(Unaddressed::class.java.name),
            type = command.binaryName(),
            yields = returned ?: producesListed.ifEmpty { null },
            declaredProduces = producesListed,
            schedules = schedules,
            description = Descriptions.of(command),
            blocking = blocking,
        )
    }

    /**
     * Whether [function], or one it overrides (an annotation is not inherited by an override), is `@Blocking`: the
     * kernel runs it on a virtual thread (decision D227). On a function returning a Reactor publisher it is a
     * [warnings] entry: the publisher is subscribed on the virtual thread, but a publisher should not block.
     */
    private fun blocking(function: KSFunctionDeclaration, reactor: Boolean, warnings: MutableList<String>): Boolean {
        val blocking = function.isAnnotatedOrOverrides(BLOCKING)
        if (blocking && reactor) {
            warnings += "${function.simpleName.asString()} is annotated @Blocking and returns a Reactor publisher: " +
                "it is called on a virtual thread, but a publisher should not block; make it a plain function"
        }
        return blocking
    }

    /** The events `@OnCommand(produces)` lists on [function], by qualified name (decision D120). */
    private fun declaredProduces(function: KSFunctionDeclaration): List<String> {
        val annotation = function.annotationIn(ON_COMMAND_ANNOTATION)
        val listed = annotation?.arguments?.firstOrNull { it.name?.asString() == PRODUCES }?.value as? List<*>
        return listed.orEmpty().filterIsInstance<KSType>().mapNotNull { it.declaration.qualifiedName?.asString() }
            .distinct()
    }

    /**
     * The commands `@OnCommand(schedules)` lists on [function], by full type name (decision D166); one not named
     * `context.aggregate.Command` is a problem.
     */
    private fun declaredSchedules(function: KSFunctionDeclaration, problems: MutableList<String>): List<String> {
        val annotation = function.annotationIn(ON_COMMAND_ANNOTATION)
        val listed = annotation?.arguments?.firstOrNull { it.name?.asString() == SCHEDULES }?.value as? List<*>
        val schedules = listed.orEmpty().filterIsInstance<String>().distinct()
        schedules.filterNot { COMMAND_NAME.matches(it) }.forEach {
            problems += "${function.simpleName.asString()} schedules \"$it\": @OnCommand(schedules) names each " +
                "command as context.aggregate.Command"
        }
        return schedules
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
        if (function.isAnnotatedOrOverrides(BLOCKING)) {
            problems += "sourcing function $name is annotated @Blocking: evolving does no I/O"
        }
        val eventType = function.parameters.single().type.resolve()
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
                declaration = event,
            )
        }
        if (event.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in event.modifiers) {
            problems += "sourcing function $name takes ${eventType.render()}, which is not a concrete class: the " +
                "kernel finds a sourcing function by its event's class, so take each event class, or a sealed type " +
                "whose subclasses are the events"
            return null
        }
        return eventBinding(event, name, returnsState).also {
            if (it.revision < 1) {
                problems += "event ${event.simpleName.asString()} has @Revision(${it.revision}): a revision starts at 1"
            }
        }
    }

    /** The binding of [function] evolving [event], a concrete event class. */
    @OptIn(KspExperimental::class)
    private fun eventBinding(event: KSClassDeclaration, function: String, returnsState: Boolean): SourcingBinding =
        SourcingBinding(
            event = event.render(),
            eventName = event.getAnnotationsByType(Name::class).firstOrNull()?.value,
            revision = event.getAnnotationsByType(Revision::class).firstOrNull()?.value ?: 1,
            aliases = event.getAnnotationsByType(Alias::class).flatMap { it.value.toList() }.toList(),
            function = function,
            returnsState = returnsState,
            type = event.binaryName(),
            description = Descriptions.of(event),
        )

    /**
     * An after hook (finding A4-3) takes its parameters as a command function does: first the command, as its body
     * (`Any`, or a command type `X`) or as the admitted `Command<X>`, then dependencies and the [DECIDE_SCOPE]; it may
     * take no command at all. A hook whose command parameter names a type `X` runs only for the commands of the
     * aggregate it accepts: without `@AfterCommand(include)` it includes the aggregate's commands of type `X` (none is
     * a problem), and with it each class included must be an `X`. So the generated cast never fails.
     */
    @Suppress("LongMethod")
    private fun afterBinding(
        function: KSFunctionDeclaration,
        commandClasses: List<KSClassDeclaration>,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): AfterBinding? {
        val name = function.simpleName.asString()
        val annotation = function.annotationIn(AFTER_COMMAND_ANNOTATION)
        fun types(argument: String): List<KSType> =
            (annotation?.arguments?.firstOrNull { it.name?.asString() == argument }?.value as? List<*>).orEmpty()
                .filterIsInstance<KSType>()
        val first = function.parameters.firstOrNull()?.type?.resolve()
        // A hook may take no command: its first parameter is then a dependency, or the decide scope.
        val takesCommand = first != null && first.render() != DECIDE_SCOPE &&
            (first.isCommandWrapper() || first.declaration is KSClassDeclaration && !first.isDependencyLike())
        val admitted = takesCommand && first!!.isCommandWrapper()
        val body = if (!takesCommand) null else if (admitted) first!!.arguments.first().type?.resolve() else first
        val bodyClass = body?.declaration as? KSClassDeclaration
        if (takesCommand && body != null && bodyClass == null) {
            problems += "after hook $name takes ${first!!.render()}: an after hook names the class of its command, " +
                "Any, or $COMMAND of it"
            return null
        }
        // A hook of a command type runs only for the commands it accepts.
        val accepting = bodyClass?.takeUnless { it.qualifiedName?.asString() in ANY_TYPES }
        val declaredInclude = types("include")
        val declaredExclude = types("exclude")
        // `include` and `exclude` name the aggregate's commands, or system commands (finding A4-10).
        val commandNames = commandClasses.map { it.render() }.toSet() + SYSTEM_COMMANDS
        (declaredInclude + declaredExclude).map { it.render() }.filterNot { it in commandNames }.distinct().forEach {
            problems += "after hook $name names $it in @AfterCommand(include, exclude), which is not a command of " +
                "the aggregate: no command function takes it, and it is no lifecycle or schedule command"
        }
        val include = when {
            accepting == null -> declaredInclude.map { it.render() }
            // A hook of a system command runs after it, which it must name otherwise.
            declaredInclude.isEmpty() && body!!.render() in SYSTEM_COMMANDS -> listOf(body.render())
            declaredInclude.isEmpty() -> commandClasses.distinct()
                .filter { body!!.isAssignableFrom(it.asStarProjectedType()) }
                .map { it.asStarProjectedType().render() }
                .also {
                    if (it.isEmpty()) {
                        problems += "after hook $name takes ${body!!.render()}, which no command function of the " +
                            "aggregate takes: it would never run"
                    }
                }

            else -> declaredInclude.map { it.render() }.also {
                declaredInclude.filterNot { included -> body!!.isAssignableFrom(included) }.forEach { included ->
                    problems += "after hook $name includes ${included.render()}, but takes ${body!!.render()}: " +
                        "each command it includes is one it takes"
                }
            }
        }
        val reactor = reactorReturn(function) != null
        val rest = if (takesCommand) function.parameters.drop(1) else function.parameters
        return AfterBinding(
            function = name,
            parameter = if (takesCommand) first!!.render() else null,
            admitted = admitted,
            arguments = rest.mapNotNull { argument(name, it, dependencies, problems) },
            reactor = reactor,
            include = include,
            exclude = declaredExclude.map { it.render() },
            suspending = Modifier.SUSPEND in function.modifiers,
            yields = EventYield.of(function.returnType?.resolve()),
            blocking = blocking(function, reactor, warnings),
        )
    }

    /** Whether this type is the admitted command, `Command<X>`. */
    private fun KSType.isCommandWrapper(): Boolean = declaration.qualifiedName?.asString() == COMMAND

    /**
     * Whether this type, first among an after hook's parameters, is a framework type other than the command, so not
     * the command the hook takes: such a parameter is reported as a dependency's would be.
     */
    private fun KSType.isDependencyLike(): Boolean = render().let { rendered ->
        FRAMEWORK_PACKAGES.any { rendered.startsWith(it) } && rendered !in SYSTEM_COMMANDS
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
        if (queryApi == true) {
            QueryPropertiesSource.generate(codeGenerator, stateOf(root), propertiesOf)
        }
    }

    /**
     * The functions of this class named [conventionalName] or carrying [annotation] (or overriding one that does). Not
     * only public: 9.x found package-private Java handlers too, and the generated model shares their package. A
     * private one annotated is a problem (finding A4-2), as on a subscription processor: the generated model cannot
     * call it, and leaving it out would drop the handler silently.
     */
    private fun KSClassDeclaration.functionsNamedOrAnnotated(
        conventionalName: String,
        annotation: String,
        problems: MutableList<String>,
    ): List<KSFunctionDeclaration> {
        val found = getAllFunctions()
            .filter { it.simpleName.asString() == conventionalName || it.isAnnotatedOrOverrides(annotation) }
            .toList()
        found.filter { it.isPrivate() && it.isAnnotatedOrOverrides(annotation) }.forEach {
            problems += "${it.simpleName.asString()} is private and annotated @${annotation.substringAfterLast('.')}: " +
                "the generated model calls it, so it is public or internal"
        }
        return found.filterNot { it.isPrivate() }
    }

    /**
     * Whether this function, or one it overrides, carries [annotation]. Inherited handlers bind with the type
     * arguments the root or state gives its superclass, a library's included (work package P.8: a reusable aggregate
     * such as wow-models' `Tree`); an annotation is not inherited by an override, so an override that leaves it out
     * still overrides a handler instead of silently dropping it.
     */
    private fun KSFunctionDeclaration.isAnnotatedOrOverrides(annotation: String): Boolean =
        generateSequence(this) { it.findOverridee() as? KSFunctionDeclaration }.any { it.hasAnnotation(annotation) }

    /** The JVM binary name of this class: `a.b.Outer$Inner` for a nested class. */
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
        val BLOCKING = Blocking::class.java.name
        const val PRODUCES = "produces"
        const val SCHEDULES = "schedules"
        val COMMAND_NAME = Regex("[^.\\s]+\\.[^.\\s]+\\.[^.\\s]+")

        val BOUNDED_CONTEXT = BoundedContext::class.java.name

        // A plain tree needs no Kotlin module, whose reflection KSP's class loader does not provide.
        val FRAGMENT_WRITER = JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
        val ON_COMMAND_ANNOTATION: String = OnCommand::class.java.name
        val ON_SOURCING_ANNOTATION: String = OnSourcing::class.java.name
        val AFTER_COMMAND_ANNOTATION: String = AfterCommand::class.java.name
        const val ANY = "kotlin.Any"
        val ANY_TYPES = setOf(ANY, "java.lang.Object")

        /** The system events every aggregate may commit (lifecycle and schedule), by Kotlin name. */
        val SYSTEM_EVENTS: Set<String> =
            (Lifecycle.EVENTS.values + Schedules.EVENTS.values).map { it.canonicalName }.toSet()

        /** The lifecycle commands (design 01 §6), by Kotlin name: an after hook may include them. */
        val LIFECYCLE_COMMANDS: Set<String> = Lifecycle.COMMANDS.values.map { it.canonicalName }.toSet()

        /** The system commands every aggregate decides (lifecycle and schedule), by Kotlin name. */
        val SYSTEM_COMMANDS: Set<String> = LIFECYCLE_COMMANDS + Schedules.COMMANDS.values.map { it.canonicalName }
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
