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

package me.ahoo.wow.openapi.route

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.DEFAULT_COMMAND_PATH
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.command.annotation.toCommandMetadata
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.infra.reflection.ClassMetadata
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.serialization.toJsonString
import org.slf4j.LoggerFactory
import org.springframework.web.util.UriTemplate
import java.lang.reflect.Field

object CommandRouteMetadataParser : CacheableMetadataParser<Class<*>, CommandRouteMetadata<*>>() {
    override fun parseToMetadata(type: Class<*>): CommandRouteMetadata<*> {
        val visitor = CommandRouteMetadataVisitor(type)
        ClassMetadata.visit(type, visitor)
        return visitor.toMetadata()
    }
}

internal class CommandRouteMetadataVisitor<C>(private val commandType: Class<C>) :
    ClassVisitor {
    companion object {
        private val log = LoggerFactory.getLogger(CommandRouteMetadataVisitor::class.java)
    }

    private var pathVariables: MutableSet<VariableMetadata> = mutableSetOf()
    private var headerVariables: MutableSet<VariableMetadata> = mutableSetOf()

    private fun Field.toVariableMetadata(name: String, nestedPath: Array<String>, required: Boolean): VariableMetadata {
        val fieldName = scan<JsonProperty>()?.value
            .orEmpty()
            .ifBlank {
                this.name
            }
        val variableName = name.ifBlank { fieldName }
        val fieldPath = buildList {
            add(fieldName)
            addAll(nestedPath)
        }
        return VariableMetadata(
            fieldPath = fieldPath,
            variableName = variableName,
            required = required,
        )
    }

    override fun visitField(field: Field) {
        field.scan<CommandRoute.PathVariable>()?.let {
            val variableMetadata = field.toVariableMetadata(it.name, it.nestedPath, it.required)
            pathVariables.add(variableMetadata)
        }
        field.scan<CommandRoute.HeaderVariable>()?.let {
            val variableMetadata = field.toVariableMetadata(it.name, it.nestedPath, it.required)
            headerVariables.add(variableMetadata)
        }
    }

    private fun CommandMetadata<*>.toMethod(routeMethod: CommandRoute.Method = CommandRoute.Method.DEFAULT): String {
        if (routeMethod != CommandRoute.Method.DEFAULT) {
            return routeMethod.name
        }
        if (isCreate) {
            return Https.Method.POST
        }
        if (isDelete) {
            return Https.Method.DELETE
        }
        return Https.Method.PUT
    }

    fun toMetadata(): CommandRouteMetadata<C> {
        val commandMetadata = commandType.toCommandMetadata()
        val summary = commandType.scan<Summary>()?.value ?: ""
        val commandRoute = commandType.scan<CommandRoute>() ?: return CommandRouteMetadata(
            enabled = true,
            path = commandMetadata.name,
            method = commandMetadata.toMethod(),
            appendIdPath = CommandRoute.AppendPath.DEFAULT,
            appendTenantPath = CommandRoute.AppendPath.DEFAULT,
            ignoreAggregateNamePrefix = false,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
            headerVariableMetadata = headerVariables,
            summary = summary
        )

        val path = parsePath(commandRoute, commandMetadata)

        return CommandRouteMetadata(
            enabled = commandRoute.enabled,
            path = path,
            method = commandMetadata.toMethod(commandRoute.method),
            prefix = commandRoute.prefix,
            appendIdPath = commandRoute.appendIdPath,
            appendTenantPath = commandRoute.appendTenantPath,
            ignoreAggregateNamePrefix = commandRoute.ignoreAggregateNamePrefix,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
            headerVariableMetadata = headerVariables,
            summary = commandRoute.summary.ifBlank { summary },
            description = commandRoute.description,
        )
    }

    private fun parsePath(
        commandRoute: CommandRoute,
        commandMetadata: CommandMetadata<C>
    ): String {
        if (commandRoute.path == DEFAULT_COMMAND_PATH) {
            return commandMetadata.name
        }
        if (commandRoute.path.isBlank()) {
            return commandRoute.path
        }
        val fullPath = PathBuilder().append(commandRoute.prefix).append(commandRoute.path).build()
        val uriPathVariables = UriTemplate(fullPath).variableNames.toSet()
        val pathVariableNames = pathVariables.map { it.variableName }.toSet()
        val missedVariableNames = uriPathVariables - pathVariableNames
        if (missedVariableNames.isNotEmpty()) {
            if (log.isWarnEnabled) {
                log.warn(
                    "Command[{}] Route PathVariables{} is not bound to fields.",
                    commandMetadata.commandType,
                    missedVariableNames.toJsonString(),
                )
            }
            missedVariableNames.forEach {
                val missedVariable = VariableMetadata(
                    fieldPath = listOf(it),
                    variableName = it,
                    required = true,
                    bound = false
                )
                pathVariables.add(missedVariable)
            }
        }

        return commandRoute.path
    }
}

fun <C> Class<out C>.commandRouteMetadata(): CommandRouteMetadata<C> {
    @Suppress("UNCHECKED_CAST")
    return CommandRouteMetadataParser.parse(this) as CommandRouteMetadata<C>
}

inline fun <reified C> commandRouteMetadata(): CommandRouteMetadata<C> {
    return C::class.java.commandRouteMetadata()
}
