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
import com.google.devtools.ksp.symbol.KSClassDeclaration
import me.ahoo.wow.api.annotation.MountedCommand
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.compiler.model.symbol.AnnotationSet
import me.ahoo.wow.compiler.model.symbol.Descriptions
import me.ahoo.wow.compiler.model.symbol.binaryName

/**
 * Reads `@MountedCommand` classes and checks them against the aggregate they are mounted under: one a
 * command function of that aggregate takes, or whose name a command of it has, is a problem, and so are a blank
 * aggregate and a `transformsTo` entry not named `context.aggregate.Command`.
 */
internal object MountedCommandResolver {
    val MOUNTED_COMMAND: String = MountedCommand::class.java.name
    private val COMMAND_NAME = Regex("[^.\\s]+\\.[^.\\s]+\\.[^.\\s]+")

    /** The binding of [declaration], a class annotated `@MountedCommand`, with the problems it has on its own. */
    @OptIn(KspExperimental::class)
    fun read(declaration: KSClassDeclaration): MountedBinding {
        val annotation = declaration.getAnnotationsByType(MountedCommand::class).first()
        val name = declaration.simpleName.asString()
        val transformsTo = annotation.transformsTo.toList()
        val problems = AnnotationSet.problemsOfType(declaration, "mounted command") +
            transformsTo.filterNot { COMMAND_NAME.matches(it) }.map {
                "mounted command $name transforms to \"$it\": @MountedCommand(transformsTo) names each command as " +
                    "context.aggregate.Command"
            } + listOfNotNull(
                "mounted command $name names no aggregate: @MountedCommand(aggregate) is its aggregate's name"
                    .takeIf { annotation.aggregate.isBlank() },
            )
        return MountedBinding(
            command = declaration.qualifiedName!!.asString(),
            commandName = declaration.getAnnotationsByType(Name::class).firstOrNull()?.value,
            aggregate = annotation.aggregate,
            type = declaration.binaryName(),
            transformsTo = transformsTo,
            problems = problems,
            description = Descriptions.of(declaration),
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
