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

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.compiler.model.symbol.ModelNames.AFTER_COMMAND_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.ANY_TYPES
import me.ahoo.wow.compiler.model.symbol.ModelNames.COMMAND
import me.ahoo.wow.compiler.model.symbol.ModelNames.DECIDE_SCOPE
import me.ahoo.wow.compiler.model.symbol.ModelNames.FRAMEWORK_PACKAGES
import me.ahoo.wow.compiler.model.symbol.ModelNames.SYSTEM_COMMANDS
import me.ahoo.wow.compiler.model.symbol.annotationIn
import me.ahoo.wow.compiler.model.symbol.blocking
import me.ahoo.wow.compiler.model.symbol.reactorReturn
import me.ahoo.wow.compiler.model.symbol.render

/**
 * The after hooks of an aggregate root. A hook takes its parameters as a command function does:
 * first the command, as its body (`Any`, or a command type `X`) or as the admitted `Command<X>`, then
 * dependencies and the [DECIDE_SCOPE]; it may take no command at all. A hook whose command parameter names a type `X`
 * runs only for the commands of the aggregate it accepts: without `@AfterCommand(include)` it includes the aggregate's
 * commands of type `X` (none is a problem), or the system command `X`, and with it each class included must be an
 * `X`. So the generated cast never fails. `include` and `exclude` name the aggregate's commands or system commands.
 */
internal object AfterHookResolver {
    /** The command parameter of a hook: its type as declared, whether it is the admitted command, and its body. */
    private class CommandParameter(val declared: KSType, val admitted: Boolean, val body: KSType?)

    /**
     * The binding of the after hook [function] of an aggregate whose command functions take [commandClasses]; its
     * parameters after the command are bound by [argument]. Null, with [problems], when it cannot be bound.
     */
    fun afterBinding(
        function: KSFunctionDeclaration,
        commandClasses: List<KSClassDeclaration>,
        problems: MutableList<String>,
        warnings: MutableList<String>,
        argument: (String, KSValueParameter) -> String?,
    ): AfterBinding? {
        val name = function.simpleName.asString()
        val parameter = commandParameter(function)
        val body = parameter?.body
        if (parameter != null && body != null && body.declaration !is KSClassDeclaration) {
            problems += "after hook $name takes ${parameter.declared.render()}: an after hook names the class of its " +
                "command, Any, or $COMMAND of it"
            return null
        }
        val annotation = function.annotationIn(AFTER_COMMAND_ANNOTATION)
        fun types(argument: String): List<KSType> =
            (annotation?.arguments?.firstOrNull { it.name?.asString() == argument }?.value as? List<*>).orEmpty()
                .filterIsInstance<KSType>()
        val declaredInclude = types("include")
        val declaredExclude = types("exclude")
        problems += namingProblems(name, declaredInclude + declaredExclude, commandClasses)
        // A hook of a command type runs only for the commands it accepts.
        val accepting = body?.takeUnless { it.declaration.qualifiedName?.asString() in ANY_TYPES }
        val include = if (accepting == null) {
            declaredInclude.map { it.render() }
        } else {
            includeOf(name, accepting, declaredInclude, commandClasses, problems)
        }
        val reactor = reactorReturn(function) != null
        val rest = if (parameter != null) function.parameters.drop(1) else function.parameters
        return AfterBinding(
            function = name,
            parameter = parameter?.declared?.render(),
            admitted = parameter?.admitted == true,
            arguments = rest.mapNotNull { argument(name, it) },
            reactor = reactor,
            include = include,
            exclude = declaredExclude.map { it.render() },
            suspending = Modifier.SUSPEND in function.modifiers,
            yields = EventYield.of(function.returnType?.resolve()),
            blocking = blocking(function, reactor, warnings),
        )
    }

    /**
     * The command [function] takes first; null when it takes none, its first parameter then a dependency or the
     * decide scope. The body of `Command<*>` is null.
     */
    private fun commandParameter(function: KSFunctionDeclaration): CommandParameter? {
        val first = function.parameters.firstOrNull()?.type?.resolve() ?: return null
        if (first.render() == DECIDE_SCOPE) {
            return null
        }
        if (first.isCommandWrapper()) {
            return CommandParameter(first, admitted = true, body = first.arguments.first().type?.resolve())
        }
        return if (first.declaration is KSClassDeclaration && !first.isDependencyLike()) {
            CommandParameter(first, admitted = false, body = first)
        } else {
            null
        }
    }

    /** The classes of [named] that are neither commands of the aggregate nor system commands. */
    private fun namingProblems(
        name: String,
        named: List<KSType>,
        commandClasses: List<KSClassDeclaration>
    ): List<String> {
        val commandNames = commandClasses.map { it.asStarProjectedType().render() }.toSet() + SYSTEM_COMMANDS
        return named.map { it.render() }.filterNot { it in commandNames }.distinct().map {
            "after hook $name names $it in @AfterCommand(include, exclude), which is not a command of the aggregate: " +
                "no command function takes it, and it is no lifecycle or schedule command"
        }
    }

    /** The commands a hook of the command type [accepting] runs after, as [declaredInclude] names them or not. */
    private fun includeOf(
        name: String,
        accepting: KSType,
        declaredInclude: List<KSType>,
        commandClasses: List<KSClassDeclaration>,
        problems: MutableList<String>,
    ): List<String> {
        if (declaredInclude.isNotEmpty()) {
            declaredInclude.filterNot { accepting.isAssignableFrom(it) }.forEach { included ->
                problems += "after hook $name includes ${included.render()}, but takes ${accepting.render()}: " +
                    "each command it includes is one it takes"
            }
            return declaredInclude.map { it.render() }
        }
        // A hook of a system command runs after it, which it must name otherwise.
        if (accepting.render() in SYSTEM_COMMANDS) {
            return listOf(accepting.render())
        }
        val included = commandClasses.distinct().map { it.asStarProjectedType() }
            .filter { accepting.isAssignableFrom(it) }.map { it.render() }
        if (included.isEmpty()) {
            problems += "after hook $name takes ${accepting.render()}, which no command function of the aggregate " +
                "takes: it would never run"
        }
        return included
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
}
