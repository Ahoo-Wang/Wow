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

package me.ahoo.wow.openapi.metadata

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.DEFAULT_COMMAND_ACTION
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.command.annotation.commandMetadata
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotations
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.serialization.toJsonString
import org.springframework.web.util.UriTemplate
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

/**
 * Parses the provided [type] into a [Metadata] object specifically for command routes.
 *
 * This object extends [CacheableMetadataParser] and thus benefits from caching mechanisms,
 * which can improve performance when parsing the same type multiple times.
 *
 * @param type The class type to be parsed into metadata.
 * @return A [Metadata] object representing the command route information of the provided type.
 *
 * Example usage:
 * ```kotlin
 * val myCommandType = MyCommand::class.java
 * val metadata: Metadata = CommandRouteMetadataParser.parse(myCommandType)
 * println(metadata) // Outputs the metadata, assuming it has a toString implementation
 * ```
 */
object CommandRouteMetadataParser : CacheableMetadataParser() {
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = CommandRouteMetadataVisitor(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

internal class CommandRouteMetadataVisitor<C : Any>(private val commandType: Class<C>) :
    ClassVisitor<C, CommandRouteMetadata<C>> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private var pathVariables: MutableSet<VariableMetadata> = mutableSetOf()
    private var headerVariables: MutableSet<VariableMetadata> = mutableSetOf()

    private fun KProperty1<*, *>.toVariableMetadata(
        name: String,
        nestedPath: Array<String>,
        required: Boolean
    ): VariableMetadata {
        val fieldName = scanAnnotation<JsonProperty>()?.value
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
            field = javaField,
            fieldPath = fieldPath,
            variableName = variableName,
            required = required,
        )
    }

    override fun visitProperty(property: KProperty1<C, *>) {
        property.scanAnnotations<CommandRoute.PathVariable>().forEach {
            val variableMetadata = property.toVariableMetadata(it.name, it.nestedPath, it.required)
            pathVariables.add(variableMetadata)
        }
        property.scanAnnotations<CommandRoute.HeaderVariable>().forEach {
            val variableMetadata = property.toVariableMetadata(it.name, it.nestedPath, it.required)
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

    override fun toMetadata(): CommandRouteMetadata<C> {
        val commandMetadata = commandType.commandMetadata()

        val summary = commandType.kotlin.scanAnnotation<Summary>()?.value.orEmpty()
        val description = commandType.kotlin.scanAnnotation<Description>()?.value.orEmpty()
        val commandRoute = commandType.kotlin.scanAnnotation<CommandRoute>() ?: return CommandRouteMetadata(
            enabled = true,
            action = commandMetadata.name,
            method = commandMetadata.toMethod(),
            appendIdPath = CommandRoute.AppendPath.DEFAULT,
            appendTenantPath = CommandRoute.AppendPath.DEFAULT,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
            headerVariableMetadata = headerVariables,
            summary = summary,
            description = description
        )

        val action = parseAction(commandRoute, commandMetadata)

        return CommandRouteMetadata(
            enabled = commandRoute.enabled,
            action = action,
            method = commandMetadata.toMethod(commandRoute.method),
            prefix = commandRoute.prefix,
            appendIdPath = commandRoute.appendIdPath,
            appendTenantPath = commandRoute.appendTenantPath,
            appendOwnerPath = commandRoute.appendOwnerPath,
            commandMetadata = commandMetadata,
            pathVariableMetadata = pathVariables,
            headerVariableMetadata = headerVariables,
            summary = commandRoute.summary.ifBlank { summary },
            description = commandRoute.description.ifBlank { description },
        )
    }

    private fun parseAction(
        commandRoute: CommandRoute,
        commandMetadata: CommandMetadata<C>
    ): String {
        if (commandRoute.action == DEFAULT_COMMAND_ACTION) {
            return commandMetadata.name
        }
        if (commandRoute.action.isBlank()) {
            return commandRoute.action
        }
        val fullPath = PathBuilder().append(commandRoute.prefix).append(commandRoute.action).build()
        val uriPathVariables = UriTemplate(fullPath).variableNames.toSet()
        val pathVariableNames = pathVariables.map { it.variableName }.toSet()
        val missedVariableNames = uriPathVariables - pathVariableNames
        if (missedVariableNames.isNotEmpty()) {
            log.warn {
                "Command[${commandMetadata.commandType}] Route PathVariables [${missedVariableNames.toJsonString()}] is not bound to fields."
            }
            missedVariableNames.forEach {
                val missedVariable = VariableMetadata(
                    field = null,
                    fieldPath = listOf(it),
                    variableName = it,
                    required = true,
                    bound = false
                )
                pathVariables.add(missedVariable)
            }
        }

        return commandRoute.action
    }
}

fun <C> Class<out C>.commandRouteMetadata(): CommandRouteMetadata<C> {
    return CommandRouteMetadataParser.parse(this)
}

inline fun <reified C> commandRouteMetadata(): CommandRouteMetadata<C> {
    return C::class.java.commandRouteMetadata()
}
