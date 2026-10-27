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
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import me.ahoo.wow.api.annotation.Alias
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.Revision
import me.ahoo.wow.api.annotation.Unaddressed
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.compiler.model.symbol.ModelNames.AFTER_COMMAND_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.ANY
import me.ahoo.wow.compiler.model.symbol.ModelNames.COMMAND
import me.ahoo.wow.compiler.model.symbol.ModelNames.DECIDE_SCOPE
import me.ahoo.wow.compiler.model.symbol.ModelNames.FRAMEWORK_PACKAGES
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_COMMAND_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_SOURCING
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_SOURCING_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.STRING
import me.ahoo.wow.compiler.model.symbol.annotationIn
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.compiler.model.symbol.reactorReturn
import me.ahoo.wow.compiler.model.symbol.render
import me.ahoo.wow.compiler.model.symbol.toName

/**
 * Reads an aggregate root into its [AggregateBinding] for the model processor: its state, its command, sourcing and
 * after-command functions with their dependencies, produced events, schedules and error codes, and the problems and
 * warnings found, each naming the declaration. The upgrade functions of the module are known before (`upgrades`); the
 * ones a root names are claimed (`claimedUpgrades`), so the processor reports those no root claims. The processor
 * also gives the mounted commands of an aggregate (`mountedOf`) and the bounded context the module declares
 * (`context`).
 */
internal class AggregateAnalyzer(
    private val upgrades: List<UpgradeFunction>,
    private val claimedUpgrades: MutableSet<UpgradeFunction>,
    private val mountedOf: (String, List<CommandBinding>, MutableList<String>) -> List<MountedBinding>,
    private val context: () -> String?,
) {
    @OptIn(KspExperimental::class)
    fun analyze(root: KSClassDeclaration): AggregateBinding {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rootName = root.qualifiedName!!.asString()
        val state = RootStateResolver.stateOf(root, problems)
        val rootIsState = state == root
        val stateArguments = stateArguments(state, problems)

        val dependencies = linkedMapOf<String, Dependency>()
        val (commands, sourcing, afters) = handlersOf(root, state, dependencies, problems, warnings)
        if (!rootIsState) {
            problems += RootStateResolver.misplacedProblems(root, state)
        }
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
            internal = with(RootStateResolver) { root.isInternal() || state.isInternal() },
        )
    }

    /** The handlers of an aggregate, as [handlersOf] finds them. */
    private data class Handlers(
        val commands: List<CommandBinding>,
        val sourcing: List<SourcingBinding>,
        val afters: List<AfterBinding>,
    )

    /**
     * The command functions and after hooks of [root] and the sourcing functions of [state], their dependencies in
     * [dependencies]; the problems of each, of the events they produce and of the annotations on their types go to
     * [problems].
     */
    private fun handlersOf(
        root: KSClassDeclaration,
        state: KSClassDeclaration,
        dependencies: MutableMap<String, Dependency>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ): Handlers {
        val commandFunctions = root.functionsNamedOrAnnotated(ON_COMMAND, ON_COMMAND_ANNOTATION, problems)
        val commands = commandFunctions.mapNotNull { function ->
            commandBinding(function, dependencies, problems, warnings)
        }
        commands.groupBy { it.command }.filterValues { it.size > 1 }.keys.forEach {
            problems += "two command functions handle $it"
        }
        val sourcingFunctions = state.functionsNamedOrAnnotated(ON_SOURCING, ON_SOURCING_ANNOTATION, problems)
        val sourcing = SourcingResolver.sourcingOf(sourcingFunctions, state, problems)
        val commandTypes = commandFunctions.mapNotNull { firstParameterClass(it, unwrapCommand = true) }
        val afters = root.functionsNamedOrAnnotated(AFTER_COMMAND, AFTER_COMMAND_ANNOTATION, problems)
            .mapNotNull { function ->
                AfterHookResolver.afterBinding(function, commandTypes, problems, warnings) { name, parameter ->
                    argument(name, parameter, dependencies, problems)
                }
            }
        problems += SourcingResolver.producesProblems(commands, sourcing)
        problems += SourcingResolver.yieldProblems(commands, afters, sourcing)
        val eventTypes = sourcingFunctions.mapNotNull { firstParameterClass(it, unwrapCommand = false) }
        problems += annotationProblems(root, state, commandTypes, eventTypes)
        return Handlers(commands, sourcing, afters)
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
     * The upgrades of the aggregate's events, from the module's `@Upgrade` functions; the problems of
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

    /** What the processor does not recognize on the model's types: the root, its state, its commands and its events. */
    private fun annotationProblems(
        root: KSClassDeclaration,
        state: KSClassDeclaration,
        commands: List<KSClassDeclaration>,
        events: List<KSClassDeclaration>,
    ): List<String> = (
        AnnotationSet.problemsOfType(root, "aggregate root", state = state == root) +
            (if (state != root) AnnotationSet.problemsOfType(state, "state", state = true) else emptyList()) +
            commands.distinct().filterNot { it.isFramework() }.flatMap { AnnotationSet.problemsOfType(it, "command") } +
            events.distinct().filterNot { it.isFramework() }.flatMap { AnnotationSet.problemsOfType(it, "event") }
        ).distinct()

    /**
     * The arguments the initial state is built with. The recommended constructor is `()`, or one whose
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
        val commandType = commandTypeOf(name, declared, admitted, problems) ?: return null
        val command = commandType.declaration as KSClassDeclaration
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
     * The command type a command function taking [declared] first decides: [declared], or its argument when it is
     * the [admitted] `Command<X>`. Null, with a problem, when it names no command class: `Command<*>`, `Any`, a type
     * parameter, or a framework type.
     */
    private fun commandTypeOf(
        name: String,
        declared: KSType,
        admitted: Boolean,
        problems: MutableList<String>,
    ): KSType? {
        val commandType = if (admitted) declared.arguments.first().type?.resolve() else declared
        val command = commandType?.declaration as? KSClassDeclaration
        if (commandType == null || command == null || command.qualifiedName?.asString() == ANY) {
            val taken = if (commandType == null) "$COMMAND<*>" else commandType.render()
            problems += "$name takes $taken: a command function names the class of its command, as its first " +
                "parameter X or $COMMAND<X>"
            return null
        }
        if (FRAMEWORK_PACKAGES.any { commandType.render().startsWith(it) }) {
            problems += "$name takes ${commandType.render()}: a decide function takes the command body, " +
                "or $COMMAND of it"
            return null
        }
        return commandType
    }

    /** The events `@OnCommand(produces)` lists on [function], by qualified name. */
    private fun declaredProduces(function: KSFunctionDeclaration): List<String> {
        val annotation = function.annotationIn(ON_COMMAND_ANNOTATION)
        val listed = annotation?.arguments?.firstOrNull { it.name?.asString() == PRODUCES }?.value as? List<*>
        return listed.orEmpty().filterIsInstance<KSType>().mapNotNull { it.declaration.qualifiedName?.asString() }
            .distinct()
    }

    /**
     * The commands `@OnCommand(schedules)` lists on [function], by full type name; one not named
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
     * takes, qualified by the parameter's `@Name`. One type with one qualifier, or none, is one
     * dependency, however many functions take it. A framework type of 9.x has no Wow 10 counterpart to pass.
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
            problems += "$function takes $rendered, a 9.x framework type with no Wow 10 counterpart"
            return null
        }
        if (parameter.hasDefault) {
            problems += "$where has a default value: the generated model passes every parameter after the command, " +
                "a dependency from the host, so it has none"
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

    /**
     * The functions of this class named [conventionalName] or carrying [annotation] (or overriding one that does). Not
     * only public: 9.x found package-private Java handlers too, and the generated model shares their package. A
     * private one annotated is a problem, as on a subscription processor: the generated model cannot
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
        // A Kotlin protected function is out of the generated model's reach; a Java one is in its package (A4-11).
        val protected = found.filter {
            Modifier.PROTECTED in it.modifiers && it.origin != Origin.JAVA && it.origin != Origin.JAVA_LIB
        }
        protected.forEach {
            problems += "${it.simpleName.asString()} is protected: the generated model calls it from outside the " +
                "class, so it is public or internal"
        }
        return found.filterNot { it.isPrivate() || it in protected }
    }

    /**
     * Whether this function, or one it overrides, carries [annotation]. Inherited handlers bind with the type
     * arguments the root or state gives its superclass, a library's included (a reusable aggregate
     * such as wow-models' `Tree`); an annotation is not inherited by an override, so an override that leaves it out
     * still overrides a handler instead of silently dropping it.
     */
    private fun KSFunctionDeclaration.isAnnotatedOrOverrides(annotation: String): Boolean =
        generateSequence(this) { it.findOverridee() as? KSFunctionDeclaration }.any { it.hasAnnotation(annotation) }

    /** The JVM binary name of this class: `a.b.Outer$Inner` for a nested class. */
    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

    private companion object {
        const val PRODUCES = "produces"
        const val SCHEDULES = "schedules"
        val COMMAND_NAME = Regex("[^.\\s]+\\.[^.\\s]+\\.[^.\\s]+")
        const val ON_COMMAND = "onCommand"
        const val AFTER_COMMAND = "afterCommand"
    }
}
