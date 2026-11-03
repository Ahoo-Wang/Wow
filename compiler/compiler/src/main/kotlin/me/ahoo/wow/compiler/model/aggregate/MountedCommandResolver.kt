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
import me.ahoo.wow.api.annotation.InternalCommand
import me.ahoo.wow.api.annotation.MountedCommand
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.compiler.model.symbol.argumentsOf
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.commandClassesOf
import me.ahoo.wow.compiler.model.symbol.declaredName
import me.ahoo.wow.compiler.model.symbol.isOf
import me.ahoo.wow.compiler.model.symbol.render

/**
 * Reads `@MountedCommand` classes and checks them against the aggregate they are mounted under: one a
 * command function of that aggregate takes, or whose name a command of it has, is a problem, and so are a blank
 * aggregate and a `transformsTo` entry that names no command class.
 */
internal object MountedCommandResolver {
    val MOUNTED_COMMAND: String = MountedCommand::class.java.name
    private val INTERNAL_COMMAND: String = InternalCommand::class.java.name
    private const val AGGREGATE = "aggregate"
    private const val TRANSFORMS_TO = "transformsTo"

    /**
     * The binding of [declaration], a class annotated `@MountedCommand`, with the problems it has on its own. One whose
     * `@MountedCommand` has an argument without a value names no aggregate, with that problem.
     */
    fun read(declaration: KSClassDeclaration): MountedBinding {
        val name = declaration.simpleName.asString()
        val problems = AnnotationSet.problemsOfType(declaration, "mounted command").toMutableList()
        val mounted = declaration.argumentsOf(MountedCommand::class, problems)
        val aggregate = mounted?.string(AGGREGATE).orEmpty()
        val transformsTo = mounted?.types(TRANSFORMS_TO).orEmpty().flatMap { type ->
            commandClassesOf(type).ifEmpty {
                problems += "mounted command $name transforms to ${type.render()}, which is no command class: " +
                    "@MountedCommand(transformsTo) names command classes"
                emptyList()
            }
        }.map { it.binaryName() }.distinct()
        if (mounted != null && aggregate.isBlank()) {
            problems += "mounted command $name names no aggregate: @MountedCommand(aggregate) is its aggregate's name"
        }
        return MountedBinding(
            command = declaration.qualifiedName!!.asString(),
            commandName = declaration.declaredName(problems),
            aggregate = aggregate,
            type = declaration.binaryName(),
            transformsTo = transformsTo,
            problems = problems,
            description = Descriptions.of(declaration, problems),
            internal = declaration.annotations.any { it.isOf(INTERNAL_COMMAND) },
        )
    }

    /** The problems of [mounted], mounted under an aggregate whose command functions are [commands]. */
    fun problems(mounted: MountedBinding, commands: List<CommandBinding>): List<String> {
        val name = mounted.commandName ?: AliasCheck.simpleName(mounted.type)
        val commandNames = commands.map { it.commandName ?: AliasCheck.simpleName(it.type) }.toSet()
        return mounted.problems + listOfNotNull(
            "mounted command $name is taken by a command function: a mounted command has no @OnCommand"
                .takeIf { commands.any { it.command == mounted.command } },
            "mounted command $name has the name of a command of the aggregate".takeIf { name in commandNames },
        )
    }
}
