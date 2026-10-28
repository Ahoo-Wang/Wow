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
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.compiler.model.aggregate.AggregateAnalyzer
import me.ahoo.wow.compiler.model.aggregate.AggregateBinding
import me.ahoo.wow.compiler.model.aggregate.AliasCheck
import me.ahoo.wow.compiler.model.aggregate.CommandBinding
import me.ahoo.wow.compiler.model.aggregate.ErrorCodeBinding
import me.ahoo.wow.compiler.model.aggregate.ErrorCodeResolver
import me.ahoo.wow.compiler.model.aggregate.MountedBinding
import me.ahoo.wow.compiler.model.aggregate.MountedCommandResolver
import me.ahoo.wow.compiler.model.aggregate.RootStateResolver
import me.ahoo.wow.compiler.model.aggregate.UpgradeFunction
import me.ahoo.wow.compiler.model.aggregate.UpgradeResolver
import me.ahoo.wow.compiler.model.manifest.ManifestFragmentBuilder
import me.ahoo.wow.compiler.model.route.ServiceRouteBinding
import me.ahoo.wow.compiler.model.route.ServiceRouteResolver
import me.ahoo.wow.compiler.model.source.ModelProviderSource
import me.ahoo.wow.compiler.model.source.ModelSource
import me.ahoo.wow.compiler.model.source.QueryPropertiesSource
import me.ahoo.wow.compiler.model.source.SubscriptionSource
import me.ahoo.wow.compiler.model.subscription.ModuleEvent
import me.ahoo.wow.compiler.model.subscription.SubscriptionBinding
import me.ahoo.wow.compiler.model.subscription.SubscriptionResolver
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.ModelNames.REACTOR_TYPES
import me.ahoo.wow.compiler.model.symbol.render
import me.ahoo.wow.manifest.ContextName
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import tools.jackson.databind.json.JsonMapper

/**
 * Compiles annotated aggregate roots into aggregate models: for each `@AggregateRoot` it
 * generates `fun <root>Model(context: String): AggregateModel<State>`, built with the kernel DSL, so the annotated
 * and the DSL style are one model and the runtime never reflects.
 *
 * Command and event types are named `context.aggregate.Name`: the class's simple name, or its `@Name`.
 *
 * It recognizes only the annotation set ([AnnotationSet]): any other annotation of
 * `me.ahoo.wow.api.annotation` on the root, its state, its commands or its events is a problem that points to the
 * migration skill, and so is an identity annotation on a command's field. An event's `@Name`,
 * `@Revision` and `@Alias` and a command's `@Name` and `@Alias` flow into the model ([AliasCheck] checks the aliases).
 * The `@Upgrade` functions of the module ([UpgradeResolver]) are chained per event: an event at revision
 * `n` needs exactly one from each of the revisions 1 to `n - 1`, none missing (no upgrade at all included), and an
 * upgrade of a class that is not an event of an aggregate of the module is an error. The generated factory's last
 * parameter, `configure`, runs on the model builder after the bindings, as a general extension point.
 *
 * Subscription processors (`@Projection`, `@Reactor`, `@Saga` with `@OnEvent` handlers) are checked by
 * [SubscriptionResolver] and bound by [SubscriptionSource]: each gets
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
 * `wow.model=report` the processor generates nothing and writes `META-INF/wow/model-report.json`, from which the
 * model's coverage is measured. Without the option it does nothing.
 *
 * The context is a parameter, which defaults to the module's when the compiler knows it (`wow.context`, else the
 * module's one `@BoundedContext`); the model then also gets an object of its names, `<Root>Model.TYPE`.
 *
 * With `wow.model=generate`, a model without problems also gets its Manifest fragment
 * ([ManifestFragmentBuilder]): `META-INF/wow/manifest/<module>.json` holds the module's aggregates and subscriptions
 * under its bounded context, with schemas left as references for the build to fill in. The KSP option
 * `wow.context` names the context (else the module's one `@BoundedContext` does; with neither, no fragment is
 * written), and `wow.manifest.module` names the file (else the context does). Beside the fragment goes the module's
 * binding index ([ModelProviderSource]): a `ModelProvider` that builds every model of the module with
 * the factories' parameters from the host's `Dependencies`, registered in `META-INF/services` for `ServiceLoader`.
 *
 * `@AggregateRoot(lifecycle = [...])` narrows the lifecycle commands a model handles: the generated
 * model calls `lifecycle(...)` with them before `configure`, and the fragment lists them.
 *
 * `@Name` on a dependency parameter qualifies it: the binding index asks for the dependency of its
 * type by that name, and two parameters of one type with different qualifiers are two dependencies.
 * `@OnCommand(produces = [...])` declares the events of a function whose return type does not say,
 * such as a `Decision`; a conventionally named `onCommand` needs the annotation to declare them. The module's
 * `@ErrorCode` constants ([ErrorCodeResolver]) are the context's `errors` in the fragment.
 *
 * A `@MountedCommand` of the module is mounted under the aggregate of the module it names: the
 * generated model mounts it (`mount<C>()`), and the fragment lists it among the aggregate's commands as mounted, with
 * its `transformsTo`. One that names no aggregate of the module, that a command function of its aggregate also takes,
 * or whose name its aggregate's commands already use, fails the compilation, and so does a `transformsTo` entry not
 * named `context.aggregate.Command`.
 *
 * `@OnEvent(aggregates = ["ctx.agg"])` names the aggregates a handler reads: of the module's context,
 * they must be aggregates of the module whose state the handler takes; of another context, the handler's binding
 * names them, and the runtime reads their published streams for it. A handler of a nullable state,
 * `StateEvent<S?>` or `StateEvent<*>`, says so in its binding and in its Manifest source. A saga's
 * `@OnRejected` handlers and `@OnCommitted` handlers are in the fragment too, and
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

    /** The module's `@ServiceRoute` functions without problems. */
    private val routes = mutableListOf<ServiceRouteBinding>()

    /** Reads each aggregate root into its binding, claiming the upgrade functions it names. */
    private val analyzer = AggregateAnalyzer(upgrades, claimedUpgrades, ::mountedOf, ::context)

    /** The module's `@MountedCommand`s, and those of them an aggregate of the module has claimed. */
    private val mountedCommands = mutableListOf<Pair<MountedBinding, KSClassDeclaration>>()
    private val claimedMounted = mutableSetOf<String>()
    private val reportedMounted = mutableSetOf<String>()

    /** The module's `@ErrorCode` codes without problems, by property. */
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
     * context name ([ContextName]), the annotation's or the KSP option's, fails the compilation.
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
            root to analyzer.analyze(root)
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
        return deferred + deferredErrorCodes + deferredMounted + processSubscriptions(resolver) +
            processRoutes(resolver)
    }

    /**
     * Collects the module's `@ServiceRoute` functions; in generate mode, their problems fail the compilation, and so
     * does a name two of them take.
     */
    private fun processRoutes(resolver: Resolver): List<KSAnnotated> {
        val functions = resolver.getSymbolsWithAnnotation(ServiceRouteResolver.SERVICE_ROUTE)
            .filterIsInstance<KSFunctionDeclaration>().toList()
        functions.filter { it.validate() }.forEach { function ->
            function.containingFile?.let { sources += it }
            function.parentDeclaration?.packageName?.asString()?.let { packages += it }
            val binding = ServiceRouteResolver.read(function)
            val problems = binding.problems + listOfNotNull(
                "service route ${binding.name} is declared twice".takeIf { routes.any { it.name == binding.name } },
            )
            if (problems.isEmpty()) {
                routes += binding
            } else if (mode == Mode.GENERATE) {
                problems.forEach { logger.error(it, function) }
            }
        }
        return functions.filterNot { it.validate() }
    }

    /** Collects the module's `@MountedCommand` classes; the ones not yet valid are deferred. */
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
     * Collects the module's `@ErrorCode` constants; in generate mode, their problems fail the
     * compilation. A code two of them hold, once qualified ([errors]), fails it too.
     */
    private fun processErrorCodes(resolver: Resolver): List<KSAnnotated> {
        val properties = resolver.getSymbolsWithAnnotation(ErrorCodeResolver.ERROR_CODE)
            .filterIsInstance<KSPropertyDeclaration>().toList()
        val stringType = resolver.builtIns.stringType
        properties.filter { it.validate() }.forEach { property ->
            sources += listOfNotNull(property.containingFile)
            val binding = ErrorCodeResolver.resolve(property, stringType)
            // A full code is known now; a local one once its aggregate is ([errors]).
            val code = binding.error?.code?.takeIf(ErrorCodeResolver::isFull)
            val previous = code?.let { full -> errorCodes.values.firstOrNull { it.error?.code == full } }
            val problems = binding.problems + listOfNotNull(
                previous?.let { "error code $code is declared by ${it.property} and ${binding.property}" },
            )
            if (problems.isEmpty() && binding.error != null) {
                errorCodes[binding.property] = binding
            } else if (mode == Mode.GENERATE) {
                problems.forEach { logger.error(it, property) }
            }
        }
        return properties.filterNot { it.validate() }
    }

    /**
     * The module's error codes as the Manifest names them, each with its property: a local name
     * qualified with its aggregate in [context].
     */
    private fun errors(context: String): List<Pair<String, ErrorManifest>> {
        val aggregates = bindings.associate { it.root to it.aggregateName }
        return errorCodes.values.mapNotNull { binding ->
            binding.qualified(context, aggregates)?.let {
                binding.property to it
            }
        }
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
            // A processor base class is read through the processors extending it.
            .filterNot { SubscriptionResolver.isProcessorBase(it) }
        val processors = (
            (SubscriptionResolver.KIND_ANNOTATIONS + SubscriptionResolver.REMOVED_KIND_ANNOTATIONS).asSequence()
                .flatMap { resolver.getSymbolsWithAnnotation(it) }
                .filterIsInstance<KSClassDeclaration>() + handlerClasses
            ).distinct().toList()
        val deferred = processors.filterNot { it.validate() }
        // The events of the module's aggregates, which a handler of each event of a type takes.
        val events = bindings.flatMap { aggregate ->
            aggregate.sourcing.mapNotNull { sourcing ->
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(sourcing.event))?.let {
                    ModuleEvent(it.asStarProjectedType(), sourcing.type, aggregate.state)
                }
            }
        }
        processors.filter { it.validate() }.forEach { processor ->
            processor.containingFile?.let { sources += it }
            packages += processor.packageName.asString()
            val binding = SubscriptionResolver.resolve(processor, REACTOR_TYPES, events)
            subscriptions += binding
            if (mode == Mode.GENERATE) {
                binding.warnings.forEach { logger.warn("${binding.processor}: $it", processor) }
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
        val collected = listOf(bindings, subscriptions, errorCodes.values, routes)
        if (collected.all { it.isEmpty() }) {
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
        val errors = errors(context)
        val problems = ManifestFragmentBuilder.problems(context, bindings, subscriptions) +
            errors.groupBy({ it.second.code }, { it.first }).filterValues { it.size > 1 }
                .map { (code, properties) -> "error code $code is declared by ${properties.joinToString(" and ")}" }
        if (problems.isNotEmpty()) {
            problems.forEach { logger.error(it) }
            return
        }
        val module = manifest.module ?: context
        val description = boundedContexts[context]?.ifBlank { null }
        val fragment = ManifestFragmentBuilder.build(
            context,
            description,
            bindings,
            subscriptions,
            errors.map { it.second },
            routes,
        )
        codeGenerator.createNewFileByPath(
            Dependencies(aggregating = true, sources = sources.toTypedArray()),
            "${Manifest.FRAGMENT_DIRECTORY}/$module",
            "json",
        ).bufferedWriter().use { it.write(FRAGMENT_WRITER.writeValueAsString(ManifestTree.write(fragment))) }
        writeModelProvider(context, "${Manifest.FRAGMENT_DIRECTORY}/$module.json", module)
    }

    /**
     * Writes the module's binding index ([ModelProviderSource]) beside its fragment, with the handlers
     * of its subscription processors, and registers it for `ServiceLoader` in
     * `META-INF/services/me.ahoo.wow.kernel.ModelProvider`.
     */
    private fun writeModelProvider(context: String, fragment: String, module: String) {
        val packageName = ModelProviderSource.commonPackage(packages)
        val className = ModelProviderSource.className(module)
        val dependencies = Dependencies(aggregating = true, sources = sources.toTypedArray())
        codeGenerator.createNewFile(dependencies, packageName, className).bufferedWriter().use {
            it.write(
                ModelProviderSource.render(packageName, className, context, fragment, bindings, subscriptions, routes),
            )
        }
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/${ModelProviderSource.MODEL_PROVIDER}",
            "",
        ).bufferedWriter().use {
            it.write((if (packageName.isEmpty()) className else "$packageName.$className") + "\n")
        }
    }

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
        ).bufferedWriter().use { it.write(ModelSource.render(binding, context())) }
        if (queryApi == true) {
            QueryPropertiesSource.generate(codeGenerator, RootStateResolver.stateOf(root), propertiesOf)
        }
    }

    private companion object {
        val AGGREGATE_ROOT = AggregateRoot::class.java.name

        val BOUNDED_CONTEXT = BoundedContext::class.java.name

        // A plain tree needs no Kotlin module, whose reflection KSP's class loader does not provide.
        val FRAGMENT_WRITER = JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
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
