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

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Sends
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.compiler.model.symbol.annotationIn
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.classOf
import me.ahoo.wow.compiler.model.symbol.commandClassesOf
import me.ahoo.wow.compiler.model.symbol.concreteClasses

/**
 * What a saga's handler sends ([SendsBinding]), of any of its kinds (`@OnEvent`, `@OnRejected`, `@OnCommitted`,
 * `@OnSchedule`): the classes its `@Sends` declares, and for what it derives without `@Sends(derives)`, those its
 * return type names. Each class names a command class, or a sealed type that stands for its concrete subclasses.
 */
internal object SendsResolver {
    val SENDS: String = Sends::class.java.name
    private const val DERIVES = "derives"
    private const val SCHEDULES = "schedules"
    private const val CALLS = "calls"
    private const val ANY = "kotlin.Any"
    private const val UNIT = "kotlin.Unit"
    private val REQUEST = CommandRequest::class.java.name

    /**
     * What [function], the handler described as [where], sends. A handler of a saga ([saga]) sends what its `@Sends`
     * declares and, without `@Sends(derives)`, derives what its return type [returned] names (or what the Reactor
     * publisher it returns, when it is [reactor], emits). A handler of another kind, [label], sends nothing. The
     * problems go to [problems]:
     *
     * - `@Sends` on a projection's or a reactor's handler;
     * - a class `@Sends` names that is no command class;
     * - `@Sends(derives)` on a handler that returns `Unit`;
     * - a command its return type names that its `@Sends(derives)` does not list;
     * - a saga's handler that returns command requests of no named class (`CommandRequest<*>`, `CommandRequest<Any>`)
     *   and declares no `@Sends(derives)`: the Manifest could not list what it derives, and the runtime would refuse
     *   each command it derives.
     */
    @Suppress("LongParameterList")
    fun resolve(
        function: KSFunctionDeclaration,
        where: String,
        saga: Boolean,
        label: String?,
        returned: KSType?,
        reactor: Boolean,
        problems: MutableList<String>,
    ): SendsBinding {
        val sends = function.annotationIn(SENDS)
        if (!saga) {
            if (sends != null && label != null) {
                problems += "$where declares @Sends, but a $label handler sends no commands: only a @Saga's do"
            }
            return SendsBinding()
        }
        val declared = commandClasses(where, DERIVES, sends.classes(DERIVES), problems)
        val schedules = commandClasses(where, SCHEDULES, sends.classes(SCHEDULES), problems)
        val calls = commandClasses(where, CALLS, sends.classes(CALLS), problems)
        val derives = when (val commands = returned?.let { returnedCommands(it, reactor) }) {
            null -> declared
            Returned.Nothing -> {
                if (declared.isNotEmpty()) {
                    problems += "$where returns Unit, so it derives no commands, but declares @Sends(derives)"
                }
                emptyList()
            }

            Returned.Unnamed -> {
                if (declared.isEmpty()) {
                    problems += "$where returns ${returned.render()}, which names no command class: declare what " +
                        "it derives with @Sends(derives = [...]), the command classes or a sealed type of them, or " +
                        "return CommandRequest<X> of the class it derives"
                }
                declared
            }

            is Returned.Named -> {
                if (declared.isEmpty()) {
                    commands.classes
                } else {
                    val listed = declared.map { it.binaryName() }.toSet()
                    commands.classes.filterNot { it.binaryName() in listed }.forEach {
                        problems += "$where returns ${it.qualifiedName!!.asString()}, which its @Sends(derives) " +
                            "does not list"
                    }
                    declared
                }
            }
        }
        return SendsBinding(derives.map(::commandClass), schedules.map(::commandClass), calls.map(::commandClass))
    }

    /** What a saga's handler that returns [returned] (a [reactor] publisher of it) derives, as the type says. */
    private sealed interface Returned {
        /** `Unit`: nothing. */
        data object Nothing : Returned

        /** Command requests of no named class: `CommandRequest<*>`, `CommandRequest<Any>`. */
        data object Unnamed : Returned

        /** The command classes `X` of a `CommandRequest<X>` (the concrete subclasses of a sealed `X`). */
        data class Named(val classes: List<KSClassDeclaration>) : Returned
    }

    /**
     * The commands [returned] (or what the Reactor publisher it returns, when it is [reactor], emits) names: `X` of a
     * `CommandRequest<X>` or of an `Iterable`, a `Sequence` or a `Flow` of them, the concrete subclasses of `X` when it
     * is sealed; nothing for `Unit`. Null for a type that is no command request: its own problem.
     */
    private fun returnedCommands(returned: KSType, reactor: Boolean): Returned? {
        if (!reactor && returned.qualifiedName() == UNIT) {
            return Returned.Nothing
        }
        val emitted = if (reactor) returned.arguments.firstOrNull()?.type?.resolve() ?: return null else returned
        val request = if (emitted.qualifiedName() == REQUEST) {
            emitted
        } else {
            emitted.arguments.firstOrNull()?.type?.resolve()?.takeIf { it.qualifiedName() == REQUEST } ?: return null
        }
        val declaration = request.arguments.firstOrNull()?.type?.resolve()?.declaration?.classOf()
            ?.takeUnless { it.qualifiedName?.asString() == ANY }
            ?: return Returned.Unnamed
        if (Modifier.SEALED in declaration.modifiers) {
            return concreteClasses(declaration).ifEmpty { null }?.let(Returned::Named) ?: Returned.Unnamed
        }
        return Returned.Named(listOf(declaration))
    }

    /**
     * The command classes [types] name, as `@Sends(member)` declares them: a class names itself, a sealed type its
     * concrete subclasses, transitively. `Any`, and an interface or an abstract class that is not sealed, name no
     * command class: a problem.
     */
    private fun commandClasses(
        where: String,
        member: String,
        types: List<KSType>,
        problems: MutableList<String>,
    ): List<KSClassDeclaration> = types.flatMap { type ->
        val classes = commandClassesOf(type)
        if (classes.isEmpty()) {
            problems += "$where sends ${type.render()} in @Sends($member), which is no command class: @Sends names " +
                "command classes, or a sealed type whose subclasses they are"
        }
        classes
    }.distinctBy { it.binaryName() }

    private fun commandClass(declaration: KSClassDeclaration): CommandClass =
        CommandClass(declaration.binaryName(), declaration.qualifiedName!!.asString())

    /** The class array argument [name] of this annotation, empty without the annotation or the argument. */
    private fun KSAnnotation?.classes(name: String): List<KSType> =
        (this?.arguments?.firstOrNull { it.name?.asString() == name }?.value as? List<*>).orEmpty()
            .filterIsInstance<KSType>()

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
}
